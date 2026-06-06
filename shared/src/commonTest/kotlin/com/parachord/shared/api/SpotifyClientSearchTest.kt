package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * MockEngine-based unit tests for [SpotifyClient.searchTrack], the shared
 * Spotify track resolution lifted verbatim from Android's
 * `ResolverManager.searchSpotifyTrack` so both platforms call one impl.
 */
class SpotifyClientSearchTest {

    private val appConfig = AppConfig(
        userAgent = "Parachord/0.5.0-test (Android; https://parachord.app)",
        isDebug = false,
    )
    private val json = Json { ignoreUnknownKeys = true }

    private val stubAuthProvider = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? =
            if (realm == AuthRealm.Spotify) AuthCredential.BearerToken("test") else null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val stubTokenRefresher = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    private fun buildClient(mock: MockEngine): SpotifyClient {
        val httpClient = HttpClient(mock) {
            installSharedPlugins(json, appConfig, stubAuthProvider, stubTokenRefresher)
        }
        return SpotifyClient(httpClient, stubAuthProvider)
    }

    @Test
    fun searchTrack_returnsResolvedSource_forPlayableTrack() = runTest {
        val mock = MockEngine { request ->
            assertEquals(
                "https://api.spotify.com/v1/search",
                request.url.toString().substringBefore("?"),
            )
            respond(
                content = """
                    {
                      "tracks": {
                        "items": [
                          {
                            "id": "abc",
                            "name": "The Underdog",
                            "artists": [ { "name": "Spoon" } ],
                            "duration_ms": 212000,
                            "is_playable": true,
                            "album": { "images": [ { "url": "http://art" } ] }
                          }
                        ]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = buildClient(mock)

        val source = client.searchTrack("The Underdog Spoon")
        assertNotNull(source)
        assertEquals("spotify", source.resolver)
        assertEquals("spotify", source.sourceType)
        assertEquals("spotify:track:abc", source.url)
        assertEquals("spotify:track:abc", source.spotifyUri)
        assertEquals("abc", source.spotifyId)
        assertEquals("The Underdog", source.matchedTitle)
        assertEquals("Spoon", source.matchedArtist)
        assertEquals(212000L, source.matchedDurationMs)
        assertEquals("http://art", source.artworkUrl)
        assertEquals(0.9, source.confidence)
    }

    @Test
    fun searchTrack_returnsNull_whenNoPlayableTrack() = runTest {
        val mock = MockEngine {
            respond(
                content = """
                    {
                      "tracks": {
                        "items": [
                          {
                            "id": "abc",
                            "name": "The Underdog",
                            "artists": [ { "name": "Spoon" } ],
                            "duration_ms": 212000,
                            "is_playable": false,
                            "album": { "images": [ { "url": "http://art" } ] }
                          }
                        ]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = buildClient(mock)

        assertNull(client.searchTrack("The Underdog Spoon"))
    }

    @Test
    fun searchTrack_skipsUnplayable_picksNextPlayable() = runTest {
        val mock = MockEngine {
            respond(
                content = """
                    {
                      "tracks": {
                        "items": [
                          {
                            "id": "bad",
                            "name": "X",
                            "artists": [ { "name": "A" } ],
                            "is_playable": false
                          },
                          {
                            "id": "def",
                            "name": "The Good One",
                            "artists": [ { "name": "B" } ],
                            "duration_ms": 200000,
                            "is_playable": true,
                            "album": { "images": [ { "url": "http://art2" } ] }
                          }
                        ]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = buildClient(mock)

        val source = client.searchTrack("The Good One B")
        assertNotNull(source)
        assertEquals("def", source.spotifyId)
    }
}
