import SwiftUI
import Shared

// MARK: - Home (Phase 3 — docs/design/parachord-ios HomeScreen)
//
// API-backed sections only (no iOS DB yet): Continue Listening, the Discover
// tile grid, and the real ListenBrainz Weekly Jams / Weekly Exploration
// carousels. DB/AI sections (Recently Added, Your Playlists, Stats, Recent
// Loves, Friend Activity, Suggestions) land with the data layer.

/// Value-based routes pushed onto the Home tab's stack — driven by both the
/// Discover tiles and the sidebar, so the sidebar navigates with the tab bar
/// still visible (a push, not a modal).
enum PCRoute: Hashable { case recommendations, pop, critical, fresh }

struct HomeScreen: View {
    /// One-shot route injected by the sidebar (consumed into `path`). The tab's
    /// nav stack lives in HomeScreen's OWN @State so re-tapping Home (which
    /// recreates HomeScreen via the shell's `.id`) reliably resets it to root —
    /// a hoisted binding raced the recreation and re-pushed the screen.
    @Binding var pendingRoute: PCRoute?
    let onMenu: () -> Void

    @State private var path: [PCRoute] = []
    @State private var model = DiscoverViewModel()
    @Environment(QueuePlaybackCoordinator.self) private var coordinator

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if let t = coordinator.currentTrack {
                        sectionHeader("Continue Listening")
                        continueCard(t)
                    }

                    sectionHeader("Discover")
                    discoverGrid

                    weeklySection("Weekly Jams", model.jams)
                    weeklySection("Weekly Exploration", model.exploration)

                    if model.isLoading && !model.loaded {
                        ProgressView().frame(maxWidth: .infinity).padding(.vertical, 24)
                    }
                }
                .padding(.bottom, 130) // clear the floating tab bar
            }
            .navigationTitle("Home")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onMenu) { Image(systemName: "line.3.horizontal") }
                        .tint(PC.fg1)
                }
            }
            .navigationDestination(for: PCRoute.self) { route in
                switch route {
                case .recommendations: RecommendationsScreen()
                case .pop:             PopOfTheTopsScreen()
                case .critical:        CriticalDarlingsScreen()
                case .fresh:           FreshDropsScreen()
                }
            }
        }
        .onChange(of: pendingRoute, initial: true) { _, route in
            if let route { path = [route]; pendingRoute = nil }
        }
        .task { model.start() }
    }

    // MARK: Sections

    private func sectionHeader(_ text: String) -> some View {
        Text(text).pcSectionHeader()
            .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 8)
    }

    private func continueCard(_ t: Track) -> some View {
        Button { coordinator.togglePlayPause() } label: {
            HStack(spacing: 14) {
                PCArtwork(name: t.title, size: 56, radius: 8)
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
        let id = UUID(); let title: String; let icon: String; let c1: UInt32; let c2: UInt32; let preset: String
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
    private let tiles: [Tile] = [
        .init(title: "For You", icon: "star.fill", c1: 0x7c3aed, c2: 0x6d28d9, preset: "foryou"),
        .init(title: "Critical Darlings", icon: "heart.fill", c1: 0xea580c, c2: 0xf59e0b, preset: "critical"),
        .init(title: "Pop of the Tops", icon: "chart.line.uptrend.xyaxis", c1: 0xec4899, c2: 0xf59e0b, preset: "pop"),
        .init(title: "Fresh Drops", icon: "drop.fill", c1: 0x10b981, c2: 0x0d9488, preset: "fresh"),
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
        VStack(alignment: .leading, spacing: 12) {
            Label(tile.title, systemImage: tile.icon)
                .font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
            Spacer(minLength: 0)
            Text("Curated").font(.system(size: 12)).foregroundStyle(.white.opacity(0.85))
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 110, alignment: .topLeading)
        .background(
            LinearGradient(colors: [Color(uiColor: UIColor(hex: tile.c1)), Color(uiColor: UIColor(hex: tile.c2))],
                           startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(color: .black.opacity(0.10), radius: 9, y: 6)
    }

    @ViewBuilder
    private func weeklySection(_ title: String, _ entries: [IosWeeklyEntry]) -> some View {
        if !entries.isEmpty {
            HStack(spacing: 10) {
                Text(title).pcSectionHeader()
                Text("ListenBrainz").font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Color(uiColor: UIColor(hex: 0xb45309)))
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Color(uiColor: UIColor(hex: 0xf59e0b)).opacity(0.16), in: RoundedRectangle(cornerRadius: 6))
                Spacer()
            }
            .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 8)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(entries, id: \.id) { entry in
                        NavigationLink {
                            PlaylistDetailView(playlistId: entry.id, title: "\(entry.weekLabel) · \(entry.kind)")
                        } label: {
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
        VStack(alignment: .leading, spacing: 8) {
            LazyVGrid(columns: [GridItem(.fixed(75), spacing: 0), GridItem(.fixed(75), spacing: 0)], spacing: 0) {
                ForEach(0..<4, id: \.self) { j in
                    PCArtwork(name: "\(entry.id)\(j)", size: 75, radius: 0)
                }
            }
            .frame(width: 150, height: 150)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(color: .black.opacity(0.10), radius: 9, y: 4)

            Text(entry.weekLabel).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1).lineLimit(1)
            Text("Weekly playlist").font(.system(size: 12)).foregroundStyle(PC.fg2)
        }
        .frame(width: 150)
    }
}
