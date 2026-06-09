import SwiftUI
import Shared

// MARK: - Pop of the Tops (Phase 4 — first curated list)
//
// Apple Music (iTunes RSS) top songs via the shared ChartsRepository, wired
// through IosContainer.loadPopOfTheTops. Numbered rows (design .ios-trk),
// tap-to-play, long-press context menu. Per-row visibility resolution keeps
// playback instant without bulk-searching Spotify on open (rate-limit safe).

@MainActor
@Observable
final class PopOfTheTopsModel {
    private let container = IosContainer.companion.shared
    var songs: [ChartSong] = []
    var entities: [Track] = []
    var isLoading = false
    var loaded = false

    func load() async {
        guard !loaded else { return }
        isLoading = true
        let result = (try? await container.loadPopOfTheTops(countryCode: "us")) ?? []
        songs = result
        entities = result.map { Self.makeTrack($0) }
        isLoading = false
        loaded = true
    }

    func resolveVisible(_ song: ChartSong, index: Int) {
        IosTrackResolverCache.shared.resolve(
            ResolveRequest(artist: song.artist, title: song.title, album: song.album),
            order: index
        )
    }

    private static func makeTrack(_ s: ChartSong) -> Track {
        Track(
            id: s.id, title: s.title, artist: s.artist,
            album: s.album, albumId: nil, duration: nil, artworkUrl: s.artworkUrl,
            sourceType: nil, sourceUrl: nil, addedAt: 0,
            resolver: nil, spotifyUri: nil, soundcloudId: nil,
            spotifyId: s.spotifyId, appleMusicId: nil, recordingMbid: s.mbid,
            artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
        )
    }
}

struct PopOfTheTopsScreen: View {
    @State private var model = PopOfTheTopsModel()
    @State private var navArtist: String?
    @State private var navAlbum: PCAlbumRef?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator

    var body: some View {
        Group {
            if model.isLoading && !model.loaded {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(Array(model.songs.enumerated()), id: \.element.id) { index, song in
                        Button {
                            coordinator.setQueue(model.entities, startIndex: index)
                        } label: {
                            row(index: index, song: song)
                        }
                        .buttonStyle(.plain)
                        .onAppear { model.resolveVisible(song, index: index) }
                        .pcTrackContextMenu(
                            model.entities[index], coordinator: coordinator,
                            onGoToArtist: { navArtist = song.artist },
                            onGoToAlbum: song.album.map { album in { navAlbum = PCAlbumRef(title: album, artist: song.artist) } }
                        )
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Pop of the Tops")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0) }
        .navigationDestination(item: $navAlbum) { AlbumScreen(title: $0.title, artist: $0.artist) }
        .task { await model.load() }
    }

    private func row(index: Int, song: ChartSong) -> some View {
        let isCurrent = coordinator.currentTrack?.id == model.entities[index].id
        return HStack(spacing: 12) {
            Text("\(index + 1)").font(.system(size: 13, design: .monospaced))
                .foregroundStyle(PC.fg3).frame(width: 24, alignment: .center)
            artwork(song)
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title).font(.system(size: 15, weight: .medium))
                    .foregroundStyle(isCurrent ? PC.accent : PC.fg1).lineLimit(1)
                Text(song.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
    }

    @ViewBuilder
    private func artwork(_ song: ChartSong) -> some View {
        if let url = song.artworkUrl, let u = URL(string: url) {
            AsyncImage(url: u) { img in
                img.resizable().aspectRatio(contentMode: .fill)
            } placeholder: {
                PCArtwork(name: song.title + song.artist, size: 44, radius: 6)
            }
            .frame(width: 44, height: 44)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        } else {
            PCArtwork(name: song.title + song.artist, size: 44, radius: 6)
        }
    }
}
