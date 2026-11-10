package com.parachord.shared.ui

/**
 * Messages both apps show for empty and error states, in one place so Android
 * and iOS say the same thing. A repository's own error text
 * ([com.parachord.shared.model.Resource.Error.message]) is shown as it is;
 * these are the screens' own words around it.
 */
object UiMessages {
    /** The error state's button on most screens. */
    const val TRY_AGAIN = "Try Again"

    /** The error state's button on a friend's profile. */
    const val RETRY = "Retry"

    // ── Recommendations ────────────────────────────────────────────────
    const val RECOMMENDED_ARTISTS_EMPTY =
        "No recommendations yet.\nListen to more music to get personalized suggestions!"
    const val RECOMMENDED_TRACKS_EMPTY =
        "No track recommendations yet.\nListen to more music to get personalized suggestions!"

    // ── Fresh Drops / Critical Darlings ────────────────────────────────
    const val FRESH_DROPS_EMPTY =
        "No new releases found.\nAdd artists to your library or connect Last.fm/ListenBrainz."

    /** Fresh Drops with a release-type filter on ("album", "ep", "single"). */
    fun freshDropsFilterEmpty(type: String): String = "No ${type}s found"

    const val CRITICS_PICKS_EMPTY = "No critics' picks available"

    /** A filtered list's search that matched nothing. */
    fun noResultsFor(query: String): String = "No results for \"$query\""

    // ── Concerts ───────────────────────────────────────────────────────
    const val CONCERTS_EMPTY = "No upcoming concerts found"
    const val CONCERTS_EMPTY_HINT = "Add artists to your collection or connect Last.fm"

    // ── History ────────────────────────────────────────────────────────
    const val HISTORY_TRACKS_EMPTY = "No listening data yet"
    const val HISTORY_ALBUMS_EMPTY = "No album data yet"
    const val HISTORY_ARTISTS_EMPTY = "No artist data yet"
    const val HISTORY_RECENT_EMPTY = "No recent listening history"

    // ── A friend's profile ─────────────────────────────────────────────
    const val FRIEND_TRACKS_EMPTY = "No listening data for this period"
    const val FRIEND_ALBUMS_EMPTY = "No album data for this period"
    const val FRIEND_ARTISTS_EMPTY = "No artist data for this period"
    const val FRIEND_RECENT_EMPTY = "No recent listening activity"

    /** A friend's Recent tab when their Last.fm profile hides it (desktop parachord#1140). */
    fun friendHidesListening(name: String): String =
        "$name hides their recent listening on Last.fm. " +
            "Their top tracks, albums and artists are in the other tabs."

    // ── Search / Album ─────────────────────────────────────────────────
    /** Shown instead of "No results" while MusicBrainz throttles us: a throttle hasn't shown that nothing matched (#375). */
    const val MUSICBRAINZ_RATE_LIMITED = "MusicBrainz is rate-limiting requests — retrying…"
    const val ALBUM_NOT_FOUND = "Album not found"

    /** An artist's On Tour tab with no dates. */
    const val TOUR_DATES_EMPTY = "No upcoming tour dates"

    // ── AI ─────────────────────────────────────────────────────────────
    /** Home's AI suggestions failed with an exception that has no message. */
    const val AI_SUGGESTIONS_UNKNOWN_ERROR = "Unknown error"
    const val NO_AI_PROVIDERS = "No AI providers configured"
    const val NO_AI_PROVIDERS_HINT = "Go to Settings → Plug-Ins to set up ChatGPT, Claude, or Gemini"

    // ── Settings ───────────────────────────────────────────────────────
    const val LISTENBRAINZ_INVALID_TOKEN = "Invalid token. Check your ListenBrainz user token."

    // ── Playlists ──────────────────────────────────────────────────────
    /**
     * A playlist was deleted here but [providerNames] (e.g. Apple Music) can't
     * delete it through their API, so it's still on those services.
     */
    fun playlistDeletionUnsupported(providerNames: List<String>): String {
        val names = providerNames.joinToString(", ")
        return "Removed from Parachord. $names doesn't allow deletion via the API — " +
            "remove manually in the $names app."
    }
}
