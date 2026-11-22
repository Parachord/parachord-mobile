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
    fun listeningAlongWith(name: String): String = "Listening along with $name"
    fun stoppedListening(name: String): String = "$name stopped listening"
    fun stoppedListeningAlongWith(name: String): String = "Stopped listening along with $name"
    fun hasntMovedOn(name: String): String = "$name hasn't moved on - resuming your queue"

    /**
     * [service]'s player refused the track and another source plays it
     * ([playedResolver] is that source's resolver id).
     */
    fun playingViaInstead(service: String, playedResolver: String): String =
        "$service can't play this track here. Playing via ${resolverDisplayName(playedResolver)}."

    /**
     * A source picked by hand in Now Playing's switcher couldn't start
     * ([failedSwitchKeepsPlayback]): [pickedResolver] is the one picked,
     * [currentResolver] the one the track stays on (null when it isn't known),
     * [playing] whether it's still playing (else it stays paused).
     */
    fun couldNotSwitchTo(pickedResolver: String, currentResolver: String?, playing: Boolean): String {
        val failed = "Couldn’t play from ${resolverDisplayName(pickedResolver)}."
        val current = currentResolver?.takeIf { it.isNotBlank() && !it.equals(pickedResolver, ignoreCase = true) }
            ?: return failed
        val name = resolverDisplayName(current)
        return if (playing) "$failed Still playing from $name." else "$failed Staying on $name."
    }

    /** The action that opens the track's page on [service] after it refused the track. */
    fun openIn(service: String): String = "Open in $service"

    /** Now Playing's line for where the queue came from ([PlaybackContext]). */
    fun contextLabel(contextType: String, contextName: String): String = when (contextType) {
        LISTEN_ALONG_CONTEXT -> listeningAlongWith(contextName)
        // A spinoff's name is already the whole line ("Spinoff from …", or a
        // radio station's name).
        "spinoff" -> contextName
        else -> "Playing from: $contextName"
    }

    /** Now Playing's line when the queue has no source context. */
    const val PLAYING_FROM_QUEUE: String = "Playing from your queue"
}

/**
 * The loaded plugins' manifest names (`manifest.name`), by id, for
 * [resolverDisplayName]: a plugin the table below doesn't know is shown by its
 * own name ("Amazon Music"), not its id. Set by PluginManager as it loads
 * plugins, as ServiceColors is.
 */
object PluginDisplayNames {
    @kotlin.concurrent.Volatile
    private var names: Map<String, String> = emptyMap()

    fun update(manifestNames: Map<String, String>) {
        names = manifestNames.filterValues { it.isNotBlank() }.mapKeys { it.key.lowercase() }
    }

    fun nameOf(resolver: String): String? = names[resolver.lowercase()]
}

/**
 * A resolver's name as people see it ("applemusic" → "Apple Music"): the known
 * services' names, else the loaded plugin's manifest name, else the id
 * capitalized.
 */
fun resolverDisplayName(resolver: String): String = when (resolver.lowercase()) {
    "spotify" -> "Spotify"
    "applemusic" -> "Apple Music"
    "soundcloud" -> "SoundCloud"
    "localfiles" -> "Local Files"
    "youtube" -> "YouTube"
    "bandcamp" -> "Bandcamp"
    "subsonic" -> "Subsonic"
    "qobuz" -> "Qobuz"
    "direct" -> "Direct"
    "lastfm" -> "Last.fm"
    "listenbrainz" -> "ListenBrainz"
    "librefm" -> "Libre.fm"
    "amazonmusic" -> "Amazon Music"
    "plex" -> "Plex"
    else -> PluginDisplayNames.nameOf(resolver) ?: resolver.replaceFirstChar { it.uppercase() }
}
