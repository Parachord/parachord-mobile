import SwiftUI
import Shared

/// App shell (Phase 1 redesign — docs/design/parachord-ios).
///
/// Custom floating chrome instead of the system TabView: Home / Search /
/// Collection / Playlists with a center Shuffleupagus FAB, a liquid-glass
/// tab bar, a floating mini-player that expands to Now Playing, a slide-in
/// Sidebar, and the Add action sheet. Components live in `Shell.swift`.
struct ContentView: View {
    @State private var playback = AppPlayback()
    @State private var tab: PCTab = .home
    @State private var showSidebar = false
    @State private var showAdd = false
    @State private var showNowPlaying = false
    @State private var showSettings = false
    /// Bumped when the active tab is re-tapped; folded into the tab content's
    /// `.id` so re-tapping pops that tab's NavigationStack to root (and thus
    /// back to where the sidebar menu lives).
    @State private var navResetCount = 0
    /// One-shot route the sidebar injects into the Home tab (HomeScreen owns
    /// the actual nav stack as internal @State so re-tap reliably pops to root).
    @State private var homePendingRoute: PCRoute?
    /// Shared namespace for the mini-player ↔ Now Playing artwork morph.
    @Namespace private var artNS

    private var coordinator: QueuePlaybackCoordinator { playback.coordinator }

    var body: some View {
        ZStack(alignment: .bottom) {
            // ── Active tab content ───────────────────────────────────
            Group {
                switch tab {
                case .home:       HomeScreen(pendingRoute: $homePendingRoute, onMenu: { showSidebar = true })
                case .search:     SearchView(onMenu: { showSidebar = true })
                case .collection: CollectionView(onMenu: { showSidebar = true })
                case .playlists:  PCPlaceholder(title: "Playlists",
                                                systemImage: "music.note.list",
                                                note: "Your playlists. Lands with the iOS library layer.",
                                                onMenu: { showSidebar = true })
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
                        title: t.title, artist: t.artist,
                        artworkUrl: pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album),
                        isPlaying: coordinator.isPlaying,
                        isStarting: coordinator.isStarting,
                        progress: coordinator.duration > 0 ? coordinator.currentTime / coordinator.duration : 0,
                        artNamespace: artNS, artIsSource: !showNowPlaying,
                        onToggle: { coordinator.togglePlayPause() },
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
                    // Queue-source banner (#209) → push the playlist/album/artist page.
                    onNavigateToContext: { ctx in
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
                    PCSidebar(onNav: handleSidebar, onClose: { closeSidebar() })
                        .ignoresSafeArea()
                    Spacer(minLength: 0)
                }
                .transition(.move(edge: .leading))
            }
        }
        // NOTE: no faux Dynamic Island while the app is in the FOREGROUND — the
        // mini-player already shows what's playing, and a real Dynamic Island is
        // a system-managed Live Activity that only appears when the app is
        // BACKGROUNDED. Rendering a mock island over our own UI covered the real
        // status bar / camera housing and is non-standard. (A real ActivityKit
        // Live Activity for background playback is a separate, larger feature.)
        .environment(coordinator)
        .task { ResolverPrefs.shared.start() }
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
        .sheet(isPresented: $showAdd) {
            PCAddSheet(onShuffleupagus: { /* Phase: DJ chat */ }, onDismiss: { showAdd = false })
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

    private func closeSidebar() { withAnimation(.easeOut(duration: 0.25)) { showSidebar = false } }

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

#Preview { ContentView() }
