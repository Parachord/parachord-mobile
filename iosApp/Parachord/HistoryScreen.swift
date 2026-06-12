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
    private var lastLoad: [String: Date] = [:]
    private let ttl: TimeInterval = 60 * 60

    func load(tab: Int, period: String) async {
        let key = tab == 3 ? "recent" : "\(tab)-\(period)"
        if let l = lastLoad[key], Date().timeIntervalSince(l) < ttl { return }
        loading = (lastLoad[key] == nil)   // spinner only the first time for this key
        switch tab {
        case 0:
            let t = (try? await container.loadTopTracks(period: period)) ?? []
            if !t.isEmpty { topTracks = t; trackEntities = t.map { Self.track(title: $0.title, artist: $0.artist, album: $0.album, art: $0.artworkUrl) } }
        case 1:
            let a = (try? await container.loadTopAlbums(period: period)) ?? []
            if !a.isEmpty { topAlbums = a }
        case 2:
            let a = (try? await container.loadTopArtists(period: period)) ?? []
            if !a.isEmpty { topArtists = a }
        default:
            let r = (try? await container.loadRecentTracks()) ?? []
            if !r.isEmpty { recent = r; recentEntities = r.map { Self.track(title: $0.title, artist: $0.artist, album: $0.album, art: $0.artworkUrl) } }
        }
        lastLoad[key] = Date()
        loading = false
    }

    func resolveVisible(artist: String, title: String, album: String?, index: Int) {
        IosTrackResolverCache.shared.resolve(ResolveRequest(artist: artist, title: title, album: album), order: index)
    }

    private static func track(title: String, artist: String, album: String?, art: String?) -> Track {
        Track(id: "\(title)|\(artist)", title: title, artist: artist, album: album, albumId: nil,
              duration: nil, artworkUrl: art, sourceType: nil, sourceUrl: nil, addedAt: 0,
              resolver: nil, spotifyUri: nil, soundcloudId: nil, spotifyId: nil, appleMusicId: nil,
              recordingMbid: nil, artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil)
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
                Button { coordinator.setQueue(model.trackEntities, startIndex: i) } label: {
                    HStack(spacing: 12) {
                        Text("\(t.rank)").font(.system(size: 13, design: .monospaced)).foregroundStyle(PC.fg3).frame(width: 24)
                        pcCover(pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album), seed: t.title + t.artist, size: 44, radius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(coordinator.currentTrack?.id == model.trackEntities[i].id ? PC.accent : PC.fg1).lineLimit(1)
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
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 12).padding(.bottom, 120)
        .overlay { if model.topArtists.isEmpty { emptyState } }
    }

    // ── Recent (list) ──────────────────────────────────────────────────
    private var recentList: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(model.recent.enumerated()), id: \.offset) { i, t in
                Button { coordinator.setQueue(model.recentEntities, startIndex: i) } label: {
                    HStack(spacing: 12) {
                        pcCover(pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album), seed: t.title + t.artist, size: 44, radius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text((t.nowPlaying ? "▶ " : "") + t.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(t.nowPlaying ? PC.accent : (coordinator.currentTrack?.id == model.recentEntities[i].id ? PC.accent : PC.fg1)).lineLimit(1)
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
        Group {
            if let url, let u = URL(string: url) {
                PCCachedImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                    placeholder: { PCArtwork(name: seed, size: nil, radius: 999) }
            } else { PCArtwork(name: seed, size: nil, radius: 999) }
        }
        .aspectRatio(1, contentMode: .fit).clipShape(Circle())
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
