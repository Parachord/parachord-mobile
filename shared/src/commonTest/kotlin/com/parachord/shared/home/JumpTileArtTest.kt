package com.parachord.shared.home

import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What each Jump back in tile draws (both apps). */
class JumpTileArtTest {

    private fun t(title: String, artist: String, album: String?, art: String? = null) =
        Track(id = "$artist-$title", title = title, artist = artist, album = album, artworkUrl = art)

    private fun mix(image: String? = null, mosaic: List<String> = emptyList()) =
        HistoryMix(id = "m", name = "m", title = "Daily Jams", imageUrl = image, url = "https://x", resolverId = "lb", resolverName = "LB", mosaic = mosaic)

    @Test
    fun `a playlist shows its own cover`() {
        val tile = JumpTile.Playlist(Playlist(id = "p", name = "P", artworkUrl = "https://a"), "Playlist")
        assertEquals(JumpTileArt(imageUrl = "https://a"), jumpTileArt(tile, emptyList()))
        val bare = JumpTile.Playlist(Playlist(id = "p", name = "P", artworkUrl = " "), "Playlist")
        assertTrue(jumpTileArt(bare, emptyList()).isEmpty)
    }

    @Test
    fun `loves show a 2x2 with four covered albums — else the newest cover`() {
        val four = listOf(
            t("1", "A", "a1", "u1"), t("2", "A", "a1", "u1b"), t("3", "B", "b", "u2"),
            t("4", "C", "c", "u3"), t("5", "D", "d", "u4"),
        )
        assertEquals(JumpTileArt(mosaic = listOf("u1", "u2", "u3", "u4")), jumpTileArt(JumpTile.Loves(four), four))
        val three = listOf(t("1", "A", null), t("2", "A", "a1", "u1"), t("3", "B", "b", "u2"))
        assertEquals(JumpTileArt(imageUrl = "u1"), jumpTileArt(JumpTile.Loves(three), three))
    }

    @Test
    fun `the album takes the cover of a love on it — else nothing so the app looks it up`() {
        val loves = listOf(t("x", "Other", "Z", "uz"), t("s", "Radiohead", "OK Computer", null), t("s2", "radiohead ", "ok computer", "uok"))
        val tile = JumpTile.Album(LoveAlbum("OK Computer", "Radiohead", "s", null))
        assertEquals(JumpTileArt(imageUrl = "uok"), jumpTileArt(tile, loves))
        assertTrue(jumpTileArt(tile, loves.take(2)).isEmpty)
    }

    @Test
    fun `a mix shows its image — else its mosaic`() {
        assertEquals(JumpTileArt(imageUrl = "i"), jumpTileArt(JumpTile.Mix(mix("i", listOf("a"))), emptyList()))
        assertEquals(JumpTileArt(mosaic = listOf("a", "b")), jumpTileArt(JumpTile.Mix(mix(null, listOf("a", "b"))), emptyList()))
    }

    @Test
    fun `tile keys tell tiles apart and survive case`() {
        val a = JumpTile.Album(LoveAlbum("OK Computer", "Radiohead", "s", null))
        val b = JumpTile.Album(LoveAlbum("ok computer", "radiohead", "t", null))
        assertEquals(jumpTileKey(a), jumpTileKey(b))
        assertNotEquals(jumpTileKey(a), jumpTileKey(JumpTile.Mix(mix())))
    }
}
