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

        // Resolver Pipeline Rule: every tracklist screen pre-resolves its
        // tracks in the background → resolver badges + instant tap-to-play.
        IosTrackResolverCache.shared.resolveInBackground(
            tracks.map { ResolveRequest(artist: $0.artist, title: $0.title, album: $0.album) }
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
                    }
                }
            }
        }
        .navigationTitle(model.title)
        .navigationBarTitleDisplayMode(.inline)
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
                // Resolver badges from the background-resolution cache.
                if let ranked = resolverCache.cached(artist: track.artist, title: track.title, album: track.album),
                   !ranked.isEmpty {
                    ResolverBadgeRow(sources: ranked)
                }
            }
            Spacer(minLength: 0)
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
