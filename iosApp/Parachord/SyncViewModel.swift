import SwiftUI
import Shared
import StoreKit

/// Drives the Sync settings tab (port of Android `SyncViewModel` + the old iOS
/// `SyncModel`). Holds per-provider enabled state, the live "is syncing" +
/// staged progress label, per-provider "what's synced" summaries, and the
/// last-synced timestamp — all republished from the shared engine via
/// `IosContainer` Flow watchers.
@MainActor @Observable
final class SyncViewModel {
    private let container = IosContainer.companion.shared

    var spotifyOn = false
    var listenBrainzOn = false
    var appleMusicOn = false
    var appleMusicBusy = false        // acquiring the MUT

    var pendingDisable: String?       // provider awaiting a keep/remove choice
    var pendingEnable: String?        // provider awaiting Done in the enable-gate sheet
    var syncing = false               // shared engine state (any trigger)
    var progressLabel: String?        // "Syncing Apple Music albums… (12/40)"
    var lastSyncedText = "Never"      // "2 hours ago"
    var status: String?
    var spotifyCooldownMs: Int64 = 0
    /// providerId → "Liked Songs · Albums · Artists · Playlists"
    var syncedSummaries: [String: String] = [:]
    /// Per-client sync-engine mode: "legacy" | "shadow" | "new" (N-way migration).
    var engineMode = "legacy"

    private var pendingResync = false
    private var syncWatcher: Cancellable?
    private var progressWatcher: Cancellable?

    var anyOn: Bool { spotifyOn || listenBrainzOn || appleMusicOn }

    func enabled(_ id: String) -> Bool {
        switch id {
        case "spotify": return spotifyOn
        case "listenbrainz": return listenBrainzOn
        case "applemusic": return appleMusicOn
        default: return false
        }
    }

    /// Local read (no network) — "retry in 3h 12m" while Spotify is in a 429 cooldown.
    var spotifyCooldownText: String? {
        guard spotifyCooldownMs > 0 else { return nil }
        let mins = Int(spotifyCooldownMs / 60_000)
        let h = mins / 60, m = mins % 60
        let when_ = h > 0 ? "\(h)h \(m)m" : "\(max(m, 1))m"
        return "Spotify rate-limited — retry in \(when_)"
    }

    func load() async {
        spotifyOn = (try? await container.isProviderSyncEnabled(providerId: "spotify"))?.boolValue ?? false
        listenBrainzOn = (try? await container.isProviderSyncEnabled(providerId: "listenbrainz"))?.boolValue ?? false
        appleMusicOn = (try? await container.isProviderSyncEnabled(providerId: "applemusic"))?.boolValue ?? false
        spotifyCooldownMs = container.spotifyCooldownRemainingMs()
        engineMode = (try? await container.getSyncEngineMode()) ?? "legacy"
        await refreshSummaries()
        await refreshLastSynced()
        startWatching()
    }

    func refreshEngineMode() async {
        engineMode = (try? await container.getSyncEngineMode()) ?? "legacy"
    }

    /// Revert to the legacy engine (preview/accept handles legacy → new).
    func revertToLegacy() {
        engineMode = "legacy"
        Task { try? await container.setSyncEngineMode(mode: "legacy") }
    }

    func refreshSummaries() async {
        for id in ["spotify", "applemusic", "listenbrainz"] {
            let axes = (try? await container.getSyncCollectionsForProvider(providerId: id)) ?? []
            syncedSummaries[id] = SyncViewModel.axesSummary(axes)
        }
    }

    func refreshLastSynced() async {
        let ms = (try? await container.lastSyncAtMs())?.int64Value ?? 0
        lastSyncedText = SyncViewModel.relativeTime(ms)
    }

    private func startWatching() {
        guard syncWatcher == nil else { return }
        syncWatcher = container.watchSyncing { [weak self] running in
            Task { @MainActor in
                guard let self else { return }
                self.syncing = running.boolValue
                if !running.boolValue {
                    await self.refreshLastSynced()
                    if self.pendingResync {
                        self.pendingResync = false
                        await self.syncNow()
                    }
                }
            }
        }
        progressWatcher = container.watchSyncProgress { [weak self] label in
            Task { @MainActor in self?.progressLabel = label as String? }
        }
    }

    func stopWatching() {
        syncWatcher?.cancel(); syncWatcher = nil
        progressWatcher?.cancel(); progressWatcher = nil
    }

    /// Toggle a provider. ON opens the config sheet as an ENABLE GATE — sync only
    /// enables when the user taps Done ([confirmEnable]); cancelling reverts
    /// ([cancelEnable]). OFF tears down via the keep/remove prompt.
    func toggle(_ id: String, _ on: Bool) {
        if on { requestEnable(id) } else { disable(id) }
    }

    /// Toggle optimistically ON and open the config-sheet gate. Apple Music
    /// acquires its Music User Token first (the gate's playlist list needs it);
    /// if the user denies authorization, revert without opening the sheet.
    func requestEnable(_ id: String) {
        if id == "applemusic" {
            Task {
                appleMusicBusy = true; status = nil
                let ok = await ensureAppleMusicMut()
                appleMusicBusy = false
                if ok { pendingEnable = "applemusic" }
                else { status = "Apple Music authorization failed" }
            }
        } else {
            pendingEnable = id
        }
    }

    /// Commit the gated enable (config sheet Done): flip the provider on, persist,
    /// and kick a sync. `setSyncProviderEnabled` also writes Spotify's legacy
    /// SyncSettings, so no separate call is needed.
    func confirmEnable() {
        guard let id = pendingEnable else { return }
        pendingEnable = nil
        switch id {
        case "spotify": spotifyOn = true
        case "listenbrainz": listenBrainzOn = true
        case "applemusic": appleMusicOn = true
        default: break
        }
        Task {
            try? await container.setSyncProviderEnabled(providerId: id, enabled: true)
            await refreshSummaries()
            await syncNow()
        }
    }

    /// Cancel the gate (sheet dismissed without Done): revert the optimistic ON.
    func cancelEnable() { pendingEnable = nil }

    /// Disable a provider — flip off optimistically, prompt keep-or-remove.
    func disable(_ id: String) {
        switch id {
        case "spotify": spotifyOn = false
        case "listenbrainz": listenBrainzOn = false
        case "applemusic": appleMusicOn = false
        default: break
        }
        pendingDisable = id
    }

    /// Ensure the Apple Music Music User Token is present (acquire via StoreKit if
    /// not). Returns false if the user denies authorization.
    private func ensureAppleMusicMut() async -> Bool {
        let has = (try? await container.hasAppleMusicUserToken())?.boolValue ?? false
        if has { return true }
        let dev = (try? await container.appleMusicDeveloperToken()) ?? ""
        if let mut = await acquireAppleMusicMUT(developerToken: dev), !mut.isEmpty {
            try? await container.setAppleMusicUserToken(token: mut)
            return true
        }
        return false
    }

    /// Resolve a pending disable with the user's keep/remove choice. Clears the
    /// pending state FIRST so the dialog's dismissal binding doesn't double-fire.
    func confirmDisable(removeItems: Bool) {
        guard let id = pendingDisable else { return }
        pendingDisable = nil
        if id == "spotify" { spotifyOn = false }
        else if id == "listenbrainz" { listenBrainzOn = false }
        else if id == "applemusic" { appleMusicOn = false }
        Task { try? await container.disableSyncProvider(providerId: id, removeItems: removeItems) }
    }

    /// Apple Music needs a Music User Token for the library API. Acquire it via
    /// StoreKit the first time the user enables sync; revert the toggle if denied.
    func setAppleMusic(_ on: Bool) {
        if !on {
            appleMusicOn = false
            pendingDisable = "applemusic"
            return
        }
        Task {
            appleMusicBusy = true; status = nil
            let hasMut = (try? await container.hasAppleMusicUserToken())?.boolValue ?? false
            if !hasMut {
                let devToken = (try? await container.appleMusicDeveloperToken()) ?? ""
                if let mut = await acquireAppleMusicMUT(developerToken: devToken), !mut.isEmpty {
                    try? await container.setAppleMusicUserToken(token: mut)
                } else {
                    appleMusicBusy = false
                    appleMusicOn = false
                    status = "Apple Music authorization failed"
                    return
                }
            }
            try? await container.setSyncProviderEnabled(providerId: "applemusic", enabled: true)
            appleMusicOn = true
            appleMusicBusy = false
            await refreshSummaries()
            await syncNow()
        }
    }

    func syncNow() async {
        guard anyOn else { return }
        status = nil
        let err = (try? await container.syncNow()) ?? "Sync error"   // "" = success
        spotifyCooldownMs = container.spotifyCooldownRemainingMs()
        if err == container.syncInProgressMessage {
            pendingResync = true
            return
        }
        status = err.isEmpty ? "Synced ✓" : "Failed: \(err)"
        await refreshLastSynced()
    }

    // ── Formatting helpers (match Android) ───────────────────────────────
    static func axesSummary(_ axes: Set<String>) -> String {
        var parts: [String] = []
        if axes.contains("tracks") { parts.append("Liked Songs") }
        if axes.contains("albums") { parts.append("Albums") }
        if axes.contains("artists") { parts.append("Artists") }
        if axes.contains("playlists") { parts.append("Playlists") }
        return parts.joined(separator: " · ")
    }

    static func relativeTime(_ ms: Int64) -> String {
        if ms == 0 { return "Never" }
        let diff = Int64(Date().timeIntervalSince1970 * 1000) - ms
        if diff < 60_000 { return "Just now" }
        if diff < 3_600_000 { return "\(diff / 60_000) minutes ago" }
        if diff < 86_400_000 { return "\(diff / 3_600_000) hours ago" }
        return "\(diff / 86_400_000) days ago"
    }
}

/// Acquire the Apple Music **Music User Token** (library Web API) via StoreKit —
/// `SKCloudServiceController.requestUserToken(forDeveloperToken:)`. Distinct from
/// the MusicKit playback token. Returns nil on denial/error.
func acquireAppleMusicMUT(developerToken: String) async -> String? {
    guard !developerToken.isEmpty else { return nil }
    let current = SKCloudServiceController.authorizationStatus()
    let status: SKCloudServiceAuthorizationStatus
    if current == .notDetermined {
        status = await withCheckedContinuation { cont in
            SKCloudServiceController.requestAuthorization { cont.resume(returning: $0) }
        }
    } else {
        status = current
    }
    guard status == .authorized else { return nil }
    let controller = SKCloudServiceController()
    return await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
        controller.requestUserToken(forDeveloperToken: developerToken) { token, error in
            cont.resume(returning: error == nil ? token : nil)
        }
    }
}
