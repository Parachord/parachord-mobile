package com.parachord.shared.playback

/**
 * How a play ended with nothing playing. Android's `PlaybackController` and
 * iOS's `QueuePlaybackCoordinator` both record one and ask
 * [failedPlayAwaitsLoad] whether Play should load the track again
 * (Android's `LoadOnPlay` `FAILED`, iOS's `needsLoadOnPlay`).
 */
enum class PlayFailure {
    /** An embedded player (SoundCloud, Bandcamp) refused it and no other source could play it. */
    EMBED_REFUSED,

    /** Spotify can't start (from the background, or in the car) and there's no other source. */
    NO_SOURCE_WITHOUT_SPOTIFY,

    /** The handler didn't start within the play's time limit (#449). */
    START_TIMED_OUT,

    /**
     * The handler gave up without starting it: Apple Music offline, where
     * MusicKit's play fails at once, or MusicKit that never loaded. On iOS,
     * also any play while the device has no network.
     */
    START_FAILED,

    /** The play threw. */
    ERROR,

    /** The local player's item failed (a local file or a stream; offline, or the stream dropped). */
    PLAYER_ERROR,
}

/**
 * Whether a play that ended with nothing playing leaves its track for Play to
 * load. Not when the queue moves on by itself: a refused track during an
 * advance or a spinoff is skipped (desktop's `autoSkipIfAdvancing`), and Play
 * then belongs to the next track. Every other failure stops on the track,
 * advancing or not: offline the next track would fail the same way, so the
 * queue would only run to its end.
 */
fun failedPlayAwaitsLoad(failure: PlayFailure, advancing: Boolean, spinoffMode: Boolean): Boolean =
    !(failure == PlayFailure.EMBED_REFUSED && (advancing || spinoffMode))

/** What the person is told when a play couldn't start ([PlayFailure.START_FAILED], [PlayFailure.START_TIMED_OUT]). */
const val PLAY_START_FAILED_MESSAGE = "Couldn't start playing. Check your connection and try again."
