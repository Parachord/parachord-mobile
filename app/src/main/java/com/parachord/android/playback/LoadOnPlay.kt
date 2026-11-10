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
internal fun loadOnPlayTarget(pending: LoadOnPlay?, currentTrackId: String?): LoadOnPlay? =
    pending?.takeIf { currentTrackId != null && it.track.id == currentTrackId }

/** How a play ended with nothing playing. */
enum class PlayFailure {
    /** An embedded player (SoundCloud, Bandcamp) refused it and no other source could play it. */
    EMBED_REFUSED,

    /** Spotify can't start from the background and there's no other source. */
    NO_SOURCE_WITHOUT_SPOTIFY,

    /** The handler didn't start within `PLAY_START_TIMEOUT_MS` (#449). */
    START_TIMED_OUT,

    /**
     * The handler gave up without starting it (`PlaybackStartFailedException`):
     * Apple Music offline, where MusicKit's play fails at once, or MusicKit
     * that never loaded.
     */
    START_FAILED,

    /** The play threw. */
    ERROR,

    /** ExoPlayer's item failed (a local file or a stream; offline, or the stream dropped). */
    PLAYER_ERROR,
}

/**
 * Whether a play that ended with nothing playing leaves its track for Play to
 * load. Not when the queue moves on by itself: a refused track during an
 * advance or a spinoff is skipped (desktop's `autoSkipIfAdvancing`), and Play
 * then belongs to the next track. Every other failure stops on the track.
 */
internal fun failedPlayAwaitsLoad(failure: PlayFailure, advancing: Boolean, spinoffMode: Boolean): Boolean =
    !(failure == PlayFailure.EMBED_REFUSED && (advancing || spinoffMode))

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
