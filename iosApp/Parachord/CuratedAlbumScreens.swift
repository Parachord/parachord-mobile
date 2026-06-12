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

// MARK: - Loading skeletons (shimmer)
//
// Mirrors Android's shimmerBrush (a ~1.3s linear highlight sweep over a muted
// base). PCSkeletonBox is the primitive; PCSkeletonRow/Grid are the templates
// screens drop into their loading states.

struct PCSkeletonBox: View {
    var width: CGFloat? = nil
    var height: CGFloat? = nil
    var radius: CGFloat = 6
    @State private var x: CGFloat = -1

    var body: some View {
        RoundedRectangle(cornerRadius: radius, style: .continuous)
            .fill(PC.bgInset)
            .frame(width: width, height: height)
            .frame(maxWidth: width == nil ? .infinity : nil)
            .overlay(
                GeometryReader { g in
                    LinearGradient(colors: [.clear, .white.opacity(0.32), .clear],
                                   startPoint: .leading, endPoint: .trailing)
                        .frame(width: g.size.width * 0.6)
                        .offset(x: x * g.size.width)
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .onAppear { withAnimation(.linear(duration: 1.3).repeatForever(autoreverses: false)) { x = 1.5 } }
    }
}

/// A track/album-row skeleton: art square + two text lines.
struct PCSkeletonRow: View {
    var art: CGFloat = 56
    var body: some View {
        HStack(spacing: 12) {
            PCSkeletonBox(width: art, height: art, radius: 8)
            VStack(alignment: .leading, spacing: 8) {
                PCSkeletonBox(width: 170, height: 13, radius: 4)
                PCSkeletonBox(width: 110, height: 11, radius: 4)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20).padding(.vertical, 10)
    }
}

struct PCSkeletonList: View {
    var count = 7
    var art: CGFloat = 56
    var body: some View {
        VStack(spacing: 0) { ForEach(0..<count, id: \.self) { _ in PCSkeletonRow(art: art) } }
    }
}

/// A grid of square-card skeletons (artwork + a title line) for album/artist grids.
struct PCSkeletonGrid: View {
    var count = 6
    var columns = 2
    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 14), count: columns), spacing: 18) {
            ForEach(0..<count, id: \.self) { _ in
                VStack(alignment: .leading, spacing: 8) {
                    PCSkeletonBox(radius: 10).aspectRatio(1, contentMode: .fit)
                    PCSkeletonBox(width: 90, height: 12, radius: 4)
                }
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
    }
}

/// Shared tab strip — mirrors Android's SwipeableTabLayout: UPPERCASE, light,
/// letter-spaced labels with a purple underline indicator under the active tab,
/// no segmented-control chrome. Used by Pop / Recommendations / Artist.
struct PCTabs: View {
    let tabs: [String]
    @Binding var selection: Int

    var body: some View {
        // Horizontally scrollable (mirrors Android's ScrollableTabRow) so 4+
        // tabs don't overflow the screen width.
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 28) {
                ForEach(tabs.indices, id: \.self) { i in
                    Button { withAnimation(.easeOut(duration: 0.2)) { selection = i } } label: {
                        VStack(spacing: 0) {
                            Text(tabs[i].uppercased())
                                .font(.system(size: 12, weight: .light)).tracking(1.3)
                                .foregroundStyle(selection == i ? PC.fg1 : PC.fg3)
                                .padding(.vertical, 14)   // ~48dp tab height (Android parity)
                            Rectangle().fill(selection == i ? PC.accent : .clear).frame(height: 2)
                        }
                        .fixedSize()
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 18)
        }
        .overlay(Rectangle().fill(PC.border).frame(height: 1), alignment: .bottom)
    }
}

/// Gradient metadata banner that sits BELOW the plain PCTopBar on the curated
/// screens (mirrors Android's CriticsHeader / FreshDropsHeader). Carries the
/// subtitle + count only — the title lives in the top bar now.
struct PCCuratedBanner: View {
    var icon: String? = nil   // SF Symbol, mirrors Android's banner icon (32dp white); Pop has none
    let subtitle: String
    let count: String?
    let gradient: [UInt32]

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            if let icon {
                Image(systemName: icon).font(.system(size: 26))
                    .foregroundStyle(.white.opacity(0.9))
            }
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
    // Singleton so data survives screen recreation (instant, no reload flicker);
    // TTL-gated revalidate so it still refreshes — just not every open. The
    // `isLoading && !loaded` skeleton gate in the view means the skeleton only
    // shows on the FIRST load; TTL revalidations keep showing the stale data.
    static let shared = CriticalDarlingsModel()
    private let container = IosContainer.companion.shared
    var albums: [CriticsPickAlbum] = []
    var isLoading = false
    var loaded = false
    private var lastLoad: Date?
    private let ttl: TimeInterval = 4 * 3600   // mirrors desktop Critical Darlings TTL
    func load() async {
        if loaded, let l = lastLoad, Date().timeIntervalSince(l) < ttl { return }
        isLoading = true
        let fresh = (try? await container.loadCriticalDarlings()) ?? []
        if !fresh.isEmpty { albums = fresh }   // keep cache on a failed/empty refresh
        lastLoad = Date(); isLoading = false; loaded = true
    }
}

struct CriticalDarlingsScreen: View {
    @State private var model = CriticalDarlingsModel.shared
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
                        PCSkeletonList(count: 6, art: 80)
                    }
                    ForEach(Array(model.albums.enumerated()), id: \.element.id) { _, album in
                        NavigationLink(value: PCRoute.album(title: album.title, artist: album.artist)) {
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
    static let shared = FreshDropsModel()
    private let container = IosContainer.companion.shared
    var drops: [FreshDrop] = []
    var isLoading = false
    var loaded = false
    private var lastLoad: Date?
    private let ttl: TimeInterval = 6 * 3600   // mirrors desktop Fresh Drops 6h TTL
    func load() async {
        if loaded, let l = lastLoad, Date().timeIntervalSince(l) < ttl { return }
        isLoading = true
        let fresh = (try? await container.loadFreshDrops()) ?? []
        if !fresh.isEmpty { drops = fresh }
        lastLoad = Date(); isLoading = false; loaded = true
    }
}

private let freshFilters: [(key: String, label: String)] =
    [("all", "All"), ("album", "Albums"), ("ep", "EPs"), ("single", "Singles")]

struct FreshDropsScreen: View {
    @State private var model = FreshDropsModel.shared
    @State private var filter = "all"
    @State private var search = ""
    @State private var searchOpen = false
    @Environment(\.dismiss) private var dismiss

    private var filtered: [FreshDrop] {
        let q = search.lowercased()
        return model.drops.filter { d in
            (filter == "all" || d.releaseType.lowercased() == filter) &&
            (q.isEmpty || d.title.lowercased().contains(q) || d.artist.lowercased().contains(q))
        }
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
                            PCSkeletonList(count: 6, art: 80)
                        }
                        ForEach(Array(filtered.enumerated()), id: \.offset) { _, drop in
                            NavigationLink(value: PCRoute.album(title: drop.title, artist: drop.artist)) {
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
        VStack(spacing: 0) {
            HStack(spacing: 8) {
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
                    .padding(.leading, 20)
                }
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { searchOpen.toggle() }
                    if !searchOpen { search = "" }
                } label: {
                    Image(systemName: "magnifyingglass").font(.system(size: 17, weight: .regular))
                        .foregroundStyle(searchOpen ? PC.accent : PC.fg1).frame(width: 34, height: 34)
                }
                .buttonStyle(.plain).padding(.trailing, 14)
            }
            .padding(.vertical, 10)

            if searchOpen {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").font(.system(size: 14)).foregroundStyle(PC.fg3)
                    TextField("Search releases or artists…", text: $search)
                        .font(.system(size: 14)).textInputAutocapitalization(.never).autocorrectionDisabled()
                    if !search.isEmpty {
                        Button { search = "" } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(PC.fg3)
                        }.buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(PC.bgInset, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                .padding(.horizontal, 20).padding(.bottom, 10)
            }
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
                    if let d = freshDate(drop.date) {
                        Text(d.text).font(.system(size: 12))
                            .foregroundStyle(d.upcoming ? Color(uiColor: UIColor(hex: 0x10B981)) : PC.fg3)
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

/// Fresh Drops date label — mirrors Android's displayDate/isUpcoming:
/// "MMM d, yyyy" for past releases (grey), "Coming MMM d, yyyy" for future
/// (emerald). Input is ISO `yyyy-MM-dd`.
private let freshInFmt: DateFormatter = {
    let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX"); return f
}()
private let freshOutFmt: DateFormatter = {
    let f = DateFormatter(); f.dateFormat = "MMM d, yyyy"; f.locale = Locale(identifier: "en_US"); return f
}()
func freshDate(_ raw: String?) -> (text: String, upcoming: Bool)? {
    guard let raw, !raw.isEmpty else { return nil }
    guard let date = freshInFmt.date(from: String(raw.prefix(10))) else { return (raw, false) }
    let formatted = freshOutFmt.string(from: date)
    let upcoming = date > Date()
    return (upcoming ? "Coming \(formatted)" : formatted, upcoming)
}

// MARK: - Concerts (#10)
//
// Upcoming shows from the user's top recommended artists via the shared
// ConcertsRepository (Ticketmaster + SeatGeek). Needs the user's API keys in
// Settings; empty otherwise. Mirrors Android's ConcertsScreen layout.

@MainActor @Observable
final class ConcertsModel {
    static let shared = ConcertsModel()
    private let container = IosContainer.companion.shared
    var events: [ConcertEvent] = []
    var isLoading = false
    var loaded = false
    private var lastLoad: Date?
    private let ttl: TimeInterval = 2 * 3600
    func load() async {
        if loaded, let l = lastLoad, Date().timeIntervalSince(l) < ttl { return }
        isLoading = true
        let fresh = (try? await container.loadConcerts()) ?? []
        if !fresh.isEmpty { events = fresh }
        lastLoad = Date(); isLoading = false; loaded = true
    }
}

private let concertInFmt: DateFormatter = {
    let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX"); return f
}()
private func concertDate(_ raw: String?) -> Date? {
    guard let raw, raw.count >= 10 else { return nil }
    return concertInFmt.date(from: String(raw.prefix(10)))
}
private func concertFmt(_ raw: String?, _ pattern: String) -> String {
    guard let d = concertDate(raw) else { return "" }
    let f = DateFormatter(); f.dateFormat = pattern; f.locale = Locale(identifier: "en_US"); return f.string(from: d)
}

struct ConcertsScreen: View {
    @State private var model = ConcertsModel.shared
    @Environment(\.dismiss) private var dismiss

    private var grouped: [(month: String, events: [ConcertEvent])] {
        let sorted = model.events.filter { $0.date != nil }.sorted { ($0.date ?? "") < ($1.date ?? "") }
        var groups: [String: [ConcertEvent]] = [:]; var order: [String] = []
        for e in sorted {
            let m = concertFmt(e.date, "MMMM yyyy")
            if groups[m] == nil { order.append(m) }
            groups[m, default: []].append(e)
        }
        return order.map { ($0, groups[$0]!) }
    }

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: "Concerts", leading: .back, onLeading: { dismiss() })
            ScrollView {
                // Banner renders immediately with the title — never gated behind
                // the content load.
                PCCuratedBanner(
                    icon: "ticket.fill",
                    subtitle: "Upcoming shows from artists you listen to",
                    count: model.events.isEmpty ? nil : "\(model.events.count) events",
                    gradient: [0x14B8A6, 0x0891B2])
                if model.isLoading && !model.loaded {
                    PCSkeletonList(count: 6, art: 56)
                } else if model.events.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "ticket").font(.system(size: 40)).foregroundStyle(PC.fg3)
                        Text("No upcoming concerts found.\nAdd Ticketmaster / SeatGeek keys in Settings to see shows.")
                            .font(.system(size: 14)).foregroundStyle(PC.fg2)
                            .multilineTextAlignment(.center).padding(.horizontal, 40)
                    }.padding(.vertical, 60)
                } else {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            ForEach(grouped, id: \.month) { group in
                                Text(group.month).font(.system(size: 13, weight: .semibold)).foregroundStyle(PC.fg2)
                                    .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 6)
                                ForEach(Array(group.events.enumerated()), id: \.offset) { _, e in
                                    eventRow(e)
                                    Divider().padding(.leading, 80)
                                }
                            }
                        }
                        .padding(.bottom, 130)
                    }
                }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task { await model.load() }
    }

    private func eventRow(_ e: ConcertEvent) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 1) {
                Text(concertFmt(e.date, "MMM").uppercased()).font(.system(size: 10, weight: .bold)).tracking(0.5)
                    .foregroundStyle(PC.onTour)
                Text(concertFmt(e.date, "d")).font(.system(size: 20, weight: .bold)).foregroundStyle(PC.fg1)
                Text(concertFmt(e.date, "EEE")).font(.system(size: 10)).foregroundStyle(PC.fg3)
            }
            .frame(width: 44)
            concertImage(e.imageUrl, seed: e.artistName ?? e.name)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(e.artistName ?? e.name).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                    Spacer(minLength: 0)
                    if let url = e.ticketUrl, let u = URL(string: url) {
                        Link(destination: u) {
                            Image(systemName: "arrow.up.right.square").font(.system(size: 16)).foregroundStyle(PC.onTour)
                        }
                    }
                }
                if let v = e.venueName, !v.isEmpty { Text(v).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1) }
                let loc = e.displayLocation ?? [e.city, e.state].compactMap { $0 }.joined(separator: ", ")
                if !loc.isEmpty {
                    HStack(spacing: 6) {
                        Text(loc).font(.system(size: 11)).foregroundStyle(PC.fg3).lineLimit(1)
                        if let t = e.time, !t.isEmpty { Text("· \(t)").font(.system(size: 11)).foregroundStyle(PC.fg3) }
                    }
                }
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 10)
    }

    @ViewBuilder
    private func concertImage(_ url: String?, seed: String) -> some View {
        if let url, let u = URL(string: url) {
            PCCachedImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                placeholder: { PCArtwork(name: seed, size: 56, radius: 8) }
                .frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        } else { PCArtwork(name: seed, size: 56, radius: 8) }
    }
}
