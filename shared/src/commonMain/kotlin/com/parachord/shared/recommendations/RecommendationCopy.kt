package com.parachord.shared.recommendations

import com.parachord.shared.playback.resolverDisplayName
import kotlin.math.roundToInt

/**
 * The Recommendations page's words, desktop's (`recommendations-page.js`).
 * Both apps draw these; the apostrophes are U+2019 as desktop's.
 */
object RecommendationCopy {
    const val TITLE = "Recommendations"
    const val SUBLINE = "From your connected services and Shuffleupagus"

    const val NOT_CONFIGURED_TITLE = "Connect a music service"
    const val NOT_CONFIGURED_BODY =
        "Get personalized recommendations based on your listening history. Connect Last.fm or ListenBrainz, or enable an AI plug-in for Shuffleupagus suggestions."
    const val CONNECT_LISTENBRAINZ = "Connect ListenBrainz"
    const val CONNECT_LASTFM = "Connect Last.fm"

    const val FAILED_TITLE = "Couldn’t load recommendations"

    const val EMPTY_TITLE = "No recommendations yet"
    const val EMPTY_BODY = "Your connected services had nothing to suggest. Listen a little more, or try again later."
    const val EMPTY_SOURCE_TITLE = "Nothing from this source yet"
    const val EMPTY_SOURCE_BODY = "This source has no recommendations right now. Pick All to see the others."

    const val TRY_AGAIN = "Try again"
    const val SEE_ALL = "See all"
    const val SHOW_LESS = "Show less"

    const val LISTENERS = "Listeners"
    const val ALBUMS = "Albums"
    const val BASED_ON_LISTENING = "Based on your listening"

    /** The filter bar's first chip. */
    const val ALL = "All"
    const val PLAY = "Play"
    const val ADD_TO_QUEUE = "Add to Queue"

    const val VIEW_HISTORY = "View history"
    const val FOLLOW = "Follow"
    const val VIEW_ON_ACHORDION = "View on Achordion"

    fun listenerNoTracks(name: String) = "$name has no recent top tracks"
    fun listenerTracksFailed(name: String) = "Couldn’t load $name’s top tracks. Try again in a moment."
    fun playing(name: String) = "Playing $name"
    fun loading(name: String) = "Loading $name…"
    fun couldntLoad(name: String) = "Couldn’t load $name"
    /** [serviceId] is a service id ("lastfm"); a blank one reads "the service". */
    fun couldntReach(serviceId: String?): String =
        "Couldn’t reach ${serviceId?.takeIf { it.isNotBlank() }?.let { resolverDisplayName(it) } ?: "the service"}"

    /** A listener's similarity as a whole percent ("88%"); null when the service sent none. */
    fun similarityBadge(similarity: Double?): String? =
        similarity?.takeIf { it.isFinite() }?.let { "${(it * 100).roundToInt()}%" }
}
