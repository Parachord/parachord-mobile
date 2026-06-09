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
    /// Shared namespace for the mini-player ↔ Now Playing artwork morph.
    @Namespace private var artNS

    private var coordinator: QueuePlaybackCoordinator { playback.coordinator }

    var body: some View {
        ZStack(alignment: .bottom) {
            // ── Active tab content ───────────────────────────────────
            Group {
                switch tab {
                case .home:       DiscoverView()   // real content until the Phase-3 Home lands
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
                PCTabBar(selected: $tab, onCenter: { showAdd = true })
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
                .ignoresSafeArea()
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
        switch id {
        case "collection": tab = .collection
        case "playlists":  tab = .playlists
        case "settings":   showSettings = true
        default: break // history / discover lists land in later phases
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
