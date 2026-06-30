# Kickoff: implement the iOS SwiftUI sync settings

Paste-ready prompt for a fresh agent picking up the iOS port. The Kotlin
foundation is done; this is the SwiftUI work. Companion plan with file-level
detail: [`2026-06-30-ios-sync-settings-port.md`](./2026-06-30-ios-sync-settings-port.md).

---

**Implement the iOS SwiftUI sync settings — porting the Android work.**

You're picking up an in-progress port. The Kotlin foundation is done; your job is the SwiftUI.

**Start here, in order:**
1. `git checkout feat/ios-sync-settings` (the work branch; it's stacked on PR #311 — once #311 merges, rebase onto `main`).
2. Read `iosApp/CLAUDE.md` (a.k.a. `iosApp/AGENTS.md`) **completely** — it's the iOS rulebook (build/run/screenshot workflow, the pbxproj 4-entry rule for new Swift files, KMP→Swift bridging gotchas, the `@MainActor @Observable` VM + `FlowWatcher` + `IosContainer` architecture pattern). Non-negotiable.
3. Read `docs/plans/2026-06-30-ios-sync-settings-port.md` — the full port plan: what's already bridged, the current iOS start point, and the three SwiftUI phases.

**What's already done (don't redo):** `IosContainer` (`shared/src/iosMain/.../ios/IosContainer.kt`) exposes the shared surface — `watchAppleMusicReauthRequired`/`clearAppleMusicReauth`, `getSyncCollectionsForProvider`, `getProviderPickerRows` (the unified picker's live-fetch + dedup merge, returns flat `ProviderChannelPlaylist` rows), plus the pre-existing `watchSyncing`/`watchSyncPhase`/`isProviderSyncEnabled`/`setSyncProviderEnabled`/`setPlaylistChannel`. Compile-verified green.

**What to build (SwiftUI, phase by phase — the doc has file-level detail):**
- **Phase 1** — Sync as its own tab before General + a status header (global "Sync now" disabled while syncing AND when no service is enabled, staged progress label like "Syncing Apple Music albums… (N/M)", "Last synced …") + three consistent cards (Spotify/AM/LB: Switch + "Configure what syncs" + a "what's synced" summary). The current sync UI lives in `GeneralTab.syncSection` + `SyncModel` in `SettingsView.swift` — extract and rebuild.
- **Phase 2** — the unified "Configure what syncs" picker sheet over `getProviderPickerRows`/`setPlaylistChannel`: origin chips (Spotify green `#1DB954` / AM red `#FA243C` / LB orange `#EB743B`), sort, owner names, "Tap to import" for `notImported` rows; changes persist live (no Done button).
- **Phase 3** — toggle-ON opens the config sheet as an enable **gate** (sync enables only on Done, cancel reverts; toggle optimistically on); and a "Reconnect Apple Music" banner shown when `watchAppleMusicReauthRequired` is true (CTA re-mints the MUT, then `clearAppleMusicReauth()`).

**Reference the Android source of truth** (it's the spec): `app/.../ui/screens/settings/SettingsScreen.kt` (the cards + status header), `app/.../ui/screens/sync/{SyncViewModel,ProviderSyncConfigSheet}.kt` (the picker + the `enableProviderFromConfig` gate + `originChipColor`), `app/.../ui/components/AppleMusicReauthBanner.kt`. Match Android's behavior; the iOS design files (`docs/design/parachord-ios/`) are for visual polish — Android wins on conflicts.

**Verify every change:** `xcodebuild build -project <abs>/iosApp/Parachord.xcodeproj -scheme Parachord -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' -derivedDataPath <abs>/iosApp/build`, then install/launch on the iPhone 17 Pro simulator and `xcrun simctl io … screenshot` (then `sips -Z 1400` before viewing). For each new `.swift` file confirm `grep -c "<File>.swift" Parachord.xcodeproj/project.pbxproj` returns **4**. simctl can't drive the UI — use a temporary `.task { }` seed to screenshot a state, removed before committing.

**Constraints:** commit/push only when the user asks; stage specific files (never `git add -A`); end commit messages with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Work the phases one at a time, compile-verifying each.
