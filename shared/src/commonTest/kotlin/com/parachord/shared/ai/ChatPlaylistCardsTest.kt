package com.parachord.shared.ai

import com.parachord.shared.model.Playlist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Desktop's playlist chat card (`app.js` ~L17860). */
class ChatPlaylistCardsTest {

    private val saved = listOf(
        Playlist(id = "p1", name = "Rainy Day"),
        Playlist(id = "p2", name = "Road Trip"),
    )

    @Test
    fun `a card opens the saved playlist by id then by name`() {
        assertEquals(ChatPlaylistTap.Open("p2"), ChatPlaylistCards.tap(ChatCardRef.Playlist("Anything", "p2", "3"), "", saved))
        assertEquals(ChatPlaylistTap.Open("p1"), ChatPlaylistCards.tap(ChatCardRef.Playlist(" rainy day ", "garbled", "3"), "", saved))
    }

    @Test
    fun `a playlist never saved offers to save the tracks the message listed`() {
        val message = "{{track|Creep|Radiohead|Pablo Honey}}\n{{track|Karma Police|Radiohead}}\n{{playlist|Gloom|x|2}}"
        val card = ChatCardRef.Playlist("Gloom", "x", "2")
        assertEquals(ChatPlaylistTap.OfferSave("Gloom", 2), ChatPlaylistCards.tap(card, message, saved))
        val (playlist, tracks) = ChatPlaylistCards.draft(card, message, now = 5)
        assertEquals("Gloom", playlist.name)
        assertTrue(playlist.id.startsWith("local-"))
        assertEquals(2, playlist.trackCount)
        assertEquals(listOf("Creep" to "Pablo Honey", "Karma Police" to null), tracks.map { it.title to it.album })
    }

    @Test
    fun `with no tracks there is nothing to save`() {
        assertEquals(
            ChatPlaylistTap.Unavailable(ChatPlaylistCards.NOT_MADE_MESSAGE),
            ChatPlaylistCards.tap(ChatCardRef.Playlist("Gloom", "x", "2"), "{{playlist|Gloom|x|2}}", saved),
        )
    }
}
