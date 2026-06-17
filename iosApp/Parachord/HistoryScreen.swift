import SwiftUI
import Shared

// MARK: - History (Last.fm top charts + Last.fm/ListenBrainz recent)
//
// Mirrors Android's HistoryScreen: Top Songs / Top Albums / Top Artists /
// Recent tabs, with a period filter on the three "top" tabs. Data comes from
// the shared HistoryRepository (needs a Last.fm username in Settings).

@MainActor @Observable
final class HistoryModel {
    // Singleton + per-key TTL: each tab/period loads once, persists across
    // screen recreation, and revalidates only after the TTL (not every open).
    // `loading` flips true only on the FIRST load of a key (nothing cached) so
    // a TTL revalidation never re-shows the skeleton over stale data.
    static let shared = HistoryModel()
    private let container = IosContainer.companion.shared

    var topTracks: [HistoryTrack] = []
    var trackEntities: [Track] = []
    var topAlbums: [HistoryAlbum] = []
    var topArtists: [HistoryArtist] = []
    var recent: [RecentTrack] = []
    var recentEntities: [Track] = []
    var loading = false

    // Per-window caches (keyed by tab+period). The displayed `top*` fields are a
    // single slot, so WITHOUT storing each window's result the early TTL return
    // left the PREVIOUS window's data on screen when you switched windows. Now
    // every load applies the SELECTED window's cached data first (instant), then
    // revalidates only if stale.
    private var tracksCache: [String: ([HistoryTrack], [Track])] = [:]
    private var albumsCache: [String: [HistoryAlbum]] = [:]
    private var artistsCache: [String: [HistoryArtist]] = [:]
    private var recentCache: ([RecentTrack], [Track])?
    private var lastLoad: [String: Date] = [:]
    private let ttl: TimeInterval = 6 * 3600

    func load(tab: Int, period: String) async {
        let key = tab == 3 ? "recent" : "\(tab)-\(period)"
        // 1) Show THIS window's cached data immediately so switching windows never
        //    leaves the prior window's results displayed.
        applyCache(tab: tab, key: key)
        let hasCache = lastLoad[key] != nil
        // 2) Fresh enough → done (instant, no refetch).
        if let l = lastLoad[key], Date().timeIntervalSince(l) < ttl { return }
        // 3) Skeleton only when there's nothing cached for this window; a stale
        //    revalidation updates the already-shown data in place.
        loading = !hasCache
        switch tab {
        case 0:
            let t = (try? await container.loadTopTracks(period: period)) ?? []
            if !t.isEmpty {
                let e = t.map { Self.track(title: $0.title, artist: $0.artist, album: $0.album, art: $0.artworkUrl) }
                tracksCache[key] = (t, e); topTracks = t; trackEntities = e
            }
        case 1:
            let a = (try? await container.loadTopAlbums(period: period)) ?? []
            if !a.isEmpty { albumsCache[key] = a; topAlbums = a }
        case 2:
            let a = (try? await container.loadTopArtists(period: period)) ?? []
            if !a.isEmpty { artistsCache[key] = a; topArtists = a }
        default:
            let r = (try? await container.loadRecentTracks()) ?? []
            if !r.isEmpty {
                let e = r.map { Self.track(title: $0.title, artist: $0.artist, album: $0.album, art: $0.artworkUrl) }
                recentCache = (r, e); recent = r; recentEntities = e
            }
        }
        lastLoad[key] = Date()
        loading = false
    }

    /// Point the displayed fields at the selected window's cached data (or empty
    /// if not yet loaded — so an uncached window shows a skeleton, not stale data).
    private func applyCache(tab: Int, key: String) {
        switch tab {
        case 0: topTracks = tracksCache[key]?.0 ?? []; trackEntities = tracksCache[key]?.1 ?? []
        case 1: topAlbums = albumsCache[key] ?? []
        case 2: topArtists = artistsCache[key] ?? []
        default: recent = recentCache?.0 ?? []; recentEntities = recentCache?.1 ?? []
        }
    }

    func resolveVisible(artist: String, title: String, album: String?, index: Int) {
        IosTrackResolverCache.shared.resolve(ResolveRequest(artist: artist, title: title, album: album), order: index)
    }

    private static func track(title: String, artist: String, album: String?, art: String?) -> Track {
        Track(id: "\(title)|\(artist)", title: title, artist: artist, album: album, albumId: nil,
              duration: nil, artworkUrl: art, sourceType: nil, sourceUrl: nil, addedAt: 0,
              resolver: nil, spotifyUri: nil, soundcloudId: nil, spotifyId: nil, appleMusicId: nil,
              isrc: nil, recordingMbid: nil, artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil)
    }
}

private let historyPeriods: [(key: String, label: String)] = [
    ("7day", "7 Days"), ("1month", "Month"), ("3month", "3 Months"),
    ("6month", "6 Months"), ("12month", "Year"), ("overall", "All Time"),
]

struct HistoryScreen: View {
    @State private var model = HistoryModel.shared
    @State private var tabIndex = 0
    @State private var period = "7day"
    @State private var navArtist: String?
    @State private var navAlbum: PCAlbumRef?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.dismiss) private var dismiss
    private var resolverCache = IosTrackResolverCache.shared

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: "History", leading: .back, onLeading: { dismiss() })
            PCCuratedBanner(
                icon: "clock.arrow.circlepath",
                subtitle: "Your top songs, albums, artists & recent plays",
                count: nil, gradient: [0x06B6D4, 0x0891B2])
            PCTabs(tabs: ["Top Songs", "Top Albums", "Top Artists", "Recent"], selection: $tabIndex)
            if tabIndex != 3 { periodChips }

            ScrollView {
                if model.loading {
                    if tabIndex == 0 || tabIndex == 3 { PCSkeletonList(count: 8, art: 44) }
                    else { PCSkeletonGrid(count: 6, columns: tabIndex == 2 ? 3 : 2) }
                } else {
                    switch tabIndex {
                    case 0: topSongs
                    case 1: topAlbums
                    case 2: topArtists
                    default: recentList
                    }
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0) }
        .navigationDestination(item: $navAlbum) { AlbumScreen(title: $0.title, artist: $0.artist) }
        .task(id: "\(tabIndex)-\(period)") { await model.load(tab: tabIndex, period: period) }
    }

    private var periodChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(historyPeriods, id: \.key) { p in
                    let on = period == p.key
                    Button { period = p.key } label: {
                        Text(p.label).font(.system(size: 13, weight: .medium))
                            .foregroundStyle(on ? .white : PC.fg1)
                            .padding(.horizontal, 14).padding(.vertical, 6)
                            .background(on ? PC.accent : PC.bgInset, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
        }
    }

    // ── Top Songs (list) ───────────────────────────────────────────────
    private var topSongs: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(model.topTracks.enumerated()), id: \.offset) { i, t in
                Button { coordinator.setQueue(model.trackEntities, startIndex: i,
                                              context: PlaybackContext(type: "history", name: "Top Songs", id: nil)) } label: {
                    HStack(spacing: 12) {
                        Text("\(t.rank)").font(.system(size: 13, design: .monospaced)).foregroundStyle(PC.fg3).frame(width: 24)
                        pcCover(pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album), seed: t.title + t.artist, size: 44, radius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(pcTrackNoMatch(artist: t.artist, title: t.title, album: t.album) ? PC.fg3
                                    : (coordinator.currentTrack?.id == model.trackEntities[i].id ? PC.accent : PC.fg1)).lineLimit(1)
                            Text("\(t.artist) · \(plays(t.playCount))").font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                        }
                        Spacer(minLength: 8)
                        if let s = resolverCache.cached(artist: t.artist, title: t.title, album: t.album), !s.isEmpty {
                            ResolverBadgeRow(sources: s)
                        }
                    }
                    .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .onAppear { model.resolveVisible(artist: t.artist, title: t.title, album: t.album, index: i) }
                .pcTrackContextMenu(model.trackEntities[i], coordinator: coordinator,
                    onGoToArtist: { navArtist = t.artist },
                    onGoToAlbum: t.album.map { a in { navAlbum = PCAlbumRef(title: a, artist: t.artist) } })
            }
            emptyIfNeeded(model.topTracks.isEmpty)
        }
        .padding(.vertical, 4).padding(.bottom, 120)
    }

    // ── Top Albums (2-col grid) ────────────────────────────────────────
    private var topAlbums: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 16) {
            ForEach(Array(model.topAlbums.enumerated()), id: \.offset) { _, a in
                NavigationLink(value: PCRoute.album(title: a.name, artist: a.artist)) {
                    VStack(alignment: .leading, spacing: 4) {
                        ZStack(alignment: .topLeading) {
                            pcCover(a.artworkUrl, seed: a.name + a.artist, size: nil, radius: 10)
                                .aspectRatio(1, contentMode: .fit).shadow(color: .black.opacity(0.12), radius: 6, y: 3)
                            Text("#\(a.rank)").font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                                .padding(.horizontal, 6).padding(.vertical, 2)
                                .background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 4)).padding(6)
                        }
                        Text(a.name).font(.system(size: 14, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1).padding(.horizontal, 2)
                        Text("\(a.artist) · \(plays(a.playCount))").font(.system(size: 12)).foregroundStyle(PC.fg2).lineLimit(1).padding(.horizontal, 2)
                    }
                }
                .buttonStyle(.plain)
                .pcAlbumContextMenu(title: a.name, artist: a.artist, artworkUrl: a.artworkUrl,
                    coordinator: coordinator,
                    onGoToAlbum: { navAlbum = PCAlbumRef(title: a.name, artist: a.artist) },
                    onGoToArtist: { navArtist = a.artist })
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8).padding(.bottom, 120)
        .overlay { if model.topAlbums.isEmpty { emptyState } }
    }

    // ── Top Artists (3-col grid, circular) ─────────────────────────────
    private var topArtists: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 18) {
            ForEach(Array(model.topArtists.enumerated()), id: \.offset) { _, a in
                NavigationLink(value: PCRoute.artist(a.name)) {
                    VStack(spacing: 6) {
                        artistCircle(a.imageUrl, seed: a.name)
                        Text(a.name).font(.system(size: 12, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1).multilineTextAlignment(.center)
                        Text(plays(a.playCount)).font(.system(size: 10)).foregroundStyle(PC.fg3)
                    }
                }
                .buttonStyle(.plain)
                .pcArtistContextMenu(name: a.name, imageUrl: a.imageUrl, coordinator: coordinator,
                    onGoToArtist: { navArtist = a.name })
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 12).padding(.bottom, 120)
        .overlay { if model.topArtists.isEmpty { emptyState } }
    }

    // ── Recent (list) ──────────────────────────────────────────────────
    private var recentList: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(model.recent.enumerated()), id: \.offset) { i, t in
                Button { coordinator.setQueue(model.recentEntities, startIndex: i,
                                              context: PlaybackContext(type: "history", name: "Recently Played", id: nil)) } label: {
                    HStack(spacing: 12) {
                        pcCover(pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album), seed: t.title + t.artist, size: 44, radius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text((t.nowPlaying ? "▶ " : "") + t.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(pcTrackNoMatch(artist: t.artist, title: t.title, album: t.album) && !t.nowPlaying ? PC.fg3
                                    : (t.nowPlaying ? PC.accent : (coordinator.currentTrack?.id == model.recentEntities[i].id ? PC.accent : PC.fg1))).lineLimit(1)
                            HStack(spacing: 6) {
                                Text(t.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                                if !t.source.isEmpty {
                                    Text(t.source).font(.system(size: 9, weight: .semibold))
                                        .foregroundStyle(PC.fg3).padding(.horizontal, 5).padding(.vertical, 1)
                                        .overlay(Capsule().strokeBorder(PC.border))
                                }
                            }
                        }
                        Spacer(minLength: 8)
                        Text(t.nowPlaying ? "Now" : timeAgo(t.timestamp)).font(.system(size: 11)).foregroundStyle(PC.fg3)
                        if let s = resolverCache.cached(artist: t.artist, title: t.title, album: t.album), !s.isEmpty {
                            ResolverBadgeRow(sources: s)
                        }
                    }
                    .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .onAppear { model.resolveVisible(artist: t.artist, title: t.title, album: t.album, index: i) }
                .pcTrackContextMenu(model.recentEntities[i], coordinator: coordinator,
                    onGoToArtist: { navArtist = t.artist },
                    onGoToAlbum: t.album.map { a in { navAlbum = PCAlbumRef(title: a, artist: t.artist) } })
            }
            emptyIfNeeded(model.recent.isEmpty)
        }
        .padding(.vertical, 4).padding(.bottom, 120)
    }

    // ── Helpers ────────────────────────────────────────────────────────
    @ViewBuilder
    private func artistCircle(_ url: String?, seed: String) -> some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay { PCArtistImage(url: url.flatMap { URL(string: $0) }) { PCArtwork(name: seed, size: nil, radius: 999) } }
            .clipShape(Circle())
    }

    @ViewBuilder private func emptyIfNeeded(_ empty: Bool) -> some View {
        if empty { emptyState.padding(.top, 60) }
    }
    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "clock").font(.system(size: 38)).foregroundStyle(PC.fg3)
            Text("No listening data yet.\nSet your Last.fm username in Settings.")
                .font(.system(size: 14)).foregroundStyle(PC.fg2).multilineTextAlignment(.center).padding(.horizontal, 40)
        }
    }

    private func plays(_ n: Int32) -> String { n == 1 ? "1 play" : "\(n) plays" }

    private func timeAgo(_ ts: Int64) -> String {
        guard ts > 0 else { return "" }
        let secs = Int64(Date().timeIntervalSince1970) - ts
        if secs < 60 { return "just now" }
        if secs < 3600 { return "\(secs / 60)m ago" }
        if secs < 86400 { return "\(secs / 3600)h ago" }
        if secs < 172800 { return "Yesterday" }
        return "\(secs / 86400)d ago"
    }
}
