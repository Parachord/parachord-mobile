package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.playback.PlaybackResumePosition
import com.parachord.shared.playback.SavedPlaybackPosition

/**
 * A track on screen that nothing holds, which Play has to start through the
 * whole pipeline (`PlaybackController.togglePlayPause`, and the media
 * session's play through `PlaybackService`). ExoPlayer holds its silent
 * keep-alive loop, a failed item or nothing, so resuming it does nothing.
 * iOS's `needsLoadOnPlay` is the same idea.
 */
data class LoadOnPlay(
    val track: TrackEntity,
    /** Where Play starts it, in ms (0 = from the top). */
    val positionMs: Long,
    /** Keep the source the track had (a manual pick) instead of choosing again. */
    val skipReselect: Boolean,
    val why: Why,
) {
    enum class Why {
        /** The queue restored at launch (#567): starts where the app last was. */
        RESTORED,

        /**
         * The idle timeout stopped a paused external track (#550): Apple
         * Music, Spotify or an embed, 5 minutes after the pause. Starts where
         * it was paused.
         */
        IDLE_STOPPED,

        /**
         * Nothing could play it and the queue didn't move on ([com.parachord.shared.playback.failedPlayAwaitsLoad]):
         * offline, every source refused, the stream failed. Starts where that
         * play was meant to start, or where the stream stopped ([failedLoad]).
         */
        FAILED,
    }
}

/**
 * What Play should load: [pending], when its track is still the current one;
 * null when nothing is waiting or another track has been played since.
 */
internal fun loadOnPlayTarget(pending: LoadOnPlay?, currentTrackId: String?): LoadOnPlay? =
    pending?.takeIf { currentTrackId != null && it.track.id == currentTrackId }

/** How a play ended with nothing playing: shared with iOS (`shared/.../playback/PlayFailure.kt`). */
typealias PlayFailure = com.parachord.shared.playback.PlayFailure

/**
 * The [LoadOnPlay] for a failed play of [track]. A resume that never started
 * (after the idle timeout, a restored queue) keeps the position it aimed for,
 * [aimedAtMs]. A stream that failed while playing starts again where it
 * stopped, [failedAtMs], by the rules a restored track follows
 * ([PlaybackResumePosition]): its first 5 s or last 10 s start over.
 */
internal fun failedLoad(
    track: TrackEntity,
    skipReselect: Boolean,
    failedAtMs: Long,
    durationMs: Long,
    aimedAtMs: Long = 0,
): LoadOnPlay {
    val atFailure = PlaybackResumePosition.resumeAtMs(
        SavedPlaybackPosition(track.id, failedAtMs, durationMs),
        track.id,
    )
    return LoadOnPlay(track, maxOf(atFailure, aimedAtMs), skipReselect, LoadOnPlay.Why.FAILED)
}

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
 * when the queue skips past it ([com.parachord.shared.playback.failedPlayAwaitsLoad] false).
 */
internal fun failedTrackStop(isExternal: Boolean, autoSkip: Boolean): FailedTrackStop = when {
    !isExternal -> FailedTrackStop.CLEAR_LOCAL_PLAYER
    autoSkip -> FailedTrackStop.KEEP_EXTERNAL_FOREGROUND
    else -> FailedTrackStop.LEAVE_EXTERNAL
}
