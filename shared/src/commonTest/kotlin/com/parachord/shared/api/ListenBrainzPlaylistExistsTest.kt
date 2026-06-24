package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MockEngine tests for [ListenBrainzClient.playlistExists] — the dead-mirror
 * self-heal existence probe.
 *
 * The safety-critical property: it returns `false` ONLY on a definitive
 * authenticated `404`. A false "gone" would clear a live link and recreate the
 * playlist as a duplicate (the 6,397-dup flood class), so every other
 * status/error MUST return `true`. It also MUST send the owner's `Token` header
 * — pushed playlists are PRIVATE and an unauth GET 404s on them even though they
 * exist.
 */
class ListenBrainzPlaylistExistsTest {

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    private fun client(engine: MockEngine) = ListenBrainzClient(
        HttpClient(engine) {
            install(ContentNegotiation) { json() }
        },
    )

    @Test
    fun authenticated404_isGone_returnsFalse() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { request ->
            seenAuth = request.headers["Authorization"]
            respond(
                """{"code":404,"error":"Cannot find playlist: abc"}""",
                HttpStatusCode.NotFound,
                jsonHeaders,
            )
        }
        val exists = client(engine).playlistExists("abc", "secret-token")
        assertFalse(exists, "an authenticated 404 means the playlist is genuinely deleted")
        assertEquals("Token secret-token", seenAuth, "the probe MUST send the owner's token")
    }

    @Test
    fun ok_returnsTrue() = runTest {
        val engine = MockEngine { respond("""{"playlist":{}}""", HttpStatusCode.OK, jsonHeaders) }
        assertTrue(client(engine).playlistExists("abc", "tok"))
    }

    @Test
    fun unauthorized401_doesNotFalseClear_returnsTrue() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized, jsonHeaders) }
        assertTrue(
            client(engine).playlistExists("abc", "tok"),
            "401 is not proof of deletion — keep the link",
        )
    }

    @Test
    fun forbidden403_doesNotFalseClear_returnsTrue() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Forbidden, jsonHeaders) }
        assertTrue(client(engine).playlistExists("abc", "tok"))
    }

    @Test
    fun serverError500_doesNotFalseClear_returnsTrue() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError, jsonHeaders) }
        assertTrue(client(engine).playlistExists("abc", "tok"))
    }

    @Test
    fun rateLimited429_doesNotFalseClear_returnsTrue() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.TooManyRequests, jsonHeaders) }
        assertTrue(client(engine).playlistExists("abc", "tok"))
    }

    @Test
    fun networkThrow_doesNotFalseClear_returnsTrue() = runTest {
        val engine = MockEngine { throw RuntimeException("connection reset") }
        assertTrue(
            client(engine).playlistExists("abc", "tok"),
            "a thrown network error must never be read as deletion",
        )
    }
}
