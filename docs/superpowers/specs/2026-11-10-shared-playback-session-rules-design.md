# Shared playback-session rules: queue, spinoff, Listen Along, Play on a stopped track

Date: 2026-11-10. Status: design, approved for planning once reviewed.

## Why

Android's `PlaybackController` (`app/src/main/java/com/parachord/android/playback/PlaybackController.kt`) and iOS's `QueuePlaybackCoordinator` (`iosApp/Parachord/ContentView.swift`) each carry their own copy of the rules for what plays next, how a spinoff and Listen Along run, and what Play does on a track nothing holds. The copies have drifted: only iOS has repeat, the end of the queue behaves differently, spinoff refills at different points, Listen Along drops tracks on Android that iOS plays, and the messages differ. CLAUDE.md says rules live once, in `shared/commonMain`, and both apps follow them. This moves those rules there, settles every difference, and makes both apps use the shared rules.

## Goals

- The rules below live in `shared/src/commonMain/kotlin/com/parachord/shared/playback/` as plain, unit-tested Kotlin (no platform types, no coroutines where a pure function will do), with tests in `commonTest` that run on the JVM and `iosSimulatorArm64Test`.
- Android and iOS call them; neither keeps its own copy of a rule that moved.
- Every difference listed below behaves as decided here on both platforms.
- The user-facing texts for these features come from one shared object.

## Non-goals

- Moving the playback engines, routers, polling loops, timers, audio sessions or media sessions. Those stay per platform; only the decisions move.
- Changing desktop. Where this design differs from desktop, a desktop ticket is drafted for the user to approve (see the end).
- The other shared-code candidates from the November audit (source reselection, "play album by name", badge visibility, search, formatters, sync picker). Separate work.

## Anti-goals

- A shared "playback controller" that tries to own the engines. The shared code answers questions ("what plays next?", "refill now?", "what does Play load?"); the platforms act on the answers.
- Behaviour changes nobody decided. Anything not listed here keeps its current behaviour on each platform.

## Decisions

Decided by the user in conversation are marked *(user)*; the rest follow desktop, which CLAUDE.md names as the source of truth, and are marked *(desktop)* or, where desktop has no equivalent, *(design call)*.

### Area 1: queue and repeat (PR 1)

- **Repeat off / all / one is a shared feature, and Android gets it.** *(user)* The shared `RepeatMode` (`PlaybackState.kt`) and `PlaybackEngine.cycleRepeatMode` exist but Android never changes the mode. The button cycles off → all → one → off. Under ONE a track that ends replays itself; Next still advances. Under ALL, the end of the queue starts again from the top. Android needs the button (Now Playing, beside shuffle) and the media session's repeat command.
- **What repeat ALL wraps to: everything in this queue**, in the order it played: the play history, the current track and Up Next, including tracks added with Add to Queue or Play Next. Next on the last track wraps too. *(design call, differs from iOS today:* iOS wraps to the list last given to `setQueue`, which leaves out added tracks, is empty after a restored queue or a Listen Along restore, and Next on the last track stops instead of wrapping.)* Shuffle on: wrap to the same set, reshuffled.
- **End of the queue clears the last track.** *(user)* When the queue (and any spinoff pool) runs out with repeat off, playback stops and the current track is cleared, on both platforms. iOS does this today; Android keeps the track paused on screen and must change. Desktop keeps it (ticket). Not the end of the queue: a Listen Along session waiting for the friend's next track (Area 3), which keeps its track.
- **A track with no playable source, while the queue advances or a spinoff runs: skipped when online, playback stops on it when offline.** *(user)* Online this is desktop's `autoSkipIfAdvancing("no playable source")` and iOS today; Android stops on it today (it reaches ExoPlayer with no URI and fails as `PLAYER_ERROR`) and changes. Offline it stops on the track for Play, as #573 made iOS do, because every next track would fail the same way. A tapped track (not advancing) with no source stops for Play in both cases. Shared shape: a new `PlayFailure.NO_SOURCE` that `failedPlayAwaitsLoad` lets skip during an advance or spinoff, like `EMBED_REFUSED`; when the device is offline the platforms record `START_FAILED` instead (iOS `IosNetworkStatus`, Android `NetworkMonitor.isOnline`), which never skips. Android must detect "no source" before routing, not let ExoPlayer fail on it.
- **Previous restarts the track when more than 3 s in.** *(design call: both mobile apps do this; desktop always goes back.)*
- **Previous with no earlier track restarts the current track.** *(desktop; iOS already does this. Android does nothing today.)*
- Shared shape (builder may refine): a pure `QueueAdvancePolicy` that, given the advance kind (track ended / Next pressed / Previous pressed), the repeat mode, whether a spinoff pool has a track, whether the queue has a next track, whether an original queue exists to wrap to, and the position, returns one action: play the pool's next track, play the queue's next track, replay the current track, wrap to the start, restart the current track, go to the previous track, or stop and clear.

### Area 2: spinoff (PR 2)

- **Pool tracks are resolved when they play**, not all up front. *(desktop, iOS)* Android currently resolves the whole pool one track at a time before starting and drops misses; it changes to queue the pool as metadata-only tracks and let on-the-fly resolution handle each (with background pre-resolution of the next few, as for the queue). A pool track with no source is skipped (Area 1's `NO_SOURCE` rule); a pool that runs out ends the spinoff and the queue resumes.
- **Whether a spinoff is available** stays the shared `checkSpinoffAvailable` (Last.fm answers), no longer "did any pool track resolve". Android's radio deep link (#397) currently uses the up-front resolve to decide whether to replace the user's queue; it must keep refusing a station whose pool came back empty from Last.fm, and the builder decides what replaces the resolve check there.
- **Refill when fewer than 3 tracks are left.** *(desktop, Android)* iOS refills at 1 or fewer and changes. Android's `PoolRefiller` rules (`app/.../playback/PoolRefiller.kt`) move to shared and both apps use them: at most one refill every 5 s, stop after 3 empty or failed refills in a row, drop tracks already in the pool (by recording MBID, then artist + title). *(design call to keep these.)*
- **Shuffle is off during a spinoff and during Listen Along.** *(desktop, Android)* iOS allows it today and changes.
- **One set of messages**, shared: the empty-pool, fetch-failed and no-seed texts, and "Spinoff from T by A" for Up Next. Android's wording wins where the two differ, unless desktop has its own.

### Area 3: Listen Along (PR 3)

- **The friend's track plays even when no source was found yet**; it is resolved at play time like any metadata-only track. *(desktop, iOS)* Android currently resolves it first and silently drops it below the 0.60 confidence floor; it stops dropping.
- **Start and stop messages on both platforms**: "Listening along with X", "X stopped listening" and "Stopped listening along with X" (desktop and Android; iOS shows none today), and "X hasn't moved on — resuming your queue" (desktop only; both apps gain it).
- **Transitions follow desktop** (`app.js` ~28340-28430, `use-playback-engine.js` ~2460-2510 and ~2775-2790), as one shared state machine the platforms drive with poll results and playback events. Polling stays per platform (15 s). *(desktop)*
  - Start: play the friend's current track.
  - The friend changes track while ours plays: hold theirs and play it when ours ends.
  - The friend changes track while the user has paused: hold theirs and play it when the user presses Play. (Android plays it at once today; iOS waits for the song to end.)
  - Our song ends and the friend is still on the same track: end the session with "X hasn't moved on — resuming your queue" and resume the user's queue. (Android keeps waiting today.)
  - Our song ends, nothing is held, and the friend has changed: wait for the next poll, keeping the track on screen. This is not the end of the queue (Area 1).
  - The friend goes offline: if a song is playing, end after it; otherwise end now with "X stopped listening" and resume the queue.
  - The user takes control (Next, Previous, plays something else): end with "Stopped listening along with X".
- **A failed poll is not "the friend went offline"**: keep the session and try again at the next poll; after 4 failed polls in a row (about a minute) treat it as offline. *(design call)* Today iOS ends the session on one failed poll, and Android does the same for deep-link (transient) sessions, because `FriendsRepository.fetchTransientFriendNowPlaying` returns null both on failure and when the friend isn't listening. In scope: change that shared API to tell the two apart.

### Area 4: Play on a stopped track (PR 4)

- Android's `LoadOnPlay`, `loadOnPlayTarget` and `failedLoad` (`app/.../playback/LoadOnPlay.kt`) move to shared, next to `PlayFailure`, and iOS uses them in place of its `needsLoadOnPlay` + `restoredResumeAtSec` pair. iOS gains what it lacks today: the slot applies only while its track is current; a manually picked source is kept on retry (`skipReselect`); a stream that failed while playing resumes where it stopped (the restored-track rules: first 5 s or last 10 s start over).
- **A tapped track with no source stops for Play on iOS too**, as on Android (`PLAYER_ERROR`), instead of skipping. *(Android; follows the shared `failedPlayAwaitsLoad`, which already says only an embed refusal during an advance or spinoff skips.)* An advancing queue still skips such a track only if `failedPlayAwaitsLoad` says so.
- **Android Auto uses the shared `CarUnplayableRun`**: in the car with Parachord not in front on the phone, the third unplayable track in a row stops with `CarMessages.OPEN_PARACHORD_ON_PHONE`, as CarPlay does. Root CLAUDE.md lists this rule as shared car logic, but Android never adopted it, and with no-source tracks now skipped during an advance (Area 1) an Android Auto playlist could otherwise run silently to its end. "In the car" on Android: an Android Auto controller is connected to the `MediaLibrarySession` (its package, `com.google.android.projection.gearhead`, as Media3 reports it), with no new dependency. *(design call)* What counts as unplayable: the failures that skip (`NO_SOURCE`, `EMBED_REFUSED` during an advance).

### Shared texts (spread across the PRs)

A `PlaybackMessages` object (like `CarMessages`) holds "X can't play this track here. Playing via Y.", "Open in X", the spinoff and Listen Along texts above. Each PR moves the texts its area uses.

## Constraints

- `shared/commonMain` rules from CLAUDE.md (no JVM-only APIs, `kotlin.concurrent.Volatile`, no `\p{N}`, no `,` in commonTest names). Run `./gradlew :shared:testDebugUnitTest :shared:iosSimulatorArm64Test` for every shared change.
- Swift sees Kotlin enums as classes with lower-camel cases, top-level functions as `<File>Kt.fn`, objects as `.shared`.
- No change to the background-survival rules in CLAUDE.md "External Playback Background Survival" (Android) or `iosApp/AGENTS.md` "Background playback" (iOS).
- Each PR ships both platforms together, verified on an Android device or emulator and in the iOS simulator (iOS device where the behaviour needs one), and updates the docs that describe it: root CLAUDE.md ("Spinoff + Listen Along", "External Playback Background Survival" → `LoadOnPlay`, CarPlay/Android Auto section), `iosApp/AGENTS.md`, KDoc.

## Order

PR 1 (queue and repeat) first: spinoff and Listen Along plug into the advance policy. Then PR 2, PR 3, PR 4; PR 4 is independent of 2 and 3 and may go earlier.

## Calls made in this document (not decided by the user)

- Previous restarts the track when more than 3 s in (mobile behaviour, not desktop's).
- Keeping Android's refill limits (5 s apart, stop after 3 empty refills, dedup).
- Repeat ALL wraps everything in the queue, and Next wraps under ALL.
- Listen Along follows desktop's transitions; 4 failed polls in a row count as offline.
- Android Auto detection from the media session's connected controller.

## Left to the builder

- Exact names and signatures of the shared types, and whether the advance policy and the Listen Along machine are pure functions or small stateful classes.
- How Android draws the repeat button and wires the media session's repeat command.
- How the pool's background pre-resolution is scheduled on Android once it stops resolving up front.
- Test cases, beyond covering every decision above.

## Desktop

The user decided two behaviours desktop doesn't have: repeat off/all/one, and clearing the last track at the end of the queue. Desktop also has no "restart when more than 3 s in" rule and doesn't keep Android's refill limits. After the PRs, draft one desktop ticket on `Parachord/parachord` covering these and ask the user before filing.
