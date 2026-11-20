package com.parachord.shared.ai

import com.parachord.shared.metadata.TrackSearchResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Desktop's `ChatCard` art lookup (`app.js` ~L17257): its sources, order and caching. */
class ChatCardEnricherTest {

    private class Calls {
        val albumArt = mutableListOf<Pair<String, String>>()
        val artistImage = mutableListOf<String>()
        val trackSearch = mutableListOf<String>()
    }

    private var now = 0L

    private fun enricher(
        calls: Calls,
        albumArt: suspend (String, String) -> String? = { _, _ -> null },
        artistImage: suspend (String) -> String? = { null },
        trackSearch: suspend (String, String) -> List<TrackSearchResult> = { _, _ -> emptyList() },
    ) = ChatCardEnricher(
        albumArt = { album, artist -> calls.albumArt += album to artist; albumArt(album, artist) },
        artistImage = { name -> calls.artistImage += name; artistImage(name) },
        trackSearch = { title, artist -> calls.trackSearch += "$artist $title"; trackSearch(title, artist) },
        nowMs = { now },
    )

    @Test
    fun `a track card with an album uses the album art pipeline`() = runTest {
        val calls = Calls()
        val e = enricher(calls, albumArt = { _, _ -> "album.jpg" })
        assertEquals("album.jpg", e.getTrackArtwork("Creep", "Radiohead", "Pablo Honey"))
        assertEquals(listOf("Pablo Honey" to "Radiohead"), calls.albumArt)
        assertEquals(emptyList(), calls.trackSearch)
    }

    @Test
    fun `a track card without an album finds the album by searching first`() = runTest {
        val calls = Calls()
        val e = enricher(
            calls,
            albumArt = { album, _ -> if (album == "OK Computer") "okc.jpg" else null },
            trackSearch = { _, _ ->
                listOf(
                    TrackSearchResult(title = "Karma Police (Live)", artist = "Radiohead", album = "Live", artworkUrl = "live.jpg"),
                    TrackSearchResult(title = "Karma Police", artist = "Radiohead", album = "OK Computer", artworkUrl = "row.jpg"),
                )
            },
        )
        assertEquals("okc.jpg", e.getTrackArtwork("Karma Police", "Radiohead", ""))
        assertEquals(listOf("OK Computer" to "Radiohead"), calls.albumArt)
    }

    @Test
    fun `a track with no album anywhere falls back to the matched song art`() = runTest {
        val calls = Calls()
        val e = enricher(
            calls,
            trackSearch = { _, _ -> listOf(TrackSearchResult(title = "Song", artist = "Band", artworkUrl = "song.jpg")) },
        )
        assertEquals("song.jpg", e.getTrackArtwork("Song", "Band", ""))
        assertEquals(emptyList(), calls.albumArt)
    }

    @Test
    fun `a wrong song is never used`() = runTest {
        val calls = Calls()
        val e = enricher(
            calls,
            trackSearch = { _, _ -> listOf(TrackSearchResult(title = "Other", artist = "Someone", album = "X", artworkUrl = "wrong.jpg")) },
        )
        assertNull(e.getTrackArtwork("Song", "Band", ""))
        assertEquals(emptyList(), calls.albumArt)
    }

    @Test
    fun `album and artist cards use the album pipeline and the artist photo chain`() = runTest {
        val calls = Calls()
        val e = enricher(calls, albumArt = { _, _ -> "a.jpg" }, artistImage = { "p.jpg" })
        assertEquals("a.jpg", e.getAlbumArtwork("Kid A", "Radiohead"))
        assertEquals("p.jpg", e.getArtistImage("Radiohead"))
        assertEquals(listOf("Kid A" to "Radiohead"), calls.albumArt)
        assertEquals(listOf("Radiohead"), calls.artistImage)
    }

    @Test
    fun `a hit is kept`() = runTest {
        val calls = Calls()
        val e = enricher(calls, artistImage = { "p.jpg" })
        e.getArtistImage("Radiohead")
        now += 365L * 24 * 60 * 60 * 1000
        assertEquals("p.jpg", e.getArtistImage("radiohead"))
        assertEquals(1, calls.artistImage.size)
    }

    @Test
    fun `a lookup that threw is not cached as a miss`() = runTest {
        val calls = Calls()
        var fail = true
        val e = enricher(calls, artistImage = { if (fail) throw IllegalStateException("offline") else "p.jpg" })
        assertNull(e.getArtistImage("Radiohead"))
        fail = false
        assertEquals("p.jpg", e.getArtistImage("Radiohead"))
        assertEquals(2, calls.artistImage.size)
    }

    @Test
    fun `a miss is kept for a while then asked again`() = runTest {
        val calls = Calls()
        var answer: String? = null
        val e = enricher(calls, albumArt = { _, _ -> answer })
        assertNull(e.getAlbumArtwork("Kid A", "Radiohead"))
        answer = "a.jpg"
        assertNull(e.getAlbumArtwork("Kid A", "Radiohead"))
        assertEquals(1, calls.albumArt.size)
        now += ChatCardEnricher.MISS_TTL_MS
        assertEquals("a.jpg", e.getAlbumArtwork("Kid A", "Radiohead"))
        assertEquals(2, calls.albumArt.size)
    }
}
