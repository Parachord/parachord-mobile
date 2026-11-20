import SwiftUI
import Shared

// MARK: - Recommendations (desktop's recommendations-page.js)
//
// The hero, the source filter, then the shared page's blocks in order. Every
// rule (which rows show, previews, skeletons, the page's state, what a tap
// plays) is shared: `watchRecommendationsPage` hands over `IosRecPage`, already
// laid out by `recommendationPageLayout`, the same layout Android's
// RecommendationsScreen draws. This file only draws it.

@MainActor @Observable
final class RecommendationsModel {
    private let container = IosContainer.companion.shared
    @ObservationIgnored private let watcher = FlowWatcher(scope: IosContainer.companion.shared.appScope)
    @ObservationIgnored private var pageSub: Cancellable?
    @ObservationIgnored private var messageSub: Cancellable?
    @ObservationIgnored private var loading = false

    var page: IosRecPage?

    /// Starts watching the page and the repository's messages (on appear).
    func start(onMessage: @escaping (String) -> Void) {
        if pageSub == nil {
            pageSub = watcher.watch(flow: container.watchRecommendationsPage()) { [weak self] v in
                guard let p = v as? IosRecPage else { return }
                Task { @MainActor in self?.page = p }
            }
        }
        if messageSub == nil {
            messageSub = watcher.watch(flow: container.recommendationsMessages()) { v in
                guard let text = v as? String else { return }
                Task { @MainActor in onMessage(text) }
            }
        }
    }

    /// Stops both watches (on disappear).
    func stop() {
        pageSub?.cancel(); pageSub = nil
        messageSub?.cancel(); messageSub = nil
    }

    /// Loads the page; [force] is Try again or pull to refresh. No load starts
    /// while one is running.
    func load(force: Bool, resetSession: Bool) async {
        if loading { return }
        loading = true
        defer { loading = false }
        try? await container.loadRecommendations(force: force, resetSession: resetSession)
    }
}

private let recGutter = CGFloat(DesignTokens.shared.SCREEN_GUTTER)
private let recCardGap: CGFloat = 12
private let recListenerSkeletons = 6

struct RecommendationsScreen: View {
    @State private var model = RecommendationsModel()
    /// The page's width, for an open row's grid.
    @State private var width: CGFloat = 0
    /// A card's or menu's destination, pushed on the tab's stack.
    @State private var route: PCRoute?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.dismiss) private var dismiss
    @Environment(\.pcOpenPluginSettings) private var openPluginSettings

    private var container: IosContainer { IosContainer.companion.shared }

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: RecommendationCopy.shared.TITLE, leading: .back, onLeading: { dismiss() })
            RecHero(hero: model.page?.hero ?? "")
            if let page = model.page {
                if page.status != "notConfigured" { filter(page) }
                content(page)
            } else {
                RecPageSkeleton()
            }
        }
        .background(GeometryReader { g in
            Color.clear.onAppear { width = g.size.width }.onChange(of: g.size.width) { _, w in width = w }
        })
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(item: $route) { pcRouteDestination($0) }
        .onAppear {
            model.start(onMessage: { say($0) })
            Task { await model.load(force: false, resetSession: true) }
        }
        .onDisappear { model.stop() }
    }

    private var actions: RecActions { RecActions(say: say, open: { route = $0 }) }

    private func say(_ text: String) {
        coordinator.notice = QueuePlaybackCoordinator.Notice(message: text, showInCar: false)
    }

    // ── Filter ────────────────────────────────────────────────────────

    private func filter(_ page: IosRecPage) -> some View {
        let all = PCChip(id: "all", label: RecommendationCopy.shared.ALL)
        let chips = [all] + page.sources.map { s in
            PCChip(id: s.id, label: s.name,
                   color: s.isShuffleupagus ? UInt32(DesignTokens.shared.FILL_ACCENT) : ResolverColor.hex(s.colorId))
        }
        return PCFilterChips(chips: chips, selection: page.selectedSource ?? "all",
                             onSelect: { id in container.setRecommendationsFilter(id: id == "all" ? nil : id) },
                             pageFill: UInt32(DesignTokens.Page.recommendations.fill))
            .padding(.vertical, 8)
    }

    // ── Body by status ────────────────────────────────────────────────

    @ViewBuilder
    private func content(_ page: IosRecPage) -> some View {
        let copy = RecommendationCopy.shared
        switch page.status {
        case "loading":
            RecPageSkeleton()
        case "notConfigured":
            RecPageMessage(title: copy.NOT_CONFIGURED_TITLE, message: copy.NOT_CONFIGURED_BODY,
                           primary: (copy.CONNECT_LISTENBRAINZ, { openPluginSettings("listenbrainz") }),
                           secondary: (copy.CONNECT_LASTFM, { openPluginSettings("lastfm") }))
        case "failed":
            RecPageMessage(title: copy.FAILED_TITLE, message: page.statusText ?? "",
                           primary: (copy.TRY_AGAIN, retry))
        case "empty":
            RecPageMessage(title: page.filteringOne ? copy.EMPTY_SOURCE_TITLE : copy.EMPTY_TITLE,
                           message: page.filteringOne ? copy.EMPTY_SOURCE_BODY : copy.EMPTY_BODY,
                           primary: (copy.TRY_AGAIN, retry))
        default:
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(page.blocks, id: \.id) { block in
                        RecBlockView(block: block, gridWidth: max(width - recGutter * 2, 0), actions: actions)
                    }
                }
                .padding(.bottom, 120)
            }
            .refreshable { await model.load(force: true, resetSession: false) }
        }
    }

    private func retry() {
        Task { await model.load(force: true, resetSession: false) }
    }
}

// MARK: - Hero and page states

/// The page band: the page's icon, the hero line and the subline, white on the gradient.
private struct RecHero: View {
    let hero: String

    var body: some View {
        HStack(spacing: 12) {
            PCIcon(icon: PageIcon.recommendations.icon, size: 32, tint: PC.onAccent)
            VStack(alignment: .leading, spacing: 2) {
                Text(hero)
                    .font(.system(size: 16, weight: .semibold)).foregroundStyle(PC.onAccent)
                    .lineLimit(2)
                Text(RecommendationCopy.shared.SUBLINE)
                    .font(.system(size: 12)).foregroundStyle(PC.onAccent)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20).padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LinearGradient(
            colors: PC.pageGradient(.recommendations).map { Color(uiColor: UIColor(hex: $0)) },
            startPoint: .leading, endPoint: .trailing))
    }
}

/// Not configured, failed and empty: a title, a line, a filled button and an optional outlined one.
private struct RecPageMessage: View {
    let title: String
    let message: String
    let primary: (String, () -> Void)
    var secondary: (String, () -> Void)? = nil

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
            Text(title)
                .font(.system(size: 16, weight: .semibold)).foregroundStyle(PC.fg1)
                .multilineTextAlignment(.center)
            Text(message)
                .font(.system(size: 14)).foregroundStyle(PC.fg2)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            Button(action: primary.1) {
                Text(primary.0)
                    .font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.onAccent)
                    .padding(.horizontal, 20).frame(minHeight: 44)
                    .background(PC.fillAccent, in: RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_BUTTON)))
            }
            .buttonStyle(.plain)
            .padding(.top, 16)
            if let secondary {
                Button(action: secondary.1) {
                    Text(secondary.0)
                        .font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1)
                        .padding(.horizontal, 20).frame(minHeight: 44)
                        .overlay(RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_BUTTON))
                            .strokeBorder(PC.border))
                }
                .buttonStyle(.plain)
                .padding(.top, 8)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// The page skeleton while nothing has loaded: a songs section and two card sections.
private struct RecPageSkeleton: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                RecSkeletonTitle()
                ForEach(0..<Int(RecommendationPageViewKt.SONG_PREVIEW), id: \.self) { _ in PCSkeletonRow(art: 44) }
                RecSkeletonTitle()
                RecSkeletonCards(lines: 3)
                RecSkeletonTitle()
                RecSkeletonCards(lines: 1)
            }
        }
        .scrollDisabled(true)
    }
}

/// A section title not known yet.
private struct RecSkeletonTitle: View {
    var body: some View {
        PCSkeletonBox(width: 140, height: 18, radius: 4)
            .padding(.leading, recGutter).padding(.top, 16).padding(.bottom, 12)
    }
}

/// One row of skeleton cards.
private struct RecSkeletonCards: View {
    let lines: Int
    var count = Int(RecommendationPageViewKt.CARD_PREVIEW)

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: recCardGap) {
                ForEach(0..<count, id: \.self) { _ in RecCardSkeleton(lines: lines) }
            }
            .padding(.horizontal, recGutter)
        }
        .scrollDisabled(true)
    }
}

// MARK: - Blocks

/// The lines under an album card (title, artist, year), a playlist card (title, source), an artist card (name).
private func recCardLines(_ type: String) -> Int {
    switch type {
    case "album": return 3
    case "playlist": return 2
    default: return 1
    }
}

/// One block of the shared layout: a section title, a row, or a skeleton.
private struct RecBlockView: View {
    let block: IosRecBlock
    /// The width an open row's grid fills.
    let gridWidth: CGFloat
    let actions: RecActions

    var body: some View {
        switch block.kind {
        case "sectionTitle":
            PCSectionHeader(title: block.title ?? "")
        case "row":
            RecRowView(block: block, gridWidth: gridWidth, actions: actions)
        case "personalSkeleton":
            PCSectionHeader(title: block.title ?? "", sub: true)
            RecSkeletonCards(lines: 3)
        case "albumsSkeleton":
            PCSectionHeader(title: block.title ?? "")
            RecSkeletonCards(lines: 3)
        case "pendingSkeleton":
            // Providers still answering with nothing on screen (desktop): unlabeled.
            RecSkeletonTitle()
            RecSkeletonCards(lines: 3)
        case "listenersSkeleton":
            PCSectionHeader(title: block.title ?? "")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: recCardGap) {
                    ForEach(0..<recListenerSkeletons, id: \.self) { _ in ListenerCardSkeleton() }
                }
                .padding(.horizontal, recGutter)
            }
            .scrollDisabled(true)
        default:
            EmptyView()
        }
    }
}

/// One row: its header with See all / Show less, then its songs or cards (desktop's `renderSubsection`).
private struct RecRowView: View {
    let block: IosRecBlock
    let gridWidth: CGFloat
    let actions: RecActions
    private var container: IosContainer { IosContainer.companion.shared }

    var body: some View {
        let rowKey = block.rowKey ?? ""
        PCSectionHeader(
            title: block.title ?? "",
            action: block.toggleLabel.map { label in (label: label, run: { container.toggleRecommendationsRow(rowKey: rowKey) }) },
            sub: block.subtitle)
        if block.type == "song" {
            ForEach(Array(block.items.enumerated()), id: \.offset) { i, item in
                RecSongRow(rowKey: rowKey, index: i, item: item, actions: actions)
            }
            if block.loadingTail {
                ForEach(0..<Int(RecommendationPageViewKt.SONG_PREVIEW), id: \.self) { _ in PCSkeletonRow(art: 44) }
            }
        } else {
            RecCardsBody(block: block, gridWidth: gridWidth, actions: actions)
        }
    }
}

/// A row's cards. Collapsed: one scrolling row of 140 pt cards. Open: a grid whose
/// cards fill the width (the shared column rule), one line at a time so only the
/// lines on screen ask for their images. Then the skeleton tail.
private struct RecCardsBody: View {
    let block: IosRecBlock
    let gridWidth: CGFloat
    let actions: RecActions

    private var isListeners: Bool { block.type == "listener" }
    private var lines: Int { recCardLines(block.type) }
    private var tail: Int {
        isListeners ? 0 : (block.loadingTail ? Int(RecommendationPageViewKt.CARD_PREVIEW) : 0) + Int(block.aiTail)
    }

    /// Every card and skeleton slot, keyed uniquely within the row.
    private var slots: [RecSlot] {
        var seen = Set<String>()
        var out: [RecSlot] = []
        func add(_ key: String, _ kind: RecSlot.Kind) {
            let unique = seen.insert(key).inserted ? key : "\(key)#\(out.count)"
            seen.insert(unique)
            out.append(RecSlot(id: unique, kind: kind))
        }
        if isListeners {
            block.listeners.forEach { add($0.id, .listener($0)) }
        } else {
            block.items.forEach { add($0.id, .item($0)) }
        }
        for i in 0..<tail { add("skeleton:\(i)", .skeleton) }
        return out
    }

    var body: some View {
        if block.expanded {
            let columns = Int(RecommendationPageViewKt.recommendationGridColumns(
                available: Double(gridWidth), gap: Double(recCardGap)))
            let cardWidth = max(floor((gridWidth - recCardGap * CGFloat(columns - 1)) / CGFloat(columns)), 1)
            let all = slots
            let lineCount = (all.count + columns - 1) / columns
            ForEach(0..<lineCount, id: \.self) { line in
                HStack(alignment: .top, spacing: recCardGap) {
                    ForEach(all[(line * columns)..<min((line + 1) * columns, all.count)]) { slot in
                        RecSlotView(slot: slot, lines: lines, width: cardWidth, actions: actions)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, recGutter)
                .padding(.top, isListeners ? 8 : 0)
                .padding(.bottom, 16)
            }
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: recCardGap) {
                    ForEach(slots) { slot in
                        RecSlotView(slot: slot, lines: lines, width: pcRecCardWidth, actions: actions)
                    }
                }
                .padding(.horizontal, recGutter)
                // The listener card's play disc sits above the avatar; leave it room.
                .padding(.top, isListeners ? 8 : 0)
            }
        }
    }
}

private struct RecSlot: Identifiable {
    enum Kind { case item(IosRecItem), listener(IosRecListener), skeleton }
    let id: String
    let kind: Kind
}

private struct RecSlotView: View {
    let slot: RecSlot
    let lines: Int
    let width: CGFloat
    let actions: RecActions

    var body: some View {
        switch slot.kind {
        case .item(let item): RecCardFor(item: item, width: width, actions: actions)
        case .listener(let l): RecListenerCardFor(listener: l, width: width, actions: actions)
        case .skeleton: RecCardSkeleton(lines: lines, width: width)
        }
    }
}

// MARK: - Cards and their actions

/// What a card or row can do beyond its own engine calls: show a message, open a page.
struct RecActions {
    let say: (String) -> Void
    let open: (PCRoute) -> Void
}

/// Plays (or queues) a queue the page produced, then shows its message, if any.
@MainActor
private func recPlay(_ result: IosRecQueueResult, coordinator: QueuePlaybackCoordinator, queue: Bool = false,
                     say: (String) -> Void) {
    if let q = result.queue {
        if queue { q.tracks.forEach { coordinator.addToQueue($0) } }
        else { coordinator.setQueue(q.tracks, startIndex: 0, context: q.context) }
    }
    if let m = result.message { say(m) }
}

private struct RecCardFor: View {
    let item: IosRecItem
    let width: CGFloat
    let actions: RecActions
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    private var container: IosContainer { IosContainer.companion.shared }

    var body: some View {
        switch item.type {
        case "album": album
        case "artist": artist
        case "playlist":
            if let weeklyId = item.weeklyId { RecWeeklyCardHost(item: item, weeklyId: weeklyId, width: width, actions: actions) }
            else { playlist }
        default: EmptyView()
        }
    }

    private var albumArtist: String { item.artist ?? "" }

    private var album: some View {
        RecAlbumCard(item: item, width: width,
                     onOpen: { actions.open(.album(title: item.name, artist: albumArtist)) },
                     onPlay: playAlbum)
            .onAppear { container.recommendationsCardShown(itemId: item.id) }
            .pcAlbumContextMenu(
                title: item.name, artist: albumArtist, artworkUrl: item.imageUrl, coordinator: coordinator,
                onGoToArtist: albumArtist.isEmpty ? nil : { actions.open(.artist(albumArtist)) })
    }

    /// Android's `playAlbum`: "Loading …", then the album (shared `CatalogQueues.album`) or "Couldn't load …".
    private func playAlbum() {
        let copy = RecommendationCopy.shared
        actions.say(copy.loading(name: item.name))
        Task {
            if let q = try? await container.albumQueue(title: item.name, artist: albumArtist) {
                coordinator.setQueue(q.tracks, startIndex: 0, context: q.context)
            } else {
                actions.say(copy.couldntLoad(name: item.name))
            }
        }
    }

    private var artist: some View {
        RecArtistCard(item: item, width: width, onOpen: { actions.open(.artist(item.name)) })
            .onAppear { container.recommendationsCardShown(itemId: item.id) }
            .pcArtistContextMenu(name: item.name, imageUrl: item.imageUrl, coordinator: coordinator)
    }

    // Task 5: open the service playlist view (`.servicePlaylist`) when `item.opensView`.
    // Until that route exists, a tap plays the playlist.
    private var playlist: some View {
        RecPlaylistCard(item: item, width: width, onOpen: { playService(queue: false) })
            .contextMenu {
                Button { playService(queue: false) } label: {
                    Label(RecommendationCopy.shared.PLAY, systemImage: "play.fill")
                }
                Button { playService(queue: true) } label: {
                    Label(RecommendationCopy.shared.ADD_TO_QUEUE, systemImage: "text.append")
                }
            }
    }

    private func playService(queue: Bool) {
        let name = item.name
        guard let url = item.url else {
            actions.say(RecommendationCopy.shared.couldntLoad(name: name)); return
        }
        Task {
            guard let r = try? await container.recommendationsServicePlaylist(url: url, name: name) else {
                actions.say(RecommendationCopy.shared.couldntLoad(name: name)); return
            }
            recPlay(r, coordinator: coordinator, queue: queue, say: actions.say)
        }
    }
}

/// A weekly card with its mosaic, which loads once the card is on screen.
private struct RecWeeklyCardHost: View {
    let item: IosRecItem
    let weeklyId: String
    let width: CGFloat
    let actions: RecActions
    @State private var covers: [String] = []
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    private var container: IosContainer { IosContainer.companion.shared }

    var body: some View {
        RecWeeklyCard(item: item, covers: covers, width: width,
                      onOpen: { actions.open(.playlist(id: weeklyId, title: item.name)) },
                      onPlay: play)
            .task(id: weeklyId) {
                if let c = try? await container.weeklyCovers(weeklyId: weeklyId) { covers = c }
            }
    }

    private func play() {
        let name = item.name
        Task {
            guard let r = try? await container.recommendationsWeeklyQueue(weeklyId: weeklyId, name: name) else {
                actions.say(RecommendationCopy.shared.couldntLoad(name: name)); return
            }
            recPlay(r, coordinator: coordinator, say: actions.say)
        }
    }
}

private struct RecListenerCardFor: View {
    let listener: IosRecListener
    let width: CGFloat
    let actions: RecActions
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    private var container: IosContainer { IosContainer.companion.shared }

    private var profile: PCRoute {
        .friend(id: listener.friendId, username: listener.name, service: listener.service, name: listener.name)
    }

    var body: some View {
        ListenerCard(listener: listener, width: width, onOpen: { actions.open(profile) }, onPlay: play)
            .contextMenu {
                Button { actions.open(profile) } label: {
                    Label(RecommendationCopy.shared.VIEW_HISTORY, systemImage: "clock")
                }
                Button { follow() } label: {
                    Label(RecommendationCopy.shared.FOLLOW, systemImage: "plus")
                }
                if listener.showsAchordion, let link = listener.achordionUrl.flatMap(URL.init(string:)) {
                    Button { UIApplication.shared.open(link) } label: {
                        Label(RecommendationCopy.shared.VIEW_ON_ACHORDION, systemImage: "arrow.up.right.square")
                    }
                }
            }
    }

    private func play() {
        let name = listener.name
        Task {
            guard let r = try? await container.recommendationsListenerPlay(service: listener.service, username: name) else {
                actions.say(RecommendationCopy.shared.listenerTracksFailed(name: name)); return
            }
            recPlay(r, coordinator: coordinator, say: actions.say)
        }
    }

    private func follow() {
        Task {
            if let message = try? await container.followListener(service: listener.service, username: listener.name) {
                actions.say(message)
            }
        }
    }
}

// MARK: - Song rows

/// One recommended song: resolved while on screen, a tap plays it and the rest
/// of its row, a long-press opens the track menu.
private struct RecSongRow: View {
    let rowKey: String
    let index: Int
    let item: IosRecItem
    let actions: RecActions
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    private var container: IosContainer { IosContainer.companion.shared }

    private var artist: String { item.artist ?? "" }

    private var isCurrent: Bool {
        guard let ctx = coordinator.playbackContext, let id = item.trackId else { return false }
        return ctx.type == RecommendationQueuesKt.RECOMMENDATIONS_CONTEXT && ctx.id == rowKey
            && coordinator.currentTrack?.id == id
    }

    /// The queue a tap plays: this song and the rest of its row (shared `songQueue`).
    private var queue: CatalogQueue? { container.recommendationsSongQueue(rowKey: rowKey, index: Int32(index)) }

    var body: some View {
        let noMatch = pcTrackNoMatch(artist: artist, title: item.name, album: nil)
        Button(action: play) {
            HStack(spacing: 12) {
                pcCover(pcTrackArt(item.imageUrl, artist: artist, title: item.name, album: nil),
                        seed: item.name + artist, size: 44, radius: 6, artist: artist, album: nil)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name).font(.system(size: 15, weight: .medium))
                        .foregroundStyle(noMatch ? PC.fg3 : (isCurrent ? PC.accent : PC.fg1)).lineLimit(1)
                    Text(artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                }
                Spacer(minLength: 0)
                if let sources = IosTrackResolverCache.shared.cached(artist: artist, title: item.name, album: nil),
                   !sources.isEmpty {
                    ResolverBadgeRow(sources: sources)
                }
            }
            .padding(.horizontal, recGutter).padding(.vertical, 8).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onAppear {
            guard !artist.isEmpty else { return }
            IosTrackResolverCache.shared.resolve(ResolveRequest(artist: artist, title: item.name, album: nil), order: index)
        }
        .onDisappear { IosTrackResolverCache.shared.leave(artist: artist, title: item.name) }
        .modifier(RecSongMenu(track: item.trackId == nil ? nil : queue?.tracks.first, coordinator: coordinator,
                              onGoToArtist: { actions.open(.artist(artist)) }))
    }

    private func play() {
        guard let q = queue else { return }
        coordinator.setQueue(q.tracks, startIndex: 0, context: q.context)
    }
}

/// The track menu, only for a song that can play (one with an artist).
private struct RecSongMenu: ViewModifier {
    let track: Track?
    let coordinator: QueuePlaybackCoordinator
    let onGoToArtist: () -> Void

    func body(content: Content) -> some View {
        if let track {
            content.pcTrackContextMenu(track, coordinator: coordinator, onGoToArtist: onGoToArtist)
        } else {
            content
        }
    }
}
