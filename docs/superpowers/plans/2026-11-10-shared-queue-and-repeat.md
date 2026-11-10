# Shared queue and repeat (PR 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One shared rule set for what plays after a track ends, after Next, and after Previous; repeat off/all/one on both platforms; the end of the queue clears the last track; a track with no source is skipped during an advance when online.

**Architecture:** Pure functions in `shared/commonMain/.../playback/QueueAdvancePolicy.kt` answer "what next?" from a few inputs; `QueueManager` gains the repeat-ALL wrap. Android's `PlaybackController` and iOS's `QueuePlaybackCoordinator` ask the policy and act on its answer. Engines, timers and polling stay per platform.

**Tech Stack:** Kotlin Multiplatform (commonMain + commonTest, JVM and iosSimulatorArm64), Android Media3 + Compose, SwiftUI.

**Spec:** `docs/superpowers/specs/2026-11-10-shared-playback-session-rules-design.md` (Area 1).

## Global Constraints

- Shared code: no JVM-only APIs; no `,` `.` `;` `:` `/` `<` `>` `[` `]` `\` in commonTest backtick names. Run `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test` after every shared change.
- Swift: Kotlin enums bridge as classes with lower-camel cases (`RepeatMode.one`), top-level functions as `QueueAdvancePolicyKt.fn(...)`, constants likewise.
- Previous restarts the current track when the position is **more than 3 000 ms** (strictly greater), as both apps do today.
- Repeat cycles **off → all → one → off**.
- Under ONE, a track that **ends** replays; **Next** still advances.
- Repeat ALL wraps to **everything in this queue** in played order (history, current, Up Next, including added tracks); Next on the last track wraps too; with shuffle on, the wrapped set is reshuffled.
- With repeat off, the end of the queue **stops and clears the current track** on both platforms.
- A track with no playable source, during an advance or spinoff: **skipped when online**, playback **stops on it when offline** (`START_FAILED`, the existing #573 rule). A tapped no-source track on iOS keeps its current behaviour in this PR (the spec moves it in PR 4).
- Don't change the background-survival rules (root CLAUDE.md "External Playback Background Survival"): never `ctrl.stop()` between two external tracks, and never touch the ExoPlayer `repeatMode` used by the silence loop for Parachord's repeat.

## Review Focus

1. **Repeat ALL with a queue where every track is unplayable** must not loop forever: an unplayable skip at the end of the queue stops instead of wrapping (Task 1 test `an unplayable skip at the end never wraps`).
2. **Repeat ONE with a track that fails to play** must not replay it forever: only `TRACK_ENDED` replays (Task 1 test `only a track that ended replays under repeat one`).
3. **Android after the queue ends and the track is cleared**: Play, the notification, Android Auto and the widget must cope with no current track without crashing (Task 3 device check step).
4. **The media session's repeat command in external mode** (Spotify, Apple Music, embeds) must change Parachord's repeat, not the ExoPlayer silence loop's (Task 4 test `setRepeatMode does not touch the delegate`).
5. **A Listen Along session when our song ends** (Android: `MainViewModel.onTrackEnded` plays the friend's pending track from the listener, then `skipNextInternal` reaches an empty queue): the screen must not be cleared, or it races the friend's track and blanks the waiting state. While `playbackContext?.type == "listen-along"`, `STOP_AND_CLEAR` falls back to today's stop-without-clear on both platforms (Task 3 and Task 5 device/simulator checks). PR 3 replaces this with the shared Listen Along machine.
6. **Wrap with shuffle on and a one-track queue** returns that track and an empty Up Next (Task 2 test `wrapping a single track replays it`).

---

### Task 1: Shared advance and previous rules

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/playback/QueueAdvancePolicy.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/playback/PlayFailure.kt` (add `NO_SOURCE`, `PLAY_NO_SOURCE_MESSAGE`)
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/playback/QueueAdvancePolicyTest.kt`, extend `PlayFailureTest.kt`

**Interfaces:**
- Produces:
  - `enum class AdvanceTrigger { TRACK_ENDED, NEXT_PRESSED, SKIPPED_UNPLAYABLE }`
  - `enum class AdvanceAction { PLAY_NEXT, REPLAY_CURRENT, WRAP_TO_START, STOP_AND_CLEAR }`
  - `fun advanceAction(trigger: AdvanceTrigger, repeatMode: RepeatMode, hasNext: Boolean, hasCurrent: Boolean): AdvanceAction`
  - `enum class PreviousAction { RESTART_CURRENT, PLAY_PREVIOUS }`
  - `const val PREVIOUS_RESTARTS_AFTER_MS = 3_000L`
  - `fun previousAction(positionMs: Long, hasPrevious: Boolean): PreviousAction`
  - `fun nextRepeatMode(mode: RepeatMode): RepeatMode`
  - `PlayFailure.NO_SOURCE` (skips during an advance or spinoff, like `EMBED_REFUSED`)
  - `const val PLAY_NO_SOURCE_MESSAGE = "Couldn't find a playable source for this track."`
- The spinoff pool is handled by the platforms before they ask the policy (unchanged in this PR); the policy only sees the queue.

Rules for `advanceAction`, in order: `TRACK_ENDED` + `ONE` + `hasCurrent` → `REPLAY_CURRENT`; `hasNext` → `PLAY_NEXT`; `ALL` and trigger is not `SKIPPED_UNPLAYABLE` → `WRAP_TO_START`; otherwise `STOP_AND_CLEAR`.
`previousAction`: `positionMs > PREVIOUS_RESTARTS_AFTER_MS` or `!hasPrevious` → `RESTART_CURRENT`, else `PLAY_PREVIOUS`.

- [ ] **Step 1: Write the failing tests** in `QueueAdvancePolicyTest`:
  - `a track that ended plays the next one` — `(TRACK_ENDED, OFF, hasNext=true, hasCurrent=true)` → `PLAY_NEXT`
  - `only a track that ended replays under repeat one` — `(TRACK_ENDED, ONE, true, true)` → `REPLAY_CURRENT`; `(NEXT_PRESSED, ONE, true, true)` → `PLAY_NEXT`; `(SKIPPED_UNPLAYABLE, ONE, true, true)` → `PLAY_NEXT`
  - `the end of the queue stops and clears with repeat off` — `(TRACK_ENDED, OFF, false, true)` and `(NEXT_PRESSED, OFF, false, true)` → `STOP_AND_CLEAR`
  - `repeat all wraps at the end — ended or Next` — `(TRACK_ENDED, ALL, false, true)` and `(NEXT_PRESSED, ALL, false, true)` → `WRAP_TO_START`
  - `an unplayable skip at the end never wraps` — `(SKIPPED_UNPLAYABLE, ALL, false, true)` → `STOP_AND_CLEAR`
  - `repeat one with nothing current falls through` — `(TRACK_ENDED, ONE, true, false)` → `PLAY_NEXT`
  - `previous restarts after 3 seconds` — `previousAction(3_001, true)` → `RESTART_CURRENT`; `previousAction(3_000, true)` → `PLAY_PREVIOUS`
  - `previous with nothing before restarts` — `previousAction(0, false)` → `RESTART_CURRENT`
  - `repeat cycles off all one` — `nextRepeatMode(OFF)` = `ALL`, `(ALL)` = `ONE`, `(ONE)` = `OFF`

  In `PlayFailureTest`: `a track with no source is skipped while the queue advances` — `failedPlayAwaitsLoad(NO_SOURCE, advancing=true, spinoff=false)` false, `(NO_SOURCE, false, true)` false, `(NO_SOURCE, false, false)` true. Update the existing "held" test to exclude `NO_SOURCE` along with `EMBED_REFUSED`.

- [ ] **Step 2: Run to see them fail**
  Run: `./gradlew :shared:testDebugUnitTest --tests '*QueueAdvancePolicyTest*' --tests '*PlayFailureTest*'`
  Expected: compilation failure (`advanceAction` unresolved).

- [ ] **Step 3: Implement** `QueueAdvancePolicy.kt` with the signatures above, and in `PlayFailure.kt` add `NO_SOURCE` (KDoc: no resolver found a playable source while online) and make `failedPlayAwaitsLoad` return false for it under `advancing || spinoffMode`. Update its KDoc.

- [ ] **Step 4: Run the tests on both targets**
  Run: `./gradlew :shared:testDebugUnitTest --tests '*QueueAdvancePolicyTest*' --tests '*PlayFailureTest*' :shared:iosSimulatorArm64Test --tests '*QueueAdvancePolicyTest*' --tests '*PlayFailureTest*'`
  Expected: PASS.

- [ ] **Step 5: Commit** `feat(shared): queue advance, previous and repeat rules`

### Task 2: Repeat-ALL wrap in QueueManager

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/playback/QueueManager.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/playback/QueueManagerWrapTest.kt`

**Interfaces:**
- Produces:
  - `fun wrapToStart(currentTrack: Track?): Track?` — gathers history + `currentTrack` (if non-null) + Up Next in that order; empty → null. Without shuffle: returns the first, Up Next = the rest. With shuffle on: shuffles the whole set, returns the first, Up Next = the rest, `originalOrder` = the rest in played order. History is cleared. Context and shuffle flag are kept. Emits a snapshot.
  - `val hasNext: Boolean` (Up Next not empty), `val hasPrevious: Boolean` (history not empty).

- [ ] **Step 1: Write the failing tests** (`QueueManagerWrapTest`, tracks built with the shared `Track` constructor):
  - `wrapping returns the first track played and queues the rest` — `setQueue([a,b,c], 0)`, `skipNext(a)` → b, `skipNext(b)` → c; `wrapToStart(c)` returns a, `snapshot.upNext` = [b, c], `hasPrevious` false.
  - `added tracks are part of the wrap` — `setQueue([a,b], 0)`, `addToQueue([x])`, advance to x; `wrapToStart(x)` → a, Up Next [b, x].
  - `wrapping with shuffle on keeps every track once` — shuffle on, three played tracks; result + Up Next contain exactly {a,b,c}; `savedOriginalOrder` = the non-first tracks in played order.
  - `wrapping a single track replays it` — `setQueue([a], 0)`; `wrapToStart(a)` → a, Up Next empty (also with shuffle on).
  - `wrapping an empty queue returns null` — fresh manager, `wrapToStart(null)` → null.
  - `hasNext and hasPrevious follow the queue`.

- [ ] **Step 2: Run to see them fail** — `./gradlew :shared:testDebugUnitTest --tests '*QueueManagerWrapTest*'` → compile failure.

- [ ] **Step 3: Implement** `wrapToStart`, `hasNext`, `hasPrevious` in `QueueManager`.

- [ ] **Step 4: Run on both targets** — `./gradlew :shared:testDebugUnitTest --tests '*QueueManagerWrapTest*' :shared:iosSimulatorArm64Test --tests '*QueueManagerWrapTest*'` → PASS. Also run the Android `QueueManagerTest` (`./gradlew :app:testDebugUnitTest --tests '*QueueManagerTest*'`) → PASS.

- [ ] **Step 5: Commit** `feat(shared): QueueManager wraps the whole queue for repeat all`

### Task 3: Android controller — repeat, end of queue, Previous, no source

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackState.kt` (drop the app's own `enum class RepeatMode`, use `com.parachord.shared.playback.RepeatMode` via `typealias RepeatMode = com.parachord.shared.playback.RepeatMode` so existing imports keep working)
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt` (`skipNext`, `skipNextInternal` ~L412-496, `skipPrevious` ~L498-527, the `Player.STATE_ENDED` and external end-of-track call sites ~L1275, 1360, 1414, 1608, 1661, the embed-refused auto-skip ~L2563-2571, and `if (action == null)` in `playTrackInternalUnsafe` ~L872)
- Modify: `app/src/main/java/com/parachord/android/di/AndroidModule.kt` only if the new constructor parameter needs it (`singleOf(::PlaybackController)` resolves `NetworkMonitor` from the graph; keep `KoinModuleVerifyTest` passing)

**Interfaces:**
- Consumes: Task 1 (`advanceAction`, `AdvanceTrigger`, `AdvanceAction`, `previousAction`, `nextRepeatMode`, `PlayFailure.NO_SOURCE`, `PLAY_NO_SOURCE_MESSAGE`, `PLAY_START_FAILED_MESSAGE`), Task 2 (`wrapToStart`, `hasNext`, `hasPrevious`).
- Produces (used by Task 4):
  - `fun cycleRepeatMode()` and `fun setRepeatMode(mode: RepeatMode)` on `PlaybackController`; both write `PlaybackState.repeatMode`.
  - `skipNextInternal(trigger: AdvanceTrigger)` replaces `skipNextInternal(userInitiated: Boolean)`: `NEXT_PRESSED` for `skipNext()`; `TRACK_ENDED` for every end-of-track detection (ExoPlayer `STATE_ENDED`, Spotify `isOurTrackDone`, Apple Music JS callback and safety net, embed `onFinished`); `SKIPPED_UNPLAYABLE` for the embed-refused and no-source auto-skips. `userInitiated` (listeners, `userRequested`) = `trigger == NEXT_PRESSED`.
  - New constructor parameter `networkMonitor: NetworkMonitor` (`com.parachord.android.data.network.NetworkMonitor`).

Behaviour:
- Spinoff pool handling stays first, unchanged. Then ask `advanceAction(trigger, repeatMode, queueManager.hasNext, currentTrack != null)`:
  - `PLAY_NEXT` → today's path (`queueManager.skipNext` + `playTrackInternal(advancing = true, ...)`).
  - `REPLAY_CURRENT` → `playTrackInternal(currentTrack, advancing = true, userRequested = false)`.
  - `WRAP_TO_START` → `queueManager.wrapToStart(currentTrack)` then play it with `advancing = true`; null → treat as `STOP_AND_CLEAR`.
  - `STOP_AND_CLEAR` while `stateHolder.state.value.playbackContext?.type == "listen-along"` → today's behaviour exactly (stop, `isPlaying = false`, keep the track).
  - `STOP_AND_CLEAR` otherwise → today's external stop block, plus for ExoPlayer `controller?.stop()` and `controller?.clearMediaItems()`, `loadOnPlay = null`, and `stateHolder.update { copy(currentTrack = null, isPlaying = false, isBuffering = false, position = 0L, duration = 0L, playbackError = null) }`; then `syncQueueState()` so the cleared state is saved.
- `skipPrevious`: position as today (external handler or `controller.currentPosition`); `previousAction(position, queueManager.hasPrevious)`: `RESTART_CURRENT` → seek to 0 (external: `activeExternalHandler.seekTo(0)`; ExoPlayer: `ctrl.seekTo(0)`); `PLAY_PREVIOUS` → today's path.
- `action == null` (no handler for the routed track): don't call `playViaExoPlayer`. `playFailure = if (networkMonitor.isOnline.value) PlayFailure.NO_SOURCE else PlayFailure.START_FAILED`; update state `isPlaying = false, isBuffering = false, playbackError = PLAY_START_FAILED_MESSAGE (offline) or PLAY_NO_SOURCE_MESSAGE (online)`. If `!failedPlayAwaitsLoad(failure, advancing, spinoffMode)`, auto-skip like the embed-refused path: clear `playbackError`, then after 600 ms `skipNextInternal(SKIPPED_UNPLAYABLE)`. Return. `playTrackInternal`'s `finally` already turns a held failure into `LoadOnPlay` `FAILED`.

- [ ] **Step 1: Make the change** as above. Rename the trigger at each call site listed under Files; leave the embed-refused logic (`playAfterEmbedRefused`) otherwise as is.

- [ ] **Step 2: Build and run unit tests**
  Run: `cd <worktree> && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests '*KoinModuleVerifyTest*' --tests '*LoadOnPlayTest*' --tests '*QueueManagerTest*' --tests '*Playback*'`
  Expected: BUILD SUCCESSFUL, tests pass.

- [ ] **Step 3: Check on an Android device or emulator** (`./gradlew installDebug`, force-stop `com.parachord.android.debug`; adb at `/Users/jherskowitz/Library/Android/sdk/platform-tools/adb`; logcat tag `PlaybackController`):
  - Queue of 3, repeat off: the last track ends → logcat shows the stop, the mini player disappears, the notification goes away, Play does nothing and nothing crashes. Do it once with an external source (Spotify or Apple Music) and once with a local file.
  - Previous at 1 s on the first track restarts it; at 5 s on any track restarts it; at 1 s on track 2 goes to track 1.
  - Listen Along with a friend: when our song ends the friend's next track plays and the screen isn't blanked in between.
  - A queue whose middle track has no source (e.g. a made-up title from a weekly playlist): online it is skipped after ~600 ms; offline (airplane mode) it stops with "Couldn't start playing. Check your connection and try again." and Play retries it once online.

- [ ] **Step 4: Commit** `feat(android): shared queue rules — end of queue clears, Previous restarts, no-source tracks skip online`

### Task 4: Android repeat button and media-session repeat

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/screens/nowplaying/NowPlayingScreen.kt` (beside the shuffle button ~L470) and its view model (`cycleRepeatMode()` forwarding to `PlaybackController`, `repeatMode` from `PlaybackState`)
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackService.kt` (`ExternalPlaybackForwardingPlayer` ~L1359)
- Test: `app/src/test/java/com/parachord/android/playback/ForwardingPlayerRepeatTest.kt` (Robolectric, same setup as `ForwardingPlayerLoadOnPlayTest`)

**Interfaces:**
- Consumes: Task 3 (`PlaybackController.cycleRepeatMode()`, `setRepeatMode(mode)`, `PlaybackState.repeatMode`).
- Produces: none for later tasks.

Behaviour:
- Button: `Icons.Rounded.Repeat` for off/all, `Icons.Rounded.RepeatOne` for one; tinted the same as active shuffle when not off, the same as inactive shuffle when off; haptic like shuffle. Content description "Repeat off" / "Repeat all" / "Repeat one".
- Forwarding player: `getRepeatMode()` returns Parachord's mode mapped to `Player.REPEAT_MODE_OFF/ALL/ONE` from `stateHolder.state.value.repeatMode`; `setRepeatMode(mode)` calls `playbackController.setRepeatMode(mapped)` and never the delegate; `COMMAND_SET_REPEAT_MODE` reported available in both `isCommandAvailable` and `getAvailableCommands`. When `PlaybackState.repeatMode` changes, notify the session's listeners with `onRepeatModeChanged` through the existing listener set (the delegate's own repeat events, from the silence loop, must stay filtered out).

- [ ] **Step 1: Write the failing tests** in `ForwardingPlayerRepeatTest`:
  - `getRepeatMode reports Parachord's mode` — state ONE → `Player.REPEAT_MODE_ONE`; ALL → `REPEAT_MODE_ALL`; OFF → `REPEAT_MODE_OFF`, with the delegate stubbed to return `REPEAT_MODE_ONE` (the silence loop) throughout.
  - `setRepeatMode goes to the controller` — `setRepeatMode(REPEAT_MODE_ALL)` → `verify { controller.setRepeatMode(RepeatMode.ALL) }`.
  - `setRepeatMode does not touch the delegate` — `verify(exactly = 0) { delegate.repeatMode = any() }` (in both external and non-external mode).
  - `the repeat command is available` — `isCommandAvailable(COMMAND_SET_REPEAT_MODE)` true and present in `getAvailableCommands()`.

- [ ] **Step 2: Run to see them fail** — `./gradlew :app:testDebugUnitTest --tests '*ForwardingPlayerRepeatTest*'` → FAIL.

- [ ] **Step 3: Implement** the forwarding-player overrides and the Now Playing button.

- [ ] **Step 4: Run** — `./gradlew :app:testDebugUnitTest --tests '*ForwardingPlayer*'` → PASS.

- [ ] **Step 5: Check on a device or emulator**: the button cycles off → all → one; repeat one replays a track that ends (external and local), Next still advances; repeat all wraps after the last track and on Next from the last track, including a track added with Add to Queue; the lock-screen / Android Auto repeat control (where shown) changes the in-app button.

- [ ] **Step 6: Commit** `feat(android): repeat off/all/one in Now Playing and the media session`

### Task 5: iOS — use the shared rules

**Files:**
- Modify: `iosApp/Parachord/ContentView.swift` (`QueuePlaybackCoordinator`: `enum RepeatMode` ~L1967, `originalQueue` ~L1971 and its uses ~L2533 and ~L2632, `skipNext` ~L2545, `skipPrevious` ~L2574, `cycleRepeatMode` ~L2605, `autoAdvance` ~L2615-2651, the `skipNext()` at the end of `.noPlayableSource` in `playTrack`)
- Modify: `iosApp/Parachord/NowPlayingScreen.swift` (~L280, the repeat button's comparisons)

**Interfaces:**
- Consumes: Task 1 (`QueueAdvancePolicyKt.advanceAction`, `AdvanceTrigger`, `AdvanceAction`, `previousAction`, `nextRepeatMode`, `PREVIOUS_RESTARTS_AFTER_MS`), Task 2 (`queueManager.wrapToStart(currentTrack:)`, `hasNext`, `hasPrevious`).

Behaviour:
- Replace the Swift `RepeatMode` with the shared `RepeatMode` (`.off/.all/.one`); `cycleRepeatMode()` uses `nextRepeatMode`.
- Remove `originalQueue`; WRAP_TO_START uses `queueManager.wrapToStart(currentTrack:)`, then `syncSnapshot()` and `playTrack(next, advancing: true)`.
- One private `advance(_ trigger: AdvanceTrigger)` holds the shared decision: Listen Along's `onTrackFinished` hook first (only for `TRACK_ENDED`, as today), then `advanceSpinoff()`, then `advanceAction(...)`: PLAY_NEXT → `queueManager.skipNext` + `playTrack(advancing: true)`; REPLAY_CURRENT → `playTrack(current, advancing: true)`; WRAP_TO_START as above; STOP_AND_CLEAR → today's queue-exhausted block (halt engine, `keepAlive.stop()`, `player.stop()`, `currentTrack = nil`, `syncSnapshot()`), except while `queueManager.playbackContext?.type == ListenAlongController.listenAlongContext`, where it only halts the engine and keeps the track. `skipNext()` = `advance(.nextPressed)`, `autoAdvance()` = `advance(.trackEnded)`, and the no-source skip at the end of `.noPlayableSource` calls `advance(.skippedUnplayable)`.
- `skipPrevious()`: `previousAction(positionMs: Int64(currentTime * 1000), hasPrevious: queueManager.hasPrevious)`; `.restartCurrent` → `seek(to: 0)`; `.playPrevious` → today's path.

- [ ] **Step 1: Make the change.**
- [ ] **Step 2: Build** — `xcodebuild build -project <worktree>/iosApp/Parachord.xcodeproj -scheme Parachord -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' -derivedDataPath <worktree>/iosApp/build` → `BUILD SUCCEEDED`. (If the worktree lacks `iosApp/Parachord/Secrets.xcconfig` / `local.properties`, copy them from the main checkout; both are gitignored.)
- [ ] **Step 3: Check in the simulator** with a temporary hook (removed before committing) that builds a queue of three local or direct-URL tracks (`Track.sourceUrl` set, so AVPlayer plays them): repeat all → after the last track ends the first plays; Add to Queue a fourth track, it is part of the next wrap; Next on the last track wraps; repeat one → an ended track replays, Next advances; repeat off → the end clears the mini player. Log each decision with `NSLog("QUEUE advance …")` while testing; keep only one concise permanent log line.
- [ ] **Step 4: Commit** `feat(ios): shared queue rules — repeat all wraps the whole queue, Next wraps under repeat all`

### Task 6: Docs and PR

**Files:**
- Modify: `CLAUDE.md` (add a short "Queue advance and repeat" subsection under the playback sections: the shared `QueueAdvancePolicy`, the rules from Global Constraints, the no-source rule; and in `LoadOnPlay`'s failure list, `NO_SOURCE` skips during an advance or spinoff)
- Modify: `iosApp/AGENTS.md` (the coordinator uses the shared policy; `originalQueue` is gone)
- Modify: KDoc on `PlaybackController.skipNextInternal` and the iOS `advance`

- [ ] **Step 1: Update the docs**; `grep -rn -i "repeat\|originalQueue\|skipNextInternal" --include='*.md' .` and fix any other place that now describes old behaviour (leave `docs/plans/` and `docs/superpowers/specs/` as they are).
- [ ] **Step 2: Full check** — `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:testDebugUnitTest` → BUILD SUCCESSFUL; iOS build as in Task 5.
- [ ] **Step 3: Commit, push the branch, open the PR against `main`** with what was checked on which device; then follow the session's PR steps (bind, read CI).
