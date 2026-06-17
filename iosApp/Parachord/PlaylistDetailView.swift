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

    init(playlistId: String, title: String) {
        self.playlistId = playlistId
        self.title = title
    }

    func load() async {
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
                            Button { coordinator.setQueue(model.trackEntities, startIndex: index) } label: {
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
                Button { coordinator.setQueue(model.trackEntities, startIndex: 0) } label: {
                    Label("Play All", systemImage: "play.fill").font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white).padding(.horizontal, 22).frame(height: 40)
                        .background(PC.accent, in: Capsule())
                }.buttonStyle(.plain)
                // Save: needs the iOS library DB layer — present for parity, wired later.
                Button {} label: {
                    Label("Save", systemImage: "square.and.arrow.down").font(.system(size: 15, weight: .medium))
                        .foregroundStyle(PC.fg1).padding(.horizontal, 18).frame(height: 40)
                        .overlay(Capsule().strokeBorder(PC.border))
                }.buttonStyle(.plain).disabled(true).opacity(0.45)
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
