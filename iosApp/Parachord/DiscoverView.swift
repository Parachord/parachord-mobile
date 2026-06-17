import SwiftUI
import Foundation
import Shared

// MARK: - Discover ViewModel (phase 5.3)
//
// First repository-backed content screen. Surfaces the user's
// ListenBrainz Weekly Jams + Weekly Exploration via the SHARED
// WeeklyPlaylistsRepository, keyed off the LB username they set in
// Settings. No auth — the createdfor endpoint is public.

/// Featured item shown inside a Discover tile (matches Android's DiscoverPreview).
struct TilePreview: Codable, Equatable {
    let title: String
    let subtitle: String
    let artworkUrl: String?
}

@MainActor
@Observable
final class DiscoverViewModel {
    /// App-lifetime singleton so loaded Discover/Weekly data survives the Home
    /// tab being recreated (tab switch / nav reset) — no reload flicker.
    static let shared = DiscoverViewModel()
    private var lastLoad: Date?
    private let ttl: TimeInterval = 6 * 3600

    private let container = IosContainer.companion.shared
    private let watcher = FlowWatcher(scope: IosContainer.companion.shared.appScope)
    private var subscription: Cancellable?
    private var debounceTask: Task<Void, Never>?
    private var lastUsername: String?

    var jams: [IosWeeklyEntry] = []
    var exploration: [IosWeeklyEntry] = []
    /// playlistId → track count, for the "N tracks" weekly-card subtitle
    /// (matches Android's weeklyTrackCounts). Filled lazily in the background.
    var trackCounts: [String: Int] = [:]
    /// playlistId → up to 4 distinct album-art URLs for the 2x2 cover mosaic
    /// (matches Android's weeklyCovers). Filled in the same background pass.
    var trackCovers: [String: [String]] = [:]
    /// preset → featured item shown inside each Discover tile (matches Android's
    /// per-tile DiscoverPreview). Loaded in the background from each tile's repo.
    var previews: [String: TilePreview] = [:]
    var isLoading = false
    var loaded = false

    /// Observe the ListenBrainz username and (re)load Weekly playlists when
    /// it changes — so setting it in Settings reflects here WITHOUT an app
    /// restart (the previous `.task`-once load never reacted to the username
    /// changing). The flow emits the current value on subscribe, which drives
    /// the initial load. The Settings field persists per keystroke, so a burst
    /// of edits is debounced into a single fetch.
    private var previewSubs: [Cancellable] = []

    func start() {
        guard subscription == nil else { return }
        subscription = watcher.watch(flow: container.settingsStore.getListenBrainzUsernameFlow()) { [weak self] value in
            let username = (value as? String) ?? ""
            Task { @MainActor in self?.onUsernameChanged(username) }
        }
        loadPreviews()
    }

    /// Each Discover tile's featured item, from its own repo's first entry
    /// (matches Android: For You = top recommended artist, Critical = latest
    /// reviewed album, Fresh = latest release, Pop = #1 album). The first three
    /// WATCH the shared repo value-flows, so each repo's cached-first emission
    /// fills the tile instantly on cold start and fresh data fades in — no
    /// discover_previews.json. (Pop's ChartsRepository has no cached flow yet, so
    /// it stays a one-shot fetch; giving Charts a cache is a shared follow-up.)
    private func loadPreviews() {
        guard previewSubs.isEmpty else { return }
        previewSubs.append(watcher.watch(flow: container.recommendedArtistsFlow()) { [weak self] v in
            guard let self, let a = (v as? [RecommendedArtist])?.first else { return }
            let reason = a.reason.flatMap { $0.isEmpty ? nil : $0 } ?? "Based on your listening"
            self.setPreview("foryou", TilePreview(title: a.name, subtitle: reason, artworkUrl: a.imageUrl))
        })
        previewSubs.append(watcher.watch(flow: container.criticsPicksFlow()) { [weak self] v in
            guard let self, let al = (v as? [CriticsPickAlbum])?.first else { return }
            self.setPreview("critical", TilePreview(title: al.title, subtitle: al.artist, artworkUrl: al.albumArt))
        })
        previewSubs.append(watcher.watch(flow: container.freshDropsFlow()) { [weak self] v in
            guard let self, let d = (v as? [FreshDrop])?.first else { return }
            self.setPreview("fresh", TilePreview(title: d.title, subtitle: d.artist, artworkUrl: d.albumArt))
        })
        Task { @MainActor [weak self] in
            guard let self, let al = (try? await self.container.loadPopOfTheTopsAlbums(countryCode: "us"))?.first else { return }
            self.setPreview("pop", TilePreview(title: al.title, subtitle: al.artist, artworkUrl: al.artworkUrl))
        }
    }

    /// Update a Discover tile preview defensively. For the SAME featured item,
    /// never DOWNGRADE to an art-less value: the curated repos emit progressively
    /// (cached → fresh → per-album art enrichment), and a transient art-less
    /// re-emit would otherwise flash the thumb between the loaded art and the
    /// placeholder (the reported Snowdrop flicker on Critical Darlings). No-op
    /// when nothing changed, so redundant emissions don't churn the cross-fade.
    private func setPreview(_ key: String, _ new: TilePreview) {
        let old = previews[key]
        var next = new
        if let old, old.title == new.title, old.subtitle == new.subtitle,
           (new.artworkUrl ?? "").isEmpty, !(old.artworkUrl ?? "").isEmpty {
            next = TilePreview(title: new.title, subtitle: new.subtitle, artworkUrl: old.artworkUrl)
        }
        guard next != old else { return }
        withAnimation(.easeInOut(duration: 0.45)) { previews[key] = next }
    }

    private func onUsernameChanged(_ username: String) {
        guard username != lastUsername else { return }
        lastUsername = username
        debounceTask?.cancel()
        debounceTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard let self, !Task.isCancelled else { return }
            await self.load(forceRefresh: true)
        }
    }

    func load(forceRefresh: Bool = false) async {
        // TTL-gated revalidate (unless forced by a username change): show cached
        // weekly carousels instantly, refresh only after the TTL — not every open.
        if !forceRefresh, loaded, let l = lastLoad, Date().timeIntervalSince(l) < ttl { return }
        isLoading = true
        let entries = (try? await container.loadWeeklyPlaylists(forceRefresh: forceRefresh)) ?? []
        if !entries.isEmpty {
            jams = entries.filter { $0.kind == "Weekly Jams" }
            exploration = entries.filter { $0.kind == "Weekly Exploration" }
            loadTrackCounts(entries)
        }
        lastLoad = Date()
        isLoading = false
        loaded = true
    }

    /// Fetch each weekly playlist's tracks just for its count (ListenBrainz,
    /// not rate-limited). Fire-and-forget per entry so the carousel renders
    /// immediately and each subtitle fills in as its count lands.
    private func loadTrackCounts(_ entries: [IosWeeklyEntry]) {
        for entry in entries where trackCounts[entry.id] == nil {
            Task { @MainActor [weak self] in
                guard let self else { return }
                let tracks = (try? await self.container.loadWeeklyPlaylistTracks(playlistId: entry.id)) ?? []
                guard !tracks.isEmpty else { return }
                self.trackCounts[entry.id] = tracks.count
                // First 4 distinct album-art URLs → the 2x2 mosaic.
                var seen = Set<String>(); var covers: [String] = []
                for t in tracks {
                    guard let art = t.albumArt, !art.isEmpty, seen.insert(art).inserted else { continue }
                    covers.append(art); if covers.count == 4 { break }
                }
                if !covers.isEmpty { self.trackCovers[entry.id] = covers }
            }
        }
    }
}

// MARK: - Discover screen

struct DiscoverView: View {
    @State private var model = DiscoverViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if model.isLoading && !model.loaded {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if model.jams.isEmpty && model.exploration.isEmpty {
                    emptyState
                } else {
                    list
                }
            }
            .navigationTitle("Discover")
        }
        .task { model.start() }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("No weekly playlists", systemImage: "sparkles")
        } description: {
            Text("Set your ListenBrainz username in Settings to see your Weekly Jams and Weekly Exploration.")
        }
    }

    private var list: some View {
        List {
            if !model.jams.isEmpty {
                Section("Weekly Jams") {
                    ForEach(model.jams, id: \.id) { entry in
                        weeklyRow(entry)
                    }
                }
            }
            if !model.exploration.isEmpty {
                Section("Weekly Exploration") {
                    ForEach(model.exploration, id: \.id) { entry in
                        weeklyRow(entry)
                    }
                }
            }
        }
        .refreshable { await model.load(forceRefresh: true) }
    }

    private func weeklyRow(_ entry: IosWeeklyEntry) -> some View {
        NavigationLink {
            PlaylistDetailView(
                playlistId: entry.id,
                title: "\(entry.weekLabel) · \(entry.kind)"
            )
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.weekLabel)
                    .font(.body.weight(.medium))
                if !entry.summary.isEmpty {
                    Text(entry.summary)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
            .padding(.vertical, 2)
        }
    }
}
