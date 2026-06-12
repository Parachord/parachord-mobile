import SwiftUI
import Shared

// MARK: - Pop of the Tops (Phase 4 — Albums + Songs tabs, desktop parity)
//
// Apple Music (iTunes RSS) top ALBUMS (default tab) + top SONGS via the shared
// ChartsRepository. Album rows navigate straight to the AlbumScreen; song rows
// play, with a long-press "Go to Album/Artist". Per-row visibility resolution
// on songs keeps playback instant without bulk-searching Spotify (rate-safe).

@MainActor
@Observable
final class PopOfTheTopsModel {
    static let shared = PopOfTheTopsModel()
    private let container = IosContainer.companion.shared
    var albums: [ChartAlbum] = []
    var songs: [ChartSong] = []
    var songEntities: [Track] = []
    var isLoading = false
    var loaded = false
    private var lastLoad: Date?
    private let ttl: TimeInterval = 2 * 3600

    func load() async {
        if loaded, let l = lastLoad, Date().timeIntervalSince(l) < ttl { return }
        isLoading = true
        let al = (try? await container.loadPopOfTheTopsAlbums(countryCode: "us")) ?? []
        if !al.isEmpty { albums = al }
        let s = (try? await container.loadPopOfTheTops(countryCode: "us")) ?? []
        if !s.isEmpty {
            songs = s
            songEntities = s.map { Self.makeTrack($0) }
        }
        lastLoad = Date()
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

private enum PopTab: String, CaseIterable { case albums = "Albums", songs = "Songs" }

struct PopOfTheTopsScreen: View {
    @State private var model = PopOfTheTopsModel.shared
    @State private var tabIndex = 0
    @State private var navArtist: String?
    @State private var navAlbum: PCAlbumRef?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.dismiss) private var dismiss
    private var resolverCache = IosTrackResolverCache.shared

    private var bannerCount: String? {
        guard !model.albums.isEmpty || !model.songs.isEmpty else { return nil }
        return "\(model.albums.count) albums · \(model.songs.count) songs"
    }

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: "Pop of the Tops", leading: .back, onLeading: { dismiss() })
            PCCuratedBanner(
                icon: "chart.bar.fill",
                subtitle: "What's trending around the world",
                count: bannerCount,
                gradient: [0xF97316, 0xEC4899, 0x8B5CF6])
            PCTabs(tabs: ["Albums", "Songs"], selection: $tabIndex)

            if model.isLoading && !model.loaded {
                ScrollView { PCSkeletonGrid(count: 6) }
            } else {
                ScrollView {
                    if tabIndex == 0 { albumsGrid } else { songsList }
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0) }
        .navigationDestination(item: $navAlbum) { AlbumScreen(title: $0.title, artist: $0.artist) }
        .task { await model.load() }
    }

    // ── Albums tab: 2-column grid (rank badge on the art) → album page ──
    private var albumsGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 16) {
            ForEach(Array(model.albums.enumerated()), id: \.element.id) { index, album in
                NavigationLink(value: PCRoute.album(title: album.title, artist: album.artist)) {
                    VStack(alignment: .leading, spacing: 4) {
                        ZStack(alignment: .topLeading) {
                            albumArt(album.artworkUrl, seed: album.title + album.artist)
                            Text("#\(index + 1)").font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                                .padding(.horizontal, 6).padding(.vertical, 2)
                                .background(Color.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 4))
                                .padding(6)
                        }
                        Text(album.title).font(.system(size: 14, weight: .medium)).foregroundStyle(PC.fg1)
                            .lineLimit(1).padding(.horizontal, 2)
                        Text(album.artist).font(.system(size: 12)).foregroundStyle(PC.fg2)
                            .lineLimit(1).padding(.horizontal, 2)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8).padding(.bottom, 120)
    }

    @ViewBuilder
    private func albumArt(_ url: String?, seed: String) -> some View {
        Group {
            if let url, let u = URL(string: url) {
                PCCachedImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                    placeholder: { PCArtwork(name: seed, size: nil, radius: 8) }
            } else {
                PCArtwork(name: seed, size: nil, radius: 8)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .shadow(color: .black.opacity(0.12), radius: 6, y: 3)
    }

    // ── Songs tab: rank + art + title/artist + resolver squares ─────────
    private var songsList: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(model.songs.enumerated()), id: \.element.id) { index, song in
                Button {
                    coordinator.setQueue(model.songEntities, startIndex: index)
                } label: {
                    HStack(spacing: 12) {
                        Text("\(index + 1)").font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(PC.fg3).frame(width: 24)
                        songArt(song.artworkUrl, seed: song.title + song.artist)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(song.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(coordinator.currentTrack?.id == model.songEntities[index].id ? PC.accent : PC.fg1)
                                .lineLimit(1)
                            Text(song.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                        }
                        Spacer(minLength: 8)
                        if let sources = resolverCache.cached(artist: song.artist, title: song.title, album: song.album),
                           !sources.isEmpty {
                            ResolverBadgeRow(sources: sources)
                        }
                    }
                    .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .onAppear { model.resolveVisible(song, index: index) }
                .pcTrackContextMenu(
                    model.songEntities[index], coordinator: coordinator,
                    onGoToArtist: { navArtist = song.artist },
                    onGoToAlbum: song.album.map { album in { navAlbum = PCAlbumRef(title: album, artist: song.artist) } }
                )
            }
        }
        .padding(.vertical, 4).padding(.bottom, 120)
    }

    @ViewBuilder
    private func songArt(_ url: String?, seed: String) -> some View {
        if let url, let u = URL(string: url) {
            PCCachedImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                placeholder: { PCArtwork(name: seed, size: 44, radius: 6) }
                .frame(width: 44, height: 44).clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        } else {
            PCArtwork(name: seed, size: 44, radius: 6)
        }
    }
}
