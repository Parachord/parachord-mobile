import SwiftUI
import Shared

// MARK: - Critical Darlings + Fresh Drops (Android-parity layout)
//
// Match the Android screens (gradient header + list + Critical's critic blurb /
// Fresh's filter chips + release badge), not the design's grid — per the
// "Android wins on conflict" rule. Each row → AlbumScreen.

/// Shared page header bar — mirrors Android's TopAppBar: the title in ALL CAPS,
/// a light weight, letter-spaced (0.2em), on the SAME ROW as the leading
/// back/menu icon, with NO color background. Used by every pushed page + Home.
struct PCTopBar: View {
    enum Leading { case back, menu, none }
    let title: String
    var leading: Leading = .back
    var onLeading: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 12) {
            if leading != .none {
                Button { onLeading?() } label: {
                    Image(systemName: leading == .back ? "chevron.left" : "line.3.horizontal")
                        .font(.system(size: 18, weight: .regular)).foregroundStyle(PC.fg1)
                        .frame(width: 34, height: 34)
                }
                .buttonStyle(.plain)
            }
            Text(title.uppercased())
                .font(.system(size: 21, weight: .light)).tracking(3.6)  // titleLarge · Light · 0.2em
                .foregroundStyle(PC.fg1).lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14).padding(.vertical, 6)
        .frame(maxWidth: .infinity)
    }
}

/// Gradient metadata banner that sits BELOW the plain PCTopBar on the curated
/// screens (mirrors Android's CriticsHeader / FreshDropsHeader). Carries the
/// subtitle + count only — the title lives in the top bar now.
struct PCCuratedBanner: View {
    let icon: String          // SF Symbol, mirrors Android's banner icon (32dp white)
    let subtitle: String
    let count: String?
    let gradient: [UInt32]

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: icon).font(.system(size: 26))
                .foregroundStyle(.white.opacity(0.9))
            VStack(alignment: .leading, spacing: 4) {
                Text(subtitle).font(.system(size: 14)).foregroundStyle(.white.opacity(0.92))
                if let count { Text(count).font(.system(size: 13, weight: .medium)).foregroundStyle(.white.opacity(0.78)) }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20).padding(.vertical, 16)
        .background(LinearGradient(
            colors: gradient.map { Color(uiColor: UIColor(hex: $0)) },
            startPoint: .leading, endPoint: .trailing))
    }
}

// MARK: Critical Darlings

@MainActor @Observable
final class CriticalDarlingsModel {
    private let container = IosContainer.companion.shared
    var albums: [CriticsPickAlbum] = []
    var isLoading = false
    var loaded = false
    func load() async {
        guard !loaded else { return }
        isLoading = true
        albums = (try? await container.loadCriticalDarlings()) ?? []
        isLoading = false; loaded = true
    }
}

struct CriticalDarlingsScreen: View {
    @State private var model = CriticalDarlingsModel()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: "Critical Darlings", leading: .back, onLeading: { dismiss() })
            ScrollView {
                LazyVStack(spacing: 0) {
                    PCCuratedBanner(
                        icon: "trophy.fill",
                        subtitle: "Top-rated albums from leading music publications",
                        count: model.albums.isEmpty ? nil : "\(model.albums.count) albums",
                        gradient: [0xF59E0B, 0xF97316, 0xEF4444])
                    if model.isLoading && !model.loaded {
                        ProgressView().frame(maxWidth: .infinity).padding(.vertical, 40)
                    }
                    ForEach(Array(model.albums.enumerated()), id: \.element.id) { _, album in
                        NavigationLink { AlbumScreen(title: album.title, artist: album.artist) } label: {
                            row(album)
                        }
                        .buttonStyle(.plain)
                        Divider().padding(.leading, 104)
                    }
                }
                .padding(.bottom, 130)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task { await model.load() }
    }

    private func row(_ album: CriticsPickAlbum) -> some View {
        HStack(alignment: .top, spacing: 12) {
            pcCover(album.albumArt, seed: album.title + album.artist, size: 80, radius: 8)
            VStack(alignment: .leading, spacing: 3) {
                Text(album.title).font(.system(size: 15, weight: .semibold)).foregroundStyle(PC.fg1).lineLimit(2)
                Text(album.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                if !album.blurb.isEmpty {
                    // Android shows the full synopsis — no maxLines/truncation.
                    Text(album.blurb).font(.system(size: 12)).foregroundStyle(PC.fg3).padding(.top, 2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}

// MARK: Fresh Drops

@MainActor @Observable
final class FreshDropsModel {
    private let container = IosContainer.companion.shared
    var drops: [FreshDrop] = []
    var isLoading = false
    var loaded = false
    func load() async {
        guard !loaded else { return }
        isLoading = true
        drops = (try? await container.loadFreshDrops()) ?? []
        isLoading = false; loaded = true
    }
}

private let freshFilters: [(key: String, label: String)] =
    [("all", "All"), ("album", "Albums"), ("ep", "EPs"), ("single", "Singles")]

struct FreshDropsScreen: View {
    @State private var model = FreshDropsModel()
    @State private var filter = "all"
    @Environment(\.dismiss) private var dismiss

    private var filtered: [FreshDrop] {
        filter == "all" ? model.drops : model.drops.filter { $0.releaseType.lowercased() == filter }
    }

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: "Fresh Drops", leading: .back, onLeading: { dismiss() })
            ScrollView {
                LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                    PCCuratedBanner(
                        icon: "drop.fill",
                        subtitle: "New releases from artists you listen to",
                        count: model.drops.isEmpty ? nil : "\(model.drops.count) releases",
                        gradient: [0x10B981, 0x14B8A6, 0x06B6D4])

                    Section {
                        if model.isLoading && !model.loaded {
                            ProgressView().frame(maxWidth: .infinity).padding(.vertical, 40)
                        }
                        ForEach(Array(filtered.enumerated()), id: \.offset) { _, drop in
                            NavigationLink { AlbumScreen(title: drop.title, artist: drop.artist) } label: {
                                row(drop)
                            }
                            .buttonStyle(.plain)
                            Divider().padding(.leading, 112)
                        }
                    } header: {
                        filterBar
                    }
                }
                .padding(.bottom, 130)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task { await model.load() }
    }

    private var filterBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(freshFilters, id: \.key) { f in
                    let on = filter == f.key
                    Button { filter = f.key } label: {
                        Text(f.label).font(.system(size: 13, weight: .medium))
                            .foregroundStyle(on ? .white : PC.fg1)
                            .padding(.horizontal, 14).padding(.vertical, 6)
                            .background(on ? PC.accent : PC.bgInset, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 10)
        }
        .background(PC.bgPrimary)
    }

    private func row(_ drop: FreshDrop) -> some View {
        HStack(spacing: 12) {
            pcCover(drop.albumArt, seed: drop.title + drop.artist, size: 80, radius: 8)
            VStack(alignment: .leading, spacing: 3) {
                Text(drop.title).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                Text(drop.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                HStack(spacing: 8) {
                    Text(drop.releaseType.uppercased()).font(.system(size: 9, weight: .bold)).tracking(0.5)
                        .foregroundStyle(PC.accent)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(PC.accent.opacity(0.12), in: RoundedRectangle(cornerRadius: 3))
                    if let date = drop.date, !date.isEmpty {
                        Text(date).font(.system(size: 12)).foregroundStyle(PC.fg3)
                    }
                }
                .padding(.top, 1)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20).padding(.vertical, 9)
        .contentShape(Rectangle())
    }
}
