package com.parachord.android.playback

/** A user's playback action, reported through [PlaybackController.onUserAction] (shared with iOS). */
typealias UserPlaybackAction = com.parachord.shared.playback.UserPlaybackAction

/**
 * What the media session's play ([play] true) or pause on the local player
 * tells the listener: RESUME only when nothing [isPlaying] or [isPlayStarting]
 * (the controller's own `play()` comes back through the session once its play
 * has set the state playing, and must not count), PAUSE only while playing;
 * null otherwise.
 */
internal fun sessionPlayPauseAction(play: Boolean, isPlaying: Boolean, isPlayStarting: Boolean): UserPlaybackAction? = when {
    play && !isPlaying && !isPlayStarting -> UserPlaybackAction.RESUME
    !play && isPlaying -> UserPlaybackAction.PAUSE
    else -> null
}
