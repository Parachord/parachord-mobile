import SwiftUI
import Shared

// MARK: - Search (#233 — full Android parity)
//
// Android's SearchViewModel/SearchScreen is the source of truth: a debounced
// query drives local DB tracks ("Library"), plus remote artists / tracks /
// albums from the shared MetadataService cascade, with resolver badges + tap-to-
// play + context menus; when the query is empty it shows Search History (recent
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
    var artists: [IosArtistResult] = []
    var remoteTracks: [TrackSearchResult] = []
    var remoteAlbums: [AlbumSearchResult] = []
    var isSearching = false
    var hasSearched = false
    var history: [SearchHistory] = []

    private var searchTask: Task<Void, Never>?
    private var historyWatcher: Cancellable?

    /// Start watching search history (idempotent). Called from the view's
    /// `.task`; the appScope-backed watcher lives with the (tab-persistent) VM,
    /// matching the FlowWatcher lifecycle used by Discover/Curated screens.
    func start() {
        guard historyWatcher == nil else { return }
        historyWatcher = container.watchSearchHistory { [weak self] items in
            self?.history = items
        }
    }

    /// Mirror Android/desktop: results show once the query is >1 char; below
    /// that we show Recent Searches.
    var showResults: Bool { query.trimmingCharacters(in: .whitespaces).count > 1 }

    /// Debounced (300ms, matching Android's `.debounce(300)`). Cancels the
    /// in-flight request on each keystroke so stale results never render.
    func onQueryChange(_ newValue: String) {
        query = newValue
        searchTask?.cancel()
        let q = newValue.trimmingCharacters(in: .whitespaces)
        guard q.count > 1 else {
            localTracks = []; artists = []; remoteTracks = []; remoteAlbums = []
            hasSearched = false; isSearching = false
            return
        }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            await run(q)
        }
    }

    private func run(_ q: String) async {
        isSearching = true
        // Fan out the four searches concurrently (local DB + the three remote
        // MetadataService cascades), like Android's parallel collectors.
        async let local = container.searchLocalTracks(query: q)
        async let arts = container.searchArtistsRemote(query: q, limit: 10)
        async let trks = container.searchTracksRemote(query: q, limit: 20)
        async let albs = container.searchAlbumsRemote(query: q, limit: 10)
        let l = (try? await local) ?? []
        let a = (try? await arts) ?? []
        let t = (try? await trks) ?? []
        let b = (try? await albs) ?? []
        if Task.isCancelled { return }
        localTracks = l
        artists = a
        remoteTracks = t
        remoteAlbums = b
        hasSearched = true
        isSearching = false
    }

    /// Metadata-only shared `Track` from a remote search hit (the 19-param
    /// initializer overwhelms Swift's inline type-checker, so it lives here).
    func makeTrack(_ t: TrackSearchResult) -> Track {
        Track(
            id: "\(t.title)|\(t.artist)", title: t.title, artist: t.artist,
            album: t.album, albumId: nil, duration: t.duration, artworkUrl: t.artworkUrl,
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
    @State private var navArtist: String?
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
            .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0) }
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
        if model.isSearching && model.localTracks.isEmpty && model.artists.isEmpty
            && model.remoteTracks.isEmpty && model.remoteAlbums.isEmpty {
            Spacer(); ProgressView(); Spacer()
        } else if model.hasSearched && model.localTracks.isEmpty && model.artists.isEmpty
            && model.remoteTracks.isEmpty && model.remoteAlbums.isEmpty {
            Spacer()
            ContentUnavailableView.search(text: model.query)
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
                    if !model.artists.isEmpty {
                        sectionHeader("Artists")
                        ForEach(Array(model.artists.enumerated()), id: \.offset) { _, artist in
                            artistRow(artist)
                        }
                    }
                    if !model.remoteTracks.isEmpty {
                        sectionHeader("Tracks")
                        let entities = model.remoteTrackEntities
                        ForEach(Array(model.remoteTracks.enumerated()), id: \.offset) { i, t in
                            trackRow(entities[i], index: i, queue: entities,
                                     resultType: "track", resultName: t.title, resultArtist: t.artist,
                                     artwork: t.artworkUrl)
                        }
                    }
                    if !model.remoteAlbums.isEmpty {
                        sectionHeader("Albums")
                        ForEach(Array(model.remoteAlbums.enumerated()), id: \.offset) { _, album in
                            albumRow(album)
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
                        seed: track.title + track.artist, size: 44, radius: 6)
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
        .pcTrackContextMenu(
            track, coordinator: coordinator,
            onGoToArtist: { navArtist = track.artist },
            onGoToAlbum: track.album.map { album in { navAlbum = PCAlbumRef(title: album, artist: track.artist) } })
    }

    private func artistRow(_ artist: IosArtistResult) -> some View {
        Button {
            model.saveHistory(type: "artist", name: artist.name, artist: nil, artwork: artist.imageUrl)
            navArtist = artist.name
        } label: {
            HStack(spacing: 12) {
                pcCover(artist.imageUrl, seed: artist.name, size: 44, radius: 22)
                Text(artist.name).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                Spacer(minLength: 8)
                Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(PC.fg3)
            }
            .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func albumRow(_ album: AlbumSearchResult) -> some View {
        Button {
            model.saveHistory(type: "album", name: album.title, artist: album.artist, artwork: album.artworkUrl)
            navAlbum = PCAlbumRef(title: album.title, artist: album.artist)
        } label: {
            HStack(spacing: 12) {
                pcCover(album.artworkUrl, seed: album.title + album.artist, size: 44, radius: 6)
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
