package com.parachord.android.playback

import androidx.media3.common.Player

/**
 * The state the media session reports while the local player (ExoPlayer) is
 * between two tracks of a playing queue (#848).
 *
 * ENDED, which lasts until the next track's source is known (seconds for a
 * plugin's manifest), and IDLE reach the car as STOPPED and NONE, and Android
 * Auto answers either by leaving Now Playing for whatever was being browsed.
 * While something is about to play, [BUFFERING][Player.STATE_BUFFERING] is
 * what's true: the car keeps Now Playing and shows its spinner, as it does
 * while an external engine starts (#677).
 */
internal fun sessionStateBetweenTracks(localState: Int, betweenTracks: Boolean): Int =
    if (betweenTracks && (localState == Player.STATE_ENDED || localState == Player.STATE_IDLE)) {
        Player.STATE_BUFFERING
    } else {
        localState
    }

/**
 * Whether the local player, ENDED or IDLE, is only between tracks.
 *
 * - [hasTrack]: a track is on screen (none once the queue has ended).
 * - [waits]: that track is stopped, waiting for Play ([LoadOnPlay]) or
 *   showing an error.
 * - [starting]: a play, an advance or an auto-skip is under way, the next item
 *   has been handed to the player, which hasn't taken it yet, or Listen Along
 *   holds the ended track until its next one.
 * - [endedAndSomethingFollows]: the track ended by itself and the queue, the
 *   spinoff pool or Listen Along has one after it; the advance hasn't begun.
 */
internal fun isBetweenTracks(
    hasTrack: Boolean,
    waits: Boolean,
    starting: Boolean,
    endedAndSomethingFollows: Boolean,
): Boolean = hasTrack && !waits && (starting || endedAndSomethingFollows)
