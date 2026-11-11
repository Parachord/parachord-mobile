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
