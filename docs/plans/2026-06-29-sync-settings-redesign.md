# Sync Settings redesign (parachord-mobile#303)

Approved redesign of the confusing sync settings. **Android first** (on-device verify), then port to iOS. Branch: `feat/303-sync-settings-redesign`.

## Current state (audit) — Android

Settings is a `SwipeableTabLayout` with 3 tabs (`SettingsScreen.kt:248`): **Plug-Ins** (`PlugInsTab` @337), **General** (`GeneralTab` @2672), **About** (@3276). Sync lives inside **General** (`GeneralTab` is a `LazyColumn` @2709):

- Appearance + Playback sections (2710-2743) — KEEP in General.
- **Spotify Sync** card (2745-2864): `Card` + `SectionHeader("Spotify Sync")`; enable `Switch` @2777; "Sync Now" filled green `#1DB954` @2818 (opens `SyncSetupSheet` wizard, providerId="spotify"); "Change sync settings" OutlinedButton @2833 (re-opens wizard); "Configure what syncs" OutlinedButton @2845 (opens `ProviderSyncConfigSheet`); "Stop syncing" error TextButton @2852; "Last synced …" @2792 (Spotify-only).
- **Apple Music Sync** card (2866-2964): filled red `#FA243C` CTA "Choose what to sync…" / "Set up Apple Music sync…" @2933; "Turn off Apple Music sync" @2949; sync state implicit (no explicit toggle); no last-sync time.
- **ListenBrainz Sync** (2966-3011): uses **`ListItem`** (not Card → header not bold, item 9) + `Switch` @2986; "Configure what syncs" TextButton @2998; no last-sync time.
- **Developer · sync engine** (DEBUG only, 3013-~3260): N-way preview / shadow / propagate toggles + migration preview. KEEP (dev-only) — move to bottom of Sync tab under a "Developer" subsection.
- Local state (2699-2707): `showSyncSetupSheet`, `syncSetupProviderId`, `showStopSyncDialog`, `configSyncProviderId`. Sheets rendered after the LazyColumn (`SyncSetupSheet`, `ProviderSyncConfigSheet`, stop dialog).

`SyncViewModel` surface to reuse: `syncEnabled`, `lastSyncAt`, `syncProgress: StateFlow<SyncEngine.SyncProgress>`, `syncResult`, `syncNow()` @386, `stopSyncing(removeItems)` @397, `openConfig(providerId)` @507 / `closeConfig()`, `supportedAxes(providerId)` @496, `isPullProvider`/`isPushProvider`, `configAxes`/`configPushable`/`configImported` (for the what's-synced summary). **No `isSyncing` flag yet** — derive from `syncProgress` phase or add one (set true in `syncNow`, false on result).

## Target layout (approved)

New **Sync tab inserted before General**: tabs = `["Plug-Ins", "Sync", "General", "About"]`; reindex the `when` (0 PlugIns, 1 SyncTab, 2 GeneralTab, 3 About).

```
┌ SYNC STATUS ─────────────────────────────┐
│ ⟳ Syncing… 23/178   (animated)  | ✓ Last synced 5m ago │
│              [ Sync now ]  (disabled mid-sync, global) │
└──────────────────────────────────────────┘
┌ Spotify ───────────────  [ toggle ] ┐
│ Syncing: Liked Songs · Albums · 12 playlists  (only when on) │
│ Last synced 5m ago                                           │
│ [ Configure what syncs ]   (one label, one style)            │
└──────────────────────────────────────────┘
┌ Apple Music ───────────  [ toggle ] ┐   (identical)
┌ ListenBrainz ──────────  [ toggle ] ┐   (Card + bold header now)
[ Developer · sync engine ]  (DEBUG only, bottom)
```

## Per-item resolution

1. **New Sync tab before General** — move the 3 cards + dev block into `SyncTab`; General keeps Appearance/Playback.
2. **Syncing animation + last-sync** — status header: animated indicator while `isSyncing`, else `Last synced {formatRelativeTime(lastSyncAt)}`; show per-card last-sync for ALL services (currently Spotify-only @2792).
3. **Disable Sync-now while syncing** — `Button(enabled = !isSyncing)`; add/derive `isSyncing` (today re-tapping hits `SyncEngine.SYNC_IN_PROGRESS`).
4. **"Stop syncing" ambiguous** — the per-service **toggle** IS enable/disable (`stopSyncing` on toggle-off, with a confirm dialog: "Stop mirroring to {service}?"); drop the separate ambiguous button.
5. **Sync button out of Spotify wizard** — single **global "Sync now"** in the status header → `syncViewModel.syncNow()` directly (no wizard). Wizard (`SyncSetupSheet`) stays for first-time setup only.
6. **Duplicate configure buttons** — one "Configure what syncs" per service → `openConfig(providerId)` / `ProviderSyncConfigSheet`; remove "Change sync settings" wizard re-entry.
7. **Inconsistent labels/style** — every service: label "Configure what syncs", same button style (OutlinedButton or TextButton — pick one).
8. **Inconsistent toggles** — every service gets the same enable `Switch` in the card header (Spotify→`syncEnabled`, AM→`appleMusicSyncEnabled`, LB→`listenBrainzSyncEnabled`).
9. **LB header not bold** — LB card uses `Card` + `SectionHeader` like the others (drop `ListItem`).
10. **What's-synced summary** — per enabled card: derive from `supportedAxes` + the persisted axis selection + playlist-selection count → "Liked Songs · Albums · N playlists".

## Implementation order

1. New `SyncTab` composable + tab wiring + move the 3 cards & dev block out of `GeneralTab` (verify build + on-device that the tab renders).
2. Unify the 3 cards (Card/header/toggle/single Configure button/consistent labels) — items 6,7,8,9.
3. Status header (animation + last-sync) + global Sync-now + `isSyncing` disable — items 2,3,5.
4. What's-synced summary — item 10.
5. Toggle-as-stop + confirm dialog — item 4.

## Android status — COMPLETE (PR #310)

All 10 items shipped across 5 launch-verified commits on `feat/303-sync-settings-redesign`:
tab split (1); unified cards LB→Card + label/style + drop dup buttons (6,7,9); status
header global Sync-now + animation + `isSyncing` disable (2,3,5); Apple Music Switch +
drop redundant stop buttons (4,8); per-card what's-synced summary via
`SyncViewModel.syncedSummaries` ← `getSyncCollectionsForProvider` (10).

## iOS port (status + plan)

**iOS is already ~80% aligned.** `iosApp/Parachord/SettingsView.swift`; sync UI in
`GeneralTab.syncSection`. The shared `SyncModel` already exposes `syncing` + `syncPhase`
(engine-driven via Combine watchers) — the status signal Android lacked exists natively.
**Already done on iOS (no work):** item 3 (`Sync now` `.disabled(!anyOn || syncing)`),
item 5 (one global `Sync now`, no wizard), item 8 (all 3 `syncToggle` rows consistent +
connection-gated), items 6/7 (one `configureRow` "Configure what syncs ›", same style),
item 4 (toggle IS enable/disable via `setProvider`/`setAppleMusic`/`confirmDisable`, no
separate stop button), item 2 partial (`Syncing… (phase)` text + `sync.status`).

**iOS gaps to close:**
1. **Item 1 — own tab.** `PCTabs(["Plug-ins","General","About"])` @525 →
   `["Plug-ins","Sync","General","About"]`; reindex `switch tab` (0 PlugIns, 1 SyncTab,
   2 General, 3 About). Extract `syncSection`/`syncToggle`/`configureRow` + the
   `@State SyncModel`/`configProvider` + the per-provider config sheet from `GeneralTab`
   into a new `SyncTab` struct; General keeps theme/scrobbling/queue + the sync-engine
   (migration preview) rows. Multi-member refactor — verify on simulator, don't do blind.
2. **Item 10 — what's-synced summary.** Replace each `syncToggle`'s static `desc` with
   the selected-axes summary when enabled. Add `SyncModel.summary(for:)` reading the
   shared `settingsStore.getSyncCollectionsForProvider(id)` (suspend → bridge via the
   existing async/`FlowWatcher` pattern) → "Liked Songs · Albums · Playlists". Mirrors
   Android's `syncedSummaries` + `formatAxesSummary`.
3. **Item 2 polish (optional).** Swap `Syncing… (phase)` text for a `ProgressView()`
   spinner; add per-row "Last synced …" (`SyncModel.status` is a coarse result string today).

**Verify via the simulator build/screenshot flow (`iosApp/AGENTS.md`).** Android impl
(PR #310) is the spec.
