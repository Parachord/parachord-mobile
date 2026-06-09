import SwiftUI
import Shared

// MARK: - Critical Darlings + Fresh Drops (Android-parity layout)
//
// Match the Android screens (gradient header + list + Critical's critic blurb /
// Fresh's filter chips + release badge), not the design's grid — per the
// "Android wins on conflict" rule. Each row → AlbumScreen.

/// Gradient header for the curated Discover screens (mirrors Android's
/// HeaderGradient). The back button sits in a nav row ABOVE the title (so they
/// never collide); the gradient background bleeds up behind the status bar
/// while the content respects the safe area.
struct PCCuratedHeader: View {
    let title: String
    let subtitle: String
    let count: String?
    let gradient: [UInt32]
    let onBack: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(.white).frame(width: 36, height: 36)
                        .background(.white.opacity(0.18), in: Circle())
                }
                Spacer(minLength: 0)
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.system(size: 28, weight: .bold)).foregroundStyle(.white)
                Text(subtitle).font(.system(size: 14)).foregroundStyle(.white.opacity(0.85))
                if let count { Text(count).font(.system(size: 13, weight: .medium)).foregroundStyle(.white.opacity(0.7)) }
            }
            .padding(.top, 14)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 22)
        .background(
            LinearGradient(
                colors: gradient.map { Color(uiColor: UIColor(hex: $0)) },
                startPoint: .leading, endPoint: .trailing)
            .ignoresSafeArea(edges: .top) // gradient bleeds behind the status bar
        )
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
        ScrollView {
            LazyVStack(spacing: 0) {
                PCCuratedHeader(
                    title: "Critical Darlings",
                    subtitle: "Top-rated albums from leading music publications",
                    count: model.albums.isEmpty ? nil : "\(model.albums.count) albums",
                    gradient: [0xF59E0B, 0xF97316], onBack: { dismiss() })
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
                    Text(album.blurb).font(.system(size: 12)).foregroundStyle(PC.fg3)
                        .lineLimit(3).padding(.top, 2)
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
        ScrollView {
            LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                PCCuratedHeader(
                    title: "Fresh Drops",
                    subtitle: "New releases from artists you listen to",
                    count: model.drops.isEmpty ? nil : "\(model.drops.count) releases",
                    gradient: [0x10B981, 0x14B8A6, 0x06B6D4], onBack: { dismiss() })

                Section {
                    if model.isLoading && !model.loaded {
                        ProgressView().frame(maxWidth: .infinity).padding(.vertical, 40)
                    }
                    ForEach(Array(filtered.enumerated()), id: \.offset) { _, drop in
                        NavigationLink { AlbumScreen(title: drop.title, artist: drop.artist) } label: {
                            row(drop)
                        }
                        .buttonStyle(.plain)
                        Divider().padding(.leading, 84)
                    }
                } header: {
                    filterBar
                }
            }
            .padding(.bottom, 130)
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
            pcCover(drop.albumArt, seed: drop.title + drop.artist, size: 56, radius: 8)
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
