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
