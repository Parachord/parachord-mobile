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
enum PCRoute: Hashable {
    case recommendations, pop, critical, fresh, concerts, history
    case artist(String)
    case album(title: String, artist: String)
    case playlist(id: String, title: String)
}

/// Single source of truth for every pushed destination. Used by EVERY tab's
/// NavigationStack via `.navigationDestination(for: PCRoute.self)`, so all
/// navigation is VALUE-BASED — never destination-based `NavigationLink { … }`,
/// which corrupts a typed-path NavigationStack on iOS 18+ (mixing the two broke
/// push + back across the app; works on older iPad OS, not the iOS 18.3 sim).
@ViewBuilder
func pcRouteDestination(_ route: PCRoute) -> some View {
    switch route {
    case .recommendations:             RecommendationsScreen()
    case .pop:                         PopOfTheTopsScreen()
    case .critical:                    CriticalDarlingsScreen()
    case .fresh:                       FreshDropsScreen()
    case .concerts:                    ConcertsScreen()
    case .history:                     HistoryScreen()
    case .artist(let name):            ArtistScreen(artistName: name)
    case .album(let title, let artist): AlbumScreen(title: title, artist: artist)
    case .playlist(let id, let title): PlaylistDetailView(playlistId: id, title: title)
    }
}

struct HomeScreen: View {
    /// One-shot route injected by the sidebar (consumed into `path`). The tab's
    /// nav stack lives in HomeScreen's OWN @State so re-tapping Home (which
    /// recreates HomeScreen via the shell's `.id`) reliably resets it to root —
    /// a hoisted binding raced the recreation and re-pushed the screen.
    @Binding var pendingRoute: PCRoute?
    let onMenu: () -> Void

    @State private var path: [PCRoute] = []
    @State private var model = DiscoverViewModel.shared
    @Environment(QueuePlaybackCoordinator.self) private var coordinator

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                PCTopBar(title: "Parachord", leading: .menu, onLeading: onMenu)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if let t = coordinator.currentTrack {
                            sectionHeader("Continue Listening")
                            continueCard(t)
                        }

                        sectionHeader("Discover")
                        discoverGrid

                        if model.isLoading && !model.loaded {
                            weeklySkeleton("Weekly Jams")
                            weeklySkeleton("Weekly Exploration")
                        } else {
                            weeklySection("Weekly Jams", model.jams)
                            weeklySection("Weekly Exploration", model.exploration)
                        }
                    }
                    .padding(.bottom, 130) // clear the floating tab bar
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: PCRoute.self) { pcRouteDestination($0) }
        }
        .onChange(of: pendingRoute, initial: true) { _, route in
            if let route { path = [route]; pendingRoute = nil }
        }
        .task { model.start(); await model.load() }
    }

    // MARK: Sections

    private func sectionHeader(_ text: String) -> some View {
        Text(text).pcSectionHeader()
            .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 8)
    }

    private func continueCard(_ t: Track) -> some View {
        Button { coordinator.togglePlayPause() } label: {
            HStack(spacing: 14) {
                pcCover(pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album),
                        seed: t.title, size: 56, radius: 8)
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
        .init(title: "Critical Darlings", icon: "heart.fill", c1: 0xea580c, c2: 0xf59e0b, subtitle: "Staff picks", preset: "critical"),
        .init(title: "Pop of the Tops", icon: "chart.line.uptrend.xyaxis", c1: 0xec4899, c2: 0xf59e0b, subtitle: "Top charts", preset: "pop"),
        .init(title: "Fresh Drops", icon: "drop.fill", c1: 0x10b981, c2: 0x0d9488, subtitle: "New releases", preset: "fresh"),
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
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 110, alignment: .topLeading)
        .background(
            LinearGradient(colors: [Color(uiColor: UIColor(hex: tile.c1)), Color(uiColor: UIColor(hex: tile.c2))],
                           startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(color: .black.opacity(0.10), radius: 9, y: 6)
    }

    /// Placeholder carousel shown while the first Weekly load is in flight —
    /// matches the 2x2-mosaic card shape so it doesn't jump on arrival.
    private func weeklySkeleton(_ title: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Text(title).pcSectionHeader()
                Spacer()
            }
            .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 8)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(0..<4, id: \.self) { _ in
                        VStack(alignment: .leading, spacing: 8) {
                            PCSkeletonBox(width: 150, height: 150, radius: 10)
                            PCSkeletonBox(width: 120, height: 13, radius: 4)
                            PCSkeletonBox(width: 80, height: 11, radius: 4)
                        }
                    }
                }
                .padding(.horizontal, 20)
            }
        }
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
                        NavigationLink(value: PCRoute.playlist(id: entry.id, title: "\(entry.weekLabel) · \(entry.kind)")) {
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
}
