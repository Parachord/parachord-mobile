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
    /// A ListenBrainz weekly playlist; `contextType` (`weekly-jam` / `weekly-exploration`) when known.
    case playlist(id: String, title: String, contextType: String? = nil)
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
    // Album, artist, playlist, service-playlist and collection contexts open where the
    // shared `playbackContextDestination` says (Android's Now Playing asks it too).
    if let dest = PlaybackContextKt.playbackContextDestination(ctx: ctx) {
        switch dest {
        case let album as ContextDestination.Album:
            return .album(title: album.title, artist: album.artist ?? "")
        case let artist as ContextDestination.Artist:
            return .artist(artist.name)
        case let playlist as ContextDestination.Playlist:
            return .playlist(id: playlist.id, title: ctx.name, contextType: ctx.type)
        case let sp as ContextDestination.ServicePlaylist:
            return .servicePlaylist(url: sp.url, name: ctx.name, source: "", image: nil)
        // Collection is a tab, not a pushed page: see pcCollectionDestination.
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

/// Where a queue-source context opens in the Collection tab, from the shared
/// `playbackContextDestination`: Collection's songs, sorted Recently Added once
/// for a Recent Loves queue (`recentLoves`), as Home's Recent Loves tile opens
/// them. Nil when the context opens a pushed page or nothing.
func pcCollectionDestination(_ ctx: PlaybackContext) -> ContextDestination.Collection? {
    PlaybackContextKt.playbackContextDestination(ctx: ctx) as? ContextDestination.Collection
}

/// Whether a queue-source context links anywhere on iOS — a pushable page
/// (pcRouteForContext) OR the Collection tab (which is a tab, not a route, so
/// the shell switches to it rather than pushing). #209.
func pcContextIsNavigable(_ ctx: PlaybackContext) -> Bool {
    pcRouteForContext(ctx) != nil || pcCollectionDestination(ctx) != nil
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
    case .playlist(let id, let title, let contextType): PlaylistDetailView(playlistId: id, title: title, contextType: contextType)
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
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.pcOpenImport) private var openImport
    @Environment(\.pcAskShuffleupagus) private var askShuffleupagus
    @Environment(\.pcOpenPluginSettings) private var openPluginSettings
    @Environment(\.pcOpenSettingsTab) private var openSettingsTab

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
        // return from a pushed page or from Settings presented over Home: a new
        // phrasing for the Ask example (`enterHome` counts each entry once).
        .task { home.start(); home.enterHome() }
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
        if let gs = page.gettingStarted {
            gettingStartedSections(gs)
        } else {
            HomeHeaderView(
                page: page,
                onCollectionRadio: { home.play(coordinator: coordinator) { try await $0.collectionRadio() } },
                onSetupAction: { id in if id == HomeSetupKt.SETUP_GETTING_STARTED { session.resumeSetup() } },
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
                    onSeeAll: { path.append(half.kind == IosHomePageKt.HALF_KIND_FRESH ? .fresh : half.kind == IosHomePageKt.HALF_KIND_CRITICAL ? .critical : .pop) },
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

    /// The getting-started layout, then "Explore while you set up" (the hero and banners are the frame's).
    @ViewBuilder private func gettingStartedSections(_ gs: IosGettingStarted) -> some View {
        HomeGettingStartedView(
            gs: gs,
            onSkip: { session.skipSetup() },
            onOpenStep: { session.openStep(id: $0) },
            onOpenPlugin: { openPluginSettings($0) },
            onOpenPluginsTab: { openSettingsTab(SettingsTab.shared.PLUGINS) },
            onSyncLibrary: { openSettingsTab(GettingStartedLayoutKt.SYNC_LIBRARY_SETTINGS_TAB) },
            onImport: { openImport() },
            onOpenPlaylist: { path.append(.savedPlaylist(id: $0)) },
            onCollectionRadio: { home.play(coordinator: coordinator) { try await $0.collectionRadio() } },
            onSearch: { onSelectTab(.search, nil) }
        )
        .padding(.top, 24)
        if !gs.explore.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                PCSectionHeader(title: HomeCopy.shared.EXPLORE_TITLE, subtitle: HomeCopy.shared.EXPLORE_SUBTITLE, gutter: homeGutter)
                VStack(spacing: 16) {
                    ForEach(gs.explore, id: \.kind) { half in
                        HomeHalfView(
                            half: half,
                            onSeeAll: { path.append(half.kind == IosHomePageKt.HALF_KIND_CRITICAL ? .critical : .pop) },
                            onOpen: { path.append(.album(title: $0.title, artist: $0.artist)) },
                            onPlay: { card in
                                let key = card.key
                                home.play(coordinator: coordinator) { try await $0.halfAlbumQueue(key: key) }
                            },
                            onShown: { session.halfCardShown(key: $0.key) },
                            onOpenArtist: { path.append(.artist($0.artist)) },
                            onRetry: { session.retryCharts() }
                        )
                    }
                }
            }
            .padding(.top, 24)
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
        case IosHomePageKt.JUMP_KIND_PLAYLIST:
            path.append(.savedPlaylist(id: tile.targetId))
        case IosHomePageKt.JUMP_KIND_ALBUM:
            path.append(.album(title: tile.targetName, artist: tile.targetArtist ?? ""))
        case IosHomePageKt.JUMP_KIND_MIX:
            guard let url = tile.targetUrl, !url.isEmpty else { return }
            path.append(.servicePlaylist(url: url, name: tile.targetName, source: tile.targetArtist ?? "", image: tile.imageUrl))
        case IosHomePageKt.JUMP_KIND_LOVES:
            onOpenRecentLoves()
        default:
            break
        }
    }
}
