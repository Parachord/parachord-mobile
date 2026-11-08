package com.parachord.shared.resolver

import com.parachord.shared.api.AppleMusicCatalog
import com.parachord.shared.api.MusicKitCatalogLimiter
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Apple Music catalog search both platforms resolve through: the
 * connection gate, the request it makes, which song it picks, and the four
 * outcomes (only an unreachable catalog lets Android fall through to iTunes).
 */
class AppleMusicCatalogResolverTest {

    private class FakeSecureStore(private val m: MutableMap<String, String> = mutableMapOf()) : SecureTokenStore {
        override fun get(key: String): String? = m[key]
        override fun set(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun contains(key: String): Boolean = m.containsKey(key)
        override fun observe(key: String): Flow<String?> = flowOf(m[key])
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class Rig(val resolver: AppleMusicCatalogResolver, val requests: MutableList<HttpRequestData>)

    private suspend fun rig(
        body: String = SONGS,
        status: HttpStatusCode = HttpStatusCode.OK,
        connected: Boolean = true,
        devToken: String = "DEVTOKEN",
        storefront: String? = null,
    ): Rig {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            requests += req
            respond(body, status, jsonHeaders)
        }
        val secure = FakeSecureStore()
        if (connected) secure.set("apple_music_user_token", "MUT")
        val settings = SettingsStore(secure, KvStore(MapSettings()))
        if (storefront != null) settings.setAppleMusicStorefront(storefront)
        val catalog = AppleMusicCatalog(HttpClient(engine), devToken, MusicKitCatalogLimiter())
        return Rig(AppleMusicCatalogResolver(catalog, settings), requests)
    }

    @Test
    fun notConnectedMakesNoRequest() = runTest {
        val r = rig(connected = false)
        assertIs<AppleMusicCatalogLookup.NotConnected>(r.resolver.lookup("x", "I Know!", "Swapmeet"))
        assertTrue(r.requests.isEmpty())
    }

    @Test
    fun searchesTheCatalogWithArtistAndTitle() = runTest {
        val r = rig()
        r.resolver.lookup("ignored query", "I Know!", "Swapmeet")
        val req = r.requests.single()
        assertEquals("api.music.apple.com", req.url.host)
        assertEquals("/v1/catalog/us/search", req.url.encodedPath)
        assertEquals("songs", req.url.parameters["types"])
        assertEquals("10", req.url.parameters["limit"])
        assertEquals("Swapmeet I Know!", req.url.parameters["term"])
        assertEquals("Bearer DEVTOKEN", req.headers[HttpHeaders.Authorization])
    }

    @Test
    fun withoutBothNamesTheQueryIsTheTerm() = runTest {
        val r = rig()
        r.resolver.lookup("  swapmeet i know  ", null, "Swapmeet")
        assertEquals("swapmeet i know", r.requests.single().url.parameters["term"])
    }

    @Test
    fun usesTheSavedStorefrontAndRejectsAnythingElse() = runTest {
        val gb = rig(storefront = "GB")
        gb.resolver.lookup("q", "I Know!", "Swapmeet")
        assertEquals("/v1/catalog/gb/search", gb.requests.single().url.encodedPath)
        assertEquals("us", AppleMusicCatalogResolver.storefrontOrDefault("x/../me"))
        assertEquals("us", AppleMusicCatalogResolver.storefrontOrDefault(""))
        assertEquals("us", AppleMusicCatalogResolver.storefrontOrDefault(null))
    }

    @Test
    fun picksTheMatchingSongNotTheFirstHit() = runTest {
        val found = rig().resolver.lookup("q", "I Know!", "Swapmeet")
        val source = assertIs<AppleMusicCatalogLookup.Found>(found).source
        assertEquals("1880091921", source.appleMusicId)
        assertEquals("applemusic", source.resolver)
        assertEquals("applemusic", source.sourceType)
        assertEquals("I Know!", source.matchedTitle)
        assertEquals("Swapmeet", source.matchedArtist)
        assertEquals(201000L, source.matchedDurationMs)
        assertEquals("AUXXX2600001", source.isrc)
        assertEquals("https://music.apple.com/us/song/i-know/1880091921", source.url)
        assertEquals("https://is1.example/600x600bb.jpg", source.artworkUrl)
    }

    @Test
    fun anExactIsrcWinsOverNames() = runTest {
        val found = rig().resolver.lookup("q", "I Know!", "Swapmeet", targetIsrc = "USAAA1111111")
        assertEquals("1000000001", assertIs<AppleMusicCatalogLookup.Found>(found).source.appleMusicId)
    }

    @Test
    fun noSongsIsAMiss() = runTest {
        val r = rig(body = """{"results":{}}""")
        assertIs<AppleMusicCatalogLookup.NoMatch>(r.resolver.lookup("q", "I Know!", "Swapmeet"))
    }

    @Test
    fun aFailedRequestIsUnavailable() = runTest {
        val r = rig(body = """{"errors":[]}""", status = HttpStatusCode.ServiceUnavailable)
        assertIs<AppleMusicCatalogLookup.Unavailable>(r.resolver.lookup("q", "I Know!", "Swapmeet"))
        assertEquals(2, r.requests.size, "one retry")
    }

    @Test
    fun noDeveloperTokenIsUnavailableWithNoRequest() = runTest {
        val r = rig(devToken = "")
        assertIs<AppleMusicCatalogLookup.Unavailable>(r.resolver.lookup("q", "I Know!", "Swapmeet"))
        assertTrue(r.requests.isEmpty())
    }

    @Test
    fun aMalformedIsrcIsDropped() {
        val body = """{"results":{"songs":{"data":[{"id":"7","attributes":{"name":"A","artistName":"B","isrc":"nope"}}]}}}"""
        val source = AppleMusicCatalogResolver.pickCatalogSong(body, "A", "B", null)
        assertNull(source?.isrc)
        assertEquals("applemusic:song:7", source?.url)
    }

    private companion object {
        // The wrong artist's "I Know" comes first; the ISRC-only row is a different recording.
        const val SONGS = """
        {"results":{"songs":{"data":[
          {"id":"1000000001","attributes":{"name":"I Know","artistName":"Someone Else","isrc":"USAAA1111111"}},
          {"id":"1880091921","attributes":{"name":"I Know!","artistName":"Swapmeet","isrc":"auxxx2600001",
            "durationInMillis":201000,"url":"https://music.apple.com/us/song/i-know/1880091921",
            "artwork":{"url":"https://is1.example/{w}x{h}bb.{f}"}}}
        ]}}}
        """
    }
}
