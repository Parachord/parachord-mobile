package com.parachord.shared.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Desktop's `resolver-cover.test.js` vectors (parachord#1666), the cross-platform contract (#851). */
class AlbumCoverTest {
    private fun track(artist: String, album: String?, title: String = "Track", source: String = "spotify") =
        TrackSearchResult(title = title, artist = artist, album = album, artworkUrl = "$artist/${album ?: title}.jpg", provider = source)

    private fun cover(t: TrackSearchResult, artist: String, album: String) =
        AlbumCover.isCoverOf(artist, album, t.artist, AlbumCover.releaseNameOf(t.album, t.title, t.provider), t.provider)

    @Test
    fun `the album by its artist`() {
        assertTrue(cover(track("Portishead", "Dummy"), "Portishead", "Dummy"))
        assertTrue(cover(track("portishead", "DUMMY"), "Portishead", "Dummy"))
    }

    @Test
    fun `never another artist's nor the artist's other album`() {
        assertFalse(cover(track("Azealia Banks", "212"), "Tundra 212", "Alive"))
        assertFalse(cover(track("Portishead", "Third"), "Portishead", "Dummy"))
        assertFalse(cover(track("Someone Else", "Dummy"), "Portishead", "Dummy"))
    }

    @Test
    fun `an edition of it however the store writes it`() {
        assertTrue(cover(track("Portishead", "Dummy (Deluxe Edition)"), "Portishead", "Dummy"))
        assertTrue(cover(track("Robyn", "Honey - Single"), "Robyn", "Honey"))
        assertTrue(cover(track("Robyn", "Honey (feat. X)"), "Robyn", "Honey"))
        assertTrue(cover(track("Ed Sheeran", "÷ (Deluxe)"), "Ed Sheeran", "÷"))
    }

    @Test
    fun `a volume a sequel or a live album is a different cover`() {
        assertFalse(cover(track("Queen", "Greatest Hits II"), "Queen", "Greatest Hits"))
        assertFalse(cover(track("Queen", "Live Killers"), "Queen", "Live"))
        assertFalse(cover(track("Fleetwood Mac", "Rumours (Live)"), "Fleetwood Mac", "Rumours"))
    }

    @Test
    fun `a joint credit however it is joined`() {
        assertTrue(cover(track("Jay-Z, Kanye West", "Watch the Throne"), "Jay-Z & Kanye West", "Watch the Throne"))
    }

    @Test
    fun `a single matches on its title only when the result names no album`() {
        assertTrue(cover(track("Robyn", "", "Honey", "bandcamp"), "Robyn", "Honey"))
        assertTrue(cover(track("Robyn", null, "Honey", "bandcamp"), "Robyn", "Honey"))
        assertTrue(cover(track("Robyn", "Single", "Honey", "applemusic"), "Robyn", "Honey"))
        assertFalse(cover(track("Michael Jackson", "Greatest Hits", "Thriller"), "Michael Jackson", "Thriller"))
    }

    @Test
    fun `a placeholder album is no album to match`() {
        assertFalse(cover(track("A", "SoundCloud", "X", "soundcloud"), "A", "SoundCloud"))
        assertFalse(cover(track("A", "Single", "X"), "A", "Single"))
    }

    @Test
    fun `nothing to compare`() {
        assertFalse(cover(track("A", "B"), "", "B"))
        assertFalse(cover(track("A", "B"), "A", ""))
        assertEquals(emptyList(), AlbumCover.trackCovers(listOf(track("A", "B").copy(artworkUrl = null)), "A", "B"))
    }

    @Test
    fun `non-Latin names match and one never stands for another`() {
        assertTrue(cover(track("Кино", "Группа крови"), "Кино", "Группа крови"))
        assertFalse(cover(track("Кино", "Звезда по имени Солнце"), "Кино", "Группа крови"))
    }

    @Test
    fun `the right cover behind another result and the album before an edition`() {
        assertEquals(
            listOf("Portishead/Dummy.jpg"),
            AlbumCover.trackCovers(listOf(track("Azealia Banks", "212"), track("Portishead", "Third"), track("Portishead", "Dummy")), "Portishead", "Dummy"),
        )
        assertEquals(
            listOf("Portishead/Dummy.jpg", "Portishead/Dummy (Deluxe Edition).jpg"),
            AlbumCover.trackCovers(listOf(track("Portishead", "Dummy (Deluxe Edition)"), track("Portishead", "Dummy")), "Portishead", "Dummy"),
        )
        assertEquals(emptyList(), AlbumCover.trackCovers(listOf(track("Azealia Banks", "212")), "Tundra 212", "Alive"))
    }

    @Test
    fun `album search results by their own title`() {
        val results = listOf(
            AlbumSearchResult(title = "Greatest Hits II", artist = "Queen", artworkUrl = "ii.jpg"),
            AlbumSearchResult(title = "Greatest Hits", artist = "Queen", artworkUrl = "i.jpg"),
        )
        assertEquals(listOf("i.jpg"), AlbumCover.albumCovers(results, "Queen", "Greatest Hits"))
        assertEquals(emptyList(), AlbumCover.albumCovers(results.take(1), "Queen", "Greatest Hits"))
    }
}
