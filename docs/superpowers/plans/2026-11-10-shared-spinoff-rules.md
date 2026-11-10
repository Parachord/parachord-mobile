# Shared spinoff rules (PR 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One spinoff session on both apps: pool tracks resolved when they play, refill below 3 tracks with the same limits, shuffle off during a spinoff or Listen Along, and the same messages, all from shared code.

**Architecture:** Pure rules and the refill state machine live in `shared/src/commonMain/kotlin/com/parachord/shared/playback/` (`SpinoffSession.kt`, `PoolRefiller.kt` moved from Android, `PlaybackMessages.kt`). Android's `PlaybackController` and iOS's `QueuePlaybackCoordinator` / `SpinoffController` keep the pool and the playback calls, and ask the shared rules. Builds on PR 1 (#583): a pool track with no source is skipped by `PlayFailure.NO_SOURCE` during a spinoff.

**Tech Stack:** Kotlin Multiplatform (commonMain + commonTest on JVM and iosSimulatorArm64), Android Compose + Media3, SwiftUI.

**Spec:** `docs/superpowers/specs/2026-11-10-shared-playback-session-rules-design.md` (Area 2). Ticket: #584.

## Global Constraints

- Desktop (`src/renderer/hooks/use-playback-engine.js` ~3370-3420, `app.js` ~30626-30700) is the reference.
- Copy, verbatim, desktop's and Android's:
  - `No similar tracks found for "<title>"`
  - `Spinning off of <title> - <artist>`
  - `Failed to fetch similar tracks` (already shared as `SPINOFF_FETCH_FAILED_MESSAGE`)
  - `Spinoff from <title> by <artist>`
- Refill fires when the pool has **fewer than 3** tracks left, at most **once every 5 000 ms**, and stops after **3** empty or failed refills in a row. Dedup is by recording MBID (case-insensitive), then `artist|title` (case-insensitive). These are today's `PoolRefiller` values.
- Pre-resolve the next **3** pool tracks in the background, the same as the queue does.
- Shuffle is off while a spinoff runs **or** while Listen Along runs (playback context type `listen-along`). It is blocked in the controller or coordinator, not only in the UI.
- A spinoff never modifies the user's queue. On exhaustion it exits and the queue resumes (unchanged from today).
- Shared-code rules from CLAUDE.md:
  - no JVM-only APIs (`System.currentTimeMillis` → `currentTimeMillis()`, `@Volatile` → `kotlin.concurrent.Volatile`);
  - no `,` `.` `;` `:` `/` `<` `>` `[` `]` `\` in commonTest names;
  - run `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test` after shared changes.
- Swift bridging: Kotlin objects are `.shared`, top-level functions are `<File>Kt.fn`, and suspend functions become `async throws`.

## Review Focus

1. **A radio deep link (`parachord://play/radio`, artist seed) whose pool has no playable track** must refuse before tearing down the user's queue (#397). With play-time resolution there's no up-front resolve left to check, so Task 1's `firstPlayableIndex` replaces it (test `a pool with nothing playable in the first five is refused`).
2. **A spinoff whose pool tracks all have no source** must skip through, exit, and resume the queue, not loop forever (Task 3 and Task 4 device checks).
3. **A refill that keeps failing** stops after 3 tries (`PoolRefillerTest` `three empty refills stop the loop`).
4. **A refill that lands after the spinoff ended** must not leave tracks in the pool. Android currently `addAll`s without checking `spinoffMode` (Task 3 test or check: `refill after exit is dropped`).
5. **Shuffle from outside the Now Playing button** (lock screen, Android Auto, a deep link `parachord://shuffle/on`) during a spinoff or Listen Along is refused (Task 1 test `shuffle is refused during a spinoff or Listen Along`; Tasks 3 and 4 route every shuffle path through it).

---

### Task 1: Shared spinoff rules and messages

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/playback/SpinoffSession.kt`
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/playback/PlaybackMessages.kt`
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/playback/SpinoffSessionTest.kt`

**Interfaces:**
- Produces:
  - `const val SPINOFF_REFILL_BELOW = 3`, `fun spinoffNeedsRefill(poolSize: Int): Boolean` (true when `poolSize < SPINOFF_REFILL_BELOW`)
  - `const val SPINOFF_PRERESOLVE_AHEAD = 3`, `fun <T> spinoffTracksToPreresolve(pool: List<T>): List<T>` (the first `SPINOFF_PRERESOLVE_AHEAD`)
  - `fun shuffleAllowed(spinoffMode: Boolean, playbackContextType: String?): Boolean`, false when `spinoffMode` or `playbackContextType == LISTEN_ALONG_CONTEXT`
  - `const val LISTEN_ALONG_CONTEXT = "listen-along"`. Android's hard-coded string and iOS's `ListenAlongController.listenAlongContext` both use it (closes a #585 carry-over item).
  - `fun spinoffTrack(title: String, artist: String): Track`: one id scheme for both apps, `"spinoff:" + title.lowercase() + "|" + artist.lowercase()` (today's iOS form), metadata only, `duration = 0`, every source field null.
  - `const val RADIO_PLAYABLE_PROBE = 5`, `suspend fun <T> firstPlayableIndex(pool: List<T>, isPlayable: suspend (T) -> Boolean): Int?`: checks pool tracks in order, at most `RADIO_PLAYABLE_PROBE`, and returns the first index for which `isPlayable` is true, or null. An `isPlayable` that throws counts as false, except `CancellationException`, which is rethrown.
  - `object PlaybackMessages`:
    - `fun noSimilarTracks(title: String): String` = `No similar tracks found for "<title>"`
    - `fun spinningOff(title: String, artist: String): String` = `Spinning off of <title> - <artist>`
    - `fun spinoffFrom(title: String, artist: String): String` = `Spinoff from <title> by <artist>`
    - `val SPINOFF_FETCH_FAILED: String = SPINOFF_FETCH_FAILED_MESSAGE`
    - `fun noPlayableSimilarTracks(title: String): String` = `Couldn't find a playable track similar to "<title>"` (new copy, for Review Focus 1)
  - #596 later adds the remaining playback strings to the same object.

- [ ] **Step 1: Write the failing tests** (`SpinoffSessionTest`):
  - `refill is needed below three tracks`: `spinoffNeedsRefill(2)` true, `(3)` false, `(0)` true.
  - `the next three pool tracks are pre-resolved`: the result for a 5-item list is its first 3, and for a 2-item list both items.
  - `shuffle is refused during a spinoff or Listen Along`: `shuffleAllowed(true, null)` false, `(false, "listen-along")` false, `(false, "playlist")` true, `(false, null)` true.
  - `spinoff tracks share one id scheme`: `spinoffTrack("Myth", "Beach House").id == "spinoff:myth|beach house"`; sources null; duration 0.
  - `the first playable pool track is found`: `isPlayable` true at index 2 → returns 2, and is never called for index 3.
  - `a pool with nothing playable in the first five is refused`: a 7-item pool, all false → null, called exactly 5 times.
  - `a probe that throws counts as not playable`: throws at 0, true at 1 → 1.
  - `messages match desktop`: the four strings, exactly.

- [ ] **Step 2: Run to see them fail.** `cd <worktree> && ./gradlew :shared:testDebugUnitTest --tests '*SpinoffSessionTest*'` → compile failure.
- [ ] **Step 3: Implement** the two files with the signatures above.
- [ ] **Step 4: Run on both targets.** `./gradlew :shared:testDebugUnitTest --tests '*SpinoffSessionTest*' :shared:iosSimulatorArm64Test --tests '*SpinoffSessionTest*'` → PASS.
- [ ] **Step 5: Commit** `feat(shared): spinoff session rules and shared spinoff messages`.

### Task 2: Move `PoolRefiller` to shared

**Files:**
- Move: `app/src/main/java/com/parachord/android/playback/PoolRefiller.kt` → `shared/src/commonMain/kotlin/com/parachord/shared/playback/PoolRefiller.kt` (package `com.parachord.shared.playback`)
- Move: `app/src/test/java/com/parachord/android/playback/PoolRefillerTest.kt` → `shared/src/commonTest/kotlin/com/parachord/shared/playback/PoolRefillerTest.kt` (kotlin.test + `runTest`)
- Modify: Android callers (`PlaybackController.kt`, `deeplink/PlayRadioDispatcher.kt`) to import the shared class.

**Interfaces:**
- Consumes: Task 1 (`spinoffNeedsRefill`).
- Produces (the same API as today, now shared):
  - `class PoolRefiller(clock: () -> Long = ::currentTimeMillis, rateLimitMs: Long = REFILL_RATE_LIMIT_MS, maxEmpty: Int = REFILL_MAX_EMPTY)`
  - `var fetcher: (suspend (String) -> List<ProtocolTrack>)?`, `var refillUrl: String?`
  - `fun canRefill(): Boolean`, `suspend fun tryRefill(currentPoolSnapshot: List<Track>): List<Track>?`, `fun resetCounters()`
  - `companion object { REFILL_RATE_LIMIT_MS = 5_000L; REFILL_MAX_EMPTY = 3; fun dedupAgainstPool(...) }`
- Refilled tracks keep today's ids (`protocol-radio-<ts>-<idx>`); they're protocol tracks, not Last.fm spinoff tracks.

- [ ] **Step 1: Move the test first**, ported to kotlin.test, with its 16 cases unchanged in meaning (including `three empty refills stop the loop` under that name, or the existing one renamed to it). Run it to see it fail to compile against the shared package.
- [ ] **Step 2: Move the class.** Replace `System::currentTimeMillis` with `::currentTimeMillis` (`com.parachord.shared.platform`), `@Volatile` with `kotlin.concurrent.Volatile`, and `TrackEntity` with `Track`. Keep the `Mutex`.
- [ ] **Step 3: Run** `./gradlew :shared:testDebugUnitTest --tests '*PoolRefillerTest*' :shared:iosSimulatorArm64Test --tests '*PoolRefillerTest*' :app:compileDebugKotlin :app:testDebugUnitTest --tests '*KoinModuleVerifyTest*'` → PASS.
- [ ] **Step 4: Commit** `refactor(shared): PoolRefiller moves to shared so iOS uses the same refill rules`.

### Task 3: Android: pool resolved at play time, shared refill and shuffle rules

**Files:**
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt`:
  - `buildSpinoffPool` ~L2115-2164
  - `startSpinoffWithSeed` ~L2036-2091
  - `startSpinoffFromPool` ~L2173-2224
  - the spinoff branch of `skipNextInternal` ~L497-525
  - `triggerPoolRefill` ~L2239-2271
  - `toggleShuffle` ~L746
- Modify: `app/src/main/java/com/parachord/android/deeplink/PlayRadioDispatcher.kt` ~L60-95
- Modify: the shuffle button in `ui/screens/nowplaying/NowPlayingScreen.kt`, and any other shuffle entry points (deep link `Shuffle`, the media session) so all of them go through `toggleShuffle`/`setShuffle` and its guard.

**Interfaces:**
- Consumes: Task 1 (all of it); Task 2 (`PoolRefiller`); PR 1's `PlayFailure.NO_SOURCE` skip during a spinoff.

Behaviour:
- **`buildSpinoffPool(seedArtist, seedTitle): List<TrackEntity>`** no longer resolves. It maps the shared builder's candidates to `spinoffTrack(title, artist)` in the builder's order (don't reshuffle), and still throws `SpinoffFetchFailedException` when `built.failed`.
- **Starting a spinoff** (`startSpinoffFromPool`), and after every pool advance: call `trackResolverCache.resolveInBackground(spinoffTracksToPreresolve(spinoffPool), backfillDb = false, priority = false)`.
- **The spinoff branch** of `skipNextInternal` refills when `spinoffNeedsRefill(spinoffPool.size)` (replacing `POOL_REFILL_THRESHOLD`).
- **A refill that finishes after the spinoff ended** is dropped. In `triggerPoolRefill`, `addAll` only while `spinoffMode` is still on and the refiller's `refillUrl` is unchanged.
- **Messages:**
  - `startSpinoffWithSeed` uses `PlaybackMessages.noSimilarTracks`, `PlaybackMessages.SPINOFF_FETCH_FAILED` and `PlaybackMessages.spinningOff`.
  - `startSpinoff` uses `PlaybackMessages.spinoffFrom`.
  - `PlayRadioDispatcher`'s "Couldn't fetch similar tracks" becomes `PlaybackMessages.SPINOFF_FETCH_FAILED`.
- **Radio deep link, artist seed (#397).** After `buildSpinoffPool` and before `teardown.prepareForNewPlayback()`:
  - Find `firstPlayableIndex(pool) { track -> resolverScoring.selectBest(resolverManager.resolve("${track.title} ${track.artist}", targetTitle = track.title, targetArtist = track.artist)) != null }`.
  - If it's null, return `PlayRadioResult.Failed(PlaybackMessages.noPlayableSimilarTracks(seed.title))` without touching the queue.
  - Otherwise start the station from that index; the tracks before it are dropped.
- **`toggleShuffle`** returns without changing anything unless `shuffleAllowed(spinoffMode, queueManager.playbackContext?.type)`. The Now Playing shuffle button is disabled for the same condition (today it is disabled only in spinoff).
- **Listen Along:** replace the hard-coded `"listen-along"` strings in `app/` with `LISTEN_ALONG_CONTEXT`.

- [ ] **Step 1: Make the change.**
- [ ] **Step 2: Build and run unit tests.** `cd <worktree> && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests '*KoinModuleVerifyTest*' --tests '*Playback*' --tests '*PlayRadio*' --tests '*ProtocolPlay*'` → PASS. Update any test that assumed a resolved pool.
- [ ] **Step 3: Check on the Pixel 9a** (serial `53111JEBF07835`; ask the user to unlock it and keep Developer options "Stay awake" on; back up and restore the debug app's `shared_prefs/parachord_kmp_prefs.xml` around any change; logcat tags `PlaybackController`, `PlayRadioDispatcher`):
  - Spinoff from a Spotify track: the toast reads `Spinning off of <title> - <artist>`, the current song keeps playing, and the next track comes from the pool, resolved when it plays.
  - A pool track with no source is skipped.
  - Shuffle is disabled during the spinoff, and `parachord://shuffle/on` is refused.
  - Exiting the spinoff resumes the queue.
  - With repeat ALL on, a spinoff plays its pool to the end, exits, and the queue resumes. Repeat then applies to the queue, not the pool.
  - `parachord://play/radio?artist=<a made-up artist>` is refused, leaving the queue alone.
  - `parachord://play/radio?artist=Beach House` plays.
- [ ] **Step 4: Commit** `feat(android): spinoff pool resolved at play time; shared refill, shuffle and messages`.

### Task 4: iOS: shared refill, shuffle and messages

**Files:**
- Modify: `iosApp/Parachord/ContentView.swift`:
  - `beginSpinoff` / `beginRadio` / `advanceSpinoff` / `refillSpinoffIfNeeded` / `exitSpinoff`, ~L2009-2071
  - `toggleShuffle` ~L2655
- Modify: `iosApp/Parachord/SpinoffController.swift` (message, display name).
- Modify: `iosApp/Parachord/NowPlayingScreen.swift` (the spinoff alert ~L344-353, the shuffle button's disabled state).
- Modify: `iosApp/Parachord/RootView.swift` ~L460-465 (radio pool start).
- Modify: `iosApp/Parachord/ListenAlongController.swift` (`listenAlongContext` = `SpinoffSessionKt.LISTEN_ALONG_CONTEXT`).
- Modify: `shared/src/iosMain/kotlin/com/parachord/shared/ios/IosContainer.kt`:
  - `resolveRadio` ~L1356-1384 and `makeSpinoffTrack` ~L1606 use the shared messages, `spinoffTrack` and `firstPlayableIndex`;
  - add `fun newSpinoffRefiller(): PoolRefiller`, with its `fetcher` wired to `protocolInputResolver.resolveByUrl(url)?.tracks ?: emptyList()`.

**Interfaces:**
- Consumes: Tasks 1-2.

Behaviour:
- **Refill.** The coordinator holds one `PoolRefiller` from `newSpinoffRefiller()`:
  - `beginRadio` sets its `refillUrl` and calls `resetCounters()`; `exitSpinoff` sets `refillUrl = nil` and resets.
  - `advanceSpinoff` refills when `spinoffNeedsRefill(spinoffPool.count)` (replacing `<= 1`), via `try await refiller.tryRefill(currentPoolSnapshot: spinoffPool)`.
  - Refilled tracks are appended only while still in that spinoff (the same guard as Android).
  - Remove `spinoffRefill` / `spinoffRefilling`.
- **Pre-resolve.** On `beginSpinoff` / `beginRadio` and after each pool advance, submit `spinoffTracksToPreresolve(spinoffPool)` to `IosTrackResolverCache` (as the queue rows do).
- **Shuffle.** `toggleShuffle` returns unless `shuffleAllowed(spinoffMode, playbackContext?.type)`, and the Now Playing shuffle button is disabled for the same condition. Check `RootView`'s deep-link `Shuffle` command, and any remote command, go through `toggleShuffle` or the same guard.
- **Messages:**
  - the empty-seed alert uses `PlaybackMessages.shared.noSimilarTracks(title:)`, replacing "Couldn't find tracks similar to …";
  - the fetch-failed alert uses `PlaybackMessages.shared.SPINOFF_FETCH_FAILED`;
  - the display name uses `spinoffFrom`;
  - starting a spinoff posts `notice = Notice(message: PlaybackMessages.shared.spinningOff(...), showInCar: false)`, as Android's toast does;
  - `resolveRadio`'s "No similar tracks for" and "Couldn't fetch similar tracks" use the shared texts.
- **Radio deep link, artist seed.** `resolveRadio` (iosMain) applies `firstPlayableIndex` with `resolveSources(...)` non-empty as `isPlayable`, before returning `radioPool`. With none playable it returns `failed` with `noPlayableSimilarTracks`; otherwise it trims the pool to start at that index.

- [ ] **Step 1: Make the change.**
- [ ] **Step 2: Build.** Run the gradle shared tests, then `xcodebuild build -project <worktree>/iosApp/Parachord.xcodeproj -scheme Parachord -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' -derivedDataPath <worktree>/iosApp/build` → `BUILD SUCCEEDED`. If the worktree lacks `iosApp/Parachord/Secrets.xcconfig` or `local.properties`, copy them from the main checkout.
- [ ] **Step 3: Check in the simulator** with a temporary hook, removed before committing:
  - spinoff start shows the notice and the pool advances;
  - shuffle is refused during the spinoff;
  - a radio link with a refill URL (a local JSPF served from the Mac or an inline `tracks=` pool plus a `refill=` URL) refills at fewer than 3 tracks and stops after 3 empty refills (log lines);
  - `parachord://play/radio?artist=<made up>` is refused.
- [ ] **Step 4: Commit** `feat(ios): shared spinoff refill, shuffle rule and messages`.

### Task 5: Docs and PR

**Files:**
- Modify: root `CLAUDE.md` "Spinoff + Listen Along — queue model": pool resolved at play time on both apps, the shared refill rules, shuffle off, shared messages, the radio first-playable check.
- Modify: `iosApp/AGENTS.md` "Spinoff + Listen Along on iOS".
- Modify: KDoc on the changed functions.

- [ ] **Step 1: Update the docs.** Run `grep -rn -i "spinoff\|refill\|POOL_REFILL" --include='*.md' .` and fix anything that describes old behaviour, outside `docs/plans/` and `docs/superpowers/`.
- [ ] **Step 2: Full check.** `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:testDebugUnitTest` → BUILD SUCCESSFUL; the iOS build as in Task 4.
- [ ] **Step 3: Rebase onto `main`** (once #583 has merged), push, and open the PR against `main`. Link #584, and list what was checked on which device.
