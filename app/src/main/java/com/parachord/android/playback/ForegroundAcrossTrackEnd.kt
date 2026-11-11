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
 * itself (external playback's, or one kept across a track's end) in place
 * until ExoPlayer plays, instead of dropping it first. Dropped, Media3 has to
 * start the foreground again for the local track, which Android refuses from
 * the background. Only for a play nobody asked for (the queue moving on) with
 * no Parachord screen showing: a user's request opens a window in which
 * Media3's start is allowed, and with a screen showing it's allowed anyway,
 * so those keep dropping it as before.
 */
internal fun localPlayKeepsHeldForeground(userRequested: Boolean, appVisible: Boolean): Boolean =
    !userRequested && !appVisible

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
 * How long a foreground the service holds itself has been idle after one
 * more check: reset while external playback runs ([externalMode], whose own
 * pause and idle timeout manage it) or a play, an advance or a skip is under
 * way ([transitionInProgress]); otherwise [idleMs] plus [stepMs]. The hold
 * goes once this reaches [FOREGROUND_HOLD_IDLE_LIMIT_MS], so it can't outlive
 * a stalled advance with nothing playing.
 */
internal fun nextForegroundHoldIdleMs(
    idleMs: Long,
    stepMs: Long,
    externalMode: Boolean,
    transitionInProgress: Boolean,
): Long = if (externalMode || transitionInProgress) 0L else idleMs + stepMs
