import SwiftUI
import Shared

// MARK: - Collection (saved Artists / Albums / Songs / Friends) — #195
//
// Mirrors Android's LibraryScreen / LibraryViewModel + the shared
// LibraryRepository's NON-SYNC slice: four tabs, per-tab sort, a shared
// free-text filter, and a header Sync action. Reads come from the DAO-backed
// facade on IosContainer (watchCollection{Tracks,Albums,Artists,Friends}); the
// model subscribes ONCE for the app lifetime (CriticalDarlingsModel pattern).
//
// Known gaps: friend-detail navigation has no iOS screen yet (#196) — rows show
// activity + offer remove but don't push. The header Sync refreshes friend
// activity for now; multi-provider library sync is #194. Remove is LOCAL-only
// until #194 wires SyncEngine.onTrackRemoved into the facade.
// See docs/plans/2026-06-17-ios-collection-195.md.

// ── Sort options (mirror Android CollectionSort.kt) ───────────────────
enum CollectionTrackSort: CaseIterable {
    case titleAsc, titleDesc, artist, album, duration, recent
    var label: String {
        switch self {
        case .titleAsc: return "Title A–Z"
        case .titleDesc: return "Title Z–A"
        case .artist: return "Artist Name"
        case .album: return "Album Name"
        case .duration: return "Duration"
        case .recent: return "Recently Added"
        }
    }
}

enum CollectionAlbumSort: CaseIterable {
    case alphaAsc, alphaDesc, artist, recent
    var label: String {
        switch self {
        case .alphaAsc: return "A–Z"
        case .alphaDesc: return "Z–A"
        case .artist: return "Artist Name"
        case .recent: return "Recently Added"
        }
    }
}

enum CollectionArtistSort: CaseIterable {
    case alphaAsc, alphaDesc, recent
    var label: String {
        switch self {
        case .alphaAsc: return "A–Z"
        case .alphaDesc: return "Z–A"
        case .recent: return "Recently Added"
        }
    }
}

enum CollectionFriendSort: CaseIterable {
    case alphaAsc, alphaDesc, recent, active, onAir
    var label: String {
        switch self {
        case .alphaAsc: return "A–Z"
        case .alphaDesc: return "Z–A"
        case .recent: return "Recently Added"
        case .active: return "Recently Active"
        case .onAir: return "On Air Now"
        }
    }
}

@MainActor @Observable
final class CollectionModel {
    // App-lifetime singleton; subscribes ONCE to the facade Flows (the
    // CriticalDarlingsModel pattern). The DAO Flows are reactive, so add/remove
    // anywhere in the app re-emits here and the UI updates with no manual reload.
    static let shared = CollectionModel()
    private let container = IosContainer.companion.shared

    var tracks: [Track] = []
    var albums: [Album] = []
    var artists: [Artist] = []
    var friends: [Friend] = []

    // Per-tab sort state. In-memory on the singleton (survives screen
    // recreation), mirroring Android's per-tab sort defaults.
    var trackSort: CollectionTrackSort = .recent
    var albumSort: CollectionAlbumSort = .recent
    var artistSort: CollectionArtistSort = .alphaAsc
    var friendSort: CollectionFriendSort = .alphaAsc

    // Free-text filter shared across tabs (mirrors LibraryViewModel._searchQuery).
    var searchQuery: String = ""

    private var trackSub: Cancellable?
    private var albumSub: Cancellable?
    private var artistSub: Cancellable?
    private var friendSub: Cancellable?
    private var started = false

    func start() {
        guard !started else { return }
        started = true
        trackSub = container.watchCollectionTracks { [weak self] list in self?.tracks = list }
        albumSub = container.watchCollectionAlbums { [weak self] list in self?.albums = list }
        artistSub = container.watchCollectionArtists { [weak self] list in self?.artists = list }
        friendSub = container.watchCollectionFriends { [weak self] list in self?.friends = list }
    }

    /// Refresh each friend's cached now-playing (fire-and-forget in the repo).
    func refreshFriends() { container.refreshFriendsActivity() }
    /// Import friends from LB/Last.fm into the local DB (mirrors Android's
    /// FriendsViewModel.init), then the reactive flow shows them.
    func syncFriends() { container.syncFriends() }

    // Each `sorted*` view filters by `searchQuery` first, then sorts — same
    // fields Android filters on (LibraryViewModel.sorted{Tracks,Albums,Artists}
    // + FriendsTab): track→title/artist/album, album→title/artist, artist→name,
    // friend→displayName. Case-insensitive `contains`.
    private func matches(_ haystacks: String?...) -> Bool {
        let q = searchQuery.trimmingCharacters(in: .whitespaces)
        if q.isEmpty { return true }
        return haystacks.contains { $0?.range(of: q, options: .caseInsensitive) != nil }
    }

    // ── Sorted + filtered views ────────────────────────────────────────
    var sortedTracks: [Track] {
        let f = tracks.filter { matches($0.title, $0.artist, $0.album) }
        switch trackSort {
        case .titleAsc:  return f.sorted { $0.title.lowercased() < $1.title.lowercased() }
        case .titleDesc: return f.sorted { $0.title.lowercased() > $1.title.lowercased() }
        case .artist:    return f.sorted { $0.artist.lowercased() < $1.artist.lowercased() }
        case .album:     return f.sorted { ($0.album ?? "").lowercased() < ($1.album ?? "").lowercased() }
        case .duration:  return f.sorted { ($0.duration?.int64Value ?? 0) > ($1.duration?.int64Value ?? 0) }
        case .recent:    return f.sorted { $0.addedAt > $1.addedAt }
        }
    }

    var sortedAlbums: [Album] {
        let f = albums.filter { matches($0.title, $0.artist) }
        switch albumSort {
        case .alphaAsc:  return f.sorted { $0.title.lowercased() < $1.title.lowercased() }
        case .alphaDesc: return f.sorted { $0.title.lowercased() > $1.title.lowercased() }
        case .artist:    return f.sorted { $0.artist.lowercased() < $1.artist.lowercased() }
        case .recent:    return f.sorted { $0.addedAt > $1.addedAt }
        }
    }

    var sortedArtists: [Artist] {
        let f = artists.filter { matches($0.name) }
        switch artistSort {
        case .alphaAsc:  return f.sorted { $0.name.lowercased() < $1.name.lowercased() }
        case .alphaDesc: return f.sorted { $0.name.lowercased() > $1.name.lowercased() }
        case .recent:    return f.sorted { $0.addedAt > $1.addedAt }
        }
    }

    var sortedFriends: [Friend] {
        let f = friends.filter { matches($0.displayName) }
        switch friendSort {
        case .alphaAsc:  return f.sorted { $0.displayName.lowercased() < $1.displayName.lowercased() }
        case .alphaDesc: return f.sorted { $0.displayName.lowercased() > $1.displayName.lowercased() }
        case .recent:    return f.sorted { $0.addedAt > $1.addedAt }
        case .active:    // played within the last 14 days, most-recent first
            let cutoff = Int64(Date().timeIntervalSince1970) - 14 * 86400
            return f.filter { $0.cachedTrackTimestamp > cutoff }
                .sorted { $0.cachedTrackTimestamp > $1.cachedTrackTimestamp }
        case .onAir:     return f.filter { $0.isOnAir }.sorted { $0.cachedTrackTimestamp > $1.cachedTrackTimestamp }
        }
    }

    // ── Mutations — LOCAL only (sync propagation lands with #194). ──────
    func removeTrack(_ t: Track)   { Task { try? await container.removeTrackFromCollection(track: t) } }
    func removeAlbum(_ a: Album)   { Task { try? await container.removeAlbumFromCollection(album: a) } }
    func removeArtist(_ a: Artist) { Task { try? await container.removeArtistFromCollection(artist: a) } }
    func removeFriend(_ f: Friend) { Task { try? await container.removeFriend(friendId: f.id) } }
    func pinFriend(_ f: Friend, _ pinned: Bool) { Task { try? await container.pinFriend(friendId: f.id, pinned: pinned) } }
}

// Tab order mirrors Android LibraryScreen + the iOS design (Artists, Albums,
// Songs, Friends) — not the Songs-first order from the original M3 sketch.
// Internal (not private) so the shell can request a sub-tab when navigating from
// the queue-source "Playing from: Collection" banner (#209).
enum CollectionTab: Int, CaseIterable { case artists = 0, albums = 1, songs = 2, friends = 3 }

struct CollectionView: View {
    let onMenu: () -> Void
    /// One-shot sub-tab the shell injects when navigating from the queue-source
    /// banner (#209) — e.g. "Playing from: Collection" lands on Songs. Consumed
    /// once on appear, then cleared.
    @Binding var pendingTab: CollectionTab?

    @State private var model = CollectionModel.shared
    @State private var tab: CollectionTab = .artists
    @State private var path: [PCRoute] = []
    @State private var searchExpanded = false
    @FocusState private var searchFocused: Bool
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    // Non-private so the synthesized memberwise init (which carries `onMenu`)
    // stays accessible from RootView. Observed for resolver-badge re-renders.
    var resolverCache = IosTrackResolverCache.shared

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                // Trailing Sync button mirrors Android's Collection header action.
                // Multi-provider library sync is #194 (not wired on iOS yet), so
                // for now it refreshes the only live/remote collection data —
                // friend now-playing. Becomes the real sync trigger with #194.
                PCTopBar(title: "Collection", leading: .menu, onLeading: onMenu,
                         trailingIcon: "arrow.triangle.2.circlepath",
                         onTrailing: { refresh() })
                PCTabs(tabs: tabLabels, selection: Binding(
                    get: { tab.rawValue },
                    set: { tab = CollectionTab(rawValue: $0) ?? .artists }))
                sortBar

                ScrollView {
                    switch tab {
                    case .artists: artistsGrid
                    case .albums:  albumsGrid
                    case .songs:   songsList
                    case .friends: friendsList
                    }
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: PCRoute.self) { pcRouteDestination($0) }
        }
        .task { model.start() }
        // Refresh friend now-playing whenever the Friends tab is shown (mirrors
        // Android's activity refresh). Cheap + gated by the repo's rate guards.
        .onChange(of: tab) { _, t in if t == .friends { model.syncFriends(); model.refreshFriends() } }
        // Consume a shell-requested sub-tab (#209 queue-source banner → Songs).
        .onChange(of: pendingTab, initial: true) { _, t in
            if let t { tab = t; pendingTab = nil }
        }
    }

    private var tabLabels: [String] {
        ["Artists (\(model.artists.count))",
         "Albums (\(model.albums.count))",
         "Songs (\(model.tracks.count))",
         "Friends (\(model.friends.count))"]
    }

    // ── Sort / filter bar — cycling sort on the left, expanding search on the
    // right (mirrors Android's CollectionFilterBar). ──────────────────────
    private var sortBar: some View {
        HStack(spacing: 8) {
            Menu {
                sortMenuItems
            } label: {
                HStack(spacing: 4) {
                    Text(currentSortLabel).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1)
                    Image(systemName: "chevron.down").font(.system(size: 12, weight: .semibold)).foregroundStyle(PC.fg2)
                }
            }
            .buttonStyle(.plain)
            Spacer(minLength: 0)

            if searchExpanded {
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").font(.system(size: 13)).foregroundStyle(PC.fg3)
                    TextField("Filter…", text: $model.searchQuery)
                        .font(.system(size: 14)).foregroundStyle(PC.fg1)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .focused($searchFocused).submitLabel(.search)
                    Button {
                        model.searchQuery = ""
                        withAnimation(.easeOut(duration: 0.18)) { searchExpanded = false }
                    } label: {
                        Image(systemName: "xmark.circle.fill").font(.system(size: 15)).foregroundStyle(PC.fg3)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 12).padding(.vertical, 7)
                .frame(width: 200)
                .background(PC.bgInset, in: Capsule())
                .overlay(Capsule().strokeBorder(PC.accent.opacity(0.5)))
                .transition(.move(edge: .trailing).combined(with: .opacity))
            } else {
                Button {
                    withAnimation(.easeOut(duration: 0.18)) { searchExpanded = true }
                    searchFocused = true
                } label: {
                    Image(systemName: "magnifyingglass").font(.system(size: 17)).foregroundStyle(PC.fg2)
                        .frame(width: 34, height: 34)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
    }

    // Header Sync action — see PCTopBar wiring above. Refreshes friend activity
    // (the only live/remote collection data until multi-provider sync lands, #194).
    private func refresh() { model.refreshFriends() }

    private var currentSortLabel: String {
        switch tab {
        case .songs:   return model.trackSort.label
        case .albums:  return model.albumSort.label
        case .artists: return model.artistSort.label
        case .friends: return model.friendSort.label
        }
    }

    /// Sort options for the active tab — a real dropdown (was a tap-to-cycle
    /// button that confused the chevron-down affordance). Checkmarks the active one.
    @ViewBuilder private var sortMenuItems: some View {
        switch tab {
        case .songs:
            ForEach(CollectionTrackSort.allCases, id: \.self) { s in
                Button { model.trackSort = s } label: { sortItemLabel(s.label, s == model.trackSort) }
            }
        case .albums:
            ForEach(CollectionAlbumSort.allCases, id: \.self) { s in
                Button { model.albumSort = s } label: { sortItemLabel(s.label, s == model.albumSort) }
            }
        case .artists:
            ForEach(CollectionArtistSort.allCases, id: \.self) { s in
                Button { model.artistSort = s } label: { sortItemLabel(s.label, s == model.artistSort) }
            }
        case .friends:
            ForEach(CollectionFriendSort.allCases, id: \.self) { s in
                Button { model.friendSort = s } label: { sortItemLabel(s.label, s == model.friendSort) }
            }
        }
    }

    @ViewBuilder private func sortItemLabel(_ text: String, _ selected: Bool) -> some View {
        if selected { Label(text, systemImage: "checkmark") } else { Text(text) }
    }

    // ── Songs (list + resolver badges + context menu) ──────────────────
    private var songsList: some View {
        let items = model.sortedTracks
        return LazyVStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { i, t in
                Button { coordinator.setQueue(items, startIndex: i,
                                              context: PlaybackContext(type: "collection", name: "Collection", id: nil)) } label: {
                    HStack(spacing: 12) {
                        pcCover(pcTrackArt(t.artworkUrl, artist: t.artist, title: t.title, album: t.album),
                                seed: t.title + t.artist, size: 44, radius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(coordinator.currentTrack?.id == t.id ? PC.accent : PC.fg1).lineLimit(1)
                            Text(t.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                        }
                        Spacer(minLength: 8)
                        if let s = resolverCache.cached(artist: t.artist, title: t.title, album: t.album), !s.isEmpty {
                            ResolverBadgeRow(sources: s)
                        }
                    }
                    .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .onAppear { resolverCache.resolve(ResolveRequest(artist: t.artist, title: t.title, album: t.album), order: i) }
                .pcTrackContextMenu(t, coordinator: coordinator,
                    onGoToArtist: { path.append(.artist(t.artist)) },
                    onGoToAlbum: t.album.map { a in { path.append(.album(title: a, artist: t.artist)) } })
            }
            if items.isEmpty { emptyState(icon: "music.note", text: "No saved songs yet.") }
        }
        .padding(.vertical, 4).padding(.bottom, 130)
    }

    // ── Albums (2-col grid) ────────────────────────────────────────────
    private var albumsGrid: some View {
        let items = model.sortedAlbums
        return LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 16) {
            ForEach(items, id: \.id) { a in
                Button { path.append(.album(title: a.title, artist: a.artist)) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        pcCover(a.artworkUrl, seed: a.title + a.artist, size: nil, radius: 10)
                            .aspectRatio(1, contentMode: .fit).shadow(color: .black.opacity(0.12), radius: 6, y: 3)
                        Text(a.title).font(.system(size: 14, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1).padding(.horizontal, 2)
                        Text(albumSubtitle(a)).font(.system(size: 12)).foregroundStyle(PC.fg2).lineLimit(1).padding(.horizontal, 2)
                    }
                }
                .buttonStyle(.plain)
                .pcAlbumContextMenu(title: a.title, artist: a.artist, artworkUrl: a.artworkUrl,
                    coordinator: coordinator,
                    onGoToAlbum: { path.append(.album(title: a.title, artist: a.artist)) },
                    onGoToArtist: { path.append(.artist(a.artist)) })
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8).padding(.bottom, 130)
        .overlay { if items.isEmpty { emptyState(icon: "square.stack", text: "No saved albums yet.").padding(.top, 60) } }
    }

    private func albumSubtitle(_ a: Album) -> String {
        if let n = a.trackCount?.intValue {
            return "\(a.artist) · \(n) \(n == 1 ? "track" : "tracks")"
        }
        return a.artist
    }

    // ── Artists (3-col grid, circular) ─────────────────────────────────
    private var artistsGrid: some View {
        let items = model.sortedArtists
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 18) {
            ForEach(items, id: \.id) { a in
                Button { path.append(.artist(a.name)) } label: {
                    VStack(spacing: 6) {
                        Color.clear.aspectRatio(1, contentMode: .fit)
                            .overlay { PCArtistImage(url: a.imageUrl.flatMap { URL(string: $0) }) { PCArtwork(name: a.name, size: nil, radius: 999) } }
                            .clipShape(Circle())
                        Text(a.name).font(.system(size: 12, weight: .medium)).foregroundStyle(PC.fg1)
                            .lineLimit(1).multilineTextAlignment(.center)
                    }
                }
                .buttonStyle(.plain)
                .pcArtistContextMenu(name: a.name, imageUrl: a.imageUrl, coordinator: coordinator,
                    onGoToArtist: { path.append(.artist(a.name)) })
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 12).padding(.bottom, 130)
        .overlay { if items.isEmpty { emptyState(icon: "person.crop.circle", text: "No saved artists yet.").padding(.top, 60) } }
    }

    // ── Friends (list) ─────────────────────────────────────────────────
    private var friendsList: some View {
        let items = model.sortedFriends
        return LazyVStack(spacing: 0) {
            ForEach(items, id: \.id) { f in
                NavigationLink(value: PCRoute.friend(id: f.id, username: f.username, service: f.service, name: f.displayName)) {
                    friendRow(f)
                }
                .buttonStyle(.plain)
                    .contextMenu {
                        // Listen along only while they're on air (Android parity);
                        // keep it available to STOP if already listening.
                        if f.isOnAir || ListenAlongController.shared.isActive(f) {
                            Button { ListenAlongController.shared.toggle(f) } label: {
                                Label(ListenAlongController.shared.isActive(f) ? "Stop Listening Along" : "Listen Along",
                                      systemImage: "headphones")
                            }
                        }
                        if f.pinnedToSidebar {
                            Button { model.pinFriend(f, false) } label: { Label("Unpin from Sidebar", systemImage: "pin.slash") }
                        } else {
                            Button { model.pinFriend(f, true) } label: { Label("Pin to Sidebar", systemImage: "pin") }
                        }
                        Button(role: .destructive) { model.removeFriend(f) } label: {
                            Label("Remove Friend", systemImage: "person.badge.minus")
                        }
                    }
            }
            if items.isEmpty { emptyState(icon: "person.2", text: "No friends yet.\nAdd friends in Settings.") }
        }
        .padding(.vertical, 4).padding(.bottom, 130)
    }

    @ViewBuilder private func friendRow(_ f: Friend) -> some View {
        HStack(alignment: .top, spacing: 12) {
            // Circular avatar + on-air green dot (design's ios-drawer__avatar).
            ZStack(alignment: .bottomTrailing) {
                Color.clear.frame(width: 46, height: 46)
                    .overlay { PCArtistImage(url: f.avatarUrl.flatMap { URL(string: $0) }) { PCArtwork(name: f.displayName, size: nil, radius: 999) } }
                    .clipShape(Circle())
                if f.isOnAir {
                    Circle().fill(Color(uiColor: UIColor(hex: 0x10B981)))
                        .frame(width: 12, height: 12)
                        .overlay(Circle().strokeBorder(PC.bgPrimary, lineWidth: 2))
                }
            }
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(f.displayName).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                    Text(serviceBadge(f.service).0).font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(serviceBadge(f.service).1)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(serviceBadge(f.service).1.opacity(0.18), in: RoundedRectangle(cornerRadius: 4))
                }
                if f.isOnAir, let track = f.cachedTrackName {
                    Text("♫ \(track)\(f.cachedTrackArtist.map { " · \($0)" } ?? "")")
                        .font(.system(size: 13)).foregroundStyle(Color(uiColor: UIColor(hex: 0x10B981))).lineLimit(1)
                } else if let track = f.cachedTrackName {
                    // Offline: last track + when they were last active (Android parity).
                    let ago = f.cachedTrackTimestamp > 0 ? " · \(pcTimeAgo(f.cachedTrackTimestamp))" : ""
                    Text("\(track)\(f.cachedTrackArtist.map { " · \($0)" } ?? "")\(ago)")
                        .font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                } else {
                    Text("Offline").font(.system(size: 13)).foregroundStyle(PC.fg3)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
    }

    /// Relative "last active" label — port of Android FriendsScreen.formatTimeAgo.
    /// `timestampSeconds` is seconds-since-epoch (Friend.cachedTrackTimestamp).
    private func pcTimeAgo(_ timestampSeconds: Int64) -> String {
        let diff = Int64(Date().timeIntervalSince1970) - timestampSeconds
        switch diff {
        case ..<60:     return "Just now"
        case ..<3600:   return "\(diff / 60)m ago"
        case ..<86400:  return "\(diff / 3600)h ago"
        case ..<172800: return "Yesterday"
        case ..<604800: return "\(diff / 86400)d ago"
        default:        return "\(diff / 604800)w ago"
        }
    }

    private func serviceBadge(_ service: String) -> (String, Color) {
        switch service {
        case "lastfm":       return ("Last.fm", Color(uiColor: UIColor(hex: 0xD51007)))
        case "listenbrainz": return ("ListenBrainz", Color(uiColor: UIColor(hex: 0xEB743B)))
        default:             return (service, PC.fg3)
        }
    }

    // ── Empty state ────────────────────────────────────────────────────
    private func emptyState(icon: String, text: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: icon).font(.system(size: 38)).foregroundStyle(PC.fg3)
            Text(text).font(.system(size: 14)).foregroundStyle(PC.fg2)
                .multilineTextAlignment(.center).padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity).padding(.top, 60)
    }
}
