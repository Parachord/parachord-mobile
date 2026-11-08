package com.parachord.shared.car

/** Wording the car surfaces (Android Auto, CarPlay) show, kept in one place. */
object CarMessages {
    const val OPEN_SPOTIFY_ON_PHONE = "Open Spotify on your phone to play this"

    /**
     * CarPlay: Bandcamp and SoundCloud need the phone app open. Shown when
     * [CAR_UNPLAYABLE_RUN_LIMIT] tracks in a row had no source that could play
     * in the car (playback stops instead of running through the queue), and
     * when a tapped track's embed was refused for having no phone window.
     */
    const val OPEN_PARACHORD_ON_PHONE = "Open Parachord on your phone to play these tracks"
}
