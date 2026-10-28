package com.parachord.shared.metadata

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The artist-photo chain (desktop parachord#1141): step order, the plugin
 * step through the registry, refused URLs, and a miss remembered only when
 * every step answered. Cases from desktop's `artist-image-chain.test.js`.
 */
class ArtistImageChainTest {

    // ── Pure parts ──────────────────────────────────────────────────────

    @Test
    fun usableUrls() {
        assertTrue(ArtistImageChain.isUsableUrl("https://is1-ssl.mzstatic.com/image/thumb/a/600x600bb.jpg"))
        assertTrue(ArtistImageChain.isUsableUrl("https://e-cdns-images.dzcdn.net/images/artist/abc/1000x1000-000000-80-0-0.jpg"))
        assertFalse(ArtistImageChain.isUsableUrl(null))
        assertFalse(ArtistImageChain.isUsableUrl("ftp://x/y.jpg"))
        assertFalse(ArtistImageChain.isUsableUrl("not a url"))
        assertFalse(ArtistImageChain.isUsableUrl("https://user:pw@host/x.jpg"))
        assertFalse(ArtistImageChain.isUsableUrl("https://nas.local/rest/getCoverArt?id=1&u=me&t=abc&s=salt"))
        assertFalse(ArtistImageChain.isUsableUrl("https://x/img?api_key=123"))
        assertFalse(ArtistImageChain.isUsableUrl("https://lastfm.freetls.fastly.net/i/u/300x300/2a96cbd8b46e442fc41c2b86b821562f.png"))
    }

    private fun deezer(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun deezerMatchesTheNameAndSkipsItsPlaceholder() {
        val found = deezer("""{"data":[
            {"name":"Somebody Else","picture_xl":"https://cdn/else.jpg","md5_image":"e"},
            {"name":"The Wrens","picture_xl":"https://cdn/wrens.jpg","md5_image":"w"}]}""")
        assertEquals("https://cdn/wrens.jpg", ArtistImageChain.deezerImageFromSearch(found, "Wrens"))
        val blank = deezer("""{"data":[{"name":"Windir","picture_xl":"https://cdn/images/artist//1000x1000.jpg","md5_image":""}]}""")
        assertNull(ArtistImageChain.deezerImageFromSearch(blank, "Windir"))
        assertNull(ArtistImageChain.deezerImageFromSearch(found, "Nobody"))
    }

    // ── The chain in MetadataService.getArtistImage ─────────────────────

    private class ImageProvider(
        override val name: String,
        override val priority: Int,
        var image: String? = null,
        var fail: Boolean = false,
    ) : MetadataProvider {
        var calls = 0
        override suspend fun isAvailable() = true
        override suspend fun searchTracks(query: String, limit: Int) = emptyList<TrackSearchResult>()
        override suspend fun searchAlbums(query: String, limit: Int) = emptyList<AlbumSearchResult>()
        override suspend fun searchArtists(query: String, limit: Int) = emptyList<ArtistInfo>()
        override suspend fun getArtistInfo(artistName: String): ArtistInfo? {
            calls++
            if (fail) error("503")
            return ArtistInfo(name = artistName, imageUrl = image, provider = name)
        }
        override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? = null
        override suspend fun getArtistAlbums(artistName: String, limit: Int) = emptyList<AlbumSearchResult>()
    }

    private val apple = ImageProvider("applemusic", 25)
    private val wikipedia = ImageProvider("wikipedia", 5)
    private val pluginCalls = mutableListOf<String>()
    private var pluginImage: String? = null

    private fun service(plugins: List<String> = listOf("subsonic")) = MetadataService(
        providers = listOf(wikipedia, apple),
        getDisabledProviders = { emptySet() },
        artistImageResolvers = { plugins },
        pluginArtistImage = { id, _ -> pluginCalls += id; pluginImage },
    )

    @Test
    fun appleMusicWinsBeforeThePlugins() = runTest {
        apple.image = "https://am/1.jpg"
        assertEquals("https://am/1.jpg", service().getArtistImage("Built to Spill"))
        assertTrue(pluginCalls.isEmpty())
        assertEquals(0, wikipedia.calls)
    }

    @Test
    fun theActiveArtistImagePluginsComeNext() = runTest {
        pluginImage = "https://nas.example/share/img/abc"
        assertEquals("https://nas.example/share/img/abc", service().getArtistImage("Built to Spill"))
        assertEquals(listOf("subsonic"), pluginCalls)
        assertEquals(0, wikipedia.calls)
    }

    @Test
    fun aPluginUrlWithCredentialsIsRefusedAndTheChainGoesOn() = runTest {
        pluginImage = "https://nas.local/rest/getCoverArt?id=1&u=me&t=tok&s=salt"
        wikipedia.image = "https://upload.wikimedia.org/x.jpg"
        assertEquals("https://upload.wikimedia.org/x.jpg", service().getArtistImage("Built to Spill"))
    }

    @Test
    fun aMissIsRememberedOnlyWhenEveryStepAnswered() = runTest {
        val s = service()
        assertNull(s.getArtistImage("Nobody"))
        val asked = apple.calls
        assertNull(s.getArtistImage("Nobody"))
        assertEquals(asked, apple.calls, "a complete miss is remembered")

        apple.fail = true
        assertNull(s.getArtistImage("Someone Else"))
        val failedOnce = apple.calls
        apple.fail = false
        apple.image = "https://am/2.jpg"
        assertEquals("https://am/2.jpg", s.getArtistImage("Someone Else"), "a failed step is not a miss")
        assertTrue(apple.calls > failedOnce)
    }
}
