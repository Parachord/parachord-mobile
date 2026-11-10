package com.parachord.shared.playback

/** User-facing playback messages, one copy for both apps. */
object PlaybackMessages {
    fun noSimilarTracks(title: String): String = "No similar tracks found for \"$title\""
    fun spinningOff(title: String, artist: String): String = "Spinning off of $title - $artist"
    fun spinoffFrom(title: String, artist: String): String = "Spinoff from $title by $artist"
    val SPINOFF_FETCH_FAILED: String = SPINOFF_FETCH_FAILED_MESSAGE
    fun noPlayableSimilarTracks(title: String): String =
        "Couldn't find a playable track similar to \"$title\""
    /** Shuffle was refused: a spinoff or Listen Along is playing ([shuffleAllowed]). */
    const val SHUFFLE_UNAVAILABLE: String = "Shuffle isn't available during a spinoff or Listen Along"
}
