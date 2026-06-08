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
import kotlin.test.assertFailsWith

/**
 * MockEngine-based unit tests for the rate-limit gate on Spotify
 * write/mutation methods (issue #176). A 429 on a write must set the
 * persisted cooldown and trip the gate exactly like a 429 on a read —
 * so subsequent gated calls short-circuit during the abuse window
 * instead of poking an already-rate-limited account.
 *
 * The cooldown is asserted *behaviorally* — a subsequent gated write
 * short-circuits with NO network call — so the test depends only on the
 * public gate semantics, not on any cooldown-introspection accessor.
 */
class SpotifyClientWriteGateTest {

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
    fun saveTracks_on429_tripsGate_andSubsequentWriteShortCircuits() = runTest {
        var calls = 0
        val mock = MockEngine {
            calls++
            respond(
                content = "",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.RetryAfter, "120"),
            )
        }
        val client = buildClient(mock)

        // The 429 on the write trips the gate (typed exception)...
        assertFailsWith<SpotifyRateLimitedException> {
            client.saveTracks(SpIdsRequest(listOf("a")))
        }
        // ...and a subsequent gated write short-circuits WITHOUT a network
        // call — proving the cooldown was armed by the write's 429.
        assertFailsWith<SpotifyRateLimitedException> {
            client.removeTracks(SpIdsRequest(listOf("b")))
        }
        assertEquals(
            1,
            calls,
            "second gated write must short-circuit during the cooldown (no network call)",
        )
    }

    @Test
    fun replacePlaylistTracks_on429_tripsGate() = runTest {
        var calls = 0
        val mock = MockEngine {
            calls++
            respond(
                content = "",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.RetryAfter, "90"),
            )
        }
        val client = buildClient(mock)

        assertFailsWith<SpotifyRateLimitedException> {
            client.replacePlaylistTracks("pl1", SpUrisRequest(listOf("spotify:track:x")))
        }
        // The armed cooldown short-circuits the next gated write with no call.
        assertFailsWith<SpotifyRateLimitedException> {
            client.replacePlaylistTracks("pl1", SpUrisRequest(listOf("spotify:track:y")))
        }
        assertEquals(1, calls)
    }

    @Test
    fun createPlaylist_onSuccess_doesNotTripGate() = runTest {
        var calls = 0
        val mock = MockEngine {
            calls++
            respond(
                content = """{ "id": "pl$calls", "name": "My PL" }""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = buildClient(mock)

        val first = client.createPlaylist("user1", SpCreatePlaylistRequest("My PL", null))
        assertEquals("pl1", first.id)

        // A 2xx write must NOT arm the cooldown — the next gated write still
        // reaches the network (no short-circuit).
        val second = client.createPlaylist("user1", SpCreatePlaylistRequest("My PL", null))
        assertEquals("pl2", second.id)
        assertEquals(2, calls, "a 2xx write must not arm the cooldown")
    }
}
