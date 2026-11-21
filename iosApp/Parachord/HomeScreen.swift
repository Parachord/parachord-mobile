import SwiftUI
import Shared

// MARK: - Home (desktop's home-page.js, Android's HomeScreen.kt)
//
// The frame (hero, the reauth banners, the announcement, the skeleton) and
// the sections, drawn from the app-wide shared session (`HomeModel`,
// `IosHomePage`). Every rule is shared; Swift only draws. This file also holds
// the app's value-based routes (`PCRoute`) and their destinations.

/// Value-based routes pushed onto the Home tab's stack — driven by both the
/// Discover tiles and the sidebar, so the sidebar navigates with the tab bar
/// still visible (a push, not a modal).
enum PCRoute: Hashable {
    case recommendations, pop, critical, fresh, concerts
    /// History, optionally opened on a deep link's tab and period (#470).
    case history(tab: String? = nil, period: String? = nil)
    case artist(String)
    /// Artist page opened straight to the On Tour tab (Now Playing on-tour dot, #201).
    case artistOnTour(String)
    case album(title: String, artist: String)
    /// Ephemeral/weekly playlist (tracks fetched from ListenBrainz on open).
    case playlist(id: String, title: String)
    /// A SAVED (DB-backed) playlist — opens the same detail as the Playlists tab.
    /// Distinct from `.playlist` (which loads weekly/ephemeral tracks and would
    /// show empty for a saved row).
    case savedPlaylist(id: String)
    /// A friend's listening profile (#235 / #196); `tab`/`period` from a deep link (#470).
    case friend(id: String, username: String, service: String, name: String, tab: String? = nil, period: String? = nil)
    /// A recommended service playlist opened in a playlist view (not saved until Save).
    case servicePlaylist(url: String, name: String, source: String, image: String?)
}

/// Single source of truth for every pushed destination. Used by EVERY tab's
/// NavigationStack via `.navigationDestination(for: PCRoute.self)`, so all
/// navigation is VALUE-BASED — never destination-based `NavigationLink { … }`,
/// which corrupts a typed-path NavigationStack on iOS 18+ (mixing the two broke
/// push + back across the app; works on older iPad OS, not the iOS 18.3 sim).
/// Map a queue-source `PlaybackContext` (#209) to its destination route — every
/// page a queue can be started from that iOS can navigate back to. iOS album
/// routing needs title + artist, so the album context carries the artist in `id`
/// (set in AlbumScreen.albumContext). Returns nil for types with no iOS page
/// (listen-along / spinoff / unknown).
func pcRouteForContext(_ ctx: PlaybackContext) -> PCRoute? {
    // Album, artist, playlist and service-playlist contexts open where the
    // shared `playbackContextDestination` says (Android's Now Playing asks it too).
    if let dest = PlaybackContextKt.playbackContextDestination(ctx: ctx) {
        switch dest {
        case let album as ContextDestination.Album:
            return .album(title: album.title, artist: album.artist ?? "")
        case let artist as ContextDestination.Artist:
            return .artist(artist.name)
        case let playlist as ContextDestination.Playlist:
            return .playlist(id: playlist.id, title: ctx.name)
        case let sp as ContextDestination.ServicePlaylist:
            return .servicePlaylist(url: sp.url, name: ctx.name, source: "", image: nil)
        default:
            return nil
        }
    }
    switch ctx.type {
    case "album", "artist", "playlist", PlaybackContextKt.SERVICE_PLAYLIST_CONTEXT:
        // The shared rule found nothing to open (a playlist or service playlist with no id).
        return nil
    case "charts":          return .pop
    case "recommendations": return .recommendations
    case "history":         return .history()
    case "listen-along":
        // id encodes "friendId|username|service" (set in ListenAlongController) so
        // the listen-along banner links to the friend's profile (#235).
        let parts = (ctx.id ?? "").components(separatedBy: "|")
        guard parts.count == 3, !parts[0].isEmpty else { return nil }
        return .friend(id: parts[0], username: parts[1], service: parts[2], name: ctx.name)
    default:                return nil
    }
}

/// Whether a queue-source context links anywhere on iOS — a pushable page
/// (pcRouteForContext) OR the Collection tab (which is a tab, not a route, so
/// the shell switches to it rather than pushing). #209.
func pcContextIsNavigable(_ ctx: PlaybackContext) -> Bool {
    pcRouteForContext(ctx) != nil || ctx.type == "collection"
}

@ViewBuilder
func pcRouteDestination(_ route: PCRoute) -> some View {
    switch route {
    case .recommendations:             RecommendationsScreen()
    case .pop:                         PopOfTheTopsScreen()
    case .critical:                    CriticalDarlingsScreen()
    case .fresh:                       FreshDropsScreen()
    case .concerts:                    ConcertsScreen()
    case .history(let tab, let period): HistoryScreen(tab: tab, period: period)
    case .artist(let name):            ArtistScreen(artistName: name)
    case .artistOnTour(let name):      ArtistScreen(artistName: name, initialTab: .onTour)
    case .album(let title, let artist): AlbumScreen(title: title, artist: artist)
    case .playlist(let id, let title): PlaylistDetailView(playlistId: id, title: title)
    case .savedPlaylist(let id):       SavedPlaylistDetailView(playlistId: id)
    case .servicePlaylist(let url, let name, let source, let image):
        ServicePlaylistView(url: url, name: name, source: source, image: image)
    case .friend(let id, let username, let service, let name, let tab, let period):
        FriendProfileScreen(friendId: id, username: username, service: service, name: name, tab: tab, period: period)
    }
}

struct HomeScreen: View {
    /// One-shot route injected by the sidebar (consumed into `path`). The tab's
    /// nav stack lives in HomeScreen's OWN @State so re-tapping Home (which
    /// recreates HomeScreen via the shell's `.id`) reliably resets it to root —
    /// a hoisted binding raced the recreation and re-pushed the screen.
    @Binding var pendingRoute: PCRoute?
    let onMenu: () -> Void
    /// Opened by the Spotify reauth banner when there's no stored Client ID to
    /// OAuth with (the user sets it + connects in Settings).
    var onOpenSettings: () -> Void = {}
    /// Switch the shell's main tab (Collection / Playlists); these are tabs, not
    /// pushable routes. Optional CollectionTab sub-selection.
    var onSelectTab: (PCTab, CollectionTab?) -> Void = { _, _ in }
    /// Recent Loves: Collection's songs, sorted Recently Added for that visit
    /// (desktop's `setCollectionSort({ tracks: 'recent' })`, Android's link sort).
    var onOpenRecentLoves: () -> Void = {}

    @State private var path: [PCRoute] = []
    @State private var home = HomeModel()
    @State private var reauth = SpotifyReauthModel()
    @State private var amReauth = AppleMusicReauthModel()
    // The old sections' state, kept only for their helpers below until they
    // are deleted (plan Task 9); nothing starts them.
    @State private var model = DiscoverViewModel.shared
    @State private var feed = HomeFeedModel()
    @State private var scanner = MediaLibraryScanner.shared
    private let container = IosContainer.companion.shared
    private let resolverCache = IosTrackResolverCache.shared
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.pcOpenImport) private var openImport
    @Environment(\.pcAskShuffleupagus) private var askShuffleupagus
    @Environment(\.pcOpenPluginSettings) private var openPluginSettings

    private var session: IosHomeSession { home.session }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                HomeHero(onMenu: onMenu)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        reauthBanners
                        if let page = home.page {
                            if page.showBanner, let banner = page.banner {
                                HomeBannerSlot(banner: banner, session: session)
                                    .id("banner-\(banner.id)")
                            }
                            if !page.ready {
                                HomeSkeleton()
                            } else {
                                sections(page)
                            }
                        } else {
                            HomeSkeleton()
                        }
                    }
                    .padding(.bottom, 130) // clear the floating tab bar
                }
            }
            .background(PC.bgPrimary)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: PCRoute.self) { pcRouteDestination($0) }
            // A fresh entry or a return from a pushed page: the loads, under their throttles.
            .onAppear {
                session.onVisible()
                Task { await reauth.refreshExpiry() }
            }
        }
        .onChange(of: pendingRoute, initial: true) { _, route in
            if let route { path = [route]; pendingRoute = nil }
        }
        // The view's creation (a tab switch or a re-tap recreates it), not a
        // return from a pushed page: a new phrasing for the Ask example.
        .task { home.start(); session.enteredHome() }
        .task { reauth.start() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                session.onVisible()
                Task { await reauth.refreshExpiry() }
            }
        }
        .task { amReauth.start() }
        .onDisappear { home.stop(); amReauth.stop() }
    }

    // MARK: Frame

    /// The Apple Music and Spotify reauth banners, kept from the old Home.
    @ViewBuilder private var reauthBanners: some View {
        if amReauth.required {
            PCAppleMusicReauthBanner(connecting: amReauth.connecting, error: amReauth.error) { amReauth.reconnect() }
                .padding(.top, 12)
        }
        if reauth.required {
            PCSpotifyReauthBanner(connecting: reauth.connecting) {
                reauth.reconnect(onNeedsSetup: onOpenSettings)
            }
        } else if let message = reauth.expiryMessage {
            PCSpotifyExpiryBanner(
                message: message,
                connecting: reauth.connecting,
                onReconnect: { reauth.reconnect(onNeedsSetup: onOpenSettings) },
                onDismiss: { reauth.dismissExpiry() }
            )
        }
    }

    /// The page once it's ready. The getting-started layout and the sections
    /// after Jump back in arrive with later tasks.
    @ViewBuilder private func sections(_ page: IosHomePage) -> some View {
        if page.gettingStarted == nil {
            HomeHeaderView(
                page: page,
                onCollectionRadio: { home.play(coordinator: coordinator) { try await $0.collectionRadio() } },
                onSetupAction: { id in if id == "getting-started" { session.resumeSetup() } },
                onDismissSetupItem: { session.dismissSetupItem(id: $0) }
            )
            .padding(.top, 24)
            if page.showJump {
                HomeJumpBackIn(
                    page: page,
                    onOpen: openTile,
                    onPlay: { tile in
                        let key = tile.key
                        home.play(coordinator: coordinator) { try await $0.tileQueue(key: key) }
                    },
                    onShown: { session.tileShown(key: $0.key) },
                    onImport: { openImport() }
                )
                .padding(.top, 8)
            }
            if page.showMixes {
                HomeMixesRow(
                    page: page,
                    onSelectSource: { session.setMixSource(id: $0) },
                    onOpen: openMix,
                    onPlay: { card in
                        let key = card.key
                        home.play(coordinator: coordinator) { try await $0.mixQueue(key: key) }
                    }
                )
                .padding(.top, 24)
            }
            if page.showPlaylists {
                HomePlaylistsRow(
                    page: page,
                    onOpen: { path.append(.savedPlaylist(id: $0.id)) },
                    onPlay: { card in
                        let id = card.id
                        home.play(coordinator: coordinator) { try await $0.playlistQueue(id: id) }
                    },
                    onSeeAll: { onSelectTab(.playlists, nil) }
                )
                .padding(.top, 24)
            }
            if page.showAlbums {
                HomeAlbumsRow(
                    page: page,
                    onSelectSource: { session.setAlbumSource(id: $0) },
                    onShown: { session.albumShown(key: $0.key) },
                    onOpen: { path.append(.album(title: $0.item.name, artist: $0.item.artist ?? "")) },
                    onOpenArtist: { path.append(.artist($0.item.artist ?? "")) },
                    onPlay: { album in
                        let key = album.key
                        home.play(coordinator: coordinator) { try await $0.albumQueue(key: key) }
                    },
                    onSeeAll: { path.append(.recommendations) }
                )
                .padding(.top, 24)
            }
            ForEach(page.halves, id: \.kind) { half in
                HomeHalfView(
                    half: half,
                    onSeeAll: { path.append(half.kind == "fresh" ? .fresh : half.kind == "critical" ? .critical : .pop) },
                    onOpen: { path.append(.album(title: $0.title, artist: $0.artist)) },
                    onPlay: { card in
                        let key = card.key
                        home.play(coordinator: coordinator) { try await $0.halfAlbumQueue(key: key) }
                    },
                    onShown: { session.halfCardShown(key: $0.key) },
                    onOpenArtist: { path.append(.artist($0.artist)) },
                    onRetry: { session.retryCharts() }
                )
                .padding(.top, 24)
            }
            if page.showConcerts {
                VStack(alignment: .leading, spacing: 0) {
                    PCSectionHeader(
                        title: HomeCopy.shared.CONCERTS_TITLE, subtitle: HomeCopy.shared.CONCERTS_SUBTITLE,
                        page: DesignTokens.Page.concerts, onSeeAll: { path.append(.concerts) }, gutter: homeGutter)
                    VStack(spacing: 12) {
                        ForEach(page.concerts, id: \.id) { c in
                            HomeConcertCard(concert: c, onOpenTour: { path.append(.artistOnTour($0)) })
                        }
                    }
                }
                .padding(.top, 24)
            }
            if page.showAsk {
                HomeAsk(
                    example: page.askExample, chatAvailable: page.chatAvailable,
                    field: Binding(get: { home.ask }, set: { home.ask = $0 }),
                    onAsk: { askShuffleupagus($0) },
                    // Android's `onOpenSettings(null)`: Settings, on no plug-in's sheet.
                    onSetUp: { openPluginSettings() }
                )
                .padding(.top, 24)
            }
        }
    }

    /// What a "Made from your listening" card opens: Recent Loves in Collection, a mix as a service playlist.
    private func openMix(_ card: IosHomeMixCard) {
        if card.isLoves {
            onOpenRecentLoves()
        } else if let url = card.url, !url.isEmpty {
            path.append(.servicePlaylist(url: url, name: card.title, source: card.source ?? "", image: card.imageUrl))
        }
    }

    /// What a Jump back in tile opens, by kind.
    private func openTile(_ tile: IosJumpTile) {
        switch tile.kind {
        case "playlist":
            path.append(.savedPlaylist(id: tile.targetId))
        case "album":
            path.append(.album(title: tile.targetName, artist: tile.targetArtist ?? ""))
        case "mix":
            guard let url = tile.targetUrl, !url.isEmpty else { return }
            path.append(.servicePlaylist(url: url, name: tile.targetName, source: tile.targetArtist ?? "", image: tile.imageUrl))
        case "loves":
            onOpenRecentLoves()
        default:
            break
        }
    }
    // MARK: Old sections (dead until plan Task 9 deletes them)

    // ── Friend Activity (#235) — friends with a now-playing/last track, on-air
    // first, tap → profile, long-press → Listen Along / Pin. Matches Android.
    private var friendActivitySection: some View {
        VStack(spacing: 0) {
            ForEach(model.friendActivity, id: \.id) { f in
                NavigationLink(value: PCRoute.friend(id: f.id, username: f.username, service: f.service, name: f.displayName)) {
                    friendActivityRow(f)
                }
                .buttonStyle(.plain)
                .contextMenu {
                    if f.isOnAir || ListenAlongController.shared.isActive(f) {
                        Button { ListenAlongController.shared.toggle(f) } label: {
                            Label(ListenAlongController.shared.isActive(f) ? "Stop Listening Along" : "Listen Along", systemImage: "headphones")
                        }
                    }
                    if f.pinnedToSidebar {
                        Button { Task { try? await container.pinFriend(friendId: f.id, pinned: false) } } label: { Label("Unpin from Sidebar", systemImage: "pin.slash") }
                    } else {
                        Button { Task { try? await container.pinFriend(friendId: f.id, pinned: true) } } label: { Label("Pin to Sidebar", systemImage: "pin") }
                    }
                }
            }
        }
        .padding(.horizontal, 20)
    }

    @ViewBuilder private func friendActivityRow(_ f: Friend) -> some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                Color.clear.frame(width: 44, height: 44)
                    .overlay { PCArtistImage(url: f.avatarUrl.flatMap { URL(string: $0) }) { PCArtwork(name: f.displayName, size: nil, radius: 0) } }
                    .clipShape(PCHexagon())   // friends = hexagons
                if f.isOnAir {
                    Circle().fill(PC.success)
                        .frame(width: 12, height: 12)
                        .overlay(Circle().strokeBorder(PC.bgPrimary, lineWidth: 2))
                }
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(f.displayName).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                if f.isOnAir, let track = f.cachedTrackName {
                    Text("♫ \(track)\(f.cachedTrackArtist.map { " · \($0)" } ?? "")")
                        .font(.system(size: 13)).foregroundStyle(PC.success).lineLimit(1)
                } else if let track = f.cachedTrackName {
                    Text("\(track)\(f.cachedTrackArtist.map { " · \($0)" } ?? "")")
                        .font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                } else {
                    Text("Offline").font(.system(size: 13)).foregroundStyle(PC.fg3)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }

    private func sectionHeader(_ text: String) -> some View {
        PCSectionHeader(title: text, gutter: 20)   // Home's rows use a 20 pt gutter
    }

    private func continueCard(_ t: Track) -> some View {
        Button { coordinator.togglePlayPause() } label: {
            HStack(spacing: 14) {
                pcCover(pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album),
                        seed: t.title, size: 56, radius: 8, artist: t.artist, album: t.album)
                VStack(alignment: .leading, spacing: 2) {
                    Text(t.title).font(.system(size: 15, weight: .semibold)).foregroundStyle(PC.fg1).lineLimit(1)
                    Text(t.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                    if !coordinator.upNext.isEmpty {
                        Text("\(coordinator.upNext.count) more in queue")
                            .font(.system(size: 11, weight: .medium)).foregroundStyle(PC.accent).padding(.top, 2)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: coordinator.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 24)).foregroundStyle(PC.accent)
            }
            .padding(12)
            .background(PC.accent.opacity(0.06), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 20).padding(.bottom, 8)
    }

    private struct Tile: Identifiable {
        let id = UUID(); let title: String; let icon: String; let c1: UInt32; let c2: UInt32
        let subtitle: String; let preset: String
        var route: PCRoute? {
            switch preset {
            case "foryou": return .recommendations
            case "pop": return .pop
            case "critical": return .critical
            case "fresh": return .fresh
            default: return nil
            }
        }
    }
    // Subtitles match Android's fallbackSubtitle (HomeScreen.kt).
    private let tiles: [Tile] = [
        .init(title: "For You", icon: "star.fill", c1: 0x7c3aed, c2: 0x6d28d9, subtitle: "Personalized picks", preset: "foryou"),
        .init(title: "Critical Darlings", icon: "heart.fill", c1: PC.pageGradient(.criticalDarlings).first!, c2: PC.pageGradient(.criticalDarlings).last!, subtitle: "Staff picks", preset: "critical"),
        .init(title: "Pop of the Tops", icon: "chart.line.uptrend.xyaxis", c1: PC.pageGradient(.popOfTheTops).first!, c2: PC.pageGradient(.popOfTheTops).last!, subtitle: "Top charts", preset: "pop"),
        .init(title: "Fresh Drops", icon: "drop.fill", c1: PC.pageGradient(.freshDrops).first!, c2: PC.pageGradient(.freshDrops).last!, subtitle: "New releases", preset: "fresh"),
    ]

    private var discoverGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
            ForEach(tiles) { tile in
                if let route = tile.route {
                    NavigationLink(value: route) { tileLabel(tile) }
                        .buttonStyle(.plain)
                } else {
                    tileLabel(tile)
                }
            }
        }
        .padding(.horizontal, 20).padding(.bottom, 8)
    }

    private func tileLabel(_ tile: Tile) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(tile.title, systemImage: tile.icon)
                .font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
            Spacer(minLength: 0)
            if let p = model.previews[tile.preset] {
                HStack(spacing: 8) {
                    tilePreviewThumb(p.artworkUrl, seed: p.title + p.subtitle)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(p.title).font(.system(size: 12, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                        Text(p.subtitle).font(.system(size: 10)).foregroundStyle(.white.opacity(0.8)).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                // New id when the featured item changes → cross-fades the cached
                // preview out and the fresh one in (the VM mutates in withAnimation).
                .id("\(tile.preset)-\(p.title)-\(p.subtitle)")
                .transition(.opacity)
            } else {
                Text(tile.subtitle).font(.system(size: 12)).foregroundStyle(.white.opacity(0.85))
                    .transition(.opacity)
            }
            // Balancing spacer so the preview sits vertically centered in the
            // space below the header (was bottom-anchored with only the top spacer).
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 110, alignment: .topLeading)
        .background(
            LinearGradient(colors: [Color(uiColor: UIColor(hex: tile.c1)), Color(uiColor: UIColor(hex: tile.c2))],
                           startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(color: .black.opacity(0.10), radius: 9, y: 6)
    }

    /// Section header shared by the Weekly skeleton + the real Weekly section, so
    /// the title + "ListenBrainz" badge are byte-identical and the header doesn't
    /// jump (badge pop-in) when content arrives (#249).
    private func weeklyHeader(_ title: String) -> some View {
        PCSectionHeader(title: title, gutter: 20) {
            Text("ListenBrainz").font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Color(uiColor: UIColor(hex: 0xb45309)))
                .padding(.horizontal, 8).padding(.vertical, 3)
                .background(PC.warning.opacity(0.16), in: RoundedRectangle(cornerRadius: 6))
        }
    }

    /// Placeholder carousel shown while the first Weekly load is in flight —
    /// matches the 2x2-mosaic card shape (cover radius 12 + 150-wide card) and the
    /// header (incl. ListenBrainz badge) so nothing jumps on arrival (#249).
    private func weeklySkeleton(_ title: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            weeklyHeader(title)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(0..<4, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: 8) {
                            PCSkeletonBox(width: 150, height: 150, radius: 12)
                            PCSkeletonBox(width: 120, height: 13, radius: 4)
                            PCSkeletonBox(width: 80, height: 11, radius: 4)
                        }
                        .frame(width: 150, alignment: .leading)
                    }
                }
                .padding(.horizontal, 20)
            }
        }
    }

    @ViewBuilder
    private func weeklySection(_ title: String, _ entries: [IosWeeklyEntry]) -> some View {
        if !entries.isEmpty {
            weeklyHeader(title)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(entries, id: \.id) { entry in
                        NavigationLink(value: PCRoute.playlist(id: entry.id, title: "\(entry.kind) · \(entry.dateLabel)")) {
                            weeklyCard(entry)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 20)
            }
        }
    }

    private func weeklyCard(_ entry: IosWeeklyEntry) -> some View {
        // nil = covers not fetched yet (show skeleton); non-nil = loaded, so a
        // missing slot is a genuine no-art fallback. Previously `?? []` collapsed
        // "loading" into "empty", so every tile flashed 4 identical letter
        // placeholders before the artwork arrived.
        let covers = model.trackCovers[entry.id]
        return VStack(alignment: .leading, spacing: 8) {
            LazyVGrid(columns: [GridItem(.fixed(75), spacing: 0), GridItem(.fixed(75), spacing: 0)], spacing: 0) {
                ForEach(0..<4, id: \.self) { j in
                    if let covers {
                        mosaicCell(j < covers.count ? covers[j] : nil, seed: "\(entry.id)\(j)")
                    } else {
                        PCSkeletonBox(radius: 0).frame(width: 75, height: 75)   // loading — shimmer, not a letter
                    }
                }
            }
            .frame(width: 150, height: 150)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(color: .black.opacity(0.10), radius: 9, y: 4)

            Text(entry.weekLabel).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1).lineLimit(1)
            Text(model.trackCounts[entry.id].map { "\($0) tracks" } ?? "Weekly playlist")
                .font(.system(size: 12)).foregroundStyle(PC.fg2)
        }
        .frame(width: 150)
    }

    @ViewBuilder
    private func mosaicCell(_ url: String?, seed: String) -> some View {
        if let url, let u = URL(string: url) {
            PCCachedImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                placeholder: { PCArtwork(name: seed, size: 75, radius: 0) }
                .frame(width: 75, height: 75).clipped()
        } else {
            PCArtwork(name: seed, size: 75, radius: 0)
        }
    }

    @ViewBuilder
    private func tilePreviewThumb(_ url: String?, seed: String) -> some View {
        if let url, let u = URL(string: url) {
            PCCachedImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                placeholder: { PCArtwork(name: seed, size: 36, radius: 4) }
                .frame(width: 36, height: 36).clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
        } else {
            PCArtwork(name: seed, size: 36, radius: 4)
        }
    }

    // MARK: Library sections (#196)

    // ── Recently Added (8 most-recently-added albums) ─────────────────────
    private var recentAlbumsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                ForEach(feed.recentAlbums, id: \.id) { album in
                    NavigationLink(value: PCRoute.album(title: album.title, artist: album.artist)) {
                        VStack(alignment: .leading, spacing: 6) {
                            pcCover(album.artworkUrl, seed: album.title + album.artist, size: 130, radius: 10, artist: album.artist, album: album.title)
                            Text(album.title).font(.system(size: 13, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                            Text(album.artist).font(.system(size: 12)).foregroundStyle(PC.fg2).lineLimit(1)
                        }
                        .frame(width: 130, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                    .pcAlbumContextMenu(
                        title: album.title, artist: album.artist, artworkUrl: album.artworkUrl,
                        coordinator: coordinator,
                        onGoToAlbum: { path.append(.album(title: album.title, artist: album.artist)) },
                        onGoToArtist: { path.append(.artist(album.artist)) })
                }
            }
            .padding(.horizontal, 20)
        }
        .padding(.bottom, 8)
    }

    // ── Your Playlists (6 most-recently-modified) ─────────────────────────
    private var recentPlaylistsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                ForEach(feed.recentPlaylists, id: \.id) { pl in
                    NavigationLink(value: PCRoute.savedPlaylist(id: pl.id)) {
                        VStack(alignment: .leading, spacing: 6) {
                            pcCover(pl.artworkUrl, seed: pl.name, size: 130, radius: 10)
                            Text(pl.name).font(.system(size: 13, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                            Text("\(pl.trackCount) tracks").font(.system(size: 12)).foregroundStyle(PC.fg2).lineLimit(1)
                        }
                        .frame(width: 130, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button { playPlaylist(pl) } label: { Label("Play Playlist", systemImage: "play.fill") }
                        Button { path.append(.savedPlaylist(id: pl.id)) } label: { Label("Open", systemImage: "arrow.up.right") }
                    }
                }
            }
            .padding(.horizontal, 20)
        }
        .padding(.bottom, 8)
    }

    private func playPlaylist(_ p: Playlist) {
        Task {
            let tracks = (try? await container.getPlaylistTracksOnce(id: p.id)) ?? []
            let entities = tracks.map { pcTrack(from: $0) }
            guard !entities.isEmpty else { return }
            coordinator.setQueue(entities, startIndex: 0,
                                 context: PlaybackContext(type: "playlist", name: p.name, id: p.id))
        }
    }

    // ── Your Collection (stat cards → Collection / Playlists tabs) ────────
    private var collectionStatsRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                statCard("Songs", feed.songCount, "music.note") { onSelectTab(.collection, .songs) }
                statCard("Albums", feed.albumCount, "square.stack") { onSelectTab(.collection, .albums) }
                statCard("Artists", feed.artistCount, "person") { onSelectTab(.collection, .artists) }
                statCard("Playlists", feed.playlistCount, "music.note.list") { onSelectTab(.playlists, nil) }
                statCard("Friends", feed.friendCount, "person.2") { onSelectTab(.collection, .friends) }
            }
            .padding(.horizontal, 20)
        }
        .padding(.bottom, 8)
    }

    private func statCard(_ label: String, _ count: Int, _ icon: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: icon).font(.system(size: 17)).foregroundStyle(PC.accent)
                Text("\(count)").font(.system(size: 22, weight: .bold)).foregroundStyle(PC.fg1)
                Text(label).font(.system(size: 12)).foregroundStyle(PC.fg2)
            }
            .padding(14)
            .frame(width: 100, alignment: .leading)
            .background(PC.cardBg, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(PC.cardBorder, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    // ── Recent Loves (10 most-recently-added tracks, with resolver badges) ─
    private var recentLovesList: some View {
        VStack(spacing: 0) {
            ForEach(Array(feed.recentLoves.enumerated()), id: \.element.id) { i, track in
                loveRow(track, index: i)
            }
        }
    }

    private func loveRow(_ track: Track, index: Int) -> some View {
        Button {
            coordinator.setQueue(feed.recentLoves, startIndex: index,
                                 context: PlaybackContext(type: "collection", name: "Recent Loves", id: nil))
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
            onGoToArtist: { path.append(.artist(track.artist)) },
            onGoToAlbum: track.album.map { album in { path.append(.album(title: album, artist: track.artist)) } })
    }

    // ── Welcome / Scan card (empty library) ───────────────────────────────
    @ViewBuilder private var welcomeScanCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Welcome to Parachord").font(.system(size: 18, weight: .bold)).foregroundStyle(PC.fg1)
            Text("Scan your local music, or connect a streaming service in Settings, to start building your library.")
                .font(.system(size: 13)).foregroundStyle(PC.fg2).fixedSize(horizontal: false, vertical: true)
            switch scanner.phase {
            case .scanning(let done, let total):
                ProgressView(value: Double(done), total: Double(max(total, 1))).tint(PC.accent)
                Text("Scanning… \(done)/\(total)").font(.system(size: 12)).foregroundStyle(PC.fg3)
            case .requesting:
                ProgressView().tint(PC.accent)
            case .denied:
                Text("Music access denied — enable it in iOS Settings → Privacy → Media & Apple Music.")
                    .font(.system(size: 12)).foregroundStyle(PC.warning)
            case .failed(let msg):
                Text("Scan failed: \(msg)").font(.system(size: 12)).foregroundStyle(PC.warning)
            default:
                Button { scanner.scan() } label: {
                    Text("Scan Local Music").font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                        .frame(maxWidth: .infinity).padding(.vertical, 11)
                        .background(RoundedRectangle(cornerRadius: 8).fill(PC.fillAccent))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(16)
        .background(PC.accent.opacity(0.06), in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 20).padding(.top, 12)
    }

    // ── AI Suggestions (Shuffleupagus) ────────────────────────────────────
    @ViewBuilder private var aiSuggestionsSection: some View {
        if feed.hasAiPlugins == true {
            if !feed.aiAlbums.isEmpty {
                aiHeader("Album suggestions")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(Array(feed.aiAlbums.enumerated()), id: \.offset) { _, al in
                            NavigationLink(value: PCRoute.album(title: al.title, artist: al.artist)) {
                                VStack(alignment: .leading, spacing: 6) {
                                    pcCover(al.artworkUrl, seed: al.title + al.artist, size: 120, radius: 10, artist: al.artist, album: al.title)
                                    Text(al.title).font(.system(size: 13, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                                    Text(al.artist).font(.system(size: 12)).foregroundStyle(PC.fg2).lineLimit(1)
                                }
                                .frame(width: 120, alignment: .leading)
                            }
                            .buttonStyle(.plain)
                            .pcAlbumContextMenu(
                                title: al.title, artist: al.artist, artworkUrl: al.artworkUrl,
                                coordinator: coordinator,
                                onGoToAlbum: { path.append(.album(title: al.title, artist: al.artist)) },
                                onGoToArtist: { path.append(.artist(al.artist)) })
                        }
                    }
                    .padding(.horizontal, 20)
                }
                .padding(.bottom, 8)
            }
            if !feed.aiArtists.isEmpty {
                aiHeader("Artist suggestions")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(Array(feed.aiArtists.enumerated()), id: \.offset) { _, ar in
                            NavigationLink(value: PCRoute.artist(ar.name)) {
                                VStack(spacing: 6) {
                                    pcCover(ar.imageUrl, seed: ar.name, size: 90, radius: 45)
                                    Text(ar.name).font(.system(size: 12, weight: .medium)).foregroundStyle(PC.fg1)
                                        .lineLimit(1).frame(width: 90)
                                }
                            }
                            .buttonStyle(.plain)
                            .pcArtistContextMenu(
                                name: ar.name, imageUrl: ar.imageUrl, coordinator: coordinator,
                                onGoToArtist: { path.append(.artist(ar.name)) })
                        }
                    }
                    .padding(.horizontal, 20)
                }
                .padding(.bottom, 8)
            }
            if feed.aiLoading && feed.aiAlbums.isEmpty && feed.aiArtists.isEmpty {
                aiHeader("Suggestions")
                ProgressView().tint(PC.accent).frame(maxWidth: .infinity).padding(.vertical, 24)
            } else if let error = feed.aiError, feed.aiAlbums.isEmpty && feed.aiArtists.isEmpty {
                // The AI suggestions error: what went wrong, with
                // the header's refresh to try again.
                aiHeader("AI suggestions")
                Text(error).font(.system(size: 13)).foregroundStyle(PC.error)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20).padding(.bottom, 8)
            }
        } else if feed.hasAiPlugins == false {
            // Parity with Android's "Surprise Me / Configure Plugins" card.
            Button { onOpenSettings() } label: {
                VStack(alignment: .leading, spacing: 6) {
                    Label("Surprise Me", systemImage: "sparkles").font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                    Text("Enable an AI plugin (ChatGPT, Claude, or Gemini) in Settings to get personalized album & artist suggestions.")
                        .font(.system(size: 12)).foregroundStyle(.white.opacity(0.9)).fixedSize(horizontal: false, vertical: true)
                }
                .padding(16).frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    LinearGradient(colors: [Color(uiColor: UIColor(hex: 0x6366f1)), Color(uiColor: UIColor(hex: 0x7c3aed))],
                                   startPoint: .topLeading, endPoint: .bottomTrailing),
                    in: RoundedRectangle(cornerRadius: 16))
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 20).padding(.top, 18)
        }
    }

    private func aiHeader(_ title: String) -> some View {
        PCSectionHeader(title: title, gutter: 20) {
            Text("Shuffleupagus").font(.system(size: 11, weight: .semibold)).foregroundStyle(PC.accent)
                .padding(.horizontal, 8).padding(.vertical, 3)
                .background(PC.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 6))
            Button { Task { await feed.loadAi() } } label: {
                Image(systemName: "arrow.clockwise").font(.system(size: 13)).foregroundStyle(PC.accent)
            }
        }
    }

    // ── Announcement banner ───────────────────────────────────────────────
    private func announcementBanner(_ ann: IosAnnouncement) -> some View {
        let color = announcementColor(ann.severity)
        return VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(ann.title).font(.system(size: 15, weight: .semibold)).foregroundStyle(PC.fg1)
                    if let body = ann.body {
                        Text(body).font(.system(size: 13)).foregroundStyle(PC.fg2).fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 8)
                Button { feed.dismissAnnouncement() } label: {
                    Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundStyle(PC.fg3)
                }
            }
            if let label = ann.ctaLabel, let urlStr = ann.ctaUrl, let url = URL(string: urlStr) {
                Button {
                    feed.trackAnnouncement(ann.id, event: "cta-click")
                    UIApplication.shared.open(url)
                } label: {
                    Text(label).font(.system(size: 14, weight: .semibold)).foregroundStyle(color)
                }
                .padding(.top, 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(color.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 20).padding(.top, 12)
    }

    private func announcementColor(_ severity: String?) -> Color {
        switch severity {
        case "error":            return PC.error
        case "warn", "warning":  return PC.warning
        case "success":          return PC.success
        default:                 return PC.accent
        }
    }
}
