package com.parachord.shared.metadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Desktop's `tests/resolver/album-art-catalog.test.js`, the track half. */
class CatalogTrackArtworkTest {

    private fun catalog(itunes: String?, deezer: String?, urls: MutableList<String> = mutableListOf()) =
        CatalogTrackArtwork(HttpClient(MockEngine { req ->
            urls += req.url.toString()
            val body = if (req.url.host == "itunes.apple.com") itunes else deezer
            if (body == null) respond("", HttpStatusCode.TooManyRequests)
            else respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }))

    @Test
    fun `nameMatch normalizes and allows a prefix either way`() {
        assertTrue(CatalogTrackArtwork.nameMatch("Black Star (Remastered)", "black star"))
        assertTrue(CatalogTrackArtwork.nameMatch("Beatles", "The Beatles"))
        assertTrue(CatalogTrackArtwork.nameMatch("Lustmord", "Lustmord"))
        assertFalse(CatalogTrackArtwork.nameMatch("", "x"))
        assertFalse(CatalogTrackArtwork.nameMatch("The Place Where the Black Stars Hang", "Black Star"))
    }

    @Test
    fun `iTunes matches artist and track and upsizes to 500px`() = runTest {
        val body = """{"results":[{"artistName":"Lustmord","trackName":"Black Star","artworkUrl100":"https://a/100x100bb.jpg"}]}"""
        val urls = mutableListOf<String>()
        assertEquals("https://a/500x500bb.jpg", catalog(body, null, urls).trackArtwork("Lustmord", "Black Star"))
        assertEquals("https://itunes.apple.com/search?term=Lustmord+Black+Star&entity=song&limit=5", urls.first())
        assertNull(catalog(body, """{"data":[]}""").trackArtwork("Lustmord", "Other"))
    }

    @Test
    fun `Deezer is asked when iTunes misses or fails and takes the album cover`() = runTest {
        val deezer = """{"data":[{"title":"Black Star","artist":{"name":"Lustmord"},"album":{"cover_medium":"https://d/med.jpg"}}]}"""
        assertEquals("https://d/med.jpg", catalog(null, deezer).trackArtwork("Lustmord", "Black Star"))
        assertEquals("https://d/med.jpg", catalog("""{"results":[]}""", deezer).trackArtwork("Lustmord", "Black Star"))
    }

    @Test
    fun `a wrong artist is rejected`() = runTest {
        val deezer = """{"data":[{"title":"Black Star","artist":{"name":"Nobody"},"album":{"cover_big":"x"}}]}"""
        assertNull(catalog("""{"results":[]}""", deezer).trackArtwork("Lustmord", "Black Star"))
    }
}
