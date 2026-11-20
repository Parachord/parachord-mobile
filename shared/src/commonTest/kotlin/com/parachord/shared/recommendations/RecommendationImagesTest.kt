package com.parachord.shared.recommendations

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecommendationImagesTest {

    private fun artist(name: String, imageUrl: String? = null) =
        RecommendationItem(type = RecommendationType.ARTIST, id = "artist:$name", name = name, imageUrl = imageUrl)

    private fun album(name: String, artist: String, imageUrl: String? = null) =
        RecommendationItem(type = RecommendationType.ALBUM, id = "album:$name", name = name, artist = artist, imageUrl = imageUrl)

    @Test
    fun `a missing artist photo is looked up once and applied`() = runTest {
        var calls = 0
        val images = RecommendationImages(artistImage = { calls++; "https://img/$it.jpg" }, albumArt = { _, _ -> null })
        val item = artist("Portishead")
        images.requestLookups(listOf(item))
        images.requestLookups(listOf(item, artist("portishead ")))
        assertEquals(1, calls)
        assertEquals("https://img/Portishead.jpg", images.imageFor(item))
    }

    @Test
    fun `a missing album cover goes to the album lookup with its artist`() = runTest {
        val asked = mutableListOf<Pair<String, String>>()
        val images = RecommendationImages(artistImage = { null }, albumArt = { t, a -> asked += t to a; "https://cover" })
        val item = album("Dummy", "Portishead")
        images.requestLookups(listOf(item))
        assertEquals(listOf("Dummy" to "Portishead"), asked)
        assertEquals("https://cover", images.imageFor(item))
    }

    @Test
    fun `a failed lookup stores nothing and is asked again`() = runTest {
        var calls = 0
        val images = RecommendationImages(
            artistImage = { calls++; if (calls == 1) throw IllegalStateException("offline") else "https://img" },
            albumArt = { _, _ -> null },
        )
        val item = artist("Air")
        images.requestLookups(listOf(item))
        assertNull(images.imageFor(item))
        images.requestLookups(listOf(item))
        assertEquals(2, calls)
        assertEquals("https://img", images.imageFor(item))
    }

    @Test
    fun `an answered miss is remembered for the session`() = runTest {
        var calls = 0
        val images = RecommendationImages(artistImage = { calls++; null }, albumArt = { _, _ -> null })
        val item = artist("Nobody")
        images.requestLookups(listOf(item))
        images.requestLookups(listOf(item))
        assertEquals(1, calls)
        assertNull(images.imageFor(item))
    }

    @Test
    fun `an item that has an image is never looked up`() = runTest {
        var calls = 0
        val images = RecommendationImages(artistImage = { calls++; "x" }, albumArt = { _, _ -> calls++; "y" })
        val withArt = artist("Air", imageUrl = "https://own")
        images.requestLookups(listOf(withArt, album("Moon Safari", "Air", imageUrl = "https://own2")))
        assertEquals(0, calls)
        assertEquals("https://own", images.imageFor(withArt))
    }

    @Test
    fun `songs and playlists have no image key`() {
        assertNull(RecommendationImages.imageKey(RecommendationItem(type = RecommendationType.SONG, id = "s", name = "S", artist = "A")))
        assertNull(RecommendationImages.imageKey(RecommendationItem(type = RecommendationType.PLAYLIST, id = "p", name = "P")))
    }
}
