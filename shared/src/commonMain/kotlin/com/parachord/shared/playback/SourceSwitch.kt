package com.parachord.shared.playback

/**
 * A source picked by hand in Now Playing's switcher (Android
 * `PlaybackController.switchSource`, iOS `QueuePlaybackCoordinator.switchResolver`)
 * that fails to start leaves the playback as it was before the pick: the
 * person asked to change source, not to stop the song. The old engine keeps
 * playing (or stays paused at its position), the track keeps its old source,
 * no [PlayFailure] or [LoadOnPlay] slot is recorded for it, and the switcher
 * shows the real source again, with [PlaybackMessages.couldNotSwitchTo].
 *
 * A play superseded by a newer one is never a failure ([superseded]), and a
 * play that isn't a manual switch (a tap, the queue moving on, Play on a
 * stopped track retrying its picked source) follows the ordinary failure
 * rules.
 */
fun failedSwitchKeepsPlayback(isManualSwitch: Boolean, failure: PlayFailure?, superseded: Boolean): Boolean =
    isManualSwitch && failure != null && !superseded

/** How a failed manual switch ([failedSwitchKeepsPlayback]) gets the playback back. */
enum class FailedSwitchRecovery {
    /** The old engine was never touched: only the state and the switcher go back. */
    UNTOUCHED,

    /** The switch paused the old engine before the new one started: resume it where it is. */
    RESUME_IN_PLACE,

    /** The old engine let go of the track while it was playing: play it again from its source, at its position. */
    REPLAY_AT_POSITION,

    /** The old engine let go of a paused track: Play starts it from its source, at its position (a [LoadOnPlay] slot). */
    HOLD_FOR_PLAY,
}

/**
 * Which [FailedSwitchRecovery] applies. [oldEngineReleased]: the old engine
 * was stopped or its item replaced before the failure was known (the new
 * source plays through the same local player, or had to stop it first).
 * [oldEnginePaused]: the switch paused it so two sources wouldn't play at
 * once. [wasPlaying]: the track was playing when the switch was asked for.
 */
fun failedSwitchRecovery(oldEngineReleased: Boolean, oldEnginePaused: Boolean, wasPlaying: Boolean): FailedSwitchRecovery =
    when {
        !oldEngineReleased && oldEnginePaused -> FailedSwitchRecovery.RESUME_IN_PLACE
        !oldEngineReleased -> FailedSwitchRecovery.UNTOUCHED
        wasPlaying -> FailedSwitchRecovery.REPLAY_AT_POSITION
        else -> FailedSwitchRecovery.HOLD_FOR_PLAY
    }
