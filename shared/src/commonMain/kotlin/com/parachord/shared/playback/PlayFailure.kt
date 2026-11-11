package com.parachord.shared.playback

/**
 * How a play ended with nothing playing. Android's `PlaybackController` and
 * iOS's `QueuePlaybackCoordinator` both record one and ask
 * [failedPlayAwaitsLoad] whether Play should load the track again (the
 * shared [LoadOnPlay] slot, `FAILED`).
 */
enum class PlayFailure {
    /** An embedded player (SoundCloud, Bandcamp) refused it and no other source could play it. */
    EMBED_REFUSED,

    /** No resolver found a playable source for the track while online. */
    NO_SOURCE,

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
 * advance or a spinoff ([PlayFailure.EMBED_REFUSED], [PlayFailure.NO_SOURCE]) is
 * skipped (desktop's `autoSkipIfAdvancing`), and Play
 * then belongs to the next track. Every other failure stops on the track,
 * advancing or not: offline the next track would fail the same way, so the
 * queue would only run to its end.
 */
fun failedPlayAwaitsLoad(failure: PlayFailure, advancing: Boolean, spinoffMode: Boolean): Boolean =
    !((failure == PlayFailure.EMBED_REFUSED || failure == PlayFailure.NO_SOURCE) && (advancing || spinoffMode))

/**
 * What a play with no source to route to records. [PlayFailure.NO_SOURCE] (the
 * queue skips it when moving on) only when the device is [online] and the
 * resolvers answered with no match. When they couldn't answer
 * ([resolversCouldNotAnswer]: offline, a rate-limit cooldown, a plugin that timed
 * out, a resolve that threw; #566) nothing has shown the track has no source, so
 * it's [PlayFailure.START_FAILED]: playback stops on the track and Play tries it
 * again.
 */
fun noSourceFailure(online: Boolean, resolversCouldNotAnswer: Boolean): PlayFailure =
    if (online && !resolversCouldNotAnswer) PlayFailure.NO_SOURCE else PlayFailure.START_FAILED

/** What the person is told when a play couldn't start ([PlayFailure.START_FAILED], [PlayFailure.START_TIMED_OUT]). */
const val PLAY_START_FAILED_MESSAGE = "Couldn't start playing. Check your connection and try again."

/**
 * What the person is told when Apple Music would play the track but Parachord
 * isn't allowed to use it (Android: MusicKit authorization failed; iOS: Apple
 * Music access denied for the app). Play tries the track again.
 */
const val APPLE_MUSIC_SIGN_IN_MESSAGE = "Sign in to Apple Music to play this track"

/** What the person is told when no resolver found a playable source ([PlayFailure.NO_SOURCE]). */
const val PLAY_NO_SOURCE_MESSAGE = "Couldn't find a playable source for this track."
