package com.parachord.android.playback

/** A user's playback action, reported through [PlaybackController.onUserAction]. */
enum class UserPlaybackAction {
    /** Next or Previous (any button: Now Playing, the mini player, the media session, the widget). */
    SKIP,

    /** The user started something else: a track, an album, a playlist, a queue item. */
    PLAY_SOMETHING_ELSE,

    /** The user paused. */
    PAUSE,

    /** The user pressed play on a paused (or stopped) track. */
    RESUME,

    /** A spinoff started (the in-app button, or a radio link after its teardown). */
    SPINOFF_STARTED,
}

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
