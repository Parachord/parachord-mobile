# Shared Play-on-a-stopped-track (PR 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One shared rule set for "Play on a track nothing holds" on both apps, a tapped track with no source stopping for Play on iOS as on Android, and Android Auto stopping after three unplayable tracks in a row as CarPlay does.

**Architecture:** Android's `LoadOnPlay` slot and its pure helpers move to `shared/commonMain/.../playback/`, next to `PlayFailure`. iOS's `QueuePlaybackCoordinator` holds a `LoadOnPlay?` in place of its `needsLoadOnPlay` + `restoredResumeAtSec` pair and records failures through the same shared functions. Android's `PlaybackController` asks the shared `CarUnplayableRun` before each auto-skip.

**Tech Stack:** Kotlin Multiplatform (commonMain + commonTest on JVM and iosSimulatorArm64), Android Media3, SwiftUI.

**Spec:** `docs/superpowers/specs/2026-11-10-shared-playback-session-rules-design.md` (Area 4). Ticket: #586. Earlier PRs: #583, #599, #601, #609.

## Global Constraints

- The slot: `LoadOnPlay(track, positionMs, skipReselect, why)` with `Why` = `RESTORED`, `IDLE_STOPPED`, `FAILED`. It applies only while its track is the current one (`loadOnPlayTarget`).
- Resume rule (`PlaybackResumePosition`, already shared): only the same track id resumes; a track under **5 s** in or within **10 s** of its end starts over.
- `failedPlayAwaitsLoad(failure, advancing, spinoffMode)` decides stop-for-Play against skip: only `EMBED_REFUSED` and `NO_SOURCE` during an advance or a spinoff skip.
- `noSourceFailure(online, resolversCouldNotAnswer)`: `NO_SOURCE` only when online and the resolvers answered with no match; otherwise `START_FAILED`.
- Messages, verbatim (already shared): `PLAY_NO_SOURCE_MESSAGE`, `PLAY_START_FAILED_MESSAGE`, `CarMessages.OPEN_PARACHORD_ON_PHONE`.
- Car rule: `CAR_UNPLAYABLE_RUN_LIMIT` = **3**. It stops only when a car is connected and Parachord isn't in front on the phone. Counts as unplayable: the failures that skip (`NO_SOURCE`, and `EMBED_REFUSED` during an advance).
- "In the car" on Android: a controller with package `com.google.android.projection.gearhead` is connected to the `MediaLibrarySession`. No new dependency.
- The auto-skip gap is **600 ms** (Android), unchanged.
- Shared-code rules from CLAUDE.md: no JVM-only APIs; no `,` `.` `;` `:` `/` `<` `>` `[` `]` `\` in commonTest names; run `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test` after shared changes. Swift sees Kotlin enums as classes with lower-camel cases and top-level functions as `<File>Kt.fn`.
- Don't change the background-survival rules (root CLAUDE.md "External Playback Background Survival", `iosApp/AGENTS.md` "Background playback"). Listen Along's hold (`heldForListener`, `ListenAlongHost`) keeps working: Play does nothing while a listener holds the track.
- Ordinary playback that doesn't fail behaves exactly as before.

## Decisions made in this plan (not in the spec)

- **`FailedTrackStop` / `failedTrackStop` stay in the Android app.** The ticket lists them to move, but they describe Android's external mode and foreground service, which iOS doesn't have. The spec names only `LoadOnPlay`, `loadOnPlayTarget` and `failedLoad`.
- **Previous on a track nothing holds** reads the slot's position, not the player's. Past 3 s it restarts: the slot's position becomes 0, the track stays on screen and nothing starts playing. Both apps.
- **Play or Pause during the 600 ms auto-skip gap does nothing** (Android). The queue is already moving to the next track.
- **The car run is reset by a new car connection inside the shared class** (`failed(carWithoutPhone, connection)`), so both apps pass a connection counter and neither keeps its own "last seen connection".
- **Android Auto's stop shows `OPEN_PARACHORD_ON_PHONE` the way `OPEN_SPOTIFY_ON_PHONE` is shown today** (`playbackError`), and leaves a `FAILED` slot so the car's Play retries the track.
- **iOS keeps a manual source pick through a retry** by remembering the forced resolver with the slot. The shared type may gain an optional `pickedResolver: String?` for that (Android leaves it null); the builder may choose another way to carry it.

## Review Focus

1. **A slot for a track that is no longer current must not load.** A fails and leaves a slot; Previous or a queue change puts B on screen without playing; Play must act on B (Task 1 tests on `loadOnPlayTarget`; Task 4 simulator check).
2. **iOS during a Spotify cooldown**: the queue reaches a track whose resolvers couldn't answer. It stops on that track for Play; it does not skip to the end of the list (Task 4).
3. **Play pressed inside the 600 ms auto-skip gap** on Android must not re-play the unplayable track or drop the foreground (Task 2 test and locked-device check).
4. **Android Auto with the phone app in front** keeps skipping (no stop); a track that plays resets the run; a tap resets it (Task 3 tests).
5. **Listen Along and the paused give-back still work** on the new slot: a held track ignores Play, and a queue given back paused starts its first track on Play (Tasks 2 and 4, existing `ListenAlongHostTest` and `PlaybackControllerListenerHooksTest` stay green; simulator check).

---

### Task 1: Shared slot, Previous position and the car run's connection reset

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/playback/LoadOnPlay.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/car/CarUnplayableRun.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/playback/PlayFailure.kt` (KDoc only: it names Android's `LoadOnPlay` and iOS's `needsLoadOnPlay`)
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/playback/LoadOnPlayTest.kt` (moved from `app/src/test/.../playback/LoadOnPlayTest.kt`, minus its `failedTrackStop` cases), `shared/src/commonTest/kotlin/com/parachord/shared/car/CarUnplayableRunTest.kt`

**Interfaces:**
- Produces, in package `com.parachord.shared.playback`, all public:
  - `data class LoadOnPlay(val track: Track, val positionMs: Long, val skipReselect: Boolean, val why: Why, val pickedResolver: String? = null) { enum class Why { RESTORED, IDLE_STOPPED, FAILED } }`
  - `fun loadOnPlayTarget(pending: LoadOnPlay?, currentTrackId: String?): LoadOnPlay?`
  - `fun idleTimeoutLoad(pending: LoadOnPlay?, current: Track?, positionMs: Long): LoadOnPlay?`
  - `fun failedLoad(track: Track, skipReselect: Boolean, failedAtMs: Long, durationMs: Long, aimedAtMs: Long = 0, pickedResolver: String? = null): LoadOnPlay`
  - `fun positionForPrevious(playerPositionMs: Long, pending: LoadOnPlay?, currentTrackId: String?): Long`: the slot's position when it is the target, else the player's.
  - `fun LoadOnPlay.restartedFromTop(): LoadOnPlay`: the same slot at position 0.
- `CarUnplayableRun` gains `fun failed(carWithoutPhone: Boolean, connection: Int): Boolean`: resets the count when `connection` differs from the last one it saw, then behaves as `failed(carWithoutPhone)`. The one-argument `failed` stays.

- [ ] **Step 1: Move the tests and add the new ones.** Move the existing `LoadOnPlayTest` cases for `loadOnPlayTarget`, `idleTimeoutLoad` and `failedLoad` to commonTest unchanged in meaning (rename any test whose name has a forbidden character). Add:
  - `the previous position is the slot's while its track is current`: slot at 120 000 for track A, player at 0, current A → 120 000.
  - `the previous position is the player's for another track`: same slot, current B, player at 4 000 → 4 000; and with no slot.
  - `restarting a slot keeps everything but the position`.
  - `a failed load keeps the picked resolver`.
  - In `CarUnplayableRunTest`: `a new car connection starts the run over` (two failures on connection 1, then a failure on connection 2 with `carWithoutPhone` → false, count 1); `the same connection keeps counting` (third failure → true).
- [ ] **Step 2: Run to see them fail.** `cd <worktree> && ./gradlew :shared:testDebugUnitTest --tests '*LoadOnPlayTest*' --tests '*CarUnplayableRunTest*'` → compile failure.
- [ ] **Step 3: Implement.** The bodies of the three moved functions are Android's, unchanged.
- [ ] **Step 4: Run on both targets.** The command above, plus `./gradlew :shared:iosSimulatorArm64Test --tests '*LoadOnPlayTest*' --tests '*CarUnplayableRunTest*'` → PASS.
- [ ] **Step 5: Commit** `feat(shared): Play-on-a-stopped-track slot and the car run's connection reset`.

### Task 2: Android uses the shared slot; four carried-over fixes

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/LoadOnPlay.kt` (keeps only `PlayFailure` typealias, `FailedTrackStop`, `failedTrackStop`; typealias `LoadOnPlay` to the shared type if that keeps imports stable)
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt`:
  - slot uses (~L138-146, 395, 797, 1002-1015, 1233, 1707, 2169, 2193);
  - `skipPrevious` (~L855-870);
  - the two auto-skip sites (~L1355-1370, ~L3043-3058);
  - `togglePlayPause`'s "no active handler, re-playing" branch (~L1055-1060);
  - `playTrackInternal`'s `finally` (~L1227-1255);
  - `playedSinceWrap` (~L177, 1227).
- Modify: `app/src/test/java/com/parachord/android/playback/LoadOnPlayTest.kt` (keeps the `failedTrackStop` cases), `PlaybackControllerListenerHooksTest.kt` or a new `PlaybackControllerStoppedTrackTest.kt`

**Interfaces:**
- Consumes: Task 1.

Behaviour:
- **Shared slot.** Every use of the slot and its helpers is the shared one; no behaviour change.
- **Previous on a stopped track.** `skipPrevious` passes `positionForPrevious(playerPosition, loadOnPlay, currentTrackId)` to `previousAction`. On `RESTART_CURRENT` while the slot is the target: the slot becomes `restartedFromTop()`, the shown position becomes 0, nothing plays, and the player isn't sought.
- **Play or Pause in the auto-skip gap.** While a skip is pending for the current track (a field set when the 600 ms job is scheduled, cleared when it runs or is overtaken), `togglePlayPause` and the media session's play/pause do nothing.
- **Foreground across an auto-skip.** When a play ends in an auto-skip, `playTrackInternal`'s `finally` keeps `foregroundHeldForPlay` for the next track instead of releasing it; it is released when the queue stops or a track plays.
- **`playedSinceWrap` race.** A play sets the flag only if no new queue was set since it started (compare a counter taken at the play's start; `playRequestCount` exists).

- [ ] **Step 1: Write the failing tests** (JVM, the real `PlaybackController` as `PlaybackControllerListenerHooksTest` builds it):
  - `previous on a restored track past three seconds restarts it without playing`: restored slot at 120 000; `skipPrevious()` → current track unchanged, no play started, the slot at 0.
  - `previous on a restored track at the top goes to the previous track`.
  - `play during the auto skip gap does nothing`: a no-source track during an advance; `togglePlayPause()` inside the gap → no play of that track; after the gap the next track plays once.
  - `a play that finishes after a new queue does not mark the new queue as played`.
- [ ] **Step 2: Run to see them fail.** `./gradlew :app:testDebugUnitTest --tests '*PlaybackControllerStoppedTrackTest*'` (or the file used).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests '*LoadOnPlay*' --tests '*Playback*' --tests '*ForwardingPlayer*' --tests '*ListenAlong*' --tests '*KoinModuleVerifyTest*'` → PASS.
- [ ] **Step 5: Check on the emulator** (`emulator-5554` only): a restored queue paused past 0:05, Previous → stays on the track at 0:00, Play starts it from the top; a queue with a no-source track in the middle, a media-key Play inside the gap → the next track still plays once.
- [ ] **Step 6: Commit** `feat(android): shared stopped-track slot; Previous, skip-gap and foreground fixes`.

### Task 3: Android Auto stops after three unplayable tracks in a row

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackService.kt` (`onConnect` ~L479, `onDisconnected`: track Android Auto controllers)
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt` (the two auto-skip sites; a successful play; explicit plays)
- Test: `app/src/test/java/com/parachord/android/playback/PlaybackControllerCarRunTest.kt`

**Interfaces:**
- Consumes: Task 1 (`CarUnplayableRun.failed(carWithoutPhone, connection)`), Task 2 (the auto-skip sites as changed there).
- `PlaybackController` gets what it needs from the service as plain values, for example `var carConnection: () -> Int?` (a counter that goes up on each Android Auto connect, null when none is connected). The controller stays ignorant of Media3 session types.

Behaviour:
- A controller whose package is `com.google.android.projection.gearhead` connecting raises the counter; when the last one disconnects, "in the car" is false.
- At each auto-skip site, before scheduling the skip: `carRun.failed(carWithoutPhone = inCar && !isAppVisible(), connection)`. True → no skip: playback stops on the track as a non-skipped failure does (`stopBeforeFailedTrack(autoSkip = false)`), `playbackError` = `CarMessages.OPEN_PARACHORD_ON_PHONE`, and the slot is `FAILED` so Play retries it.
- A play that starts calls `carRun.started()`. A play that isn't the queue moving on (`advancing == false`) calls `carRun.reset()`.
- Not in the car, or with a Parachord screen showing, the queue skips as before.

- [ ] **Step 1: Write the failing tests:**
  - `the third unplayable track in a row stops in the car with the phone app not in front`: three no-source tracks during an advance, in the car, app not visible → the third isn't skipped; `playbackError` is `OPEN_PARACHORD_ON_PHONE`; `needsLoadOnPlay` is true.
  - `with the phone app in front the queue keeps skipping`.
  - `a track that plays starts the run over`: fail, fail, play, fail, fail → no stop.
  - `a tap starts the run over`.
  - `not in the car nothing stops`.
- [ ] **Step 2: Run to see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** `./gradlew :app:testDebugUnitTest --tests '*PlaybackControllerCarRunTest*' --tests '*Playback*' --tests '*KoinModuleVerifyTest*'` → PASS.
- [ ] **Step 5: Commit** `feat(android): Android Auto stops after three unplayable tracks in a row`.

### Task 4: iOS uses the shared slot

**Files:**
- Modify: `iosApp/Parachord/ContentView.swift` (`QueuePlaybackCoordinator`):
  - `needsLoadOnPlay` / `restoredResumeAtSec` (~L1921-1925) and every use (~L2608-2612, 2736, 2749, 2888-2891, 2939-2941, 3216-3218, 3385-3386, 3404);
  - the `.noPlayableSource` branch (~L3095-3206);
  - `skipPrevious`;
  - the AVPlayer failure path (~L1706-1733, ~L3293);
  - the car run (~L1900-1901, 3188-3194).
- Modify: whatever carries the resolve result into the play path, so the branch knows when the resolvers couldn't answer (`IosContainer` ~L3144 already returns a `ResolveReport`).

**Interfaces:**
- Consumes: Task 1.

Behaviour:
- **One slot.** `loadOnPlay: LoadOnPlay?` replaces the pair. Play loads `loadOnPlayTarget(loadOnPlay, currentTrack?.id)`: the track at the slot's position (the existing `seekOnceStarted`), with the slot's picked resolver forced when it has one. Any play clears the slot. Every place that set `needsLoadOnPlay = true` builds a slot: the restored queue and `setQueuePaused` are `RESTORED`; failures use `failedLoad`; `releaseListenerHold` leaves a `FAILED` slot at 0 for the track on screen.
- **A tapped track with no source stops for Play.** When nothing could play the track and it isn't a listener's: `noSourceFailure(online, resolversCouldNotAnswer)` picks the failure, and `failedPlayAwaitsLoad` decides. `NO_SOURCE` while tapped (not advancing, no spinoff): halt, notice `PLAY_NO_SOURCE_MESSAGE`, slot `FAILED`. `NO_SOURCE` during an advance or spinoff: skip, as today, through the car run. `START_FAILED` (offline, or the resolvers couldn't answer): halt, notice `PLAY_START_FAILED_MESSAGE`, slot `FAILED`, advancing or not.
- **A manual pick is kept.** The coordinator remembers the forced resolver of the track that is playing; a slot made for that track carries it.
- **A stream that fails while playing** (AVPlayer's item fails after it started) leaves a `FAILED` slot from `failedLoad(failedAtMs:, durationMs:)`, with the shared start-failed notice, in place of today's handling.
- **Previous** uses `positionForPrevious` and `restartedFromTop`, as Android in Task 2.
- **The car run** calls `failed(carWithoutPhone:connection:)` with `CarPlaySession.shared.connectionCount`; `unplayableRunConnection` goes.
- Listen Along's hold is untouched: `heldForListener` still makes Play do nothing.

- [ ] **Step 1: Make the change.**
- [ ] **Step 2: Build.** `./gradlew :shared:testDebugUnitTest`, then `xcodebuild build -project <worktree>/iosApp/Parachord.xcodeproj -scheme Parachord -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' -derivedDataPath <worktree>/iosApp/build` → `BUILD SUCCEEDED`.
- [ ] **Step 3: Check in the simulator** with a temporary hook (removed before committing; grep that none remains), with `NSLog` evidence for each:
  - a tapped track with no source → the no-source notice, the track stays, Play tries it again;
  - the same track reached by the queue moving on → skipped;
  - resolvers that couldn't answer (a simulated failure) during an advance → stops on the track with the start-failed notice; Play retries;
  - offline tap, then online Play → plays (the #573 behaviour, unchanged);
  - a restored queue at 2:00: Play resumes at 2:00; Previous first → stays on the track at 0:00 and Play starts from the top;
  - A fails and leaves a slot, Next puts B on screen without playing (or a queue change), Play → B plays, not A;
  - a manual source pick, then a failure of that play → Play retries the same source;
  - a Listen Along session waiting after its song → Play does nothing; the session ending → Play starts the track on screen; a queue given back paused → Play starts its first track.
- [ ] **Step 4: Commit** `feat(ios): Play on a stopped track uses the shared slot; a tapped track with no source stops`.

### Task 5: Docs, device checks and PR

**Files:**
- Modify: root `CLAUDE.md`: "External Playback Background Survival" → the `LoadOnPlay` paragraph (shared now; iOS uses it); "Queue advance and repeat (shared)" → the "A track with no source skips, per platform" bullet (no longer per platform); "CarPlay / Android Auto / Android Automotive" (Android Auto has the unplayable-run stop); "Spinoff + Listen Along" if it names `needsLoadOnPlay`.
- Modify: `iosApp/AGENTS.md`: the sections that describe `needsLoadOnPlay`, the no-source branch and the CarPlay unplayable run.
- Modify: KDoc on the changed functions.

- [ ] **Step 1: Update the docs.** `grep -rn "needsLoadOnPlay\|LoadOnPlay\|restoredResumeAtSec\|CarUnplayableRun\|NO_SOURCE" --include='*.md' .` and fix what describes old behaviour, outside `docs/plans/`, `docs/design/` and `docs/superpowers/`.
- [ ] **Step 2: Full check.** `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:testDebugUnitTest` → BUILD SUCCESSFUL; the iOS build.
- [ ] **Step 3: Device checks with the user.**
  - Pixel 9a, screen locked: a local track, then a track with no source, then a Spotify or Apple Music track in one queue → the third starts with the screen still locked (the foreground survived the skip).
  - Android Auto (a car, or the Desktop Head Unit with the Pixel): a playlist with three tracks in a row that can't play, the phone locked → playback stops on the third with "Open Parachord on your phone"; Play in the car retries it. With Parachord open on the phone the queue skips on.
- [ ] **Step 4: Rebase onto `main`, push, open the PR** against `main`, closing #586, listing what was checked on which device.
- [ ] **Step 5: Ask about the desktop ticket.** With all four PRs landed, draft the Parachord/parachord ticket the design calls for (repeat off/all/one, clearing the last track at the end of the queue, Previous restarting after 3 s, the refill limits, Listen Along's failed-poll rule and the other rulings mobile now leads on) and ask before filing.
