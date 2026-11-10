# Shared Listen Along (PR 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One Listen Along session on both apps, as a shared state machine with desktop's transitions and messages; a failed poll no longer reads as "the friend went offline".

**Architecture:** A pure `ListenAlongSession` in `shared/src/commonMain/kotlin/com/parachord/shared/playback/` takes events (start, poll result, our song ended, pause, resume, the user's actions) and returns effects (play this track, show this message, end and restore or discard the queue). Android's `MainViewModel` and iOS's `ListenAlongController` keep the polling loop, the suspended queue and the playback calls, and do what the session says. `FriendsRepository` gains a poll that tells "listening", "not listening" and "couldn't find out" apart.

**Tech Stack:** Kotlin Multiplatform (commonMain + commonTest on JVM and iosSimulatorArm64), Android ViewModel + Compose, SwiftUI.

**Spec:** `docs/superpowers/specs/2026-11-10-shared-playback-session-rules-design.md` (Area 3). Ticket: #585. Earlier PRs: #583 (queue and repeat), #599 (spinoff), #601 (repeat-all lap).

## Global Constraints

- Desktop is the reference: `app.js` ~28340-28430 and ~28510-28545, `use-playback-engine.js` ~2340-2360, ~2455-2510, ~2615-2630, ~2772-2796.
- Messages, verbatim from desktop:
  - `Listening along with <name>`
  - `<name> stopped listening`
  - `Stopped listening along with <name>`
  - `<name> hasn't moved on - resuming your queue`
- Poll every **15 000 ms** (both apps today). **4** failed polls in a row count as the friend going offline (about a minute).
- A friend is listening when the repository says so (`Friend.isOnAir`: the cached track is under 10 minutes old; a transient friend when the service flags now-playing).
- The friend's track is a metadata-only track, resolved when it plays. Android stops resolving it first and dropping it under the 0.60 floor.
- One track key for "is this the same song": `name.lowercase() + "|" + artist.lowercase()` (both apps today).
- The user's own queue is suspended once on entering Listen Along and kept across a switch to another friend (both apps today). Ending the session restores it or discards it as the session says.
- Shared-code rules from CLAUDE.md: no JVM-only APIs; no `,` `.` `;` `:` `/` `<` `>` `[` `]` `\` in commonTest names; rethrow `CancellationException`; run `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test` after shared changes. A Kotlin `suspend` function Swift calls needs `@Throws`.
- Don't change the background-survival rules (root CLAUDE.md "External Playback Background Survival", `iosApp/AGENTS.md` "Background playback").

## Decisions made in this plan (not in the spec)

- **The in-app Next button stays "catch up to the friend"** on both apps (enabled only while the friend is ahead). Any other Next or Previous (lock screen, car, headphones) ends the session and resumes the user's queue; desktop falls through to the user's queue there. Playing something else ends the session and keeps what the user chose (the suspended queue is discarded).
- **A friend's track that can't play** shows no error. The session waits for the friend's next track, or plays the held one.
- **Our song ends and the session doesn't know where the friend is** (the last poll failed, or none has answered yet): it waits for the next poll and doesn't say "hasn't moved on". This is the spec's "nothing is held and the friend has changed" case, widened to "unknown".
- **Listen Along consumes the end of its own song.** While a session is active the platform asks the session what to do when the song ends and does not also advance the queue. This replaces #583's two interim special cases (repeat counts as off, the end of the queue keeps the track).
- **`start` with a friend who has no current track** shows the start message and waits for the first poll.

## Review Focus

1. **A failed poll while our song plays must not end the session** (Task 1 test `one failed poll changes nothing`; Task 2 test for the repository's three outcomes).
2. **The friend moves on while the user has paused**: nothing plays until the user presses Play, then the friend's track plays, not the paused one (Task 1 test `a held track plays when the user resumes`).
3. **Our song ends and the session ends in the same moment** (friend offline, or hasn't moved on): the user's queue resumes at its first track, once; the end-of-track advance must not also skip it (Tasks 3 and 4, device checks).
4. **Repeat one or all during Listen Along** must not replay our song or wrap (Tasks 3 and 4: the session consumes the track end, so the advance policy is never asked).
5. **Switching straight from one friend to another** keeps the same suspended queue and doesn't show "Stopped listening along" for the first friend (Task 1 test `starting with another friend restarts the session`).

---

### Task 1: Shared session, track and messages

**Files:**
- Create: `shared/src/commonMain/kotlin/com/parachord/shared/playback/ListenAlongSession.kt`
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/playback/PlaybackMessages.kt` (four messages)
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/playback/ListenAlongSessionTest.kt`

**Interfaces:**
- Produces:
  - `const val LISTEN_ALONG_POLL_MS = 15_000L`, `const val LISTEN_ALONG_FAILED_POLLS_LIMIT = 4`
  - `data class FriendTrack(val name: String, val artist: String, val album: String?, val artworkUrl: String?) { val key: String }` with `key = name.lowercase() + "|" + artist.lowercase()`
  - `fun listenAlongTrack(track: FriendTrack): Track`: metadata only (title, artist, album, artwork), `id = "listen-along:" + track.key`, `duration = 0`, every source field null (today's iOS shape).
  - `sealed interface ListenAlongPoll { data class Listening(val track: FriendTrack) ; data object NotListening ; data object Failed }`
  - `sealed interface ListenAlongEffect { data class Play(val track: FriendTrack) ; data class Message(val text: String) ; data class End(val restoreQueue: Boolean) }`
  - `class ListenAlongSession(val friendName: String)` (plain, single-threaded; the platform calls it on its main thread). Each method returns `List<ListenAlongEffect>`:
    - `start(current: FriendTrack?)`
    - `onPoll(result: ListenAlongPoll, isPlaying: Boolean)`
    - `onSongEnded()`
    - `onTrackUnplayable()`
    - `onUserPaused()` / `onUserResumed()`
    - `onCatchUp()`
    - `onUserSkipped()` (Next or Previous that isn't the catch-up)
    - `onUserPlayedSomethingElse()`
    - `onUserStopped()` (the Listen Along toggle)
  - Read-only: `val friendIsAhead: Boolean` (a held track exists), `val lastPlayedKey: String?`, `val isEnded: Boolean`.
  - `PlaybackMessages`: `fun listeningAlongWith(name)`, `fun stoppedListening(name)`, `fun stoppedListeningAlongWith(name)`, `fun hasntMovedOn(name)`.

Rules (desktop's):
- `start(current)`: `Message(listeningAlongWith)`, then `Play(current)` when non-null (remember its key).
- `onPoll(Listening(t), isPlaying)`: clear the failed count and any "end after this song".
  - Same key as the song we last played: drop any held track. If we were waiting after our song ended (below): `Message(hasntMovedOn)`, `End(true)`.
  - A new key while we're playing, or while the user has paused: hold it (replacing an older held track). No effect.
  - A new key, not playing and not user-paused (our song ended and we were waiting, or the last track was unplayable): `Play(t)`.
- `onPoll(NotListening, isPlaying)`: when playing, mark "end after this song" and drop the held track; otherwise `Message(stoppedListening)`, `End(restoreQueue = true)`.
- `onPoll(Failed, …)`: count it. Below `LISTEN_ALONG_FAILED_POLLS_LIMIT` in a row: no effect. At the limit: as `NotListening`.
- `onSongEnded()`:
  - "End after this song" set: `Message(stoppedListening)`, `End(true)`.
  - A held track: `Play(held)`.
  - Nothing held, and the last poll answered with the song we just played: `Message(hasntMovedOn)`, `End(true)`.
  - Nothing held, and we don't know where the friend is (the last poll failed, or no poll has answered since this song started): no effect. The session waits for the next poll with the track kept on screen: a new key plays, the same key ends with `hasntMovedOn`, not listening or the 4th failed poll ends with `stoppedListening`.
- `onTrackUnplayable()`: a held track → `Play(held)`; otherwise no effect (wait for the next poll; the session is now "not playing").
- `onUserPaused()` sets the pause mark; `onUserResumed()` clears it and returns `Play(held)` when a track is held (the platform then doesn't resume the paused one), else nothing.
- `onCatchUp()`: `Play(held)` when a track is held, else nothing.
- `onUserSkipped()`: `Message(stoppedListeningAlongWith)`, `End(true)`. `onUserPlayedSomethingElse()`: `Message(stoppedListeningAlongWith)`, `End(false)`. `onUserStopped()`: `Message(stoppedListeningAlongWith)`, `End(true)`.
- After an `End`, every method returns an empty list.

- [ ] **Step 1: Write the failing tests** (`ListenAlongSessionTest`; `a` and `b` are `FriendTrack`s with different keys):
  - `starting plays the friend's track and says so`: `start(a)` → `[Message("Listening along with Ana"), Play(a)]`.
  - `starting with nothing playing waits for the first poll`: `start(null)` → message only; then `onPoll(Listening(a), isPlaying = false)` → `[Play(a)]`.
  - `the friend's next track is held until ours ends`: after `start(a)`, `onPoll(Listening(b), true)` → `[]`, `friendIsAhead` true; `onSongEnded()` → `[Play(b)]`.
  - `the same track again changes nothing`: `onPoll(Listening(a), true)` → `[]`, `friendIsAhead` false.
  - `a held track plays when the user resumes`: `onUserPaused()`, `onPoll(Listening(b), false)` → `[]`; `onUserResumed()` → `[Play(b)]`.
  - `resuming with nothing held resumes the paused track`: `onUserPaused()`, `onUserResumed()` → `[]`.
  - `the friend not moving on ends the session and resumes the queue`: `start(a)`, `onPoll(Listening(a), true)`, `onSongEnded()` → `[Message("Ana hasn't moved on - resuming your queue"), End(true)]`.
  - `our song ending after a failed poll waits for the next poll`: `start(a)`, `onPoll(Failed, true)`, `onSongEnded()` → `[]`; `onPoll(Listening(b), false)` → `[Play(b)]`.
  - `waiting ends when the friend is still on the same track`: `start(a)`, `onPoll(Failed, true)`, `onSongEnded()` → `[]`; `onPoll(Listening(a), false)` → `[Message("Ana hasn't moved on - resuming your queue"), End(true)]`.
  - `our song ending before any poll answered waits`: `start(a)`, `onSongEnded()` → `[]`.
  - `a friend who goes offline mid-song ends it after the song`: `onPoll(NotListening, true)` → `[]`; `onSongEnded()` → `[Message("Ana stopped listening"), End(true)]`.
  - `a friend who goes offline while nothing plays ends it now`: `onPoll(NotListening, false)` → `[Message("Ana stopped listening"), End(true)]`.
  - `a friend who comes back before the song ends is followed again`: `onPoll(NotListening, true)`, `onPoll(Listening(b), true)`, `onSongEnded()` → `[Play(b)]`.
  - `one failed poll changes nothing`: `onPoll(Failed, true)` ×3 → `[]` each; `onSongEnded()` with a track held earlier still plays it.
  - `four failed polls in a row count as offline`: the 4th `onPoll(Failed, false)` → `[Message("Ana stopped listening"), End(true)]`; a `Listening` poll in between resets the count.
  - `an unplayable friend track waits for the next one`: `start(a)`, `onTrackUnplayable()` → `[]`; `onPoll(Listening(b), false)` → `[Play(b)]`.
  - `catching up plays the held track`: held b, `onCatchUp()` → `[Play(b)]`; with nothing held → `[]`.
  - `a skip ends the session and resumes the queue`: `onUserSkipped()` → `[Message("Stopped listening along with Ana"), End(true)]`.
  - `playing something else ends the session and keeps the choice`: `onUserPlayedSomethingElse()` → `[Message("Stopped listening along with Ana"), End(false)]`.
  - `nothing happens after the session ended`: after an `End`, every method → `[]`.
  - `starting with another friend restarts the session`: a new `ListenAlongSession("Ben").start(b)` → `[Message("Listening along with Ben"), Play(b)]` (the platform replaces the session without calling `onUserStopped`; documents the contract).
  - `listen along tracks are metadata only`: `listenAlongTrack(a).id == "listen-along:" + a.key`, sources null.
- [ ] **Step 2: Run to see them fail.** `cd <worktree> && ./gradlew :shared:testDebugUnitTest --tests '*ListenAlongSessionTest*'` → compile failure.
- [ ] **Step 3: Implement** the file and the four messages.
- [ ] **Step 4: Run on both targets.** `./gradlew :shared:testDebugUnitTest --tests '*ListenAlongSessionTest*' --tests '*SpinoffSessionTest*' :shared:iosSimulatorArm64Test --tests '*ListenAlongSessionTest*'` → PASS.
- [ ] **Step 5: Commit** `feat(shared): Listen Along session rules and messages`.

### Task 2: A poll that tells "not listening" from "couldn't find out"

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/parachord/shared/repository/FriendsRepository.kt` (`refreshFriendActivity` ~L441, `fetchTransientFriendNowPlaying` ~L103)
- Modify: `shared/src/iosMain/kotlin/com/parachord/shared/ios/IosContainer.kt` (~L1579-1587: add a `@Throws` bridge)
- Test: `shared/src/commonTest/kotlin/com/parachord/shared/repository/FriendsListenAlongPollTest.kt` (Ktor `MockEngine` routed by host, as `ListenBrainzMbidLookupTest` does)

**Interfaces:**
- Consumes: Task 1 (`ListenAlongPoll`, `FriendTrack`).
- Produces: `suspend fun FriendsRepository.pollListenAlong(friend: Friend): ListenAlongPollResult` where `data class ListenAlongPollResult(val poll: ListenAlongPoll, val friend: Friend?)` (`friend` = the refreshed friend for the UI, null when unknown).
  - Transient friend (`friend.transient`): ask the service for now-playing. A track → `Listening`; the service answered with none → `NotListening`; the request failed → `Failed`.
  - Saved friend: refresh its activity. The refresh failed (any exception, including the Last.fm rate-limit cooldown) → `Failed`. The row is gone → `NotListening`. Otherwise `Listening(track)` when `isOnAir` with a track name and artist, else `NotListening`. A hidden Last.fm profile (`LastFmHiddenListeningException`) is `NotListening`.
- `fetchTransientFriendNowPlaying` keeps its signature for the deep link's entry check; both it and `refreshFriendActivity` are refactored onto internal helpers that report failure, so the new method doesn't swallow it.
- iOS: `IosContainer.pollListenAlong(friend)` with `@Throws(Throwable::class)`.

- [ ] **Step 1: Write the failing tests**: for ListenBrainz and Last.fm transient friends: now-playing → `Listening` with the right `FriendTrack`; an empty answer → `NotListening`; HTTP 500 and a thrown transport error → `Failed`. For a saved friend (fake DAO or the repository's existing test seam): refresh throws → `Failed`; refreshed and on air → `Listening`; refreshed and not on air → `NotListening`; deleted row → `NotListening`.
- [ ] **Step 2: Run to see them fail** (compile failure).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :shared:testDebugUnitTest --tests '*FriendsListenAlongPollTest*' :shared:iosSimulatorArm64Test --tests '*FriendsListenAlongPollTest*' :app:compileDebugKotlin :app:testDebugUnitTest --tests '*ListenAlongDispatcherTest*' --tests '*KoinModuleVerifyTest*'` → PASS.
- [ ] **Step 5: Commit** `feat(shared): Listen Along poll reports a failed request apart from "not listening"`.

### Task 3: Android uses the shared session

**Files:**
- Modify: `app/src/main/java/com/parachord/android/ui/MainViewModel.kt` (the Listen Along block ~L258-579)
- Modify: `app/src/main/java/com/parachord/android/playback/PlaybackController.kt`:
  - `onTrackEndedListener` / `onUserPlaybackActionListener` ~L223-226 and their call sites ~L386, 405, 497-499, 645;
  - the interim Listen Along cases in `skipNextInternal` ~L552-567 and `stopAtEndOfQueue`;
  - `togglePlayPause`.
- Modify: `app/src/main/java/com/parachord/android/ui/screens/nowplaying/NowPlayingScreen.kt` (repeat button disabled during Listen Along, as shuffle is)

**Interfaces:**
- Consumes: Tasks 1-2.
- `PlaybackController` hooks become (names for the builder to keep or refine):
  - `var onOwnTrackEnded: (() -> Boolean)?`: called for a track that **ended** (`AdvanceTrigger.TRACK_ENDED` only, not an unplayable skip). Returning true means "handled": the controller does not advance.
  - `var onTrackUnplayable: ((Track) -> Boolean)?`: called when the current track couldn't play (the `NO_SOURCE` / start-failure exits); true means handled (no auto-skip, no error message).
  - `var onUserAction: ((UserPlaybackAction) -> Unit)?` with `enum UserPlaybackAction { SKIP, PLAY_SOMETHING_ELSE, PAUSE, RESUME }` in place of the single `onUserPlaybackActionListener`.
  - `togglePlayPause` on a user resume asks `var onUserResume: (() -> Boolean)?` first; true means the listener started something else (the held track), so don't resume.

Behaviour:
- `MainViewModel` holds a `ListenAlongSession?`. `startListenAlong(friend)` creates it, runs `start(current)` and applies the effects; the 15 s loop calls `friendsRepository.pollListenAlong(friend)` and `session.onPoll(result.poll, isPlaying)`, and updates `_listenAlongFriend` from `result.friend`. The old fields (`pendingListenAlongTrack`, `deferredStopFriendName`, `_lastListenAlongKey`) go; `listenAlongFriendIsAhead` reads `session.friendIsAhead`; `advanceToFriend()` calls `onCatchUp()`.
- Applying effects: `Play(t)` → `playbackController.playTrack(listenAlongTrack(t), context = PlaybackContext(LISTEN_ALONG_CONTEXT, friendName))` with the "Listen Along is driving" guard kept; `Message` → the toast flow; `End(restore)` → today's `stopListenAlong` body (cancel the loop, release the wake lock, restore or discard the suspended queue) without its own toast.
- No up-front resolve: the `resolveWithHints` + `selectBest` block is removed.
- The controller no longer special-cases Listen Along: remove the repeat-as-OFF and `stopAtEndOfQueue(clear = !listenAlong)` paths. While a session is active `onOwnTrackEnded` returns true.
- Replace every remaining literal toast in the Listen Along block with `PlaybackMessages`.
- The repeat button is disabled while `playbackContext.type == LISTEN_ALONG_CONTEXT`.
- `AndroidProtocolPlayTeardown`'s Listen Along stopper (a `parachord://play` or radio link taking over) keeps ending the session silently and discarding the suspended queue: it calls the same end path as `End(restoreQueue = false)` without a message, and the Spinoff start does the same.

- [ ] **Step 1: Make the change.**
- [ ] **Step 2: Build and test.** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests '*KoinModuleVerifyTest*' --tests '*Playback*' --tests '*ListenAlong*' --tests '*ForwardingPlayer*'` → PASS.
- [ ] **Step 3: Check on the emulator** (`emulator-5554` only; never the physical phone unless the controller says so) with a temporary debug hook, removed before committing, that feeds the session scripted poll results for a fake friend (tracks that resolve through Bandcamp without sign-in). Record logcat for each:
  - start → toast and the friend's track plays;
  - a new track while ours plays → held, plays when ours ends;
  - paused, then a new track → nothing plays; Play starts the friend's track;
  - song ends with the friend on the same track → "hasn't moved on" and the suspended queue resumes at its **first** track;
  - not listening mid-song → the song finishes, then "stopped listening" and the queue resumes;
  - three failed polls → nothing; the fourth → "stopped listening";
  - a media-button Next → "Stopped listening along" and the queue resumes; a tap on another track → the session ends and that track plays;
  - repeat all on throughout: our song never replays, nothing wraps.
- [ ] **Step 4: Commit** `feat(android): Listen Along runs on the shared session`.

### Task 4: iOS uses the shared session

**Files:**
- Modify: `iosApp/Parachord/ListenAlongController.swift`
- Modify: `iosApp/Parachord/ContentView.swift`:
  - `onTrackFinished` ~L1970 and its use in `advance` ~L2625-2632;
  - the interim Listen Along cases in `advance` / `stopAtEndOfQueue`;
  - `togglePlayPause`, `skipNext`, `skipPrevious`;
  - the `.noPlayableSource` branch for a Listen Along track.
- Modify: `iosApp/Parachord/NowPlayingScreen.swift` (repeat button disabled during Listen Along; Next still calls `advanceToFriend`)
- Modify: `iosApp/Parachord/RootView.swift` if the notices need wiring

**Interfaces:**
- Consumes: Task 1 (`ListenAlongSession`, effects as Swift classes: check with `is ListenAlongEffectPlay` etc.; `PlaybackMessages.shared`), Task 2 (`IosContainer.pollListenAlong(friend:)`).

Behaviour (the same as Android's Task 3):
- `ListenAlongController` holds the session, applies effects (`Play` → `setQueue([listenAlongTrack])` with the Listen Along context and the friend id; `Message` → `coordinator.notice = Notice(message:, showInCar: false)`; `End(restore)` → today's `stop(restore:)` body without re-entering the session).
- The poll calls `pollListenAlong` and `onPoll(…, isPlaying:)`. A failed bridge call counts as `Failed`.
- Pause and resume from the coordinator's `togglePlayPause` reach the session; a resume that returns `Play` plays the held track.
- `skipNext` / `skipPrevious` from the lock screen, CarPlay or headphones during a session call `onUserSkipped()`; the Now Playing Next button keeps calling `advanceToFriend()` → `onCatchUp()`.
- A user play of anything else (a `setQueue` whose context isn't Listen Along while a session is active) calls `onUserPlayedSomethingElse()`; today's "the next poll sees a different context" check becomes immediate.
- A Listen Along track with no playable source calls `onTrackUnplayable()`, with no notice and no skip.
- The coordinator no longer special-cases Listen Along in `advance` / `stopAtEndOfQueue`; `onTrackFinished` keeps consuming the track end.
- The repeat button is disabled during Listen Along.
- Starting a Spinoff or a radio link during a session ends it silently, as today (`SpinoffController`, `IosContainer` radio teardown).

- [ ] **Step 1: Make the change.**
- [ ] **Step 2: Build.** Gradle shared tests, then `xcodebuild build -project <worktree>/iosApp/Parachord.xcodeproj -scheme Parachord -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' -derivedDataPath <worktree>/iosApp/build` → `BUILD SUCCEEDED`.
- [ ] **Step 3: Check in the simulator** with a temporary hook (removed before committing; grep that none remains) feeding scripted poll results, covering the same list as Task 3 Step 3, with `NSLog` evidence.
- [ ] **Step 4: Commit** `feat(ios): Listen Along runs on the shared session`.

### Task 5: Docs and PR

**Files:**
- Modify: root `CLAUDE.md` "Spinoff + Listen Along — queue model" (the Listen Along bullet), "Queue advance and repeat (shared)" (drop the interim Listen Along sentences), "Friend Follow / Unfollow" if it mentions the transient poll.
- Modify: `iosApp/AGENTS.md` "Spinoff + Listen Along on iOS", "Queue advance on iOS".
- Modify: KDoc on the changed functions.

- [ ] **Step 1: Update the docs.** `grep -rn -i "listen along\|listen-along\|listenAlong" --include='*.md' .` and fix what describes old behaviour, outside `docs/plans/`, `docs/design/` and `docs/superpowers/`.
- [ ] **Step 2: Full check.** `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :app:testDebugUnitTest` → BUILD SUCCESSFUL; the iOS build.
- [ ] **Step 3: A real-friend check with the user** on the Pixel 9a and an iOS device or simulator: listen along with a friend who is playing music (or the user's own account playing on another device, through `parachord://listen-along?service=…&user=…`), through one track change and one stop.
- [ ] **Step 4: Rebase onto `main`, push, open the PR** against `main`, closing #585, listing what was checked on which device.
