package com.parachord.shared.playback

import com.parachord.shared.home.COLLECTION_CONTEXT
import com.parachord.shared.home.LOVES_CONTEXT
import com.parachord.shared.home.MIX_CONTEXT
import kotlinx.serialization.Serializable

@Serializable
data class PlaybackContext(
    val type: String,
    val name: String,
    val id: String? = null,
)

/**
 * The context type of a queue started from a service playlist that isn't saved
 * (a recommended Apple Music or Spotify playlist): its id is the playlist's
 * link, not a local playlist id, so "Playing from" opens the playlist view for
 * that link rather than a saved playlist.
 */
const val SERVICE_PLAYLIST_CONTEXT = "service-playlist"

/** Where Now Playing's "Playing from" line opens. */
sealed class ContextDestination {
    /** [artist] is the context's id when it carries one (the album page's form). */
    data class Album(val title: String, val artist: String?) : ContextDestination()
    data class Artist(val name: String) : ContextDestination()
    /** A saved playlist by its local id. */
    data class Playlist(val id: String) : ContextDestination()
    /** An unsaved service playlist's own view, by its link. */
    data class ServicePlaylist(val url: String, val name: String) : ContextDestination()
    /**
     * Collection's songs. [recentLoves] opens them sorted Recently Added, once (Home's
     * Recent Loves, desktop's queue drawer); otherwise in the user's own sort.
     */
    data class Collection(val recentLoves: Boolean) : ContextDestination()
}

/**
 * Where a queue's context opens, or null when it opens nothing here (a spinoff,
 * Listen Along, a context with no id to open). Both apps' Now Playing call it;
 * a platform may also link contexts this doesn't cover to pages only it has.
 */
fun playbackContextDestination(ctx: PlaybackContext): ContextDestination? {
    val id = ctx.id?.takeIf { it.isNotBlank() }
    return when (ctx.type) {
        "album" -> ContextDestination.Album(ctx.name, id)
        "artist" -> ContextDestination.Artist(ctx.name)
        "playlist" -> id?.let { ContextDestination.Playlist(it) }
        SERVICE_PLAYLIST_CONTEXT -> id?.let { ContextDestination.ServicePlaylist(it, ctx.name) }
        // A mix queue's id is the mix's link (HomeModel's jumpTileContext). Desktop keeps the
        // mix id and opens Home today; parachord#1592. A queue saved before this carries the
        // old id, which isn't a link, so it opens nothing.
        // Desktop's queue drawer opens Collection's songs newest-loved-first (queue-drawer.js).
        LOVES_CONTEXT -> ContextDestination.Collection(recentLoves = true)
        COLLECTION_CONTEXT -> ContextDestination.Collection(recentLoves = false)
        MIX_CONTEXT -> id?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            ?.let { ContextDestination.ServicePlaylist(it, ctx.name) }
        else -> null
    }
}
