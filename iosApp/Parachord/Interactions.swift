import SwiftUI
import Shared

// MARK: - Phase 2.5 interactions (docs/design/parachord-ios)
//
// Haptic-style track context menus (long-press) + the mock Dynamic Island
// live activity. Pure UI — no Spotify calls.

extension View {
    /// Long-press haptic context menu for a track row, mirroring the design's
    /// ContextMenu items. Play Next / Add to Queue are wired to the queue;
    /// nav + share items land with later phases.
    func pcTrackContextMenu(
        _ track: Track,
        coordinator: QueuePlaybackCoordinator,
        onGoToArtist: (() -> Void)? = nil,
        onGoToAlbum: (() -> Void)? = nil,
        onRemoveFromQueue: (() -> Void)? = nil
    ) -> some View {
        contextMenu {
            Button { coordinator.playNext(track) } label: {
                Label("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward")
            }
            Button { coordinator.addToQueue(track) } label: {
                Label("Add to Queue", systemImage: "text.append")
            }
            PCAddToPlaylistMenu(track: track)
            // Edit Metadata (iOS local-files tag editor) — local files only.
            // Streaming tracks resolve BY
            // title/artist against Spotify/Apple Music, so editing those would
            // break resolution; local files carry their own row metadata (often
            // just a filename after import), so cleaning it up is safe + useful.
            if track.resolver == "localfiles" {
                Button { TrackMetadataEditor.shared.start(track) } label: {
                    Label("Edit Metadata", systemImage: "pencil")
                }
            }
            // Queue-context only (#220): remove this track from the up-next list.
            if let onRemoveFromQueue {
                Button(role: .destructive) { onRemoveFromQueue() } label: {
                    Label("Remove from Queue", systemImage: "text.badge.minus")
                }
            }
            if let onGoToArtist {
                Button { onGoToArtist() } label: { Label("Go to Artist", systemImage: "music.mic") }
            }
            if let onGoToAlbum {
                Button { onGoToAlbum() } label: { Label("Go to Album", systemImage: "square.stack") }
            }
            Divider()
            Button { ShareCoordinator.shared.shareTrack(track) } label: {
                Label("Share", systemImage: "square.and.arrow.up")
            }
            // Always-on collection toggle (Android parity) — self-managed: observes
            // the DB and adds/removes. On the Collection screen the row drops out
            // reactively when removed (the collection flow re-emits). #204
            PCCollectionToggleButton(target: .track(track))
        } preview: {
            // Rich preview: artwork + title/artist (Apple Music style).
            HStack(spacing: 12) {
                PCArtwork(name: track.title, size: 56, radius: 8)
                VStack(alignment: .leading, spacing: 3) {
                    Text(track.title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    Text(track.artist).font(.system(size: 13)).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(14)
            .frame(width: 280)
        }
    }

    /// Long-press menu for an ALBUM card (#204, Android `AlbumContextMenu`):
    /// Play / Queue album, Go to Album / Artist, collection toggle. Play/Queue
    /// fetch the album's tracks (MetadataService) and route through the queue.
    func pcAlbumContextMenu(
        title: String, artist: String, artworkUrl: String? = nil,
        coordinator: QueuePlaybackCoordinator,
        onGoToAlbum: (() -> Void)? = nil,
        onGoToArtist: (() -> Void)? = nil
    ) -> some View {
        contextMenu {
            PCAlbumMenuItems(title: title, artist: artist, artworkUrl: artworkUrl,
                             coordinator: coordinator, onGoToAlbum: onGoToAlbum, onGoToArtist: onGoToArtist)
        }
    }

    /// Long-press menu for an ARTIST card (#204, Android `ArtistContextMenu`):
    /// Play / Queue top songs, Go to Artist, collection toggle.
    func pcArtistContextMenu(
        name: String, imageUrl: String? = nil,
        coordinator: QueuePlaybackCoordinator,
        onGoToArtist: (() -> Void)? = nil
    ) -> some View {
        contextMenu {
            PCArtistMenuItems(name: name, imageUrl: imageUrl, coordinator: coordinator, onGoToArtist: onGoToArtist)
        }
    }
}

// MARK: - Heart (collection toggle) button — Now Playing + mini-player

/// Reactive love/collection heart for the current track. Observes the DB so the
/// fill reflects membership, and toggles add/remove on tap. Styled for the
/// always-dark player surfaces (filled = brand red, empty = muted white).
struct PCHeartButton: View {
    let track: Track
    var iconSize: CGFloat = 20
    var tap: CGFloat = 40
    @State private var inCollection = false
    @State private var sub: Cancellable?
    private let container = IosContainer.companion.shared

    var body: some View {
        Button { toggle() } label: {
            Image(systemName: inCollection ? "heart.fill" : "heart")
                .font(.system(size: iconSize))
                .foregroundStyle(inCollection ? PC.error : .white.opacity(0.6))
                .frame(width: tap, height: tap)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // Re-watch when the track changes (the player stays mounted; currentTrack swaps).
        .task(id: "\(track.title)\u{1}\(track.artist)") { watch() }
        .onDisappear { sub?.cancel(); sub = nil }
    }

    private func watch() {
        sub?.cancel()
        sub = container.watchTrackInCollection(title: track.title, artist: track.artist) { yes in inCollection = yes.boolValue }
    }

    private func toggle() {
        Task {
            if inCollection { try? await container.removeTrackFromCollection(track: track) }
            else { try? await container.addTrackToCollection(track: track) }
        }
    }
}

// MARK: - Add to Playlist submenu (#240)

/// Track-menu "Add to Playlist…" → a native submenu listing the saved playlists;
/// tapping one appends the track. Loads the list when the menu opens. (Creating
/// a NEW playlist needs a host-presented text prompt — tracked separately.)
struct PCAddToPlaylistMenu: View {
    let track: Track
    @State private var playlists: [Playlist] = []
    private let container = IosContainer.companion.shared

    var body: some View {
        Menu {
            Button { PlaylistCreator.shared.start(track: track) } label: {
                Label("New Playlist…", systemImage: "plus")
            }
            if !playlists.isEmpty {
                Divider()
                ForEach(playlists, id: \.id) { p in
                    Button(p.name) {
                        Task { try? await container.addTrackToPlaylist(playlistId: p.id, track: track) }
                    }
                }
            }
        } label: {
            Label("Add to Playlist…", systemImage: "text.badge.plus")
        }
        .task { playlists = (try? await container.getSavedPlaylistsOnce()) ?? [] }
    }
}

// MARK: - Track metadata (tag) editor (#248)

/// Global presenter for the in-app metadata editor, hosted once at the root
/// (mirrors `PlaylistCreator`) so a context-menu button — which can't host its
/// own sheet — can trigger it. Edits the DB row's title/artist/album; does NOT
/// rewrite the on-disk file's tags.
@MainActor
@Observable
final class TrackMetadataEditor {
    static let shared = TrackMetadataEditor()
    private init() {}
    var showing = false
    var trackId = ""
    var title = ""
    var artist = ""
    var album = ""

    func start(_ track: Track) {
        trackId = track.id
        title = track.title
        artist = track.artist
        album = track.album ?? ""
        showing = true
    }

    func save() {
        let id = trackId, t = title, a = artist, al = album
        showing = false
        guard !id.isEmpty else { return }
        let albumOpt = al.isEmpty ? nil : al
        Task { @MainActor in
            try? await IosContainer.companion.shared.updateTrackMetadata(
                id: id, title: t, artist: a, album: albumOpt)
            // Re-resolve under the NEW title/artist so badges + album art refresh.
            // The DB write lands first so the localfiles match (findLocalFile) uses
            // the corrected metadata; the art skeleton shows until this resolves.
            IosTrackResolverCache.shared.resolve(
                ResolveRequest(artist: a, title: t, album: albumOpt), order: 0)
        }
    }
}

/// The editor sheet — title / artist / album fields. Hosted from RootView via
/// `TrackMetadataEditor.shared.showing`.
struct TrackMetadataEditorSheet: View {
    @Bindable var editor: TrackMetadataEditor
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Track Info") {
                    TextField("Title", text: $editor.title)
                    TextField("Artist", text: $editor.artist)
                    TextField("Album", text: $editor.album)
                }
                Section {
                    Text("Updates how this track appears in Parachord — its title, artist, and album for display, playback, and scrobbles. The original file on disk isn't changed.")
                        .font(.system(size: 12)).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Edit Metadata")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { editor.showing = false; dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { editor.save(); dismiss() }
                        .disabled(editor.title.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}

// MARK: - Album / Artist context-menu content (#204)

/// Album menu buttons. Play/Queue are async (fetch tracks → resolve-on-play via
/// the coordinator). Collection toggle is self-managed via PCCollectionToggleButton.
private struct PCAlbumMenuItems: View {
    let title: String
    let artist: String
    let artworkUrl: String?
    let coordinator: QueuePlaybackCoordinator
    var onGoToAlbum: (() -> Void)?
    var onGoToArtist: (() -> Void)?
    private let container = IosContainer.companion.shared

    var body: some View {
        Button { play(queue: false) } label: { Label("Play Album", systemImage: "play.fill") }
        Button { play(queue: true) } label: { Label("Queue Album", systemImage: "text.append") }
        if let onGoToAlbum {
            Button { onGoToAlbum() } label: { Label("Go to Album", systemImage: "square.stack") }
        }
        if let onGoToArtist {
            Button { onGoToArtist() } label: { Label("Go to Artist", systemImage: "music.mic") }
        }
        Divider()
        PCCollectionToggleButton(target: .album(title: title, artist: artist, artworkUrl: artworkUrl))
    }

    private func play(queue: Bool) {
        Task {
            guard let detail = try? await container.getAlbumDetail(albumTitle: title, artistName: artist) else { return }
            let entities = detail.tracks.map { pcTrack(from: $0) }
            guard !entities.isEmpty else { return }
            let ctx = PlaybackContext(type: "album", name: title, id: "\(title)|\(artist)")
            if queue {
                entities.forEach { coordinator.addToQueue($0) }
            } else {
                coordinator.setQueue(entities, startIndex: 0, context: ctx)
            }
        }
    }
}

/// Artist menu buttons. Play/Queue use the artist's top tracks.
private struct PCArtistMenuItems: View {
    let name: String
    let imageUrl: String?
    let coordinator: QueuePlaybackCoordinator
    var onGoToArtist: (() -> Void)?
    private let container = IosContainer.companion.shared

    var body: some View {
        Button { play(queue: false) } label: { Label("Play Top Songs", systemImage: "play.fill") }
        Button { play(queue: true) } label: { Label("Queue Top Songs", systemImage: "text.append") }
        if let onGoToArtist {
            Button { onGoToArtist() } label: { Label("Go to Artist", systemImage: "music.mic") }
        }
        Divider()
        PCCollectionToggleButton(target: .artist(name: name, imageUrl: imageUrl))
    }

    private func play(queue: Bool) {
        Task {
            let top = (try? await container.getArtistTopTracks(artistName: name)) ?? []
            let entities = top.map { pcTrack(from: $0) }
            guard !entities.isEmpty else { return }
            let ctx = PlaybackContext(type: "artist", name: name, id: name)
            if queue {
                entities.forEach { coordinator.addToQueue($0) }
            } else {
                coordinator.setQueue(entities, startIndex: 0, context: ctx)
            }
        }
    }
}

/// Self-managed Add/Remove-from-Collection button used by all three context
/// menus (#204). Observes the DB for the live in-collection state (so the label
/// is correct) and toggles via the container. Used inside `.contextMenu { }`,
/// so the watcher starts when the menu opens.
struct PCCollectionToggleButton: View {
    enum Target {
        case track(Track)
        case album(title: String, artist: String, artworkUrl: String?)
        case artist(name: String, imageUrl: String?)
    }
    let target: Target
    @State private var inCollection = false
    @State private var sub: Cancellable?
    private let container = IosContainer.companion.shared

    var body: some View {
        Button(role: inCollection ? .destructive : nil) { toggle() } label: {
            Label(inCollection ? "Remove from Collection" : "Add to Collection",
                  systemImage: inCollection ? "heart.slash" : "heart")
        }
        .task { startWatch() }
        .onDisappear { sub?.cancel(); sub = nil }
    }

    private func startWatch() {
        guard sub == nil else { return }
        switch target {
        case .track(let t):
            sub = container.watchTrackInCollection(title: t.title, artist: t.artist) { yes in inCollection = yes.boolValue }
        case .album(let title, let artist, _):
            sub = container.watchAlbumInCollection(title: title, artist: artist) { yes in inCollection = yes.boolValue }
        case .artist(let name, _):
            sub = container.watchArtistInCollection(name: name) { yes in inCollection = yes.boolValue }
        }
    }

    private func toggle() {
        Task {
            switch target {
            case .track(let t):
                if inCollection { try? await container.removeTrackFromCollection(track: t) }
                else { try? await container.addTrackToCollection(track: t) }
            case .album(let title, let artist, let art):
                try? await container.toggleAlbumCollection(title: title, artist: artist, artworkUrl: art, year: 0, trackCount: 0)
            case .artist(let name, let image):
                try? await container.toggleArtistCollection(name: name, imageUrl: image)
            }
        }
    }
}

// MARK: - Mock Dynamic Island live activity (chrome.jsx DynamicIslandLive)

/// Prototype now-playing pill near the top. Collapsed = artwork + EQ bars;
/// expands ~4.5s on each track change to reveal the title. A MOCK visual (not
/// an ActivityKit Live Activity), so on a device it sits at/over the hardware
/// Dynamic Island.
struct PCDynamicIsland: View {
    let title: String
    let isPlaying: Bool
    @State private var expanded = false
    @State private var eqUp = false

    var body: some View {
        HStack(spacing: 8) {
            PCArtwork(name: title, size: 24, radius: 6)
            if expanded {
                Text(title).font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white).lineLimit(1).transition(.opacity)
            }
            Spacer(minLength: 0)
            if isPlaying { eqBars }
        }
        .padding(.horizontal, 8)
        .frame(width: expanded ? 220 : 128, height: 37)
        .background(.black, in: Capsule())
        .onAppear { eqUp = true; pulse() }
        .onChange(of: title) { _, _ in pulse() }
    }

    private func pulse() {
        withAnimation(.spring(duration: 0.32)) { expanded = true }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 4_500_000_000)
            withAnimation(.spring(duration: 0.32)) { expanded = false }
        }
    }

    private var eqBars: some View {
        HStack(spacing: 2) {
            ForEach(0..<3, id: \.self) { i in
                let lows: [CGFloat] = [0.4, 0.8, 0.6]
                let highs: [CGFloat] = [1.0, 0.5, 0.85]
                Capsule().fill(PC.accentSoft)
                    .frame(width: 2.5, height: 14)
                    .scaleEffect(y: eqUp ? highs[i] : lows[i], anchor: .center)
                    .animation(.easeInOut(duration: 0.5).repeatForever().delay(Double(i) * 0.15), value: eqUp)
            }
        }
        .frame(height: 14)
    }
}
