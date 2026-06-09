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
            spotifyId: nil, appleMusicId: nil, recordingMbid: nil,
            artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
        )
    }
}

struct PlaylistDetailView: View {
    @State private var model: PlaylistDetailViewModel
    @State private var navArtist: String?
    @State private var navAlbum: PCAlbumRef?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    /// Observed so badge rows re-render as background resolution lands.
    private var resolverCache = IosTrackResolverCache.shared

    init(playlistId: String, title: String) {
        _model = State(initialValue: PlaylistDetailViewModel(playlistId: playlistId, title: title))
    }

    var body: some View {
        Group {
            if model.isLoading && !model.loaded {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if model.tracks.isEmpty {
                ContentUnavailableView(
                    "No tracks",
                    systemImage: "music.note.list",
                    description: Text("This playlist came back empty.")
                )
            } else {
                List {
                    ForEach(Array(model.tracks.enumerated()), id: \.element.id) { index, track in
                        Button {
                            coordinator.setQueue(model.trackEntities, startIndex: index)
                        } label: {
                            row(index: index, track: track)
                        }
                        .buttonStyle(.plain)
                        // Visibility-scoped resolution (desktop parity): each
                        // row resolves itself only when it scrolls into view,
                        // tagged with `index` so the shared cache resolves
                        // top-down rather than in onAppear order.
                        .onAppear { model.resolveVisible(track, index: index) }
                        .pcTrackContextMenu(
                            model.trackEntities[index], coordinator: coordinator,
                            onGoToArtist: { navArtist = model.trackEntities[index].artist },
                            onGoToAlbum: model.trackEntities[index].album.map { album in
                                { navAlbum = PCAlbumRef(title: album, artist: model.trackEntities[index].artist) }
                            }
                        )
                    }
                }
            }
        }
        .navigationTitle(model.title)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0) }
        .navigationDestination(item: $navAlbum) { AlbumScreen(title: $0.title, artist: $0.artist) }
        .task { await model.load() }
    }

    @ViewBuilder
    private func row(index: Int, track: IosPlaylistTrack) -> some View {
        let isCurrent = index < model.trackEntities.count
            && coordinator.currentTrack?.id == model.trackEntities[index].id
        HStack(spacing: 12) {
            Text("\(index + 1)")
                .font(.callout.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 28, alignment: .trailing)
            VStack(alignment: .leading, spacing: 3) {
                Text(track.title)
                    .font(.body)
                    .foregroundStyle(isCurrent ? Color(red: 0.49, green: 0.23, blue: 0.93) : .primary)
                    .lineLimit(1)
                Text(albumLine(track))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            // Resolver squares sit rightmost (Android TrackRow order: after
            // the duration; these LB tracks have no duration).
            if let ranked = resolverCache.cached(artist: track.artist, title: track.title, album: track.album),
               !ranked.isEmpty {
                ResolverBadgeRow(sources: ranked)
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
    }

    private func albumLine(_ track: IosPlaylistTrack) -> String {
        if let album = track.album, !album.isEmpty {
            return "\(track.artist) · \(album)"
        }
        return track.artist
    }
}
