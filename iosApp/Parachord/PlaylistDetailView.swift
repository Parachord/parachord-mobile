import SwiftUI
import Shared

// MARK: - Playlist detail (phase 5.4)
//
// Loads + shows the tracks of a single ListenBrainz weekly playlist via
// the shared WeeklyPlaylistsRepository. Browse-only for now: these are
// metadata-only LB tracks (no resolver / streaming IDs), so playback
// waits on the iOS resolver pipeline. Establishes the NavigationStack
// push pattern (Discover row → detail) the rest of the app reuses.

@MainActor
@Observable
final class PlaylistDetailViewModel {

    private let container = IosContainer.companion.shared

    let playlistId: String
    let title: String

    var tracks: [IosPlaylistTrack] = []
    /// Metadata-only shared Tracks (no resolver IDs) — what the coordinator
    /// queues + resolves on the fly. Parallel to `tracks` by index.
    var trackEntities: [Track] = []
    var isLoading = false
    var loaded = false
    /// Whether this ephemeral weekly playlist is already saved to the library
    /// (#236). Reactive — flips when the save lands (or if it's already saved).
    var saved = false
    var saving = false
    private var savedSub: Cancellable?

    init(playlistId: String, title: String) {
        self.playlistId = playlistId
        self.title = title
    }

    func load() async {
        // Start watching saved-state regardless of the early-return below, so a
        // re-opened (already-saved) playlist shows "Saved".
        if savedSub == nil {
            savedSub = container.watchWeeklyPlaylistSaved(playlistId: playlistId) { [weak self] yes in
                self?.saved = yes.boolValue
            }
        }
        guard !loaded else { return }
        isLoading = true
        tracks = (try? await container.loadWeeklyPlaylistTracks(playlistId: playlistId)) ?? []
        trackEntities = tracks.map { Self.makeTrack($0) }
        isLoading = false
        loaded = true

        // NOTE: tracks are NOT bulk-resolved here. Resolution is driven
        // per-visible-row from the List (`.onAppear`), mirroring the
        // desktop's ResolutionScheduler, which resolves ONLY imminently-
        // visible tracks (viewport + overscan) rather than the whole list.
        // Bulk-resolving a 50-track playlist on open fired ~50 Spotify
        // searches (+ ~50 iTunes) in one burst, which tripped Spotify's
        // abuse window (HTTP 429, Retry-After ~3600s) on the shared client
        // ID. See `resolveVisible(_:)`.
    }

    /// Save this ephemeral weekly playlist to the library (#236). No-op if empty
    /// or already saved/in-flight; `saved` flips reactively via the watcher.
    func save() {
        guard !saved, !saving, !trackEntities.isEmpty else { return }
        saving = true
        let artwork = tracks.compactMap { $0.albumArt }.first { !$0.isEmpty }
        Task {
            _ = try? await container.saveWeeklyPlaylist(
                playlistId: playlistId, title: title, description: nil,
                artworkUrl: artwork, tracks: trackEntities)
            saving = false
        }
    }

    /// Resolve a single track on demand (called when its row scrolls into
    /// view), tagged with its row `index` as the priority order so the shared
    /// cache drains top-down (top of the page first), not in `onAppear` order.
    /// The cache dedups by key + in-flight + queued, so this is safe to call
    /// repeatedly as rows recycle.
    func resolveVisible(_ track: IosPlaylistTrack, index: Int) {
        IosTrackResolverCache.shared.resolve(
            ResolveRequest(artist: track.artist, title: track.title, album: track.album),
            order: index
        )
    }

    /// Build a metadata-only shared `Track` from an LB playlist track. The
    /// 19-param Kotlin-bridged initializer overwhelms Swift's inline type
    /// checker in a `.map` closure, so it lives in a standalone func.
    private static func makeTrack(_ t: IosPlaylistTrack) -> Track {
        Track(
            id: t.id, title: t.title, artist: t.artist,
            album: t.album, albumId: nil, duration: nil, artworkUrl: t.albumArt,
            sourceType: nil, sourceUrl: nil, addedAt: 0,
            resolver: nil, spotifyUri: nil, soundcloudId: nil,
            spotifyId: nil, appleMusicId: nil, isrc: nil, recordingMbid: nil,
            artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
        )
    }
}

struct PlaylistDetailView: View {
    @State private var model: PlaylistDetailViewModel
    @State private var navArtist: String?
    @State private var navAlbum: PCAlbumRef?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.dismiss) private var dismiss
    /// Observed so badge rows re-render as background resolution lands.
    private var resolverCache = IosTrackResolverCache.shared

    init(playlistId: String, title: String) {
        _model = State(initialValue: PlaylistDetailViewModel(playlistId: playlistId, title: title))
    }

    /// Queue-source context (#209) so the queue panel shows a "Playing from: …"
    /// link back to this playlist.
    private var playlistContext: PlaybackContext {
        PlaybackContext(type: "playlist", name: model.title, id: model.playlistId)
    }

    /// First 4 distinct track covers for the header mosaic.
    private var covers: [String] {
        var seen = Set<String>(); var out: [String] = []
        for t in model.tracks {
            guard let a = t.albumArt, !a.isEmpty, seen.insert(a).inserted else { continue }
            out.append(a); if out.count == 4 { break }
        }
        return out
    }

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: model.title, leading: .back, onLeading: { dismiss() })
            if model.isLoading && !model.loaded {
                ScrollView { PCSkeletonList(count: 8, art: 44) }
            } else if model.tracks.isEmpty {
                ContentUnavailableView("No tracks", systemImage: "music.note.list",
                    description: Text("This playlist came back empty."))
            } else {
                ScrollView {
                    header
                    LazyVStack(spacing: 0) {
                        ForEach(Array(model.tracks.enumerated()), id: \.element.id) { index, track in
                            Button { coordinator.setQueue(model.trackEntities, startIndex: index, context: playlistContext) } label: {
                                row(index: index, track: track)
                            }
                            .buttonStyle(.plain)
                            .onAppear { model.resolveVisible(track, index: index) }
                            .pcTrackContextMenu(
                                model.trackEntities[index], coordinator: coordinator,
                                onGoToArtist: { navArtist = model.trackEntities[index].artist },
                                onGoToAlbum: model.trackEntities[index].album.map { album in
                                    { navAlbum = PCAlbumRef(title: album, artist: model.trackEntities[index].artist) }
                                })
                        }
                    }
                    .padding(.bottom, 130)
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0) }
        .navigationDestination(item: $navAlbum) { AlbumScreen(title: $0.title, artist: $0.artist) }
        .task { await model.load() }
    }

    // Mosaic cover + title + meta + Play All / Save (Android WeeklyPlaylistScreen).
    private var header: some View {
        VStack(spacing: 12) {
            LazyVGrid(columns: [GridItem(.fixed(80), spacing: 0), GridItem(.fixed(80), spacing: 0)], spacing: 0) {
                ForEach(0..<4, id: \.self) { j in
                    mosaicCell(j < covers.count ? covers[j] : nil, seed: "\(model.playlistId)\(j)")
                }
            }
            .frame(width: 160, height: 160)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .shadow(color: .black.opacity(0.12), radius: 10, y: 5)

            Text(model.title).font(.system(size: 18, weight: .semibold)).foregroundStyle(PC.fg1)
                .multilineTextAlignment(.center)
            Text("ListenBrainz · \(model.tracks.count) tracks").font(.system(size: 13)).foregroundStyle(PC.fg2)

            HStack(spacing: 10) {
                Button { coordinator.setQueue(model.trackEntities, startIndex: 0, context: playlistContext) } label: {
                    Label("Play All", systemImage: "play.fill").font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white).padding(.horizontal, 22).frame(height: 40)
                        .background(PC.accent, in: Capsule())
                }.buttonStyle(.plain)
                // Save the ephemeral weekly playlist to the library (#236). Flips
                // to "Saved" reactively once written (or if already saved).
                Button { model.save() } label: {
                    Label(model.saved ? "Saved" : "Save",
                          systemImage: model.saved ? "checkmark" : "square.and.arrow.down")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(model.saved ? PC.accent : PC.fg1)
                        .padding(.horizontal, 18).frame(height: 40)
                        .overlay(Capsule().strokeBorder(model.saved ? PC.accent : PC.border))
                }
                .buttonStyle(.plain)
                .disabled(model.saved || model.saving || model.trackEntities.isEmpty)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 16)
    }

    @ViewBuilder
    private func mosaicCell(_ url: String?, seed: String) -> some View {
        if let url, let u = URL(string: url) {
            PCCachedImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                placeholder: { PCArtwork(name: seed, size: 80, radius: 0) }
                .frame(width: 80, height: 80).clipped()
        } else { PCArtwork(name: seed, size: 80, radius: 0) }
    }

    @ViewBuilder
    private func row(index: Int, track: IosPlaylistTrack) -> some View {
        let isCurrent = index < model.trackEntities.count
            && coordinator.currentTrack?.id == model.trackEntities[index].id
        HStack(spacing: 12) {
            Text("\(index + 1)").font(.system(size: 13, design: .monospaced))
                .foregroundStyle(PC.fg3).frame(width: 24)
            trackArt(track)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title).font(.system(size: 15, weight: .medium))
                    .foregroundStyle(pcTrackNoMatch(artist: track.artist, title: track.title, album: track.album) ? PC.fg3
                        : (isCurrent ? PC.accent : PC.fg1)).lineLimit(1)
                Text(albumLine(track)).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
            }
            Spacer(minLength: 8)
            if let ranked = resolverCache.cached(artist: track.artist, title: track.title, album: track.album),
               !ranked.isEmpty {
                ResolverBadgeRow(sources: ranked)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
    }

    @ViewBuilder
    private func trackArt(_ track: IosPlaylistTrack) -> some View {
        if let url = track.albumArt, let u = URL(string: url) {
            PCCachedImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                placeholder: { PCArtwork(name: track.title + track.artist, size: 44, radius: 6) }
                .frame(width: 44, height: 44).clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        } else { PCArtwork(name: track.title + track.artist, size: 44, radius: 6) }
    }

    private func albumLine(_ track: IosPlaylistTrack) -> String {
        if let album = track.album, !album.isEmpty {
            return "\(track.artist) · \(album)"
        }
        return track.artist
    }
}

// MARK: - Saved playlists library (iOS playlist views — list / detail / edit)
//
// The Playlists tab + a saved-playlist detail backed by the SQLDelight DB
// (playlistDao / playlistTrackDao via IosContainer), distinct from the
// EPHEMERAL weekly PlaylistDetailView above (WeeklyPlaylistsRepository). Detail
// supports full edit: rename, delete, remove track, and drag-to-reorder (the
// #220 List + .onMove + always-active editMode pattern). Saving a weekly
// playlist (#236) lands here.

/// Global "new playlist" create prompt (#242). The track menu's Add-to-Playlist
/// "New Playlist…" and the FAB's "New Playlist" both trigger it; a single alert
/// hosted at the ContentView root presents the name field and creates the
/// playlist — optionally appending a pending track (the add-to-playlist case).
@MainActor
@Observable
final class PlaylistCreator {
    static let shared = PlaylistCreator()
    private init() {}
    var showing = false
    var name = ""
    private var pendingTrack: Track?

    func start(track: Track? = nil) { pendingTrack = track; name = ""; showing = true }

    func create() {
        let n = name.trimmingCharacters(in: .whitespaces)
        let track = pendingTrack
        pendingTrack = nil
        guard !n.isEmpty else { return }
        Task {
            let container = IosContainer.companion.shared
            let id = try? await container.createPlaylist(name: n)
            if let id, let track { try? await container.addTrackToPlaylist(playlistId: id, track: track) }
        }
    }
}

/// PlaylistTrack → playable Track (19-param init in a helper to spare Swift's
/// inline type-checker). Shared by the playlist detail + the "Play Playlist" menu.
func pcTrack(from t: PlaylistTrack) -> Track {
    Track(
        id: "\(t.trackTitle)|\(t.trackArtist)", title: t.trackTitle, artist: t.trackArtist,
        album: t.trackAlbum, albumId: nil, duration: t.trackDuration, artworkUrl: t.trackArtworkUrl,
        sourceType: nil, sourceUrl: t.trackSourceUrl, addedAt: 0,
        resolver: t.trackResolver, spotifyUri: t.trackSpotifyUri, soundcloudId: t.trackSoundcloudId,
        spotifyId: t.trackSpotifyId, appleMusicId: t.trackAppleMusicId, isrc: nil,
        recordingMbid: t.trackRecordingMbid, artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
    )
}

/// Playlist sort options — mirrors Android's `PlaylistSort` (same `name` keys,
/// so the persisted choice is shared across platforms).
enum PlaylistSort: String, CaseIterable {
    case RECENT, CREATED, MODIFIED, ALPHA_ASC, ALPHA_DESC
    var label: String {
        switch self {
        case .RECENT: return "Recently Added"
        case .CREATED: return "Date Created"
        case .MODIFIED: return "Recently Modified"
        case .ALPHA_ASC: return "A-Z"
        case .ALPHA_DESC: return "Z-A"
        }
    }
}

@MainActor
@Observable
final class PlaylistsListModel {
    private let container = IosContainer.companion.shared
    private var raw: [Playlist] = []
    var sort: PlaylistSort = .RECENT
    var mirrors: [String: [String]] = [:]   // localPlaylistId -> push-mirror provider ids
    var playlists: [Playlist] { sorted(raw) }
    private var sub: Cancellable?

    func start() {
        guard sub == nil else { return }
        sub = container.watchSavedPlaylists { [weak self] list in
            self?.raw = list
            Task { await self?.loadMirrors() }   // refresh chips when the list changes (post-sync)
        }
        Task {
            if let s = try? await container.getPlaylistsSort(), let parsed = PlaylistSort(rawValue: s) {
                sort = parsed
            }
            await loadMirrors()
        }
    }

    func loadMirrors() async {
        let list = (try? await container.getAllPlaylistMirrors()) as? [IosPlaylistMirrors] ?? []
        var dict: [String: [String]] = [:]
        for m in list { dict[m.localPlaylistId] = m.providerIds }
        mirrors = dict
    }

    func setSort(_ s: PlaylistSort) {
        sort = s
        Task { try? await container.setPlaylistsSort(sort: s.rawValue) }
    }

    private func sorted(_ list: [Playlist]) -> [Playlist] {
        switch sort {
        case .RECENT: return list.sorted { $0.createdAt > $1.createdAt }
        case .CREATED: return list.sorted { $0.createdAt < $1.createdAt }
        case .MODIFIED: return list.sorted { $0.lastModified > $1.lastModified }
        case .ALPHA_ASC: return list.sorted { $0.name.lowercased() < $1.name.lowercased() }
        case .ALPHA_DESC: return list.sorted { $0.name.lowercased() > $1.name.lowercased() }
        }
    }
}

struct PlaylistsScreen: View {
    @State private var model = PlaylistsListModel()
    @State private var path: [String] = []
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    private let container = IosContainer.companion.shared
    @State private var renameTarget: Playlist?
    @State private var renameText = ""
    @State private var deleteTarget: Playlist?
    @State private var syncTarget: Playlist?
    let onMenu: () -> Void
    init(onMenu: @escaping () -> Void = {}) { self.onMenu = onMenu }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                PCTopBar(title: "Playlists", leading: .menu, onLeading: onMenu)
                if !model.playlists.isEmpty {
                    HStack {
                        Menu {
                            ForEach(PlaylistSort.allCases, id: \.self) { s in
                                Button { model.setSort(s) } label: {
                                    if model.sort == s { Label(s.label, systemImage: "checkmark") } else { Text(s.label) }
                                }
                            }
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "arrow.up.arrow.down").font(.system(size: 11))
                                Text(model.sort.label).font(.system(size: 13, weight: .medium))
                                Image(systemName: "chevron.down").font(.system(size: 9))
                            }
                            .foregroundStyle(PC.fg2)
                        }
                        Spacer()
                    }
                    .padding(.horizontal, 20).padding(.vertical, 8)
                }
                if model.playlists.isEmpty {
                    Spacer()
                    VStack(spacing: 8) {
                        Image(systemName: "music.note.list").font(.system(size: 34)).foregroundStyle(PC.fg3)
                        Text("No saved playlists yet").font(.system(size: 14)).foregroundStyle(PC.fg2)
                        Text("Save a Weekly Jam or Exploration to see it here.")
                            .font(.system(size: 12)).foregroundStyle(PC.fg3).multilineTextAlignment(.center)
                    }.padding(.horizontal, 40)
                    Spacer()
                } else {
                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(model.playlists, id: \.id) { p in
                                Button { path.append(p.id) } label: { row(p) }.buttonStyle(.plain)
                                    .contextMenu {
                                        Button { playPlaylist(p) } label: { Label("Play Playlist", systemImage: "play.fill") }
                                        Button { syncTarget = p } label: { Label("Sync…", systemImage: "arrow.triangle.2.circlepath") }
                                        Button { renameTarget = p; renameText = p.name } label: { Label("Rename", systemImage: "pencil") }
                                        Button(role: .destructive) { deleteTarget = p } label: { Label("Delete Playlist", systemImage: "trash") }
                                    }
                            }
                        }.padding(.bottom, 130)
                    }
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: String.self) { id in SavedPlaylistDetailView(playlistId: id) }
            .task { model.start() }
            .alert("Rename Playlist", isPresented: Binding(get: { renameTarget != nil }, set: { if !$0 { renameTarget = nil } })) {
                TextField("Name", text: $renameText)
                Button("Cancel", role: .cancel) { renameTarget = nil }
                Button("Save") {
                    let n = renameText.trimmingCharacters(in: .whitespaces)
                    if let p = renameTarget, !n.isEmpty { Task { try? await container.renamePlaylist(id: p.id, name: n) } }
                    renameTarget = nil
                }
            }
            .sheet(isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } })) {
                if let p = deleteTarget {
                    DeletePlaylistSheet(playlist: p) { deleteTarget = nil }
                        .presentationDetents([.medium])
                }
            }
            .sheet(isPresented: Binding(get: { syncTarget != nil }, set: { if !$0 { syncTarget = nil } })) {
                if let p = syncTarget {
                    PlaylistSyncChannelsSheet(playlist: p) {
                        syncTarget = nil
                        Task { await model.loadMirrors() }   // refresh chips after channel edits
                    }
                    .presentationDetents([.medium])
                }
            }
        }
    }

    private func playPlaylist(_ p: Playlist) {
        Task {
            let tracks = (try? await container.getPlaylistTracksOnce(id: p.id)) ?? []
            let entities = tracks.map { pcTrack(from: $0) }
            guard !entities.isEmpty else { return }
            coordinator.setQueue(entities, startIndex: 0, context: PlaybackContext(type: "playlist", name: p.name, id: p.id))
        }
    }

    private func row(_ p: Playlist) -> some View {
        HStack(spacing: 12) {
            pcCover(p.artworkUrl, seed: p.name, size: 52, radius: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(p.name).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                Text(subtitle(p)).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                if hasChips(p) { chips(p) }
            }
            Spacer(minLength: 8)
            Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(PC.fg3)
        }
        .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
    }

    // Source chips — help spot cross-provider duplicates (a playlist that exists
    // as both a `spotify-` and an `applemusic-` row). Mirrors Android's
    // PlaylistsScreen chips + the hosted-XSPF badge.
    // The providers a playlist EFFECTIVELY syncs with (override-aware, from the
    // shared effective-channels map) — reflects the live Sync-menu state, NOT the
    // row's id-prefix, so a fully-deselected playlist shows no provider chips.
    private func mergedProviders(_ p: Playlist) -> Set<String> {
        Set(model.mirrors[p.id] ?? [])
    }
    private func hasChips(_ p: Playlist) -> Bool { true }

    @ViewBuilder private func chips(_ p: Playlist) -> some View {
        let providers = mergedProviders(p)
        HStack(spacing: 5) {
            if p.sourceUrl != nil {
                Text("🌐 Hosted").font(.system(size: 10, weight: .medium))
                    .foregroundStyle(Color(uiColor: UIColor(hex: 0x3B82F6)))
                    .padding(.horizontal, 6).padding(.vertical, 1)
                    .background(RoundedRectangle(cornerRadius: 4).fill(Color(uiColor: UIColor(hex: 0xEFF6FF))))
            }
            if providers.contains("spotify") { sourceChip("Spotify", 0x1DB954) }
            if providers.contains("applemusic") { sourceChip("Apple Music", 0xFA243C) }
            if providers.contains("listenbrainz") { sourceChip("ListenBrainz", 0xEB743B) }
            // Fallback: a user-created playlist with no source and no mirrors.
            if providers.isEmpty && p.sourceUrl == nil { sourceChip("Local", 0x9CA3AF) }
        }
        .padding(.top, 1)
    }

    private func sourceChip(_ text: String, _ hex: UInt32) -> some View {
        let c = Color(uiColor: UIColor(hex: hex))
        return Text(text).font(.system(size: 10, weight: .medium))
            .foregroundStyle(c)
            .padding(.horizontal, 6).padding(.vertical, 1)
            .background(RoundedRectangle(cornerRadius: 4).fill(c.opacity(0.12)))
    }

    private func subtitle(_ p: Playlist) -> String {
        let n = Int(p.trackCount)
        let t = "\(n) \(n == 1 ? "track" : "tracks")"
        if let owner = p.ownerName, !owner.isEmpty { return "\(t) · \(owner)" }
        return t
    }
}

/// Delete a playlist with a per-mirror choice: each remote it's linked to gets a
/// toggle (default on) for "also delete from there". Unchecked services keep
/// their copy. Apple Music can't be deleted via its API (handled server-side as
/// Unsupported — local removal still happens).
private struct DeletePlaylistSheet: View {
    let playlist: Playlist
    let onClose: () -> Void
    @Environment(\.dismiss) private var dismiss
    private let container = IosContainer.companion.shared
    @State private var providers: [String] = []
    @State private var selected: Set<String> = []
    @State private var working = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("This removes “\(playlist.name)” from Parachord.")
                        .font(.system(size: 14)).foregroundStyle(PC.fg2)
                        .padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 4)

                    if !providers.isEmpty {
                        sectionLabel("Also delete from")
                        ForEach(providers, id: \.self) { pid in mirrorToggle(pid) }
                        Text("Unchecked services keep their copy (it may re-import on the next sync). Apple Music can’t be deleted via its API — remove it in the Apple Music app.")
                            .font(.system(size: 11)).foregroundStyle(PC.fg3)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 20).padding(.top, 8)
                    }

                    Button(role: .destructive) { confirm() } label: {
                        Text(working ? "Deleting…" : "Delete Playlist")
                            .font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                            .frame(maxWidth: .infinity).padding(.vertical, 12)
                            .background(Capsule().fill(Color(uiColor: UIColor(hex: 0xEF4444))))
                    }
                    .disabled(working)
                    .padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 24)
                }
            }
            .background(PC.bgPrimary.ignoresSafeArea())
            .navigationTitle("Delete Playlist").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss(); onClose() }.foregroundStyle(PC.accent)
                }
            }
        }
        .task {
            providers = (try? await container.getPlaylistMirrorProviders(id: playlist.id)) as? [String] ?? []
            selected = Set(providers)
        }
    }

    private func mirrorToggle(_ pid: String) -> some View {
        Toggle(isOn: Binding(get: { selected.contains(pid) }, set: { on in
            if on { selected.insert(pid) } else { selected.remove(pid) }
        })) {
            Text(providerName(pid)).font(.system(size: 15)).foregroundStyle(PC.fg1)
        }
        .tint(PC.accent).padding(.horizontal, 20).padding(.vertical, 6)
    }

    private func confirm() {
        working = true
        Task {
            _ = try? await container.deletePlaylistFromMirrors(id: playlist.id, fromProviders: Array(selected))
            dismiss(); onClose()
        }
    }

    private func providerName(_ id: String) -> String {
        switch id {
        case "spotify": return "Spotify"
        case "applemusic": return "Apple Music"
        case "listenbrainz": return "ListenBrainz"
        default: return id.capitalized
        }
    }

    private func sectionLabel(_ t: String) -> some View {
        Text(t.uppercased()).font(.system(size: 11, weight: .bold)).tracking(1.4).foregroundStyle(PC.fg3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 4)
    }
}

/// Per-playlist Sync menu: toggle which services this playlist syncs with. The
/// per-playlist channel override is authoritative — disabling detaches (no dup),
/// enabling mirrors it to that service on the next sync.
private struct PlaylistSyncChannelsSheet: View {
    let playlist: Playlist
    let onClose: () -> Void
    @Environment(\.dismiss) private var dismiss
    private let container = IosContainer.companion.shared
    @State private var channels: [IosSyncChannel] = []
    @State private var pendingOff: IosSyncChannel?   // channel awaiting keep/delete choice
    @State private var note: String?                 // e.g. AM-can't-delete message

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Choose which services “\(playlist.name)” syncs with.")
                        .font(.system(size: 13)).foregroundStyle(PC.fg2)
                        .padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 8)
                    ForEach(channels, id: \.providerId) { ch in channelRow(ch) }
                    Text("Turning a service off keeps the playlist in Parachord but stops syncing it there. Turning one on mirrors it to that service on the next sync.")
                        .font(.system(size: 11)).foregroundStyle(PC.fg3)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 20).padding(.top, 10)
                    Spacer(minLength: 24)
                }
            }
            .background(PC.bgPrimary.ignoresSafeArea())
            .navigationTitle("Sync").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss(); onClose() }.foregroundStyle(PC.accent)
                }
            }
        }
        .task { await reload() }
        // Toggle a channel OFF → keep on the service, or delete it there too?
        .confirmationDialog(
            "Stop syncing to \(pendingOff?.displayName ?? "")?",
            isPresented: Binding(get: { pendingOff != nil }, set: { if !$0 { pendingOff = nil } }),
            titleVisibility: .visible
        ) {
            if let ch = pendingOff {
                Button("Just stop syncing") { Task { await applyOff(ch.providerId, deleteRemote: false); pendingOff = nil } }
                Button("Delete from \(ch.displayName) too", role: .destructive) {
                    Task { await applyOff(ch.providerId, deleteRemote: true); pendingOff = nil }
                }
            }
            Button("Cancel", role: .cancel) { pendingOff = nil }
        } message: {
            Text("Keep “\(playlist.name)” on \(pendingOff?.displayName ?? "") and just stop syncing it, or delete it from \(pendingOff?.displayName ?? "") too?")
        }
        .alert("Heads up", isPresented: Binding(get: { note != nil }, set: { if !$0 { note = nil } })) {
            Button("OK") { note = nil }
        } message: { Text(note ?? "") }
    }

    private func channelRow(_ ch: IosSyncChannel) -> some View {
        // Toggleable when connected AND (can mirror here OR already on — so you
        // can always turn an enabled channel off).
        let interactive = ch.connected && (ch.available || ch.enabled)
        return HStack(spacing: 12) {
            Circle().fill(channelColor(ch.providerId)).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 1) {
                Text(ch.displayName).font(.system(size: 15)).foregroundStyle(interactive ? PC.fg1 : PC.fg3)
                if !ch.connected {
                    Text("Connect in Settings to sync here").font(.system(size: 11)).foregroundStyle(PC.fg3)
                } else if !ch.available && !ch.enabled {
                    Text("Can’t mirror this playlist here").font(.system(size: 11)).foregroundStyle(PC.fg3)
                }
            }
            Spacer()
            Toggle("", isOn: Binding(get: { ch.enabled }, set: { on in
                if on { Task { await set(ch.providerId, true) } }   // enable: apply directly
                else { pendingOff = ch }                            // disable: ask keep/delete
            }))
                .labelsHidden().tint(PC.accent).disabled(!interactive)
        }
        .padding(.horizontal, 20).padding(.vertical, 10)
    }

    private func set(_ pid: String, _ on: Bool) async {
        try? await container.setPlaylistChannel(localId: playlist.id, providerId: pid, enabled: on)
        await reload()
    }

    private func applyOff(_ pid: String, deleteRemote: Bool) async {
        let unsupported = (try? await container.disablePlaylistChannel(
            localId: playlist.id, providerId: pid, deleteRemote: deleteRemote)) ?? nil
        if let u = unsupported {
            note = "\(u) doesn’t allow deletion via its API — remove “\(playlist.name)” manually in the \(u) app."
        }
        await reload()
    }

    private func reload() async {
        channels = (try? await container.getPlaylistSyncChannels(localId: playlist.id)) as? [IosSyncChannel] ?? []
    }

    private func channelColor(_ id: String) -> Color {
        switch id {
        case "spotify": return Color(uiColor: UIColor(hex: 0x1DB954))
        case "applemusic": return Color(uiColor: UIColor(hex: 0xFA243C))
        case "listenbrainz": return Color(uiColor: UIColor(hex: 0xEB743B))
        default: return PC.fg3
        }
    }
}

@MainActor
@Observable
final class SavedPlaylistModel {
    private let container = IosContainer.companion.shared
    let playlistId: String
    var playlist: Playlist?
    var tracks: [PlaylistTrack] = []
    var entities: [Track] = []
    private var pSub: Cancellable?
    private var tSub: Cancellable?

    var mirrors: [IosPlaylistMirrorLink] = []

    init(playlistId: String) { self.playlistId = playlistId }
    var name: String { playlist?.name ?? "Playlist" }

    func start() {
        if pSub == nil { pSub = container.watchPlaylist(id: playlistId) { [weak self] p in self?.playlist = p } }
        if tSub == nil {
            tSub = container.watchPlaylistTracks(id: playlistId) { [weak self] ts in
                self?.tracks = ts
                self?.entities = ts.map { Self.makeTrack($0) }
            }
        }
        Task { mirrors = (try? await container.getPlaylistMirrorLinks(id: playlistId)) as? [IosPlaylistMirrorLink] ?? [] }
    }

    func rename(_ newName: String) {
        let n = newName.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return }
        Task { try? await container.renamePlaylist(id: playlistId, name: n) }
    }
    func delete() { Task { try? await container.deletePlaylist(id: playlistId) } }
    func removeAt(_ i: Int) { Task { try? await container.removePlaylistTrackAt(id: playlistId, index: Int32(i)) } }
    func move(from: Int, to: Int) { Task { try? await container.movePlaylistTrack(id: playlistId, from: Int32(from), to: Int32(to)) } }

    private static func makeTrack(_ t: PlaylistTrack) -> Track { pcTrack(from: t) }
}

struct SavedPlaylistDetailView: View {
    @State private var model: SavedPlaylistModel
    @State private var editing = false
    @State private var showRename = false
    @State private var renameText = ""
    @State private var showDelete = false
    @State private var navArtist: String?
    @State private var navAlbum: PCAlbumRef?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.dismiss) private var dismiss
    private var resolverCache = IosTrackResolverCache.shared

    init(playlistId: String) { _model = State(initialValue: SavedPlaylistModel(playlistId: playlistId)) }

    private var ctx: PlaybackContext { PlaybackContext(type: "playlist", name: model.name, id: model.playlistId) }

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: model.name, leading: .back, onLeading: { dismiss() },
                     trailingIcon: editing ? "checkmark" : "pencil", onTrailing: { editing.toggle() })
            if editing { editList } else { viewList }
        }
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0) }
        .navigationDestination(item: $navAlbum) { AlbumScreen(title: $0.title, artist: $0.artist) }
        .task { model.start() }
        .alert("Rename Playlist", isPresented: $showRename) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) {}
            Button("Save") { model.rename(renameText) }
        }
        .confirmationDialog("Delete “\(model.name)”?", isPresented: $showDelete, titleVisibility: .visible) {
            Button("Delete Playlist", role: .destructive) { model.delete(); dismiss() }
            Button("Cancel", role: .cancel) {}
        }
    }

    private var header: some View {
        VStack(spacing: 12) {
            pcCover(model.playlist?.artworkUrl, seed: model.name, size: 160, radius: 10)
                .shadow(color: .black.opacity(0.12), radius: 10, y: 5)
            Text(model.name).font(.system(size: 18, weight: .semibold)).foregroundStyle(PC.fg1)
                .multilineTextAlignment(.center)
            Text("\(model.tracks.count) \(model.tracks.count == 1 ? "track" : "tracks")")
                .font(.system(size: 13)).foregroundStyle(PC.fg2)
            // Source/mirror chips — each opens the playlist on that service.
            if !model.mirrors.isEmpty {
                HStack(spacing: 6) {
                    ForEach(model.mirrors, id: \.providerId) { link in
                        if let url = mirrorURL(link) {
                            Link(destination: url) { mirrorChip(link.providerId) }
                        } else {
                            mirrorChip(link.providerId)
                        }
                    }
                }
            }
            HStack(spacing: 10) {
                Button { coordinator.setQueue(model.entities, startIndex: 0, context: ctx) } label: {
                    Label("Play All", systemImage: "play.fill").font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white).padding(.horizontal, 22).frame(height: 40)
                        .background(PC.accent, in: Capsule())
                }.buttonStyle(.plain).disabled(model.entities.isEmpty)
                if editing {
                    Button { renameText = model.name; showRename = true } label: {
                        Label("Rename", systemImage: "pencil").font(.system(size: 15, weight: .medium))
                            .foregroundStyle(PC.fg1).padding(.horizontal, 16).frame(height: 40)
                            .overlay(Capsule().strokeBorder(PC.border))
                    }.buttonStyle(.plain)
                    Button { showDelete = true } label: {
                        Image(systemName: "trash").font(.system(size: 15)).foregroundStyle(PC.error)
                            .frame(width: 40, height: 40).overlay(Circle().strokeBorder(PC.border))
                    }.buttonStyle(.plain)
                }
            }
        }
        .frame(maxWidth: .infinity).padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 16)
    }

    /// Web URL for a playlist on a given service, or nil if not linkable.
    private func mirrorURL(_ link: IosPlaylistMirrorLink) -> URL? {
        switch link.providerId {
        case "spotify": return URL(string: "https://open.spotify.com/playlist/\(link.externalId)")
        case "listenbrainz": return URL(string: "https://listenbrainz.org/playlist/\(link.externalId)/")
        case "applemusic": return URL(string: "https://music.apple.com/library/playlist/\(link.externalId)")
        default: return nil
        }
    }

    @ViewBuilder private func mirrorChip(_ providerId: String) -> some View {
        let (label, hex): (String, UInt32) = {
            switch providerId {
            case "spotify": return ("Spotify", 0x1DB954)
            case "applemusic": return ("Apple Music", 0xFA243C)
            case "listenbrainz": return ("ListenBrainz", 0xEB743B)
            default: return (providerId.capitalized, 0x9CA3AF)
            }
        }()
        let c = Color(uiColor: UIColor(hex: hex))
        HStack(spacing: 3) {
            Text(label).font(.system(size: 11, weight: .semibold))
            Image(systemName: "arrow.up.right").font(.system(size: 8, weight: .bold))
        }
        .foregroundStyle(c)
        .padding(.horizontal, 8).padding(.vertical, 3)
        .background(Capsule().fill(c.opacity(0.12)))
    }

    // View mode: tap-to-play rows with badges + context menus (app-wide style).
    private var viewList: some View {
        ScrollView {
            header
            if model.tracks.isEmpty {
                Text("No tracks in this playlist.").font(.system(size: 14)).foregroundStyle(PC.fg3).padding(40)
            } else {
                LazyVStack(spacing: 0) {
                    ForEach(Array(model.tracks.enumerated()), id: \.offset) { i, t in
                        Button { coordinator.setQueue(model.entities, startIndex: i, context: ctx) } label: {
                            trackRow(t, index: i)
                        }
                        .buttonStyle(.plain)
                        .onAppear { resolverCache.resolve(ResolveRequest(artist: t.trackArtist, title: t.trackTitle, album: t.trackAlbum), order: i) }
                        .pcTrackContextMenu(
                            model.entities[i], coordinator: coordinator,
                            onGoToArtist: { navArtist = t.trackArtist },
                            onGoToAlbum: t.trackAlbum.map { a in { navAlbum = PCAlbumRef(title: a, artist: t.trackArtist) } })
                    }
                }
                .padding(.bottom, 130)
            }
        }
    }

    private func trackRow(_ t: PlaylistTrack, index: Int) -> some View {
        HStack(spacing: 12) {
            Text("\(index + 1)").font(.system(size: 13, design: .monospaced)).foregroundStyle(PC.fg3).frame(width: 24)
            pcCover(pcTrackArt(t.trackArtworkUrl, artist: t.trackArtist, title: t.trackTitle, album: t.trackAlbum),
                    seed: t.trackTitle + t.trackArtist, size: 44, radius: 6)
            VStack(alignment: .leading, spacing: 2) {
                Text(t.trackTitle).font(.system(size: 15, weight: .medium))
                    .foregroundStyle(coordinator.currentTrack?.id == model.entities[index].id ? PC.accent : PC.fg1).lineLimit(1)
                Text(t.trackAlbum.map { "\(t.trackArtist) · \($0)" } ?? t.trackArtist)
                    .font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
            }
            Spacer(minLength: 8)
            if let ranked = resolverCache.cached(artist: t.trackArtist, title: t.trackTitle, album: t.trackAlbum), !ranked.isEmpty {
                ResolverBadgeRow(sources: ranked)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
    }

    // Edit mode: a styled List so .onMove (drag-reorder) + .onDelete (remove)
    // work, with editMode forced active so the handles + delete controls are
    // always visible (#220 queue-panel pattern).
    private var editList: some View {
        List {
            ForEach(Array(model.tracks.enumerated()), id: \.offset) { _, t in
                HStack(spacing: 12) {
                    pcCover(t.trackArtworkUrl, seed: t.trackTitle + t.trackArtist, size: 40, radius: 6)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.trackTitle).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                        Text(t.trackArtist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .listRowBackground(PC.bgPrimary)
                .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 12))
            }
            .onMove { src, dst in
                guard let from = src.first else { return }
                model.move(from: from, to: dst > from ? dst - 1 : dst)
            }
            .onDelete { idx in idx.sorted(by: >).forEach { model.removeAt($0) } }
        }
        .listStyle(.plain)
        .environment(\.editMode, .constant(.active))
    }
}
