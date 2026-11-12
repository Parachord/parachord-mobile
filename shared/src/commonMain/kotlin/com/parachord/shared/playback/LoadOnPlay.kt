package com.parachord.shared.playback

import com.parachord.shared.model.Track

/**
 * A track on screen that nothing holds, which Play has to start through the
 * whole pipeline (Android: `PlaybackController.togglePlayPause`, and the media
 * session's play through `PlaybackService`; iOS: the queue coordinator's play).
 * The player holds its silent keep-alive loop, a failed item or nothing, so
 * resuming it does nothing. Both platforms keep one of these in a slot and
 * follow the same rules below.
 */
data class LoadOnPlay(
    val track: Track,
    /** Where Play starts it, in ms (0 = from the top). */
    val positionMs: Long,
    /** Keep the source the track had (a manual pick) instead of choosing again. */
    val skipReselect: Boolean,
    val why: Why,
    /** The resolver the person picked by hand, kept for the retry; null when none was. */
    val pickedResolver: String? = null,
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
         * Nothing could play it and the queue didn't move on ([failedPlayAwaitsLoad]):
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
fun loadOnPlayTarget(pending: LoadOnPlay?, currentTrackId: String?): LoadOnPlay? =
    pending?.takeIf { currentTrackId != null && it.track.id == currentTrackId }

/**
 * What the idle timeout leaves for Play when it stops the paused external
 * [current] track at [positionMs]: a slot already [pending] for that track
 * (a failed play, which re-chooses its source; a queue given back paused),
 * else [LoadOnPlay.Why.IDLE_STOPPED], which keeps the source and resumes
 * where it was paused.
 */
fun idleTimeoutLoad(pending: LoadOnPlay?, current: Track?, positionMs: Long): LoadOnPlay? {
    if (current == null) return null
    loadOnPlayTarget(pending, current.id)?.let { return it }
    return LoadOnPlay(current, positionMs, skipReselect = true, why = LoadOnPlay.Why.IDLE_STOPPED)
}

/**
 * The [LoadOnPlay] for a failed play of [track]. A resume that never started
 * (after the idle timeout, a restored queue) keeps the position it aimed for,
 * [aimedAtMs]. A stream that failed while playing starts again where it
 * stopped, [failedAtMs], by the rules a restored track follows
 * ([PlaybackResumePosition]): its first 5 s or last 10 s start over.
 * [pickedResolver] is the resolver the person chose by hand, kept for the retry.
 */
fun failedLoad(
    track: Track,
    skipReselect: Boolean,
    failedAtMs: Long,
    durationMs: Long,
    aimedAtMs: Long = 0,
    pickedResolver: String? = null,
): LoadOnPlay {
    val atFailure = PlaybackResumePosition.resumeAtMs(
        SavedPlaybackPosition(track.id, failedAtMs, durationMs),
        track.id,
    )
    return LoadOnPlay(track, maxOf(atFailure, aimedAtMs), skipReselect, LoadOnPlay.Why.FAILED, pickedResolver)
}

/**
 * Where Previous measures from: the slot's position while its track is the
 * current one (the player holds nothing, so its own position reads 0), else
 * the player's, [playerPositionMs].
 */
fun positionForPrevious(playerPositionMs: Long, pending: LoadOnPlay?, currentTrackId: String?): Long =
    loadOnPlayTarget(pending, currentTrackId)?.positionMs ?: playerPositionMs

/** The same slot at position 0: what Previous leaves when it restarts the track. */
fun LoadOnPlay.restartedFromTop(): LoadOnPlay = copy(positionMs = 0)

/**
 * Whether Play has nothing to start (#617): no track on screen (the queue
 * ended with repeat off and the track was cleared, or the queue was cleared),
 * nothing playing and nothing starting. The lock screen, the car or a headset
 * can still send Play then; resuming the last engine would report "playing"
 * with nothing loaded, or restart a stale track. Play does nothing instead.
 */
fun playStartsNothing(hasCurrentTrack: Boolean, isPlaying: Boolean, isStarting: Boolean): Boolean =
    !hasCurrentTrack && !isPlaying && !isStarting
