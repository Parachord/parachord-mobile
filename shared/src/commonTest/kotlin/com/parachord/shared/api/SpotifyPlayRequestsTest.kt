package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A one-track Spotify play goes out as the track's album with an offset, not a
 * bare `uris` list: Spotify's Android app accepts the bare form and plays
 * nothing (Nov 2026, Spotify 9.2.0 on a Pixel 9a, Android 17).
 */
class SpotifyPlayRequestsTest {

    private val trackUri = "spotify:track:0vGCxnTwYZFtOAW3oz28kd"

    private fun track(id: String?, albumId: String?) =
        SpTrack(id = id, name = "Judy Is a Punk", album = albumId?.let { SpAlbumRef(id = it) })

    @Test
    fun `a track plays inside its album starting at the track`() = runTest {
        val requests = SpotifyPlayRequests(lookupTrack = { track(it, "5uYDAwW0SZgcfOFkxrST64") })
        val request = requests.forTrack(trackUri)
        assertEquals("spotify:album:5uYDAwW0SZgcfOFkxrST64", request.contextUri)
        assertEquals(SpPlaybackOffset(uri = trackUri), request.offset)
        assertNull(request.uris)
    }

    @Test
    fun `the album is looked up once per track`() = runTest {
        var lookups = 0
        val requests = SpotifyPlayRequests(lookupTrack = { lookups++; track(it, "album1") })
        requests.prefetch(trackUri)
        requests.forTrack(trackUri)
        requests.forTrack(trackUri)
        assertEquals(1, lookups)
    }

    @Test
    fun `a relinked track starts at the id its album holds`() = runTest {
        val requests = SpotifyPlayRequests(lookupTrack = { track("relinkedId123", "album1") })
        val request = requests.forTrack(trackUri)
        assertEquals("spotify:album:album1", request.contextUri)
        assertEquals("spotify:track:relinkedId123", request.offset?.uri)
    }

    @Test
    fun `a failed lookup plays the bare track and is asked again next time`() = runTest {
        var lookups = 0
        val requests = SpotifyPlayRequests(lookupTrack = {
            lookups++
            if (lookups == 1) throw SpotifyRateLimitedException(60_000) else track(it, "album1")
        })
        assertEquals(listOf(trackUri), requests.forTrack(trackUri).uris)
        assertEquals("spotify:album:album1", requests.forTrack(trackUri).contextUri)
        assertEquals(2, lookups)
    }

    @Test
    fun `a track with no album plays bare and isn't looked up again`() = runTest {
        var lookups = 0
        val requests = SpotifyPlayRequests(lookupTrack = { lookups++; track(it, null) })
        assertEquals(listOf(trackUri), requests.forTrack(trackUri).uris)
        assertEquals(listOf(trackUri), requests.forTrack(trackUri).uris)
        assertEquals(1, lookups)
    }

    @Test
    fun `anything but a track uri plays bare without a lookup`() = runTest {
        var lookups = 0
        val requests = SpotifyPlayRequests(lookupTrack = { lookups++; track(it, "album1") })
        for (uri in listOf("spotify:episode:abc", "spotify:track:", "spotify:track:a/b", "https://open.spotify.com/track/x")) {
            assertEquals(listOf(uri), requests.forTrack(uri).uris)
        }
        assertEquals(0, lookups)
    }

    @Test
    fun `the cache keeps at most maxEntries tracks`() = runTest {
        var lookups = 0
        val requests = SpotifyPlayRequests(lookupTrack = { lookups++; track(it, "album1") }, maxEntries = 2)
        requests.prefetch("spotify:track:a")
        requests.prefetch("spotify:track:b")
        requests.prefetch("spotify:track:c") // evicts a
        requests.prefetch("spotify:track:b")
        requests.prefetch("spotify:track:a")
        assertEquals(4, lookups)
    }

    // ── The body on the wire, through the shared client's JSON config ──

    private val appConfig = AppConfig(userAgent = "Parachord/test", isDebug = false)
    // The shared client encodes defaults (SharedModule), which is what put
    // `"context_uri": null` into every play.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; coerceInputValues = true }
    private val auth = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = AuthCredential.BearerToken("test")
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val refresher = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    private suspend fun sentBody(request: SpPlaybackRequest): JsonObject {
        var body = ""
        val mock = MockEngine { req ->
            body = (req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond("", HttpStatusCode.NoContent)
        }
        val client = SpotifyClient(HttpClient(mock) { installSharedPlugins(json, appConfig, auth, refresher) }, auth)
        client.startPlayback(request, deviceId = "dev1")
        return Json.parseToJsonElement(body).jsonObject
    }

    @Test
    fun `an album play sends the context and offset and no uris`() = runTest {
        val body = sentBody(SpPlaybackRequest(contextUri = "spotify:album:a1", offset = SpPlaybackOffset(trackUri)))
        assertEquals(setOf("context_uri", "offset"), body.keys)
        assertEquals("spotify:album:a1", body["context_uri"]!!.jsonPrimitive.content)
        assertEquals(trackUri, body["offset"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a bare play sends no null context`() = runTest {
        val body = sentBody(SpPlaybackRequest(uris = listOf(trackUri)))
        assertEquals(setOf("uris"), body.keys)
        assertFalse(body.containsKey("context_uri"))
        assertEquals(trackUri, body["uris"]!!.jsonArray.single().jsonPrimitive.content)
    }
}
