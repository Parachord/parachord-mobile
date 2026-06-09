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
    /// Home tab's value-based navigation stack — driven by the Discover tiles
    /// AND the sidebar (so sidebar items push with the tab bar still visible).
    @State private var homePath: [PCRoute] = []
    /// Shared namespace for the mini-player ↔ Now Playing artwork morph.
    @Namespace private var artNS

    private var coordinator: QueuePlaybackCoordinator { playback.coordinator }

    var body: some View {
        ZStack(alignment: .bottom) {
            // ── Active tab content ───────────────────────────────────
            Group {
                switch tab {
                case .home:       HomeScreen(path: $homePath, onMenu: { showSidebar = true })
                case .search:     SearchView()
                case .collection: PCPlaceholder(title: "Collection",
                                                systemImage: "square.stack",
                                                note: "Your saved tracks, albums & artists. Lands with the iOS library layer.",
                                                onMenu: { showSidebar = true })
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
                        isPlaying: coordinator.isPlaying,
                        progress: coordinator.duration > 0 ? coordinator.currentTime / coordinator.duration : 0,
                        artNamespace: artNS, artIsSource: !showNowPlaying,
                        onToggle: { coordinator.togglePlayPause() },
                        onExpand: { withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) { showNowPlaying = true } }
                    )
                }
                PCTabBar(selected: $tab, onCenter: { showAdd = true },
                         onReselect: { t in
                             if t == .home { homePath = [] } else { navResetCount += 1 }
                         })
            }
            .padding(.bottom, 6)
            .opacity(showNowPlaying ? 0 : 1)

            // ── Now Playing (in-shell overlay for the shared-element morph) ──
            if showNowPlaying {
                PCNowPlaying(
                    coordinator: coordinator,
                    artNamespace: artNS,
                    onClose: { withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) { showNowPlaying = false } }
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
        .overlay(alignment: .top) {
            // Mock Dynamic Island live activity — hidden while Now Playing is up.
            if let t = coordinator.currentTrack, !showNowPlaying {
                PCDynamicIsland(title: t.title, isPlaying: coordinator.isPlaying)
                    .padding(.top, 11)
            }
        }
        .environment(coordinator)
        .sheet(isPresented: $showAdd) {
            PCAddSheet(onShuffleupagus: { /* Phase: DJ chat */ }, onDismiss: { showAdd = false })
        }
        .sheet(isPresented: $showSettings) {
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
        default:                nil
        }
        if let route {
            tab = .home
            homePath = [route]
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
            HStack {
                Button(action: onMenu) {
                    Image(systemName: "line.3.horizontal").font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(PC.fg1).frame(width: 36, height: 36)
                        .background(.ultraThinMaterial, in: Circle())
                }
                .buttonStyle(.plain)
                Spacer()
            }
            .padding(.horizontal, 16).padding(.top, 10)

            Text(title).font(.system(size: 34, weight: .bold)).tracking(0.36)
                .foregroundStyle(PC.fg1)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20).padding(.top, 6)

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
