# iOS Queue Reorder + Persistence (#220 remaining)

**Issue #220 acceptance:** queue rows support remove + **drag-reorder** driving the shared `QueueManager`; queue/shuffle/context state **persists and restores** on relaunch.

**Already done (merged `9bcb34b`):** remove-from-queue (`coordinator.removeFromQueue` → `queueManager.removeFromQueue`, wired via the track context menu in `PCQueuePanel`).

**Remaining:** drag-reorder + persistence. Source of truth: Android `QueueManager.kt` (shared — already has `moveInQueue`/`restoreState`), `QueuePersistence.kt` + `QueueState.kt` (app-only), `SettingsStore` (shared — `persistQueue` / `get|set|clearPersistedQueueState`). iOS: `QueuePlaybackCoordinator` (`ContentView.swift`), `PCQueuePanel` (`NowPlayingScreen.swift`).

## What's already shared (iOS reuses, no Android changes)
- `QueueManager.moveInQueue(from,to)`, `removeFromQueue`, `restoreState(...)`, `history`, `savedOriginalOrder`, `snapshot` (`QueueSnapshot{upNext, playbackContext, shuffleEnabled}`).
- `SettingsStore.persistQueue` (Flow, default **true**), `isPersistQueueEnabled()`, `get/set/clearPersistedQueueState()` (KvStore-backed → works on iOS).
- Android's `PersistedQueueState`/`SerializableTrack` live in `app/` only; iOS gets its own shared serializable model (queue state is per-device, never synced — no need to match Android's bytes).

## Part A — Drag-to-reorder
1. **Coordinator** (`ContentView.swift`): add
   `func moveInQueue(from: Int, to: Int) { queueManager.moveInQueue(fromIndex: Int32(from), toIndex: Int32(to)); syncSnapshot(); persistSoon() }`.
2. **`PCQueuePanel`** (`NowPlayingScreen.swift`): the up-next rows are a `LazyVStack` in a `ScrollView`. Convert to a styled SwiftUI `List` so `.onMove` (native drag-reorder) + `.onDelete` (swipe-remove, complements the existing context-menu remove) work. Preserve the dark look with `.listStyle(.plain)`, `.scrollContentBackground(.hidden)`, `.listRowBackground(.clear)`, `.listRowSeparator(.hidden)`, `.listRowInsets(EdgeInsets())`. Keep the custom header (grabber + Clear + close) above the List. `.onMove` → translate SwiftUI `toOffset` (pre-removal) to `QueueManager`'s post-removal index (`to = dest > from ? dest-1 : dest`).
   - If plain-`List` long-press drag isn't reliable without EditMode, add a small "Edit" toggle in the header (Apple-Music idiom).

## Part B — Persistence
1. **Shared model** — make `Track` + `PlaybackContext` `@Serializable` (additive; both are plain data classes). Add `shared/commonMain/.../playback/QueuePersistence.kt`: `@Serializable data class PersistedQueueState(currentTrack: Track?, upNext, playHistory, playbackContext, shuffleEnabled, originalOrder)` + `encode(...)->String` / `decode(json)->PersistedQueueState?` using a shared `Json { ignoreUnknownKeys = true }`. (Holds `Track` directly — no `SerializableTrack` DTO needed once `Track` is serializable.)
2. **Save** — `QueuePlaybackCoordinator.persistSoon()`: debounced (~500 ms, cancel-previous Task) snapshot save, gated on `settingsStore.isPersistQueueEnabled()`. Build state from `currentTrack`, `queueManager.snapshot.value` (upNext/context/shuffle), `queueManager.history`, `queueManager.savedOriginalOrder`; `settingsStore.setPersistedQueueState(encode(...))`. Call from every mutation (`setQueue`, `skipNext/Previous`, `playFromQueue`, `removeFromQueue`, `moveInQueue`, `addToQueue`, `insertNext`, `toggleShuffle`, `clearQueue`) + on currentTrack change.
3. **Restore** — on launch (`AppPlayback.init` / coordinator): if enabled and a saved state exists, `queueManager.restoreState(...)`, set `currentTrack` **paused** (don't auto-play — Android parity), `syncSnapshot()`.
4. **Setting** — honor existing `persistQueue` (default true). Add a "Remember queue" toggle to iOS Settings if quick; otherwise default-on is acceptable parity.

## Milestones
- **A**: reorder (coordinator + `PCQueuePanel` List) → build + on-device drag verify.
- **B**: persistence (serializable model + save/restore + setting) → build + relaunch-restore verify.
- **Verify**: `:shared` iOS compile + full iOS build green, zero new warnings; drag-reorder reorders the queue; kill + relaunch restores queue/current/shuffle.

## Notes / risks
- Don't auto-play the restored track (paused, Android parity).
- `QueueManager.snapshot.value` bridges to Swift as `Any?` — cast to `QueueSnapshot` (existing `syncSnapshot` pattern).
- Making `Track` `@Serializable` is broadly useful and low-risk, but rebuild Android too to confirm no fallout (shared module).
