import SwiftUI

/// The Sync settings tab (port of Android's dedicated Sync tab): a status header
/// with the global "Sync now" + staged progress, then one consistent card per
/// connected provider (Spotify / Apple Music / ListenBrainz). Lives inside the
/// Settings screen's ScrollView (no ScrollView of its own).
struct SyncSettingsView: View {
    @Bindable var model: SettingsViewModel
    @State private var sync = SyncViewModel()
    @State private var reauth = AppleMusicReauthModel()
    /// Single sheet driver (one `.sheet` only — two `.sheet(item:)` on one view
    /// conflict in SwiftUI and can spuriously present). `.configure` = the normal
    /// "Configure what syncs" sheet; `.gate` = the toggle-ON enable gate.
    @State private var activeSheet: SyncConfigSheet?
    @State private var showMigration = false

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if reauth.required {
                PCAppleMusicReauthBanner(connecting: reauth.connecting) { reauth.reconnect() }
                    .padding(.top, 18)
            }

            SyncProgressView(model: sync)
                .padding(.top, reauth.required ? 0 : 18)

            if let cd = sync.spotifyCooldownText {
                HStack(spacing: 6) {
                    Image(systemName: "clock.badge.exclamationmark").font(.system(size: 12))
                    Text(cd).font(.system(size: 12, weight: .medium))
                }
                .foregroundStyle(PC.warning)
                .padding(.horizontal, 20)
            }
            if let s = sync.status {
                Text(s).font(.system(size: 12)).foregroundStyle(PC.fg3)
                    .padding(.horizontal, 20)
            }

            if model.isConnected("spotify") { spotifyCard }
            appleMusicCard
            if model.isConnected("listenbrainz") { listenBrainzCard }

            syncEngineSection
        }
        .padding(.bottom, 130)
        .task { await sync.load(); reauth.start() }
        .onDisappear { sync.stopWatching(); reauth.stop() }
        .sheet(isPresented: $showMigration, onDismiss: { Task { await sync.refreshEngineMode() } }) {
            MigrationPreviewView()
        }
        // Keep-or-remove on disable (#194 / cross-provider dedup). Dismissing
        // without a choice defaults to keep (the provider is disabled either way).
        .confirmationDialog(
            "Disable \(syncProviderName(sync.pendingDisable)) sync",
            isPresented: Binding(get: { sync.pendingDisable != nil },
                                 set: { presented in if !presented { sync.confirmDisable(removeItems: false) } }),
            titleVisibility: .visible
        ) {
            Button("Keep synced items") { sync.confirmDisable(removeItems: false) }
            Button("Remove from this device", role: .destructive) { sync.confirmDisable(removeItems: true) }
        } message: {
            Text("Remove the items that came only from this service? Items also synced from another service are kept.")
        }
        // Enable gate (Phase 3): toggling a provider ON sets `pendingEnable`,
        // which opens the config sheet as a gate — sync enables only on Done
        // (confirmEnable); cancel/swipe-dismiss reverts the optimistic ON.
        .onChange(of: sync.pendingEnable) { _, new in
            if let id = new {
                activeSheet = .gate(id: id, name: syncProviderName(id))
            } else if case .gate = activeSheet {
                activeSheet = nil
            }
        }
        .sheet(item: $activeSheet, onDismiss: {
            // A gate sheet dismissed without committing (swipe) must revert the
            // optimistic ON. confirmEnable/cancelEnable already cleared it on Done/Cancel.
            if sync.pendingEnable != nil { sync.cancelEnable() }
        }) { sheet in
            switch sheet {
            case .configure(let id, let name):
                ProviderSyncConfigSheet(target: ProviderConfigTarget(id: id, name: name))
            case .gate(let id, let name):
                ProviderSyncConfigSheet(
                    target: ProviderConfigTarget(id: id, name: name),
                    onDone: { sync.confirmEnable() },
                    onCancel: { sync.cancelEnable() }
                )
            }
        }
    }

    private var spotifyCard: some View {
        SyncCardView(
            providerName: "Spotify Sync",
            enabled: sync.spotifyOn || sync.pendingEnable == "spotify",
            busy: false,
            summary: sync.syncedSummaries["spotify"] ?? "",
            defaultDesc: "Sync your saved tracks, albums, artists, and playlists.",
            note: nil,
            lastSyncedText: sync.lastSyncedText,
            onToggle: { sync.toggle("spotify", $0) },
            onConfigure: { activeSheet = .configure(id: "spotify", name: "Spotify") }
        )
    }

    private var appleMusicCard: some View {
        SyncCardView(
            providerName: "Apple Music Sync",
            enabled: sync.appleMusicOn || sync.pendingEnable == "applemusic",
            busy: sync.appleMusicBusy,
            summary: sync.syncedSummaries["applemusic"] ?? "",
            defaultDesc: "Sync your Apple Music library and playlists.",
            note: "Note: Apple Music's API doesn't allow Parachord to delete or rename playlists, or remove tracks from a playlist. Those actions silently no-op on Apple Music — make those changes in the Music app instead.",
            lastSyncedText: sync.lastSyncedText,
            onToggle: { sync.toggle("applemusic", $0) },
            onConfigure: { activeSheet = .configure(id: "applemusic", name: "Apple Music") }
        )
    }

    private var listenBrainzCard: some View {
        SyncCardView(
            providerName: "ListenBrainz Sync",
            enabled: sync.listenBrainzOn || sync.pendingEnable == "listenbrainz",
            busy: false,
            summary: sync.syncedSummaries["listenbrainz"] ?? "",
            defaultDesc: "Pushes Parachord-curated playlists to your ListenBrainz profile. Loved tracks sync separately via scrobblers.",
            note: nil,
            lastSyncedText: sync.lastSyncedText,
            onToggle: { sync.toggle("listenbrainz", $0) },
            onConfigure: { activeSheet = .configure(id: "listenbrainz", name: "ListenBrainz") }
        )
    }

    /// "Sync engine" section — the N-way (multimaster) migration opt-in. Shows
    /// the current engine and a Preview→switch entry (mirrors Android's migration
    /// preview). The preview is a no-write dry-run; switching to "new" only
    /// happens on Accept inside the sheet.
    private var syncEngineSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("SYNC ENGINE")
                .font(.system(size: 11, weight: .semibold)).tracking(1.4)
                .foregroundStyle(PC.fg3)
                .padding(.horizontal, 20).padding(.bottom, 8)

            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(engineTitle).font(.system(size: 16, weight: .medium)).foregroundStyle(PC.fg1)
                        Text(engineDesc)
                            .font(.system(size: 12)).foregroundStyle(PC.fg2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                    if sync.engineMode == "new" {
                        Text("NEW")
                            .font(.system(size: 10, weight: .bold)).tracking(0.5)
                            .foregroundStyle(PC.accent)
                            .padding(.horizontal, 7).padding(.vertical, 2)
                            .background(RoundedRectangle(cornerRadius: 5).fill(PC.accent.opacity(0.12)))
                    }
                }

                Divider().background(PC.cardBorder)

                if sync.engineMode == "new" {
                    Button { sync.revertToLegacy() } label: {
                        Text("Switch back to legacy sync")
                            .font(.system(size: 14, weight: .medium)).foregroundStyle(PC.fg2)
                    }
                    .buttonStyle(.plain)
                } else {
                    Button { showMigration = true } label: {
                        HStack {
                            Text("Preview & switch to new sync")
                                .font(.system(size: 14, weight: .medium)).foregroundStyle(PC.accent)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.system(size: 12, weight: .semibold)).foregroundStyle(PC.accent)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 12).fill(PC.cardBg))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(PC.cardBorder, lineWidth: 1))
            .padding(.horizontal, 16)
        }
    }

    private var engineTitle: String {
        // Matches the other platforms' label ("Use new sync (preview)"); once
        // switched, reflects that you're on the new multimaster engine.
        sync.engineMode == "new" ? "New sync (multimaster)" : "Use New Sync (preview)"
    }

    private var engineDesc: String {
        switch sync.engineMode {
        case "new":
            return "Reconciles playlist edits across Spotify, Apple Music, and ListenBrainz. Using Parachord elsewhere? Switch those devices to new sync too."
        default:
            return "Preview exactly what switching to the new sync engine would change — then accept, report a problem, or cancel. Nothing is armed until you accept."
        }
    }

    private func syncProviderName(_ id: String?) -> String {
        switch id {
        case "spotify": return "Spotify"
        case "listenbrainz": return "ListenBrainz"
        case "applemusic": return "Apple Music"
        default: return "service"
        }
    }
}

/// The one sheet the Sync tab can present — either a normal config or an enable
/// gate. Distinct `id`s so SwiftUI treats configure vs gate as different sheets.
private enum SyncConfigSheet: Identifiable {
    case configure(id: String, name: String)
    case gate(id: String, name: String)
    var id: String {
        switch self {
        case .configure(let i, _): return "cfg-\(i)"
        case .gate(let i, _): return "gate-\(i)"
        }
    }
}
