package com.parachord.android.playback

/**
 * Keeping PlaybackService in the foreground when a local track (ExoPlayer)
 * ends by itself and the queue moves on (#614).
 *
 * Media3 1.5.1's MediaSessionService takes the service out of the foreground
 * as soon as its player stops being "playing" (`STATE_ENDED`), and Android
 * 12+ doesn't let an app that isn't on screen start a foreground service
 * again: the next track's request, an external route's or Media3's own for
 * the next local track, is refused with `ForegroundServiceStartNotAllowedException`
 * (seen on a Pixel 9a, Android 17, 186 ms after the advance began), and on
 * Android 15+ the app's network is cut soon after. So the foreground is kept
 * across the end instead of asked for again: at `STATE_ENDED` the service
 * stops passing Media3's notification updates on (its own external-playback
 * foreground, [PlaybackService]'s `isExternalForeground`), while it is still
 * in the foreground, and the next track's route takes it over.
 */

/**
 * Whether the service keeps the foreground when the local player ends the
 * current track by itself.
 *
 * - [appVisible]: with a Parachord screen showing, a later start is allowed.
 * - [externalPlayback]: external playback holds its own foreground already.
 * - [somethingFollows]: the queue has a next track (or repeat replays or
 *   wraps), a spinoff pool has one, or Listen Along drives the track and
 *   decides what follows. At the end of the queue Media3 lets it go, as before.
 */
internal fun shouldKeepForegroundAtLocalTrackEnd(
    appVisible: Boolean,
    externalPlayback: Boolean,
    somethingFollows: Boolean,
): Boolean = !appVisible && !externalPlayback && somethingFollows

/**
 * Whether a local (ExoPlayer) play leaves the foreground the service holds
 * itself (external playback's, a play-start hold, or one kept across a
 * track's end) in place until ExoPlayer plays, instead of dropping it first.
 * Dropped, Media3 has to start the foreground again for the local track,
 * which Android refuses from the background. Kept whenever no Parachord
 * screen shows, whoever asked for the play: plays that count as
 * user-requested aren't always a person's (Listen Along's `playTrack` and
 * `playQueue`), and with the service still in the foreground Media3's own
 * start is an allowed update either way, so dropping first gains nothing.
 * With a screen showing a later start is allowed, and it drops as before.
 */
internal fun localPlayKeepsHeldForeground(appVisible: Boolean): Boolean = !appVisible

/**
 * The silent keep-alive loop external playback plays on ExoPlayer is an app
 * resource (`android.resource://…/raw/silence`); a track ExoPlayer plays
 * itself (a local file's `content://`, a stream's `https://`) never is. When
 * ExoPlayer starts playing anything else while the service holds its own
 * foreground, the service hands the foreground back to Media3.
 */
internal fun isSilenceKeepAliveUri(uri: String?): Boolean =
    uri != null && uri.startsWith("android.resource://")

/** How often the service checks a foreground it holds itself. */
internal const val FOREGROUND_HOLD_CHECK_MS = 5_000L

/**
 * How long the service keeps a foreground it holds itself with nothing
 * playing and nothing starting, before letting it go.
 */
internal const val FOREGROUND_HOLD_IDLE_LIMIT_MS = 30_000L

/**
 * How long it's kept while Listen Along waits for the friend's next track
 * after a local song (the controller's `heldForListener`): 5 minutes, the
 * length of the idle timeout. The session's own end (a friend who stopped,
 * failed polls) releases it sooner. An external song held for the listener
 * has no such limit: the session's player stays in external mode after the
 * idle timeout, so its foreground stays until the session ends. Once this
 * limit passes, the friend's next track can't take the foreground from the
 * background.
 */
internal const val FOREGROUND_HOLD_LISTENER_LIMIT_MS = 5L * 60 * 1000

/**
 * How long a foreground the service holds itself has been idle after one
 * more check: reset while external playback runs ([external]: its own pause
 * and idle timeout manage it) or a play, an advance or a skip is under way
 * ([transitionInProgress]); otherwise [idleMs] plus [stepMs].
 */
internal fun nextForegroundHoldIdleMs(
    idleMs: Long,
    stepMs: Long,
    external: Boolean,
    transitionInProgress: Boolean,
): Long = if (external || transitionInProgress) 0L else idleMs + stepMs

/**
 * Whether the service lets go of a foreground it holds after [idleMs] of
 * nothing using it: [FOREGROUND_HOLD_IDLE_LIMIT_MS], or
 * [FOREGROUND_HOLD_LISTENER_LIMIT_MS] while Listen Along waits for its next
 * track ([listenerWaiting]). So it can't outlive a stalled advance, or a local
 * track that never starts, with nothing playing.
 */
internal fun foregroundHoldExpired(idleMs: Long, listenerWaiting: Boolean): Boolean =
    idleMs >= if (listenerWaiting) FOREGROUND_HOLD_LISTENER_LIMIT_MS else FOREGROUND_HOLD_IDLE_LIMIT_MS
