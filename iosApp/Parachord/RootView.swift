import SwiftUI

/// App shell (phase 5.0). The real ContentView — a TabView whose first
/// tab is the first production screen (Settings), with the phase 1–4
/// platform-actual smoke tests preserved behind a "Dev" tab so they
/// stay verifiable while the rest of the screens get built.
///
/// As real screens land (Library, Now Playing, Search, Playlists…) they
/// become tabs here and the Dev tab eventually retires.
struct ContentView: View {
    /// Single app-wide playback engine, shared by the Now Playing tab
    /// and the persistent mini player.
    @State private var playback = AppPlayback()
    /// Open on Discover — a music app landing on real content beats an
    /// empty Now Playing.
    @State private var selectedTab = 1  // Discover

    var body: some View {
        TabView(selection: $selectedTab) {
            NowPlayingView(playback: playback)
                .tabItem {
                    Label("Playing", systemImage: "play.circle")
                }
                .tag(0)

            DiscoverView()
                .tabItem {
                    Label("Discover", systemImage: "sparkles")
                }
                .tag(1)

            SearchView()
                .tabItem {
                    Label("Search", systemImage: "magnifyingglass")
                }
                .tag(2)

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape")
                }
                .tag(3)

            DevSmokeTestView()
                .tabItem {
                    Label("Dev", systemImage: "hammer")
                }
                .tag(4)
        }
        // Mini player floats above the tab bar on every tab whenever
        // something is queued.
        .safeAreaInset(edge: .bottom) {
            MiniPlayer(playback: playback)
        }
        // The one shared coordinator, reachable by pushed screens
        // (Discover → PlaylistDetail) for tap-to-play.
        .environment(playback.coordinator)
        // Spotify Connect device picker (Android parity) — shown when play
        // hits ambiguous live devices with no remembered preference.
        .sheet(item: Binding(
            get: { playback.spotify.pickerRequest },
            set: { if $0 == nil { playback.spotify.onDevicePicked(nil) } }
        )) { request in
            SpotifyDevicePickerSheet(request: request) { playback.spotify.onDevicePicked($0) }
        }
    }
}

#Preview {
    ContentView()
}
