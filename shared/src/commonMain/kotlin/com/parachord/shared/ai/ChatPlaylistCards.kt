package com.parachord.shared.ai

import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.platform.randomUUID

/** What a tap on a `{{playlist|name|id|count}}` chat card does. */
sealed class ChatPlaylistTap {
    /** The playlist is saved: open it. */
    data class Open(val playlistId: String) : ChatPlaylistTap()
    /** It was never saved, but the message lists tracks: offer to save them. */
    data class OfferSave(val name: String, val trackCount: Int) : ChatPlaylistTap()
    /** Nothing to open or save: show [message]. */
    data class Unavailable(val message: String) : ChatPlaylistTap()
}

/**
 * Desktop's playlist chat card (`renderCard`'s playlist branch, `app.js`
 * ~L17860), shared by both apps. A tap opens the saved playlist the card
 * names: by its id, else by its name (the model sometimes garbles the id).
 * A playlist the model described without calling `create_playlist` was never
 * saved; desktop then opens the tracks the message lists
 * ([ChatContent.messageTracks]) as a temporary playlist with a Save button.
 * The phones offer the same Save straight away ([draft]), and open the saved
 * copy. With no tracks in the message there's nothing to save.
 */
object ChatPlaylistCards {

    /** Desktop's words for a playlist the model never made. */
    const val NOT_MADE_MESSAGE = "Shuffleupagus didn’t make this playlist. Ask it to create the playlist again."

    /** A card's tap couldn't read the saved playlists. */
    const val OPEN_FAILED_MESSAGE = "Couldn’t open the playlist. Try again."

    /** Saving the offered playlist failed. */
    const val SAVE_FAILED_MESSAGE = "Couldn’t save the playlist. Try again."

    /** Shown with the Save offer. */
    fun saveOfferMessage(name: String, trackCount: Int): String =
        "Shuffleupagus didn’t save “$name”. Save the ${com.parachord.shared.format.countOf(trackCount, "track")} it listed as a playlist?"

    fun find(card: ChatCardRef.Playlist, playlists: List<Playlist>): Playlist? {
        playlists.firstOrNull { card.id.isNotBlank() && it.id == card.id }?.let { return it }
        val wanted = card.name.trim().lowercase()
        if (wanted.isEmpty()) return null
        return playlists.firstOrNull { it.name.trim().lowercase() == wanted }
    }

    fun tap(card: ChatCardRef.Playlist, messageContent: String, playlists: List<Playlist>): ChatPlaylistTap {
        find(card, playlists)?.let { return ChatPlaylistTap.Open(it.id) }
        val tracks = ChatContent.messageTracks(messageContent)
        if (tracks.isEmpty()) return ChatPlaylistTap.Unavailable(NOT_MADE_MESSAGE)
        return ChatPlaylistTap.OfferSave(card.name.trim().ifEmpty { "Shuffleupagus playlist" }, tracks.size)
    }

    /**
     * The local playlist and its metadata-only tracks for a card the model
     * never saved; the tracks resolve when they play, like any chat track.
     */
    fun draft(card: ChatCardRef.Playlist, messageContent: String, now: Long = currentTimeMillis()): Pair<Playlist, List<Track>> {
        val tracks = ChatContent.messageTracks(messageContent).map { t ->
            Track(
                id = "chat-${randomUUID()}",
                title = t.title,
                artist = t.artist,
                album = t.album.ifBlank { null },
            )
        }
        val playlist = Playlist(
            id = "local-${randomUUID()}",
            name = card.name.trim().ifEmpty { "Shuffleupagus playlist" },
            description = "Made in your chat with Shuffleupagus",
            trackCount = tracks.size,
            createdAt = now,
            updatedAt = now,
            lastModified = now,
        )
        return playlist to tracks
    }
}
