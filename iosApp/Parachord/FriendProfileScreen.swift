import SwiftUI
import Shared

// MARK: - Friend Profile (#235 / #196)
//
// A friend's listening profile — "the History tab, but for someone else."
// Mirrors HistoryScreen (Top Songs / Albums / Artists / Recent + period chips),
// fed from the shared FriendsRepository.getFriend* APIs via IosContainer, with a
// header (avatar, on-air/now-playing, Listen Along).

@MainActor @Observable
final class FriendProfileModel {
    private let container = IosContainer.companion.shared
    let friendId: String
    let username: String
    let service: String
    let name: String

    /// Full record (fetched by id) — drives the on-air line + Listen Along.
    var friend: Friend?

    var topTracks: [HistoryTrack] = []
    var trackEntities: [Track] = []
    var topAlbums: [HistoryAlbum] = []
    var topArtists: [HistoryArtist] = []
    var recent: [RecentTrack] = []
    var recentEntities: [Track] = []
    var loading = false

    private var tracksCache: [String: ([HistoryTrack], [Track])] = [:]
    private var albumsCache: [String: [HistoryAlbum]] = [:]
    private var artistsCache: [String: [HistoryArtist]] = [:]
    private var recentCache: ([RecentTrack], [Track])?
    private var lastLoad: [String: Date] = [:]
    private let ttl: TimeInterval = 6 * 3600

    init(friendId: String, username: String, service: String, name: String) {
        self.friendId = friendId; self.username = username; self.service = service; self.name = name
    }

    func loadFriend() async {
        // DB-cached friend first (instant), then refresh activity so the on-air
        // now-playing track is CURRENT — the cached copy may be stale or empty
        // until the periodic 2-min refresh runs, so opening a profile would show
        // no "now playing" (and you couldn't tell what you'd listen along to).
        let base = (try? await container.getFriend(friendId: friendId)) ?? nil
        friend = base
        guard let cur = base else {
            // Transient (deeplink) friend isn't in the DB — fetch their now-playing.
            friend = (try? await container.fetchTransientFriendNowPlaying(service: service, user: username)) ?? nil
            return
        }
        if let refreshed = (try? await container.refreshFriendActivity(friend: cur)) ?? nil {
            friend = refreshed
        }
    }

    /// True only for a MANUAL sidebar pin — NOT a friend that's transiently in the
    /// sidebar because they're on-air (`autoPinned`). The pin icon + toggle key off
    /// this so an on-air friend isn't shown as pinned.
    var isManuallyPinned: Bool { (friend?.pinnedToSidebar == true) && (friend?.autoPinned != true) }

    /// Pin/unpin from the top bar (Android parity), then re-read for the icon state.
    /// `pinFriend(pinned:true)` always sets a MANUAL pin (clears autoPinned), so
    /// tapping pin on an auto-pinned on-air friend converts it to a real pin that
    /// persists after they go offline.
    func togglePin() {
        guard let f = friend else { return }
        let manual = isManuallyPinned
        Task {
            try? await container.pinFriend(friendId: f.id, pinned: !manual)
            friend = (try? await container.getFriend(friendId: friendId)) ?? friend
        }
    }

    func load(tab: Int, period: String) async {
        let key = tab == 3 ? "recent" : "\(tab)-\(period)"
        applyCache(tab: tab, key: key)
        let hasCache = lastLoad[key] != nil
        if let l = lastLoad[key], Date().timeIntervalSince(l) < ttl { return }
        loading = !hasCache
        switch tab {
        case 0:
            let t = (try? await container.getFriendTopTracks(username: username, service: service, period: period)) ?? []
            if !t.isEmpty {
                let e = t.map { Self.track($0.title, $0.artist, $0.album, $0.artworkUrl) }
                tracksCache[key] = (t, e); topTracks = t; trackEntities = e
            }
        case 1:
            let a = (try? await container.getFriendTopAlbums(username: username, service: service, period: period)) ?? []
            if !a.isEmpty { albumsCache[key] = a; topAlbums = a }
        case 2:
            let a = (try? await container.getFriendTopArtists(username: username, service: service, period: period)) ?? []
            if !a.isEmpty { artistsCache[key] = a; topArtists = a }
        default:
            let r = (try? await container.getFriendRecentTracks(username: username, service: service)) ?? []
            if !r.isEmpty {
                let e = r.map { Self.track($0.title, $0.artist, $0.album, $0.artworkUrl) }
                recentCache = (r, e); recent = r; recentEntities = e
            }
        }
        lastLoad[key] = Date(); loading = false
    }

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

    private static func track(_ title: String, _ artist: String, _ album: String?, _ art: String?) -> Track {
        Track(id: "\(title)|\(artist)", title: title, artist: artist, album: album, albumId: nil,
              duration: nil, artworkUrl: art, sourceType: nil, sourceUrl: nil, addedAt: 0,
              resolver: nil, spotifyUri: nil, soundcloudId: nil, spotifyId: nil, appleMusicId: nil,
              isrc: nil, recordingMbid: nil, artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil)
    }
}

private let friendPeriods: [(key: String, label: String)] = [
    ("7day", "7 Days"), ("1month", "Month"), ("3month", "3 Months"),
    ("6month", "6 Months"), ("12month", "Year"), ("overall", "All Time"),
]

struct FriendProfileScreen: View {
    @State private var model: FriendProfileModel
    @State private var tabIndex = 0
    @State private var period = "7day"
    @State private var navArtist: String?
    @State private var navAlbum: PCAlbumRef?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.dismiss) private var dismiss
    private var resolverCache = IosTrackResolverCache.shared
    /// Observed so the Listen Along button's label flips Start↔Stop reactively.
    @State private var listenAlong = ListenAlongController.shared

    init(friendId: String, username: String, service: String, name: String) {
        _model = State(initialValue: FriendProfileModel(friendId: friendId, username: username, service: service, name: name))
    }

    var body: some View {
        VStack(spacing: 0) {
            // Pin/unpin lives in the top bar (Android parity). Listen Along sits in
            // the header below — shown ONLY when the friend is on air (or already
            // being listened-along with, so you can stop).
            PCTopBar(title: model.name, leading: .back, onLeading: { dismiss() },
                     trailingIcon: model.isManuallyPinned ? "pin.fill" : "pin",
                     onTrailing: { model.togglePin() })
            header
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
        .task { await model.loadFriend() }
        .task(id: "\(tabIndex)-\(period)") { await model.load(tab: tabIndex, period: period) }
    }

    // ── Header — centered, matches Android FriendDetailScreen ──────────
    private static let onAirGreen = Color(uiColor: UIColor(hex: 0x22C55E))

    private var header: some View {
        VStack(spacing: 6) {
            Circle().fill(PC.bgInset).frame(width: 80, height: 80)
                .overlay { PCArtistImage(url: model.friend?.avatarUrl.flatMap { URL(string: $0) }) { PCArtwork(name: model.name, size: nil, radius: 999) } }
                .clipShape(Circle())
            if model.friend?.isOnAir == true {
                Text("● ON AIR").font(.system(size: 11, weight: .bold)).foregroundStyle(Self.onAirGreen)
                // What they're listening to right now (Android parity — the friend's
                // cached now-playing track, shown in on-air green).
                if let track = model.friend?.cachedTrackName, !track.isEmpty {
                    Text("♫ \(track)\(model.friend?.cachedTrackArtist.map { " · \($0)" } ?? "")")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Self.onAirGreen)
                        .lineLimit(1)
                        .padding(.horizontal, 24)
                }
            }
            Text("@\(model.username)").font(.system(size: 13)).foregroundStyle(PC.fg2)
            Text("Listening activity from \(serviceName)").font(.system(size: 11)).foregroundStyle(PC.fg3)
            // Listen Along — on air only (or active, to stop). Gating matches the
            // friend rows + sidebar; the controller is already bound to the coordinator.
            if let f = model.friend, f.isOnAir || listenAlong.isActive(f) {
                let active = listenAlong.isActive(f)
                Button { listenAlong.toggle(f) } label: {
                    Label(active ? "Stop Listening Along" : "Listen Along", systemImage: "headphones")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 18).padding(.vertical, 9)
                        .background(active ? PC.fg3 : Self.onAirGreen, in: Capsule())
                }
                .buttonStyle(.plain)
                .padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 12)
    }

    private var serviceName: String {
        switch model.service {
        case "lastfm": return "Last.fm"
        case "listenbrainz": return "ListenBrainz"
        default: return model.service
        }
    }

    private var periodChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(friendPeriods, id: \.key) { p in
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

    // ── Top Songs ──────────────────────────────────────────────────────
    private var topSongs: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(zip(model.topTracks, model.trackEntities).enumerated()), id: \.offset) { i, pair in
                let (t, entity) = pair
                Button { coordinator.setQueue(model.trackEntities, startIndex: i,
                                              context: PlaybackContext(type: "friend", name: "\(model.name) · Top Songs", id: nil)) } label: {
                    HStack(spacing: 12) {
                        Text("\(t.rank)").font(.system(size: 13, design: .monospaced)).foregroundStyle(PC.fg3).frame(width: 24)
                        pcCover(pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album), seed: t.title + t.artist, size: 44, radius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(pcTrackNoMatch(artist: t.artist, title: t.title, album: t.album) ? PC.fg3
                                    : (coordinator.currentTrack?.id == entity.id ? PC.accent : PC.fg1)).lineLimit(1)
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
                .pcTrackContextMenu(entity, coordinator: coordinator,
                    onGoToArtist: { navArtist = t.artist },
                    onGoToAlbum: t.album.map { a in { navAlbum = PCAlbumRef(title: a, artist: t.artist) } })
            }
            emptyIfNeeded(model.topTracks.isEmpty)
        }
        .padding(.vertical, 4).padding(.bottom, 120)
    }

    // ── Top Albums ─────────────────────────────────────────────────────
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

    // ── Top Artists ────────────────────────────────────────────────────
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

    // ── Recent ─────────────────────────────────────────────────────────
    private var recentList: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(zip(model.recent, model.recentEntities).enumerated()), id: \.offset) { i, pair in
                let (t, entity) = pair
                Button { coordinator.setQueue(model.recentEntities, startIndex: i,
                                              context: PlaybackContext(type: "friend", name: "\(model.name) · Recent", id: nil)) } label: {
                    HStack(spacing: 12) {
                        pcCover(pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album), seed: t.title + t.artist, size: 44, radius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text((t.nowPlaying ? "▶ " : "") + t.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(pcTrackNoMatch(artist: t.artist, title: t.title, album: t.album) && !t.nowPlaying ? PC.fg3
                                    : (t.nowPlaying ? PC.accent : (coordinator.currentTrack?.id == entity.id ? PC.accent : PC.fg1))).lineLimit(1)
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
                .pcTrackContextMenu(entity, coordinator: coordinator,
                    onGoToArtist: { navArtist = t.artist },
                    onGoToAlbum: t.album.map { a in { navAlbum = PCAlbumRef(title: a, artist: t.artist) } })
            }
            emptyIfNeeded(model.recent.isEmpty)
        }
        .padding(.vertical, 4).padding(.bottom, 120)
    }

    // ── Helpers ────────────────────────────────────────────────────────
    @ViewBuilder private func artistCircle(_ url: String?, seed: String) -> some View {
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
            Image(systemName: "person.crop.circle").font(.system(size: 38)).foregroundStyle(PC.fg3)
            Text("No listening data for \(model.name) yet.")
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
