import SwiftUI

/// App shell (phase 5.0). The real ContentView — a TabView whose first
/// tab is the first production screen (Settings), with the phase 1–4
/// platform-actual smoke tests preserved behind a "Dev" tab so they
/// stay verifiable while the rest of the screens get built.
///
/// As real screens land (Library, Now Playing, Search, Playlists…) they
/// become tabs here and the Dev tab eventually retires.
struct ContentView: View {
    var body: some View {
        TabView {
            SearchView()
                .tabItem {
                    Label("Search", systemImage: "magnifyingglass")
                }

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape")
                }

            DevSmokeTestView()
                .tabItem {
                    Label("Dev", systemImage: "hammer")
                }
        }
    }
}

#Preview {
    ContentView()
}
