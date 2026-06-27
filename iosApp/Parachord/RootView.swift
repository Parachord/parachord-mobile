import SwiftUI
import Shared
import UniformTypeIdentifiers

/// App shell (Phase 1 redesign — docs/design/parachord-ios).
///
/// Custom floating chrome instead of the system TabView: Home / Search /
/// Collection / Playlists with a center Shuffleupagus FAB, a liquid-glass
/// tab bar, a floating mini-player that expands to Now Playing, a slide-in
/// Sidebar, and the Add action sheet. Components live in `Shell.swift`.
struct ContentView: View {
    @State private var playback = AppPlayback()
    @State private var theme = ThemeObserver()
    @State private var creator = PlaylistCreator.shared
    @State private var importer = PlaylistImporter.shared
    @State private var friendAdder = FriendAdder.shared
    @State private var metaEditor = TrackMetadataEditor.shared
    @State private var listenAlong = ListenAlongController.shared
    @State private var spinoff = SpinoffController.shared
    @State private var tab: PCTab = .home
    @State private var showSidebar = false
    @State private var showAdd = false
    @State private var showNowPlaying = false
    @State private var showSettings = false
    @State private var showChat = false
    @State private var pendingChat = false
    @State private var chatDragX: CGFloat = 0
    /// Bumped when the active tab is re-tapped; folded into the tab content's
    /// `.id` so re-tapping pops that tab's NavigationStack to root (and thus
    /// back to where the sidebar menu lives).
    @State private var navResetCount = 0
    /// One-shot route the sidebar injects into the Home tab (HomeScreen owns
    /// the actual nav stack as internal @State so re-tap reliably pops to root).
    @State private var homePendingRoute: PCRoute?
    /// One-shot sub-tab for the Collection tab, set when the queue-source banner
    /// links to "Collection" so it opens on Songs rather than the default. (#209)
    @State private var collectionPendingTab: CollectionTab?
    /// Deep-link (#228): transient ack banner + the queue-mutating command
    /// awaiting confirmation (queue add / clear).
    @State private var dlToast: String?
    @State private var dlConfirm: IosDeepLinkCommand?
    /// Shared namespace for the mini-player ↔ Now Playing artwork morph.
    @Namespace private var artNS

    private var coordinator: QueuePlaybackCoordinator { playback.coordinator }

    var body: some View {
        ZStack(alignment: .bottom) {
            // ── Active tab content ───────────────────────────────────
            Group {
                switch tab {
                case .home:       HomeScreen(pendingRoute: $homePendingRoute, onMenu: { showSidebar = true }, onOpenSettings: { showSettings = true })
                case .search:     SearchView(onMenu: { showSidebar = true })
                case .collection: CollectionView(onMenu: { showSidebar = true }, pendingTab: $collectionPendingTab)
                case .playlists:  PlaylistsScreen(onMenu: { showSidebar = true })
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // Re-tapping the active tab bumps navResetCount → new id → fresh
            // NavigationStack (pops to root). Switching tabs already recreates.
            .id("\(tab.rawValue)-\(navResetCount)")

            // ── Floating mini-player + tab bar ───────────────────────
            VStack(spacing: 10) {
                if let t = coordinator.currentTrack {
                    PCMiniPlayer(
                        track: t,
                        title: t.title, artist: t.artist,
                        artworkUrl: pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album),
                        isPlaying: coordinator.isPlaying,
                        isStarting: coordinator.isStarting,
                        progress: coordinator.duration > 0 ? coordinator.currentTime / coordinator.duration : 0,
                        artNamespace: artNS, artIsSource: !showNowPlaying,
                        onToggle: { coordinator.togglePlayPause() },
                        onNext: { coordinator.skipNext() },
                        onExpand: { withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) { showNowPlaying = true } }
                    )
                }
                PCTabBar(selected: $tab, onCenter: { showAdd = true },
                         // Re-tapping the active tab bumps navResetCount → new
                         // `.id` → the tab content (and its NavigationStack,
                         // whose path is internal @State) is recreated fresh at
                         // root, popping every push regardless of how it got there.
                         onReselect: { _ in navResetCount += 1 })
            }
            .padding(.bottom, 6)
            .opacity(showNowPlaying ? 0 : 1)

            // ── Now Playing (in-shell overlay for the shared-element morph) ──
            if showNowPlaying {
                PCNowPlaying(
                    coordinator: coordinator,
                    artNamespace: artNS,
                    onClose: { withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) { showNowPlaying = false } },
                    // Tapping the artist closes Now Playing and pushes the artist
                    // page onto the Home tab's stack.
                    onArtist: { name in
                        withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) { showNowPlaying = false }
                        tab = .home
                        homePendingRoute = .artist(name)
                    },
                    // On-tour dot (#201): same path as onArtist, but deep-links
                    // straight to the artist's On Tour tab.
                    onArtistOnTour: { name in
                        withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) { showNowPlaying = false }
                        tab = .home
                        homePendingRoute = .artistOnTour(name)
                    },
                    // Queue-source banner (#209) → open the source page. Pushable
                    // pages go on the Home stack; Collection is a tab, so switch to it.
                    onNavigateToContext: { ctx in
                        if ctx.type == "collection" {
                            withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) { showNowPlaying = false }
                            collectionPendingTab = .songs   // land on Songs, not the default Artists
                            tab = .collection
                            return
                        }
                        guard let route = pcRouteForContext(ctx) else { return }
                        withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) { showNowPlaying = false }
                        tab = .home
                        homePendingRoute = route
                    }
                )
                // NOTE: do NOT add .ignoresSafeArea() here — the content must
                // respect the top safe area (status bar / Dynamic Island). The
                // dark background goes full-bleed via npBg.ignoresSafeArea()
                // INSIDE PCNowPlaying.
                .zIndex(5)
                .transition(.move(edge: .bottom))
            }

            // ── Sidebar drawer (slide-in over a scrim) ───────────────
            if showSidebar {
                Color.black.opacity(0.44).ignoresSafeArea()
                    .transition(.opacity)
                    .onTapGesture { withAnimation(.easeOut(duration: 0.25)) { showSidebar = false } }
                HStack(spacing: 0) {
                    PCSidebar(onNav: handleSidebar, onClose: { closeSidebar() },
                              onListenAlong: { listenAlong.toggle($0) },
                              onFriendProfile: { f in
                                  closeSidebar()
                                  tab = .home
                                  homePendingRoute = .friend(id: f.id, username: f.username, service: f.service, name: f.displayName)
                              })
                        .ignoresSafeArea()
                    Spacer(minLength: 0)
                }
                .transition(.move(edge: .leading))
            }

            // ── DJ Chat / Shuffleupagus (slides in from the RIGHT, Android parity) ──
            if showChat {
                ChatScreen(onClose: { closeChat() })
                    .environment(coordinator)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(PC.Player.bg)
                    .offset(x: chatDragX)
                    .ignoresSafeArea()
                    .overlay(alignment: .leading) {
                        // Thin leading-edge swipe-right-to-close zone — a screen-wide
                        // drag would fight the chat's scroll/input, so scope it to the edge.
                        Color.clear.frame(width: 22).contentShape(Rectangle()).ignoresSafeArea()
                            .highPriorityGesture(
                                DragGesture(minimumDistance: 10)
                                    .onChanged { v in chatDragX = max(0, v.translation.width) }
                                    .onEnded { v in
                                        if v.translation.width > 90 { closeChat() }
                                        else { withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { chatDragX = 0 } }
                                    }
                            )
                    }
                    .transition(.move(edge: .trailing))
                    .zIndex(60)
            }
        }
        // NOTE: no faux Dynamic Island while the app is in the FOREGROUND — the
        // mini-player already shows what's playing, and a real Dynamic Island is
        // a system-managed Live Activity that only appears when the app is
        // BACKGROUNDED. Rendering a mock island over our own UI covered the real
        // status bar / camera housing and is non-standard. (A real ActivityKit
        // Live Activity for background playback is a separate, larger feature.)
        .environment(coordinator)
        // #234: honor the Settings › General theme override (system/light/dark).
        // Forcing the scheme here also flips the `dyn()` palette (SwiftUI overrides
        // the trait). NowPlaying stays dark regardless — its surfaces use the
        // constant PC.Player.* tokens, not `dyn`.
        .preferredColorScheme(theme.scheme)
        .task { theme.start() }
        .task { ResolverPrefs.shared.start() }
        // #235 Listen Along: bind the engine to the shared coordinator, and accept
        // parachord://listen-along?service=&user= deep links.
        .onAppear { listenAlong.bind(coordinator); spinoff.bind(coordinator); bindChatPlayback() }
        .onOpenURL { handleDeepLink($0) }
        // Deep-link queue-mutation confirmation (#228 — parity with Android's
        // QueueAdd/QueueClear confirm dialogs).
        .alert("Deep link", isPresented: Binding(get: { dlConfirm != nil }, set: { if !$0 { dlConfirm = nil } })) {
            Button("Cancel", role: .cancel) { dlConfirm = nil }
            Button(dlConfirm?.kind == "queueClear" ? "Clear" : "Add") {
                if let c = dlConfirm { performConfirmed(c) }
                dlConfirm = nil
            }
        } message: {
            Text(dlConfirm?.kind == "queueClear"
                 ? "An external link wants to clear your queue."
                 : "An external link wants to add a track to your queue:\n\n\(dlConfirm?.artist ?? "") – \(dlConfirm?.title ?? "")")
        }
        // Deep-link ack banner (#228) — top, auto-dismissing.
        .overlay(alignment: .top) {
            if let msg = dlToast {
                Text(msg)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Capsule().fill(Color.black.opacity(0.82)))
                    .padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        // #235 On-Air triggers: refresh every friend's now-playing and auto-pin
        // on-air friends into the sidebar (auto-unpin when they go offline),
        // every 2 min — mirrors Android's MainViewModel cycle. Runs an initial
        // pass on launch so currently-listening friends surface right away.
        .task {
            let container = IosContainer.companion.shared
            container.syncFriends()   // import LB following + Last.fm friends on launch
            while !Task.isCancelled {
                container.refreshFriendsAndAutoPin()
                try? await Task.sleep(nanoseconds: 120_000_000_000)
            }
        }
        // Collection sync (#194, Phase 1): prune expired tombstones once, then
        // run a foreground sync on launch + every 15 min. syncNow() is gated
        // internally on Settings → sync enabled, so this no-ops until the user
        // turns it on. (Background BGTaskScheduler is a follow-up.)
        .task {
            let container = IosContainer.companion.shared
            try? await container.pruneTombstonesOnLaunch()
            while !Task.isCancelled {
                _ = try? await container.syncNow()
                try? await Task.sleep(nanoseconds: 900_000_000_000)   // 15 min
            }
        }
        // Hosted XSPF polling (#254): re-fetch hosted playlists on launch + every
        // 5 min while foregrounded (mirrors desktop cadence + Android's foreground
        // timer). pollHostedPlaylists() no-ops when there are no hosted rows.
        // Background BGTaskScheduler is a follow-up, same as collection sync.
        .task {
            let container = IosContainer.companion.shared
            while !Task.isCancelled {
                _ = try? await container.pollHostedPlaylists()
                try? await Task.sleep(nanoseconds: 300_000_000_000)   // 5 min
            }
        }
        // Restore the persisted queue on launch (paused — never auto-plays). #220
        .task { await coordinator.restoreQueue() }
        // Resolve the current track as soon as it changes (incl. natural
        // auto-advance, where the next track may not have been pre-resolved) so
        // the mini-player art is ready and crossfades reliably — not only when the
        // upcoming track happened to already be cached. The resolver cache is
        // @Observable, so the mini's pcTrackArt re-renders when this lands.
        .task(id: coordinator.currentTrack?.id) {
            if let t = coordinator.currentTrack {
                IosTrackResolverCache.shared.resolve(
                    ResolveRequest(artist: t.artist, title: t.title, album: t.album), order: 0)
            }
        }
        .sheet(isPresented: $showAdd, onDismiss: {
            // Slide the chat in AFTER the add sheet finishes dismissing.
            if pendingChat { pendingChat = false; withAnimation(.easeInOut(duration: 0.3)) { showChat = true } }
        }) {
            PCAddSheet(onShuffleupagus: { pendingChat = true; showAdd = false }, onDismiss: { showAdd = false })
        }
        // New-playlist create prompt (#242), hosted once at the root so both the
        // FAB's "New Playlist" and the track menu's "Add to Playlist → New
        // Playlist…" route through it.
        .alert("New Playlist", isPresented: Binding(get: { creator.showing }, set: { creator.showing = $0 })) {
            TextField("Playlist name", text: Binding(get: { creator.name }, set: { creator.name = $0 }))
            Button("Cancel", role: .cancel) { creator.showing = false }
            Button("Create") { creator.create() }
        }
        // XSPF import + Add Friend modals (#254/#198), hosted once at the root so
        // the FAB and the Playlists screen share one flow. Bundled into a single
        // ViewModifier — inlining all six modifiers blew the type-checker budget.
        .modifier(PCAddActionModals(importer: importer, friendAdder: friendAdder))
        // In-app track metadata editor (#248), hosted once at the root so the
        // track context menu's "Edit Metadata" can present it (a contextMenu
        // can't host its own sheet).
        .sheet(isPresented: Binding(get: { metaEditor.showing }, set: { metaEditor.showing = $0 })) {
            TrackMetadataEditorSheet(editor: metaEditor)
        }
        // Settings is a FULL-SCREEN cover (not a card sheet). SettingsView's
        // PCTopBar back button calls dismiss(), which closes the cover.
        .fullScreenCover(isPresented: $showSettings) {
            NavigationStack { SettingsView() }
        }
        // Spotify Connect device picker (Phase: Spotify) — hosted at the shell.
        .sheet(item: Binding(
            get: { playback.spotify.pickerRequest },
            set: { if $0 == nil { playback.spotify.onDevicePicked(nil) } }
        )) { request in
            SpotifyDevicePickerSheet(request: request) { playback.spotify.onDevicePicked($0) }
        }
    }

    /// Wire the DJ-chat tool surface to the live playback coordinator (#223). The
    /// shared IosDjToolExecutor calls these closures; we hop to the main actor and
    /// drive the coordinator (fire-and-forget, like the spinoff/scrobble bridges).
    private func bindChatPlayback() {
        let coord = coordinator
        IosContainer.companion.shared.bindChatPlayback(
            onPlayTrack: { track in Task { @MainActor in
                coord.setQueue([track], startIndex: 0,
                               context: PlaybackContext(type: "chat", name: "Shuffleupagus", id: nil)) } },
            onAddToQueue: { track in Task { @MainActor in coord.addToQueue(track) } },
            onClearQueue: { Task { @MainActor in coord.clearQueue() } },
            onPause: { Task { @MainActor in if coord.isPlaying { coord.togglePlayPause() } } },
            onResume: { Task { @MainActor in if !coord.isPlaying { coord.togglePlayPause() } } },
            onSkipNext: { Task { @MainActor in coord.skipNext() } },
            onSkipPrevious: { Task { @MainActor in coord.skipPrevious() } },
            onSetShuffle: { _ in Task { @MainActor in coord.toggleShuffle() } }
        )
    }

    private func closeChat() { withAnimation(.easeInOut(duration: 0.28)) { showChat = false; chatDragX = 0 } }

    private func closeSidebar() { withAnimation(.easeOut(duration: 0.25)) { showSidebar = false } }

    /// Full `parachord://` deep-link dispatch (#228). Parses via the shared
    /// `DeepLinkParser` (through the container) and drives navigation / playback.
    /// Protocol-play, radio, import, and external Spotify/Apple links are parsed
    /// as `unsupported` (a follow-up ticket); listen-along (#235) keeps working.
    private func handleDeepLink(_ url: URL) {
        guard url.scheme == "parachord",
              let comps = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }
        let segs = comps.path.split(separator: "/").map(String.init)
        var query: [String: String] = [:]
        for item in comps.queryItems ?? [] { if let v = item.value { query[item.name] = v } }
        guard let cmd = IosContainer.companion.shared.parseDeepLink(
            scheme: url.scheme, host: comps.host, path: segs, query: query) else { return }
        dispatchDeepLink(cmd)
    }

    private func dispatchDeepLink(_ cmd: IosDeepLinkCommand) {
        let coord = coordinator
        switch cmd.kind {
        // ── Playback ──
        case "play":
            guard let a = cmd.artist, let t = cmd.title else { return }
            let track = IosContainer.companion.shared.metadataTrack(artist: a, title: t, album: cmd.album)
            coord.setQueue([track], startIndex: 0,
                           context: PlaybackContext(type: "deeplink", name: t, id: nil))
            dlAck("Playing \(t)")
        case "control":
            switch cmd.action {
            case "pause", "resume", "play": coord.togglePlayPause()
            case "skip", "next": coord.skipNext()
            case "previous": coord.skipPrevious()
            default: break
            }
        case "queueAdd", "queueClear": dlConfirm = cmd   // confirm first
        case "shuffle":
            if coord.shuffleEnabled != cmd.shuffleOn { coord.toggleShuffle() }
            dlAck(cmd.shuffleOn ? "Shuffle on" : "Shuffle off")
        case "volume": break   // system-level on iOS; ignored (parity with Android)
        case "listenAlong":
            guard let s = cmd.service, let u = cmd.user else { return }
            dlAck("Catching up to \(u)…")
            Task { @MainActor in
                if let f = (try? await IosContainer.companion.shared
                    .fetchTransientFriendNowPlaying(service: s, user: u)) ?? nil {
                    listenAlong.start(f)
                } else {
                    dlAck("\(u) isn't listening on \(s) right now")
                }
            }
        // ── Navigation ──
        case "navHome": tab = .home; homePendingRoute = nil
        case "navArtist": if let n = cmd.name { tab = .home; homePendingRoute = .artist(n) }
        case "navAlbum": if let a = cmd.artist, let t = cmd.title { tab = .home; homePendingRoute = .album(title: t, artist: a) }
        case "navLibrary": tab = .collection; collectionPendingTab = libraryTab(cmd.tab)
        case "navHistory": tab = .home; homePendingRoute = .history
        case "navFriend": if let id = cmd.id { navigateToFriend(id) }
        case "navRecommendations": tab = .home; homePendingRoute = .recommendations
        case "navCharts": tab = .home; homePendingRoute = .pop
        case "navCritical": tab = .home; homePendingRoute = .critical
        case "navPlaylists": tab = .playlists
        case "navPlaylist": if let id = cmd.id { tab = .home; homePendingRoute = .playlist(id: id, title: "") }
        case "navSettings": showSettings = true
        case "navSearch": tab = .search   // query pre-fill: follow-up
        case "navChat": withAnimation(.easeInOut(duration: 0.3)) { showChat = true }   // prompt injection: follow-up
        case "unsupported": dlAck("That link type isn't supported yet")
        default: break
        }
    }

    private func performConfirmed(_ cmd: IosDeepLinkCommand) {
        let coord = coordinator
        switch cmd.kind {
        case "queueAdd":
            guard let a = cmd.artist, let t = cmd.title else { return }
            coord.addToQueue(IosContainer.companion.shared.metadataTrack(artist: a, title: t, album: cmd.album))
            dlAck("Added to queue: \(t)")
        case "queueClear":
            coord.clearQueue(); dlAck("Queue cleared")
        default: break
        }
    }

    private func navigateToFriend(_ id: String) {
        Task { @MainActor in
            if let f = (try? await IosContainer.companion.shared.getFriend(friendId: id)) ?? nil {
                tab = .home
                homePendingRoute = .friend(id: f.id, username: f.username, service: f.service, name: f.displayName)
            } else {
                dlAck("Friend not found")
            }
        }
    }

    private func libraryTab(_ tab: String?) -> CollectionTab? {
        switch tab?.lowercased() {
        case "albums": return .albums
        case "artists": return .artists
        case "songs", "tracks": return .songs
        case "friends": return .friends
        default: return nil
        }
    }

    /// Show a transient ack banner (#228). Auto-dismisses after ~2.6s.
    private func dlAck(_ message: String) {
        withAnimation(.easeInOut(duration: 0.25)) { dlToast = message }
        let shown = message
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            if dlToast == shown { withAnimation(.easeInOut(duration: 0.25)) { dlToast = nil } }
        }
    }

    private func handleSidebar(_ id: String) {
        closeSidebar()
        // Discover items push onto the Home tab's stack.
        let route: PCRoute? = switch id {
        case "recommendations": .recommendations
        case "pop":             .pop
        case "critical":        .critical
        case "fresh":           .fresh
        case "concerts":        .concerts
        case "history":         .history
        default:                nil
        }
        if let route {
            tab = .home
            homePendingRoute = route
            return
        }
        switch id {
        case "collection": tab = .collection
        case "playlists":  tab = .playlists
        case "settings":   showSettings = true
        default: break // history / concerts land with their screens
        }
    }
}

/// Simple styled placeholder for tabs whose data layer isn't wired yet
/// (Collection / Playlists need the iOS SQLDelight DB). Top nav (menu) +
/// large title to match the shell, with a note about what's coming.
struct PCPlaceholder: View {
    let title: String
    let systemImage: String
    let note: String
    let onMenu: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: title, leading: .menu, onLeading: onMenu)
            Spacer()
            VStack(spacing: 12) {
                Image(systemName: systemImage).font(.system(size: 44)).foregroundStyle(PC.fg3)
                Text(note).font(.system(size: 14)).foregroundStyle(PC.fg2)
                    .multilineTextAlignment(.center).padding(.horizontal, 40)
            }
            Spacer(); Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PC.bgPrimary)
    }
}

/// Root-level modals for the FAB's "Import Playlist" + "Add Friend" actions
/// (#254/#198). Bundled as a ViewModifier so ContentView's body stays within
/// the Swift type-checker's budget.
private struct PCAddActionModals: ViewModifier {
    @Bindable var importer: PlaylistImporter
    @Bindable var friendAdder: FriendAdder

    func body(content: Content) -> some View {
        content
            .confirmationDialog("Import Playlist", isPresented: $importer.showOptions, titleVisibility: .visible) {
                Button("Import .xspf File") { importer.showFileImporter = true }
                Button("Import from URL") { importer.url = ""; importer.showUrlPrompt = true }
            }
            .fileImporter(
                isPresented: $importer.showFileImporter,
                allowedContentTypes: [UTType(filenameExtension: "xspf") ?? .xml, .xml],
                allowsMultipleSelection: false,
            ) { result in
                switch result {
                case .success(let urls): if let u = urls.first { importer.importLocalFile(u) }
                case .failure(let e): importer.message = e.localizedDescription
                }
            }
            .alert("Import from URL", isPresented: $importer.showUrlPrompt) {
                TextField("https://…/playlist.xspf", text: $importer.url)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Cancel", role: .cancel) {}
                Button("Import") { importer.importFromUrl() }
            } message: {
                Text("Paste a Spotify, Apple Music, or hosted XSPF playlist URL. Hosted XSPF playlists also stay in sync as the source changes.")
            }
            .alert("Import", isPresented: Binding(get: { importer.message != nil }, set: { if !$0 { importer.message = nil } })) {
                Button("OK") { importer.message = nil }
            } message: { Text(importer.message ?? "") }
            .alert("Add Friend", isPresented: $friendAdder.showing) {
                TextField("Username or profile URL", text: $friendAdder.input)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Cancel", role: .cancel) {}
                Button("Add") { friendAdder.add() }
            } message: {
                Text("Follow a ListenBrainz or Last.fm user by username, or paste their profile URL.")
            }
            .alert("Add Friend", isPresented: Binding(get: { friendAdder.message != nil }, set: { if !$0 { friendAdder.message = nil } })) {
                Button("OK") { friendAdder.message = nil }
            } message: { Text(friendAdder.message ?? "") }
    }
}

#Preview { ContentView() }
