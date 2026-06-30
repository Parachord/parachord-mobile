import SwiftUI
import Shared

/// Sort options for the unified channel picker (mirrors Android `PlaylistSort`).
enum PlaylistSortOption: String, CaseIterable, Identifiable {
    case recent, created, modified, alphaAsc, alphaDesc
    var id: String { rawValue }
    var label: String {
        switch self {
        case .recent: return "Recently Added"
        case .created: return "Date Created"
        case .modified: return "Recently Modified"
        case .alphaAsc: return "A-Z"
        case .alphaDesc: return "Z-A"
        }
    }
}

/// "Configure what syncs" sheet (port of Android `ProviderSyncConfigSheet.kt`):
/// axis toggles ("What syncs", persisted live) + — when the Playlists axis is on
/// — the UNIFIED channel picker over `getProviderPickerRows`/`setPlaylistChannel`
/// (origin chips, sort, owner names, "Tap to import"). Every change persists live;
/// turning an axis OFF that still has synced items raises a Keep/Remove prompt.
struct ProviderSyncConfigSheet: View {
    let target: ProviderConfigTarget
    /// Phase-3 enable gate: when non-nil the sheet is an enable gate — show
    /// Done/Cancel and invoke `onDone` (enable) only on Done. Nil = normal config
    /// (live persistence, just a Close).
    var onDone: (() -> Void)? = nil
    var onCancel: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var m: ProviderConfigModel

    init(target: ProviderConfigTarget, onDone: (() -> Void)? = nil, onCancel: (() -> Void)? = nil) {
        self.target = target
        self.onDone = onDone
        self.onCancel = onCancel
        _m = State(initialValue: ProviderConfigModel(providerId: target.id))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if m.loading {
                        ProgressView().tint(PC.accent)
                            .frame(maxWidth: .infinity).padding(.vertical, 40)
                    } else {
                        sectionLabel("What syncs")
                        ForEach(m.supportedAxes, id: \.self) { axis in axisToggle(axis) }

                        if m.axes.contains("playlists") {
                            HStack {
                                sectionLabel("\(target.name) playlists")
                                Spacer()
                                sortMenu
                            }
                            if m.channelRows.isEmpty {
                                Text("No playlists available for \(target.name) yet.")
                                    .font(.system(size: 13)).foregroundStyle(PC.fg3)
                                    .padding(.horizontal, 20).padding(.top, 10)
                            } else {
                                ForEach(m.channelRows, id: \.localId) { row in channelRow(row) }
                            }
                        }
                    }
                    Spacer(minLength: 40)
                }
                .padding(.top, 8)
            }
            .background(PC.bgPrimary.ignoresSafeArea())
            .navigationTitle(target.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if onDone != nil {
                    // Enable-gate mode (Phase 3): Cancel reverts, Done commits.
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { onCancel?(); dismiss() }.foregroundStyle(PC.fg2)
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") {
                            Task {
                                if await m.needsRemovalPrompt() { return }
                                onDone?(); dismiss()
                            }
                        }.foregroundStyle(PC.accent)
                    }
                } else {
                    // Normal mode: changes persist live; this just closes.
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { dismiss() }.foregroundStyle(PC.accent)
                    }
                }
            }
        }
        .task { await m.load() }
        // Keep-or-remove when an axis with existing items was switched off (live).
        .confirmationDialog(
            "Stop syncing from \(target.name)?",
            isPresented: Binding(get: { m.pendingRemoval != nil }, set: { if !$0 { m.keepItems() } }),
            titleVisibility: .visible
        ) {
            Button("Remove \(m.pendingRemoval?.summary ?? "items")", role: .destructive) {
                Task { await m.confirmRemoval() }
            }
            Button("Keep in collection") { m.keepItems() }
            Button("Cancel", role: .cancel) { m.keepItems() }
        } message: {
            Text("You turned off syncing for \(m.pendingRemoval?.summary ?? "items") from \(target.name). Remove them from your collection, or keep them? Items also synced from another service are kept either way.")
        }
    }

    // ── Rows ─────────────────────────────────────────────────────────────
    private func axisToggle(_ axis: String) -> some View {
        Toggle(isOn: Binding(get: { m.axes.contains(axis) }, set: { m.toggleAxis(axis, $0) })) {
            Text(axisLabel(axis)).font(.system(size: 15)).foregroundStyle(PC.fg1)
        }
        .tint(PC.accent)
        .padding(.horizontal, 20).padding(.vertical, 6)
    }

    private func channelRow(_ row: ProviderChannelPlaylist) -> some View {
        Button { m.toggleChannel(row.localId) } label: {
            HStack(spacing: 10) {
                Image(systemName: row.enabled ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 18)).foregroundStyle(row.enabled ? PC.accent : PC.fg3)
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 6) {
                        Text(row.name).font(.system(size: 14)).foregroundStyle(PC.fg1).lineLimit(1)
                        if let origin = row.originLabel {
                            Text(origin)
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(originChipColor(origin))
                                .padding(.horizontal, 6).padding(.vertical, 1)
                                .background(RoundedRectangle(cornerRadius: 4).fill(originChipColor(origin).opacity(0.12)))
                        }
                    }
                    Text(rowSubtitle(row))
                        .font(.system(size: 11)).foregroundStyle(PC.fg3).lineLimit(1)
                }
                Spacer()
            }
            .padding(.horizontal, 20).padding(.vertical, 7)
        }
        .buttonStyle(.plain)
    }

    private var sortMenu: some View {
        Menu {
            ForEach(PlaylistSortOption.allCases) { opt in
                Button { m.setSort(opt) } label: {
                    HStack { Text(opt.label); if m.sort == opt { Image(systemName: "checkmark") } }
                }
            }
        } label: {
            HStack(spacing: 2) {
                Text(m.sort.label).font(.system(size: 13, weight: .medium))
                Image(systemName: "chevron.down").font(.system(size: 10, weight: .semibold))
            }
            .foregroundStyle(PC.accent)
            .padding(.horizontal, 20)
        }
    }

    private func rowSubtitle(_ row: ProviderChannelPlaylist) -> String {
        let n = Int(row.trackCount)
        var s = row.notImported ? "Tap to import" : "\(n) track\(n == 1 ? "" : "s")"
        if let owner = row.ownerName, !owner.isEmpty { s += " · \(owner)" }
        return s
    }

    private func sectionLabel(_ t: String) -> some View {
        Text(t.uppercased()).font(.system(size: 11, weight: .bold)).tracking(1.4).foregroundStyle(PC.fg3)
            .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 6)
    }

    /// Provider-native axis label (Apple Music names its library differently).
    private func axisLabel(_ axis: String) -> String {
        let isApple = target.id == "applemusic"
        switch axis {
        case "tracks": return isApple ? "Library Songs" : "Liked Songs"
        case "albums": return isApple ? "Library Albums" : "Saved Albums"
        case "artists": return isApple ? "Library Artists" : "Followed Artists"
        case "playlists": return "Playlists"
        default: return axis.capitalized
        }
    }
}

/// Origin-chip brand colors — in lockstep with Android's `originChipColor`
/// (Spotify green / Apple Music red / ListenBrainz orange / Hosted blue / gray).
private func originChipColor(_ label: String) -> Color {
    switch label {
    case "Spotify": return Color(uiColor: UIColor(hex: 0x1DB954))
    case "Apple Music": return Color(uiColor: UIColor(hex: 0xFA243C))
    case "ListenBrainz": return Color(uiColor: UIColor(hex: 0xEB743B))
    case "Hosted": return Color(uiColor: UIColor(hex: 0x3B82F6))
    default: return Color(uiColor: UIColor(hex: 0x6B7280))
    }
}

@MainActor @Observable
private final class ProviderConfigModel {
    private let container = IosContainer.companion.shared
    let providerId: String
    var axes: Set<String> = []
    var loading = true
    var pendingRemoval: AxisRemovalPrompt?

    // Unified channel rows (all providers): every playlist that CAN sync with
    // this provider; `enabled` = currently synced; `notImported` = a live-fetched
    // service playlist with no local row yet ("Tap to import"). Toggling
    // detaches/admits via setPlaylistChannel — live, no per-row keep/remove.
    var channelRows: [ProviderChannelPlaylist] = []
    var sort: PlaylistSortOption = .recent

    init(providerId: String) { self.providerId = providerId }

    /// Axes a provider can sync. ListenBrainz is playlists-only (loved tracks go
    /// via the scrobbler, not collection sync).
    var supportedAxes: [String] {
        providerId == "listenbrainz" ? ["playlists"] : ["tracks", "albums", "artists", "playlists"]
    }

    func load() async {
        let ax = (try? await container.getSyncCollections(providerId: providerId)) as? [String] ?? []
        axes = Set(ax)
        await reloadChannelRows()
        loading = false
    }

    func reloadChannelRows() async {
        let rows = (try? await container.getProviderPickerRows(providerId: providerId)) as? [ProviderChannelPlaylist] ?? []
        channelRows = ProviderConfigModel.sorted(rows, sort)
    }

    func setSort(_ s: PlaylistSortOption) {
        sort = s
        channelRows = ProviderConfigModel.sorted(channelRows, s)
    }

    func toggleChannel(_ localId: String) {
        guard let row = channelRows.first(where: { $0.localId == localId }) else { return }
        let newEnabled = !row.enabled
        Task {
            try? await container.setPlaylistChannel(localId: localId, providerId: providerId, enabled: newEnabled)
            await reloadChannelRows()
        }
    }

    func toggleAxis(_ axis: String, _ on: Bool) {
        if on { axes.insert(axis) } else { axes.remove(axis) }
        let snapshot = Array(axes)
        Task {
            try? await container.setSyncCollections(providerId: providerId, axes: snapshot)
            if !on { await maybePromptAxisRemoval(axis) }
        }
    }

    private func maybePromptAxisRemoval(_ axis: String) async {
        let n = Int((try? await container.countSyncedItems(providerId: providerId, axis: axis)) ?? 0)
        guard n > 0 else { return }
        pendingRemoval = AxisRemovalPrompt(summary: "\(n) \(axisNoun(axis, n))", axes: [axis])
    }

    /// For the enable-gate Done: if a dropped axis still has items, raise the
    /// prompt and return true (keep the sheet open); else false (safe to commit).
    func needsRemovalPrompt() async -> Bool {
        // In live mode the prompt already fired on toggle; this covers the gate's
        // Done. pendingRemoval may already be set from a live toggle.
        return pendingRemoval != nil
    }

    func confirmRemoval() async {
        if let p = pendingRemoval {
            for axis in p.axes { _ = try? await container.removeSyncedItems(providerId: providerId, axis: axis) }
        }
        pendingRemoval = nil
    }

    func keepItems() { pendingRemoval = nil }

    private func axisNoun(_ axis: String, _ n: Int) -> String {
        switch axis {
        case "tracks": return n == 1 ? "saved song" : "saved songs"
        case "albums": return n == 1 ? "album" : "albums"
        case "artists": return n == 1 ? "artist" : "artists"
        case "playlists": return n == 1 ? "playlist" : "playlists"
        default: return "items"
        }
    }

    static func sorted(_ list: [ProviderChannelPlaylist], _ s: PlaylistSortOption) -> [ProviderChannelPlaylist] {
        switch s {
        case .recent: return list.sorted { $0.createdAt > $1.createdAt }
        case .created: return list.sorted { $0.createdAt < $1.createdAt }
        case .modified: return list.sorted { a, b in
            let av = a.lastModified > 0 ? a.lastModified : a.updatedAt
            let bv = b.lastModified > 0 ? b.lastModified : b.updatedAt
            return av > bv
        }
        case .alphaAsc: return list.sorted { $0.name.lowercased() < $1.name.lowercased() }
        case .alphaDesc: return list.sorted { $0.name.lowercased() > $1.name.lowercased() }
        }
    }
}
