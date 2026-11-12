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
    /// Android's filter bar: chart country ("" = Global, Last.fm songs only),
    /// the Songs tab's source ("apple" / "lastfm"), and the search box.
    var country = "us"
    var songsSource = "apple"
    var query = ""
    private var lastLoad: Date?
    private var lastKey = ""
    private let ttl: TimeInterval = 6 * 3600

    var countries: [ChartCountry] { container.chartCountries() }

    /// The picker's label: "Global" only means something for Last.fm songs.
    func countryName(onSongsTab: Bool) -> String {
        if country.isEmpty && onSongsTab && songsSource == "lastfm" { return "Global" }
        return countries.first { $0.code == country }?.name ?? "United States"
    }

    var visibleAlbums: [ChartAlbum] { ChartsModelsKt.filterChartAlbums(albums: albums, query: query) }
    /// Filtered songs, still paired with their queue entities.
    var visibleSongs: [(ChartSong, Track)] {
        let keep = Set(ChartsModelsKt.filterChartSongs(songs: songs, query: query).map(\.id))
        return zip(songs, songEntities).filter { keep.contains($0.0.id) }
    }

    func setCountry(_ code: String) {
        guard code != country else { return }
        country = code
        Task { await load() }
    }

    func setSongsSource(_ source: String) {
        guard source != songsSource else { return }
        songsSource = source
        Task { await load() }
    }

    func load() async {
        let key = "\(country)|\(songsSource)"
        if loaded, key == lastKey, let l = lastLoad, Date().timeIntervalSince(l) < ttl { return }
        isLoading = true
        let (c, src) = (country, songsSource)
        let al = (try? await container.loadPopOfTheTopsAlbums(countryCode: c)) ?? []
        let s = (try? await container.loadPopOfTheTopsSongs(countryCode: c, source: src)) ?? []
        // A newer pick landed while this one loaded: let that load fill the lists.
        guard c == country, src == songsSource else { return }
        albums = al
        songs = s
        songEntities = s.map { Self.makeTrack($0) }
        lastKey = key
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
            spotifyId: s.spotifyId, appleMusicId: nil, isrc: nil, recordingMbid: s.mbid,
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
            filterBar

            if model.isLoading && (!model.loaded || model.albums.isEmpty && model.songs.isEmpty) {
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

    // ── Filter bar (Android `ChartsFilterBar`): country, source (Songs), search ──
    @State private var searchOpen = false
    private let orange = Color(uiColor: UIColor(hex: 0xF97316))

    private var filterBar: some View {
        let onSongs = tabIndex == 1
        return VStack(spacing: 0) {
            HStack(spacing: 4) {
                Menu {
                    if onSongs && model.songsSource == "lastfm" {
                        Button { model.setCountry("") } label: {
                            if model.country.isEmpty { Label("Global", systemImage: "checkmark") } else { Text("Global") }
                        }
                    }
                    ForEach(model.countries, id: \.code) { c in
                        Button { model.setCountry(c.code) } label: {
                            if model.country == c.code { Label(c.name, systemImage: "checkmark") } else { Text(c.name) }
                        }
                    }
                } label: { filterChip(model.countryName(onSongsTab: onSongs)) }
                if onSongs {
                    Menu {
                        ForEach([("apple", "Apple Music"), ("lastfm", "Last.fm")], id: \.0) { id, name in
                            Button { model.setSongsSource(id) } label: {
                                if model.songsSource == id { Label(name, systemImage: "checkmark") } else { Text(name) }
                            }
                        }
                    } label: { filterChip(model.songsSource == "lastfm" ? "Last.fm" : "Apple Music") }
                }
                Spacer()
                if model.isLoading && model.loaded { ProgressView().controlSize(.small).tint(orange) }
                Button {
                    searchOpen.toggle()
                    if !searchOpen { model.query = "" }
                } label: {
                    Image(systemName: searchOpen ? "xmark" : "magnifyingglass")
                        .font(.system(size: 16)).foregroundStyle(PC.fg2).frame(width: 36, height: 36)
                }
                .accessibilityLabel(searchOpen ? "Close search" : "Search")
            }
            .padding(.horizontal, 8).padding(.vertical, 2)
            if searchOpen {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").font(.system(size: 14)).foregroundStyle(PC.fg3)
                    TextField("Search…", text: $model.query)
                        .font(.system(size: 14)).textInputAutocapitalization(.never).autocorrectionDisabled()
                    if !model.query.isEmpty {
                        Button { model.query = "" } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(PC.fg3)
                        }
                        .accessibilityLabel("Clear")
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 10)
                .background(PC.bgInset, in: RoundedRectangle(cornerRadius: 8))
                .padding(.horizontal, 16).padding(.bottom, 8)
            }
        }
    }

    private func filterChip(_ text: String) -> some View {
        HStack(spacing: 2) {
            Text(text).font(.system(size: 13, weight: .medium)).foregroundStyle(PC.fg1)
            Image(systemName: "chevron.down").font(.system(size: 10, weight: .semibold)).foregroundStyle(PC.fg2)
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .contentShape(Rectangle())
    }

    // ── Albums tab: 2-column grid (rank badge on the art) → album page ──
    private var albumsGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 16) {
            ForEach(Array(model.visibleAlbums.enumerated()), id: \.element.id) { index, album in
                NavigationLink(value: PCRoute.album(title: album.title, artist: album.artist)) {
                    VStack(alignment: .leading, spacing: 4) {
                        ZStack(alignment: .topLeading) {
                            albumArt(album.artworkUrl, seed: album.title + album.artist)
                            Text("#\(album.rank > 0 ? Int(album.rank) : index + 1)").font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
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
                .pcAlbumContextMenu(title: album.title, artist: album.artist, artworkUrl: album.artworkUrl,
                    coordinator: coordinator,
                    onGoToAlbum: { navAlbum = PCAlbumRef(title: album.title, artist: album.artist) },
                    onGoToArtist: { navArtist = album.artist })
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
            // ZIP songs + entities so the two parallel arrays stay paired — they're
            // set in separate @Observable assignments, so a raw songEntities[index]
            // can go out of range and crash on render after a background
            // auto-advance changes currentTrack. #322.
            let visible = model.visibleSongs
            ForEach(Array(visible.enumerated()), id: \.offset) { index, pair in
                let (song, entity) = pair
                Button {
                    // Android plays from the tapped song through the end of the
                    // shown (filtered) list.
                    coordinator.setQueue(visible.map(\.1), startIndex: index,
                                         context: PlaybackContext(type: "charts", name: "Top Songs", id: nil))
                } label: {
                    HStack(spacing: 12) {
                        Text("\(song.rank > 0 ? Int(song.rank) : index + 1)").font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(PC.fg3).frame(width: 24)
                        songArt(song.artworkUrl, seed: song.title + song.artist)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(song.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(pcTrackNoMatch(artist: song.artist, title: song.title, album: song.album) ? PC.fg3
                                    : (coordinator.currentTrack?.id == entity.id ? PC.accent : PC.fg1))
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
                .onDisappear { IosTrackResolverCache.shared.leave(artist: song.artist, title: song.title) }
                .pcTrackContextMenu(
                    entity, coordinator: coordinator,
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
