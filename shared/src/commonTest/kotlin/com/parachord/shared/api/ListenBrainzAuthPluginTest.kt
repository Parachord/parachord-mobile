package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MockEngine tests for [ListenBrainzAuthPlugin].
 *
 * The MockEngine handler captures the outbound request's `Authorization`
 * header at the engine boundary — i.e. AFTER the plugin pipeline has run.
 * That's exactly what we need to verify: did the plugin attach (or pass
 * through, or preserve) the header correctly?
 */
class ListenBrainzAuthPluginTest {

    @Test
    fun attachesTokenForListenBrainzHost() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            respond("{}", HttpStatusCode.OK)
        }
        val client = HttpClient(engine) {
            install(ListenBrainzAuthPlugin) { tokenProvider = { "secret-token" } }
        }
        client.get("https://api.listenbrainz.org/1/user/foo/playing-now")
        assertEquals("Token secret-token", seenAuth)
    }

    @Test
    fun doesNotAttachTokenForOtherHosts() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            respond("{}", HttpStatusCode.OK)
        }
        val client = HttpClient(engine) {
            install(ListenBrainzAuthPlugin) { tokenProvider = { "secret-token" } }
        }
        client.get("https://example.com/something")
        assertNull(seenAuth)
    }

    @Test
    fun noTokenConfigured_passesThroughWithoutAuthHeader() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            respond("{}", HttpStatusCode.Unauthorized)
        }
        val client = HttpClient(engine) {
            install(ListenBrainzAuthPlugin) { tokenProvider = { null } }
        }
        client.get("https://api.listenbrainz.org/1/user/foo/playing-now")
        assertNull(seenAuth)
    }

    @Test
    fun preservesExistingAuthHeader() = runTest {
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            respond("{}", HttpStatusCode.OK)
        }
        val client = HttpClient(engine) {
            install(ListenBrainzAuthPlugin) { tokenProvider = { "plugin-token" } }
        }
        client.get("https://api.listenbrainz.org/1/user/foo/playing-now") {
            headers { append("Authorization", "Token explicit-token") }
        }
        assertEquals("Token explicit-token", seenAuth)
    }

    @Test
    fun providerThrows_passesThroughWithoutAuthHeader() = runTest {
        // We deliberately don't propagate the throwable — a thrown exception
        // here would kill every LB call. The plugin logs the failure (Log.w)
        // and falls through; callers see the natural 401 from the server.
        var seenAuth: String? = null
        val engine = MockEngine { req ->
            seenAuth = req.headers["Authorization"]
            respond("{}", HttpStatusCode.OK)
        }
        val client = HttpClient(engine) {
            install(ListenBrainzAuthPlugin) {
                tokenProvider = { error("boom") }
            }
        }
        client.get("https://api.listenbrainz.org/1/user/foo/playing-now")
        assertNull(seenAuth)
    }
}
