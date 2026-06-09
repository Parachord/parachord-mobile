import SwiftUI
import Shared

// MARK: - Parachord iOS shell (Phase 1)
//
// Custom floating chrome from docs/design/parachord-ios: a liquid-glass
// split-pill tab bar with a center Shuffleupagus FAB, a floating mini-player,
// a slide-in sidebar drawer, and the Add action sheet. Ported from
// chrome.jsx + styles.css. The system TabView is deliberately NOT used —
// the design's bar floats over content with translucency.

// MARK: Tabs

enum PCTab: String, CaseIterable {
    case home, search, collection, playlists
    var title: String { rawValue.capitalized }
    var icon: String {
        switch self {
        case .home: return "house.fill"
        case .search: return "magnifyingglass"
        case .collection: return "square.stack.fill"
        case .playlists: return "music.note.list"
        }
    }
}

// MARK: - Generative artwork placeholder (ArtPlaceholder parity)

struct PCArtwork: View {
    let name: String
    var size: CGFloat? = nil          // nil = fill
    var radius: CGFloat = 8

    // Dark gradient palettes from data.jsx ART_PALETTES.
    private static let palettes: [(UInt32, UInt32)] = [
        (0x1a3a5c, 0x0f2540), (0x2c1f4a, 0x1a1230), (0x2d4a3a, 0x1a2e22),
        (0x5c2a2a, 0x3a1a1a), (0x3a2d4a, 0x251c30), (0x1a3a4a, 0x0e2530),
        (0x4a3a1a, 0x2e2410), (0x3a1a3a, 0x241224), (0x1a4a3a, 0x0e2e22),
        (0x4a1a2a, 0x2e0e1a), (0x1f3a2c, 0x122418), (0x3a2a4a, 0x241a30),
        (0x2c3a5c, 0x1a2440), (0x5c3a2a, 0x40251a), (0x4a3a3a, 0x2e2424),
    ]

    private var hash: Int {
        var h: Int32 = 0
        for b in name.unicodeScalars { h = (h &<< 5) &- h &+ Int32(b.value) }
        return Int(abs(h))
    }
    private var initials: String {
        let parts = name.split(separator: " ").prefix(2).compactMap { $0.first }
        let s = String(parts).uppercased()
        return s.isEmpty ? "P" : s
    }

    var body: some View {
        let pal = Self.palettes[hash % Self.palettes.count]
        let dim = size
        RoundedRectangle(cornerRadius: radius, style: .continuous)
            .fill(LinearGradient(
                colors: [Color(uiColor: UIColor(hex: pal.0)), Color(uiColor: UIColor(hex: pal.1))],
                startPoint: .topLeading, endPoint: .bottomTrailing))
            .overlay(
                Text(initials)
                    .font(.system(size: (dim ?? 80) * 0.34, weight: .bold))
                    .tracking(-0.5)
                    .foregroundStyle(.white.opacity(0.85))
            )
            .frame(width: dim, height: dim)
    }
}

// MARK: - Liquid-glass tab bar + FAB

struct PCTabBar: View {
    @Binding var selected: PCTab
    let onCenter: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 0) {
                ForEach(PCTab.allCases, id: \.self) { tab in
                    Button { selected = tab } label: {
                        VStack(spacing: 2) {
                            Image(systemName: tab.icon).font(.system(size: 21))
                            Text(tab.title).font(.system(size: 10, weight: .medium))
                        }
                        .frame(maxWidth: .infinity)
                        .foregroundStyle(selected == tab ? PC.accent : PC.fg3)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 10)
            .frame(height: 56)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 32, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 32, style: .continuous).strokeBorder(.white.opacity(0.10)))
            .shadow(color: .black.opacity(0.18), radius: 12, y: 6)

            Button(action: onCenter) {
                Image(systemName: "plus")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 56, height: 56)
                    .background(
                        LinearGradient(colors: [Color(uiColor: UIColor(hex: 0xa78bfa)), Color(uiColor: UIColor(hex: 0x7c3aed))],
                                       startPoint: .topLeading, endPoint: .bottomTrailing),
                        in: Circle())
                    .shadow(color: Color(uiColor: UIColor(hex: 0x7c3aed)).opacity(0.45), radius: 12, y: 6)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
    }
}

// MARK: - Floating mini-player

struct PCMiniPlayer: View {
    let title: String
    let artist: String
    let isPlaying: Bool
    var progress: Double = 0
    var artNamespace: Namespace.ID
    var artIsSource: Bool = true
    let onToggle: () -> Void
    let onExpand: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                PCArtwork(name: title, size: 44, radius: 8)
                    .matchedGeometryEffect(id: "npArt", in: artNamespace, isSource: artIsSource)
                    .shadow(color: .black.opacity(0.2), radius: 1, y: 1)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1).lineLimit(1)
                    Text(artist).font(.system(size: 12)).foregroundStyle(PC.fg2).lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: "heart").font(.system(size: 20)).foregroundStyle(PC.error)
                    .frame(width: 40, height: 40)
                Button(action: onToggle) {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 20)).foregroundStyle(PC.fg1)
                        .frame(width: 40, height: 40)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 10).padding(.vertical, 8)

            // progress bar
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Rectangle().fill(.primary.opacity(0.10))
                    Rectangle().fill(PC.accent).frame(width: geo.size.width * max(0, min(1, progress)))
                }
            }
            .frame(height: 2)
        }
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.white.opacity(0.10)))
        .shadow(color: .black.opacity(0.12), radius: 12, y: 6)
        .padding(.horizontal, 12)
        .contentShape(Rectangle())
        .onTapGesture(perform: onExpand)
    }
}

// MARK: - Add action sheet (Shuffleupagus / New Playlist / Import / Add Friend)

struct PCAddSheet: View {
    let onShuffleupagus: () -> Void
    let onDismiss: () -> Void

    private struct Action { let icon: String; let label: String; let sub: String; let go: (() -> Void)? }

    var body: some View {
        let actions: [Action] = [
            .init(icon: "sparkles", label: "Ask Shuffleupagus", sub: "Let the AI DJ build a queue", go: onShuffleupagus),
            .init(icon: "music.note.list", label: "New Playlist", sub: "Start an empty playlist", go: nil),
            .init(icon: "globe", label: "Import Playlist", sub: "Mirror an XSPF / remote feed", go: nil),
            .init(icon: "person.badge.plus", label: "Add Friend", sub: "Follow a ListenBrainz / Last.fm user", go: nil),
        ]
        return NavigationStack {
            List {
                Section("Add to Parachord") {
                    ForEach(actions.indices, id: \.self) { i in
                        let a = actions[i]
                        Button { onDismiss(); a.go?() } label: {
                            HStack(spacing: 16) {
                                Image(systemName: a.icon).font(.system(size: 22)).foregroundStyle(PC.accent).frame(width: 30)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(a.label).font(.system(size: 17)).foregroundStyle(PC.fg1)
                                    Text(a.sub).font(.system(size: 13)).foregroundStyle(PC.fg2)
                                }
                            }.padding(.vertical, 2)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .navigationTitle("Add")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onDismiss) } }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Sidebar drawer

struct PCSidebar: View {
    let onNav: (String) -> Void
    let onClose: () -> Void

    private struct Row: Identifiable { let id: String; let label: String; let icon: String; let color: Color }

    private let yourMusic: [Row] = [
        .init(id: "playlists", label: "Playlists", icon: "music.note.list", color: PC.accent),
        .init(id: "collection", label: "Collection", icon: "square.stack.fill", color: PC.accent),
        .init(id: "history", label: "History", icon: "clock.arrow.circlepath", color: Color(uiColor: UIColor(hex: 0x3b82f6))),
    ]
    private let discover: [Row] = [
        .init(id: "fresh", label: "Fresh Drops", icon: "drop.fill", color: Color(uiColor: UIColor(hex: 0x10b981))),
        .init(id: "recommendations", label: "Recommendations", icon: "star.fill", color: Color(uiColor: UIColor(hex: 0xf59e0b))),
        .init(id: "pop", label: "Pop of the Tops", icon: "chart.line.uptrend.xyaxis", color: Color(uiColor: UIColor(hex: 0xea580c))),
        .init(id: "critical", label: "Critical Darlings", icon: "trophy.fill", color: Color(uiColor: UIColor(hex: 0xef4444))),
        .init(id: "concerts", label: "Concerts", icon: "ticket.fill", color: PC.onTour),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("PARACHORD").pcAppTitle().padding(.horizontal, 24).padding(.bottom, 20)
            group("Your Music", yourMusic)
            group("Discover", discover)
            Spacer(minLength: 0)
            Divider()
            Button { onNav("settings") } label: {
                HStack(spacing: 16) {
                    Image(systemName: "gearshape").font(.system(size: 18)).foregroundStyle(PC.fg2)
                    Text("SETTINGS").font(.system(size: 13, weight: .medium)).tracking(1.8).foregroundStyle(PC.fg2)
                }
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24).padding(.vertical, 14)
        }
        .padding(.top, 64).padding(.bottom, 24)
        .frame(width: 320, alignment: .leading)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(PC.bgPrimary)
        .clipShape(.rect(bottomTrailingRadius: 28, topTrailingRadius: 28))
        .shadow(color: .black.opacity(0.24), radius: 14, x: 6)
    }

    @ViewBuilder private func group(_ header: String, _ rows: [Row]) -> some View {
        Text(header).font(.system(size: 11, weight: .bold)).tracking(1.6).textCase(.uppercase)
            .foregroundStyle(PC.fg3).padding(.horizontal, 24).padding(.top, 14).padding(.bottom, 6)
        ForEach(rows) { r in
            Button { onNav(r.id) } label: {
                HStack(spacing: 16) {
                    Image(systemName: r.icon).font(.system(size: 18)).foregroundStyle(r.color).frame(width: 20)
                    Text(r.label).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1)
                }
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24).padding(.vertical, 11)
        }
    }
}
