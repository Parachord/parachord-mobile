# iOS Sync Settings port (mirror Android PR #310 + #311)

Port the Android sync-settings work to the SwiftUI app. **Status: Kotlin
foundation DONE + compile-verified (`:shared:compileKotlinIosSimulatorArm64`
green). The SwiftUI (Phases 1–3) is the focused next pass.** Branch:
`feat/ios-sync-settings` (stacked on #311 — depends on the shared APIs added there).

## What Android shipped (the target)

- **#303 redesign (PR #310, merged):** Sync is its OWN tab before General; a
  status header with one global **Sync now** (disabled while syncing AND when no
  service is enabled), animated progress that NAMES the stage
  ("Syncing Apple Music albums… (N/M)"; Spotify tracks = "Spotify Liked Songs"),
  "Last synced …" when idle; three CONSISTENT cards (Spotify/AM/LB) each with a
  Switch + one "Configure what syncs" button; a per-card "what's synced" summary.
- **Unified picker + AM reauth (PR #311):** the "Configure what syncs" sheet is
  ONE provider-agnostic picker backed by the per-playlist CHANNEL OVERRIDE
  (`PlaylistSyncChannelManager.getProviderChannelPlaylists`/`setChannel` — same
  authoritative state as the per-playlist Sync menu; disable detaches, never
  deletes). Shows native imports + push candidates + current mirrors +
  live-fetched not-yet-imported native playlists ("Tap to import"), deduped
  against native rows + `linkedExternalIdsForProvider` + same-name local rows so a
  playlist never appears twice; origin chips (Spotify green `#1DB954` / AM red
  `#FA243C` / LB orange `#EB743B`) + sort + owner names.
- **Three follow-ups:** (a) toggle-ON opens the config sheet as a GATE — sync
  enables only on Done, cancel reverts (toggle optimistically ON meanwhile);
  (b) "Sync now" disabled when no service enabled; (c) "Reconnect Apple Music"
  banner — `SyncEngine.appleMusicReauthRequired` (StateFlow, commonMain) set when
  an AM library call 401s (stale MUT), self-clears on a successful AM fetch.

## Foundation — DONE (this commit)

`shared/src/iosMain/.../ios/IosContainer.kt` now bridges everything the SwiftUI
needs (the complex live-fetch + dedup merge stays in shared Kotlin so Swift
renders flat rows):

- `watchAppleMusicReauthRequired(onEach:) -> Cancellable` (FlowWatcher on
  `syncEngine.appleMusicReauthRequired`) + `clearAppleMusicReauth()`.
- `getSyncCollectionsForProvider(providerId) -> Set<String>` (for the summary).
- `getProviderPickerRows(providerId) -> List<ProviderChannelPlaylist>` — the
  unified picker: local channel rows + (pull providers) live-fetched not-imported,
  deduped against native + `linkedExternalIdsForProvider` + same-name local names.
  Mirrors Android `SyncViewModel.openConfig`.
- Already present + reused: `watchSyncing`, `watchSyncPhase`, `isProviderSyncEnabled`,
  `setSyncProviderEnabled`, `setPlaylistChannel` (kicks a sync on enable),
  `stopProviderSync`, `countItemsForProviderAxis`/`removeItemsForProviderAxis`.

`ProviderChannelPlaylist` fields are all Swift-safe (no `description`/`hash`).

## SwiftUI — the next pass (Phases 1–3)

Follow `iosApp/AGENTS.md`: `@MainActor @Observable` VM over `IosContainer`,
FlowWatcher → `@Observable` state (store the `Cancellable`, `.cancel()` in
`onDisappear`), suspend setters from `Task {}`. **Every new `.swift` file needs
the 4 pbxproj entries (`grep -c "<File>.swift" project.pbxproj` MUST be 4).**
Build: `xcodebuild … -scheme Parachord -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5'`.

**Current iOS start point:** the sync UI is `GeneralTab.syncSection` in
`SettingsView.swift` with a `SyncModel` (`@Observable`) — it already has
syncToggle / configureRow / Sync-now and watches `watchSyncing`/`watchSyncPhase`,
but Sync is NOT its own tab, there's no status header, no summary, no unified
picker, no toggle-gate, no reauth banner. Extract + rebuild.

- **Phase 1 — tab + header + cards + summary.** New `SyncSettingsView.swift`,
  `SyncViewModel.swift` (`isSyncing` via `watchSyncing`; staged progress label —
  expose a `SyncProgress`-shaped watcher or reuse `watchSyncPhase` + add
  current/total; `syncedSummaries[provider]` via `getSyncCollectionsForProvider`),
  `SyncCardView.swift`, `SyncProgressView.swift`. Add a `.tag()`ed Sync tab in
  `RootView.swift` before General. Sync-now `disabled` = `isSyncing || noProviderEnabled`.
- **Phase 2 — unified picker.** New `ProviderConfigSheet.swift` +
  `ProviderConfigModel.swift`: `.task { rows = await container.getProviderPickerRows(pid) }`
  (cancel on disappear), render rows with origin chips + sort + owner + "Tap to
  import" (notImported), toggle via `container.setPlaylistChannel(localId, pid, on)`
  then reload. No Done button — changes persist live.
- **Phase 3 — gate + banner.** Toggle-ON opens the config sheet, enable only on
  Done (mirror Android `pendingEnableProviderId` + `enableProviderFromConfig` — on
  iOS use `setSyncProviderEnabled` for AM/LB; Spotify also needs the legacy
  `saveSyncSettings` — see Android `SyncViewModel.enableProviderFromConfig`).
  New `AppleMusicReauthBanner.swift` at the top of `SyncSettingsView`, shown when
  `watchAppleMusicReauthRequired` is true; CTA re-mints the MUT (the existing AM
  authorize / `acquireAppleMusicMUT` flow) then `clearAppleMusicReauth()`.

**Bridging traps (from AGENTS.md):** extract big initializers to standalone funcs
(type-checker); FlowWatcher emissions erase to `Any?` — cast
(`(v as? KotlinBoolean)?.boolValue`); `fetchPlaylists()` is suspend (already
wrapped in `getProviderPickerRows`); store + cancel every FlowWatcher.

Full file-level plan + risks: the `ios-sync-settings-port-plan` workflow output
in this session's transcript.
