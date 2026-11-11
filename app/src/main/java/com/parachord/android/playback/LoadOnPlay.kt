package com.parachord.android.playback

/** How a play ended with nothing playing: shared with iOS (`shared/.../playback/PlayFailure.kt`). */
typealias PlayFailure = com.parachord.shared.playback.PlayFailure

/** How the controller stops what was playing before a track that can't play ([failedTrackStop]). */
internal enum class FailedTrackStop {
    /**
     * Stop the external handler, its polling and its end-of-track detection,
     * but keep external mode, the silent keep-alive loop and the foreground:
     * the queue skips on, and a play started from the background (screen off,
     * the car) can't take the foreground back once it's gone. The next
     * track's route takes it over; the end of the queue gives it up.
     */
    KEEP_EXTERNAL_FOREGROUND,

    /** Stop the external handler and leave external mode and the foreground: playback stops on the track. */
    LEAVE_EXTERNAL,

    /** Stop and clear ExoPlayer, so the previous item can't end (and advance the queue) or report its position. */
    CLEAR_LOCAL_PLAYER,
}

/**
 * [FailedTrackStop] for a track that can't play: [isExternal] when an external
 * handler (Spotify, Apple Music, an embed) was playing before it, [autoSkip]
 * when the queue skips past it ([com.parachord.shared.playback.failedPlayAwaitsLoad] false),
 * [followed] when a listener drives the track and plays its next one
 * ([PlaybackController.drivesTrack], Listen Along): both keep the foreground,
 * so the next track can start with the screen off.
 */
internal fun failedTrackStop(isExternal: Boolean, autoSkip: Boolean, followed: Boolean = false): FailedTrackStop = when {
    !isExternal -> FailedTrackStop.CLEAR_LOCAL_PLAYER
    autoSkip || followed -> FailedTrackStop.KEEP_EXTERNAL_FOREGROUND
    else -> FailedTrackStop.LEAVE_EXTERNAL
}

/**
 * Play or Pause does nothing while the queue is about to skip past the track
 * on screen ([pendingTrackId], the track [PlaybackController]'s 600 ms
 * auto-skip is waiting on): playing it again would re-play a track that
 * can't play, and the skip would then start the next one without it.
 */
internal fun autoSkipPendingFor(pendingTrackId: String?, currentTrackId: String?): Boolean =
    pendingTrackId != null && pendingTrackId == currentTrackId

/**
 * Whether a play that ended with [failure] (null: it started) marks the
 * queue as played (`playedSinceWrap`). Only for the queue it belongs to: a
 * new queue set while it was in flight ([queueAtStart] != [queueNow]) hasn't
 * played anything yet.
 */
internal fun playMarksQueuePlayed(completed: Boolean, failure: PlayFailure?, queueAtStart: Int, queueNow: Int): Boolean =
    completed && failure == null && queueAtStart == queueNow

/**
 * Whether the foreground a play took as it started ([shouldPromoteAtPlayStart])
 * stays for the next track when nothing played: only when the queue skips past
 * this track ([skipPendingForTrack]) and no external playback owns the
 * foreground. Released here, the next track would have to promote from the
 * background, which Android refuses with the screen off.
 */
internal fun keepsForegroundForAutoSkip(skipPendingForTrack: Boolean, externalPlayback: Boolean): Boolean =
    skipPendingForTrack && !externalPlayback

/**
 * Whether the media session's seek goes to [PlaybackController.seekTo]
 * ([controllerExternal]: an external engine plays; [loadPending]: the track
 * waits for Play to load it, so the seek moves where Play starts) instead of
 * ExoPlayer. Decided by the controller's own state, never the session
 * player's external flag: when the two disagreed (external mode left on
 * after a failed track), the controller sent its seek to the session, which
 * sent it back, forever (#607).
 */
internal fun sessionSeekGoesToController(controllerExternal: Boolean, loadPending: Boolean): Boolean =
    controllerExternal || loadPending
