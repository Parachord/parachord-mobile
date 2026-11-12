import SwiftUI
import Shared

// MARK: - Search (#233 — full Android parity)
//
// The rules come from the shared SearchSession (#590), as on Android: a
// debounced query (two characters or more) drives local DB tracks ("Library")
// and albums ("Library Albums"), plus remote artists / tracks / albums from the
// shared MetadataService cascade, with resolver badges + tap-to-play + context
// menus; below two characters it shows Search History (recent
// searches, tap to re-run, swipe/X to delete, Clear All). Result section order
// matches Android (Library → Artists → Tracks → Albums); the search-field +
// Recent-Searches visuals follow the iOS design (screens.jsx SearchScreen).
//
// History persists through the shared SearchHistoryDao (same table/contract as
// Android + desktop), wired into IosContainer (#233).

@MainActor
@Observable
final class SearchViewModel {

    private let container = IosContainer.companion.shared

    var query: String = ""
    var localTracks: [Track] = []
    var localAlbums: [Album] = []
    var artists: [IosArtistResult] = []
    var remoteTracks: [TrackSearchResult] = []
    var remoteAlbums: [AlbumSearchResult] = []
    /// Covers found for rows that came without one, keyed by
    /// `SearchSession.companion.trackArtKey` / `albumArtKey` / `artistArtKey`.
    var art: [String: String] = [:]
    var isSearching = false
    var hasSearched = false
    var history: [SearchHistory] = []
    /// MusicBrainz is throttling us (#375): an empty result then isn't "no
    /// results", so the screen says so instead (Android's SearchScreen).
    var musicBrainzRateLimited = false
    private var rateLimitWatcher: Cancellable?

    /// The shared session (#590) holds the rules: two characters, 300 ms
    /// debounce, a keystroke cancelling the running search in Kotlin (#475),
    /// local tracks + albums, the remote categories and covers for rows
    /// without one. The rows on screen resolve here, as they appear.
    private let session: SearchSession
    private var sessionWatcher: Cancellable?
    private var historyWatcher: Cancellable?

    init() {
        session = IosContainer.companion.shared.makeSearchSession()
    }

    /// Start watching search history and the session (idempotent). Called from
    /// the view's `.task`; the appScope-backed watchers live with the
    /// (tab-persistent) VM, matching the FlowWatcher lifecycle used by
    /// Discover/Curated screens.
    func start() {
        guard historyWatcher == nil else { return }
        historyWatcher = container.watchSearchHistory { [weak self] items in
            self?.history = items
        }
        rateLimitWatcher = container.watchMusicBrainzRateLimited { [weak self] limited in
            Task { @MainActor in self?.musicBrainzRateLimited = limited.boolValue }
        }
        sessionWatcher = container.watchSearch(session: session) { [weak self] state in
            guard let self else { return }
            self.localTracks = state.localTracks
            self.localAlbums = state.localAlbums
            self.artists = state.artists
            self.remoteTracks = state.remoteTracks
            self.remoteAlbums = state.remoteAlbums
            self.art = state.art
            self.isSearching = state.isSearching
            self.hasSearched = state.hasSearched
        }
    }

    /// Results show once the query searches (two characters, as desktop);
    /// below that we show Recent Searches.
    var showResults: Bool { SearchSession.companion.isSearchable(text: query) }

    var hasAnyResults: Bool {
        !localTracks.isEmpty || !localAlbums.isEmpty || !artists.isEmpty
            || !remoteTracks.isEmpty || !remoteAlbums.isEmpty
    }

    func onQueryChange(_ newValue: String) {
        query = newValue
        session.query(text: newValue)
    }

    /// A result row came on screen: the shared session looks up its cover if
    /// it has none (#590), as for the first rows. Android does the same.
    func trackShown(_ t: TrackSearchResult) { session.trackShown(track: t) }
    func albumShown(_ a: AlbumSearchResult) { session.albumShown(album: a) }
    func artistShown(_ a: IosArtistResult) { session.artistShown(name: a.name, imageUrl: a.imageUrl) }

    func trackArt(_ t: TrackSearchResult) -> String? {
        t.artworkUrl ?? art[SearchSession.companion.trackArtKey(title: t.title, artist: t.artist)]
    }

    func albumArt(_ a: AlbumSearchResult) -> String? {
        art[SearchSession.companion.albumArtKey(title: a.title, artist: a.artist)] ?? a.artworkUrl
    }

    func artistImage(_ a: IosArtistResult) -> String? {
        a.imageUrl ?? art[SearchSession.companion.artistArtKey(name: a.name)]
    }

    /// Metadata-only shared `Track` from a remote search hit (the 19-param
    /// initializer overwhelms Swift's inline type-checker, so it lives here).
    func makeTrack(_ t: TrackSearchResult) -> Track {
        Track(
            id: "\(t.title)|\(t.artist)", title: t.title, artist: t.artist,
            album: t.album, albumId: nil, duration: t.duration, artworkUrl: trackArt(t),
            sourceType: nil, sourceUrl: nil, addedAt: 0,
            resolver: nil, spotifyUri: nil, soundcloudId: nil,
            spotifyId: t.spotifyId, appleMusicId: nil, isrc: nil, recordingMbid: t.mbid,
            artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
        )
    }

    var remoteTrackEntities: [Track] { remoteTracks.map { makeTrack($0) } }

    // ── History (save on select; dedup + trim handled in the container) ──────
    func saveHistory(type: String, name: String, artist: String?, artwork: String?) {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        Task { try? await container.saveSearchHistory(query: q, resultType: type, resultName: name, resultArtist: artist, artworkUrl: artwork) }
    }
    func deleteHistory(_ id: Int64) { Task { try? await container.deleteSearchHistory(id: id) } }
    func clearHistory() { Task { try? await container.clearSearchHistory() } }
}

// MARK: - Search screen

struct SearchView: View {
    @State private var model = SearchViewModel()
    @State private var navArtist: PCArtistRef?
    @State private var navAlbum: PCAlbumRef?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    /// Observed so badge rows re-render as background resolution lands.
    private var resolverCache = IosTrackResolverCache.shared
    let onMenu: () -> Void
    /// Deep-link prefill (#256): when set, seed the query + run the search, then clear.
    private var pendingQuery: Binding<String?>

    init(onMenu: @escaping () -> Void = {}, pendingQuery: Binding<String?> = .constant(nil)) {
        self.onMenu = onMenu
        self.pendingQuery = pendingQuery
    }

    private var ctx: PlaybackContext {
        PlaybackContext(type: "search", name: model.query, id: "search")
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                PCTopBar(title: "Search", leading: .menu, onLeading: onMenu)
                searchField
                if model.showResults {
                    resultsList
                } else {
                    historyList
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0.name, mbid: $0.mbid) }
            .navigationDestination(item: $navAlbum) { AlbumScreen(title: $0.title, artist: $0.artist) }
            .task {
                model.start()
                if let q = pendingQuery.wrappedValue, !q.isEmpty {
                    model.onQueryChange(q)
                    pendingQuery.wrappedValue = nil
                }
            }
        }
    }

    // MARK: Search field (design: screens.jsx ios-searchField)

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").font(.system(size: 14)).foregroundStyle(PC.fg3)
            TextField("Search tracks, albums, artists…", text: Binding(
                get: { model.query }, set: { model.onQueryChange($0) }))
                .font(.system(size: 15)).textInputAutocapitalization(.never).autocorrectionDisabled()
            if !model.query.isEmpty {
                Button { model.onQueryChange("") } label: {
                    Image(systemName: "xmark.circle.fill").font(.system(size: 16)).foregroundStyle(PC.fg3)
                }.buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 9)
        .background(PC.bgInset, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 6)
    }

    // MARK: Recent searches (query blank)

    @ViewBuilder
    private var historyList: some View {
        if model.history.isEmpty {
            Spacer()
            VStack(spacing: 8) {
                Image(systemName: "magnifyingglass").font(.system(size: 34)).foregroundStyle(PC.fg3)
                Text("Search for tracks, albums, and artists")
                    .font(.system(size: 14)).foregroundStyle(PC.fg2)
            }
            Spacer()
        } else {
            ScrollView {
                HStack {
                    Text("Recent Searches").font(.system(size: 13, weight: .semibold)).foregroundStyle(PC.fg2)
                    Spacer()
                    Button("Clear All") { model.clearHistory() }
                        .font(.system(size: 13)).foregroundStyle(PC.accent)
                }
                .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 4)

                LazyVStack(spacing: 0) {
                    ForEach(model.history, id: \.id) { entry in
                        historyRow(entry)
                    }
                }
                .padding(.bottom, 130)
            }
        }
    }

    private func historyRow(_ entry: SearchHistory) -> some View {
        Button {
            model.onQueryChange(entry.query)   // re-run the search (Android parity)
        } label: {
            HStack(spacing: 12) {
                pcCover(entry.artworkUrl, seed: entry.resultName ?? entry.query, size: 44, radius: 6)
                VStack(alignment: .leading, spacing: 2) {
                    Text("“\(entry.query)”").font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                    Text(historySubtitle(entry)).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                }
                Spacer(minLength: 8)
                Button { model.deleteHistory(entry.id) } label: {
                    Image(systemName: "xmark").font(.system(size: 12, weight: .semibold)).foregroundStyle(PC.fg3)
                }.buttonStyle(.plain)
            }
            .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func historySubtitle(_ e: SearchHistory) -> String {
        let type = (e.resultType ?? "").capitalized
        let name = e.resultName ?? ""
        if type.isEmpty { return name }
        return name.isEmpty ? type : "\(type): \(name)"
    }

    // MARK: Results

    @ViewBuilder
    private var resultsList: some View {
        if !model.hasAnyResults && !model.hasSearched {
            Spacer(); ProgressView(); Spacer()
        } else if !model.hasAnyResults {
            Spacer()
            if model.musicBrainzRateLimited {
                // A throttled MusicBrainz hasn't shown that nothing matched.
                Text(UiMessages.shared.MUSICBRAINZ_RATE_LIMITED)
                    .font(.system(size: 14)).foregroundStyle(PC.fg2)
                    .multilineTextAlignment(.center).padding(32)
            } else {
                ContentUnavailableView.search(text: model.query)
            }
            Spacer()
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if !model.localTracks.isEmpty {
                        sectionHeader("Library")
                        ForEach(Array(model.localTracks.prefix(5).enumerated()), id: \.element.id) { i, track in
                            trackRow(track, index: i, queue: Array(model.localTracks.prefix(5)),
                                     resultType: "track", resultName: track.title, resultArtist: track.artist,
                                     artwork: track.artworkUrl)
                        }
                    }
                    if !model.localAlbums.isEmpty {
                        sectionHeader("Library Albums")
                        ForEach(Array(model.localAlbums.prefix(5)), id: \.id) { album in
                            localAlbumRow(album)
                        }
                    }
                    if !model.artists.isEmpty {
                        sectionHeader("Artists")
                        ForEach(Array(model.artists.enumerated()), id: \.offset) { _, artist in
                            artistRow(artist).onAppear { model.artistShown(artist) }
                        }
                    }
                    if !model.remoteTracks.isEmpty {
                        sectionHeader("Tracks")
                        let entities = model.remoteTrackEntities
                        // ZIP so a shorter entities array (non-atomic @Observable
                        // update) can't index out of range vs remoteTracks. #322.
                        ForEach(Array(zip(model.remoteTracks, entities).enumerated()), id: \.offset) { i, pair in
                            let (t, entity) = pair
                            trackRow(entity, index: i, queue: entities,
                                     resultType: "track", resultName: t.title, resultArtist: t.artist,
                                     artwork: entity.artworkUrl)
                                .onAppear { model.trackShown(t) }
                        }
                    }
                    if !model.remoteAlbums.isEmpty {
                        sectionHeader("Albums")
                        ForEach(Array(model.remoteAlbums.enumerated()), id: \.offset) { _, album in
                            albumRow(album).onAppear { model.albumShown(album) }
                        }
                    }
                }
                .padding(.bottom, 130)
            }
        }
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(PC.fg2)
            .padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 6)
    }

    private func trackRow(_ track: Track, index: Int, queue: [Track],
                          resultType: String, resultName: String, resultArtist: String?, artwork: String?) -> some View {
        Button {
            model.saveHistory(type: resultType, name: resultName, artist: resultArtist, artwork: artwork)
            coordinator.setQueue(queue, startIndex: index, context: ctx)
        } label: {
            HStack(spacing: 12) {
                pcCover(pcTrackArt(track.artworkUrl, artist: track.artist, title: track.title, album: track.album),
                        seed: track.title + track.artist, size: 44, radius: 6, artist: track.artist, album: track.album)
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                    Text(track.album.map { "\(track.artist) · \($0)" } ?? track.artist)
                        .font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                }
                Spacer(minLength: 8)
                if let ranked = resolverCache.cached(artist: track.artist, title: track.title, album: track.album),
                   !ranked.isEmpty {
                    ResolverBadgeRow(sources: ranked)
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onAppear { resolverCache.resolve(ResolveRequest(artist: track.artist, title: track.title, album: track.album), order: index) }
        .onDisappear { IosTrackResolverCache.shared.leave(artist: track.artist, title: track.title) }
        .pcTrackContextMenu(
            track, coordinator: coordinator,
            onGoToArtist: { navArtist = PCArtistRef(name: track.artist) },
            onGoToAlbum: track.album.map { album in { navAlbum = PCAlbumRef(title: album, artist: track.artist) } })
    }

    private func artistRow(_ artist: IosArtistResult) -> some View {
        // A row without a photo gets one from the artist-photo chain (the
        // shared session looks it up, as desktop's search artist cards do).
        let image = model.artistImage(artist)
        return Button {
            model.saveHistory(type: "artist", name: artist.name, artist: nil, artwork: image)
            // MusicBrainz rows carry the artist's id, so the page skips
            // MusicBrainz's name search (#478).
            navArtist = PCArtistRef(name: artist.name, mbid: artist.mbid)
        } label: {
            HStack(spacing: 12) {
                pcCover(image, seed: artist.name, size: 44, radius: 22)
                Text(artist.name).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(PC.fg3)
            }
            .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func localAlbumRow(_ album: Album) -> some View {
        Button {
            model.saveHistory(type: "album", name: album.title, artist: album.artist, artwork: album.artworkUrl)
            navAlbum = PCAlbumRef(title: album.title, artist: album.artist)
        } label: {
            HStack(spacing: 12) {
                pcCover(album.artworkUrl, seed: album.title + album.artist, size: 44, radius: 6, artist: album.artist, album: album.title)
                VStack(alignment: .leading, spacing: 2) {
                    Text(album.title).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                    Text(localAlbumSubtitle(album)).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(PC.fg3)
            }
            .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func localAlbumSubtitle(_ a: Album) -> String {
        if let y = a.year { return "\(y.intValue) · \(a.artist)" }
        return a.artist
    }

    private func albumRow(_ album: AlbumSearchResult) -> some View {
        let art = model.albumArt(album)
        return Button {
            model.saveHistory(type: "album", name: album.title, artist: album.artist, artwork: art)
            navAlbum = PCAlbumRef(title: album.title, artist: album.artist)
        } label: {
            HStack(spacing: 12) {
                pcCover(art, seed: album.title + album.artist, size: 44, radius: 6, artist: album.artist, album: album.title)
                VStack(alignment: .leading, spacing: 2) {
                    Text(album.title).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                    Text(albumSubtitle(album)).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(PC.fg3)
            }
            .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func albumSubtitle(_ a: AlbumSearchResult) -> String {
        // year is a boxed KotlinInt? across the KMP boundary — needs .intValue.
        if let y = a.year { return "\(y.intValue) · \(a.artist)" }
        return a.artist
    }
}
