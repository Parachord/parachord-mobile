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

/** What a play that ended with nothing playing does: [unplayableTrackOutcome]. */
sealed class UnplayableTrackOutcome {
    /** The queue moves on to the next track. */
    data object Skip : UnplayableTrackOutcome()

    /**
     * Playback stops on the track, with [message]; Play loads it again.
     * [byCar] is the car's stop (the run of unplayable tracks), which shows
     * the car's message and no action on the phone.
     */
    data class Stop(val message: String, val byCar: Boolean = false) : UnplayableTrackOutcome()
}

/**
 * Stop or skip, and what to say, for a track nothing played (#616). The one
 * order both apps follow:
 *
 * 1. [failedPlayAwaitsLoad]: a failure that leaves the track for Play stops on it;
 * 2. otherwise the queue would skip it, and [carStopsSkip] may stop it instead
 *    (the car's run of unplayable tracks; asked only here, since it counts);
 * 3. the message: the car's, [refusedMessage] for a refused embed,
 *    [PLAY_NO_SOURCE_MESSAGE] for no source, else [PLAY_START_FAILED_MESSAGE].
 *
 * Use [noSourceFailure] for a track with no source. Cases only one platform
 * has (Listen Along's own track, Apple Music not authorized, Spotify or an
 * embed tapped in the car) stay with the caller, before this.
 */
fun unplayableTrackOutcome(
    failure: PlayFailure,
    advancing: Boolean,
    spinoffMode: Boolean,
    refusedMessage: String? = null,
    carStopsSkip: () -> Boolean,
): UnplayableTrackOutcome {
    if (!failedPlayAwaitsLoad(failure, advancing, spinoffMode)) {
        return if (carStopsSkip()) {
            UnplayableTrackOutcome.Stop(com.parachord.shared.car.CarMessages.OPEN_PARACHORD_ON_PHONE, byCar = true)
        } else {
            UnplayableTrackOutcome.Skip
        }
    }
    val message = when (failure) {
        PlayFailure.EMBED_REFUSED -> refusedMessage ?: PLAY_START_FAILED_MESSAGE
        PlayFailure.NO_SOURCE -> PLAY_NO_SOURCE_MESSAGE
        else -> PLAY_START_FAILED_MESSAGE
    }
    return UnplayableTrackOutcome.Stop(message)
}
