package com.parachord.android.auth

import android.content.Context
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.store.SecureTokenStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Covers the session-scoped Spotify refresh kill-switch wired into
 * [OAuthManager] for issue #156.
 *
 * Regression target: every handler that independently calls
 * `refreshSpotifyToken` on 401 (device poller, scrobbler, sync engine)
 * would re-fire the refresh after the user revoked Parachord from
 * accounts.spotify.com — observed ~100 token-endpoint POSTs in 30s
 * across 8 worker threads. The kill-switch + sentinel exception cap
 * that at exactly one POST per session.
 */
class OAuthManagerSpotifyRefreshTest {

    private lateinit var server: MockWebServer
    private lateinit var manager: OAuthManager
    private lateinit var settings: SettingsStore
    private lateinit var secureStore: SecureTokenStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        secureStore = mockk(relaxed = true)
        every { secureStore.get("oauth_pending_flows") } returns null

        settings = mockk(relaxed = true)
        every { settings.secureStore } returns secureStore
        coEvery { settings.getSpotifyRefreshToken() } returns "valid-refresh-token"

        // Redirect any request to https://accounts.spotify.com/... to MockWebServer.
        // refreshSpotifyToken hardcodes the URL, so injecting a base-URL isn't
        // possible without changing the production API — rewriting at the
        // interceptor layer gets us a network-faithful test without that
        // refactor.
        val redirectInterceptor = Interceptor { chain ->
            val originalUrl = chain.request().url
            val rewritten = server.url(originalUrl.encodedPath).newBuilder()
                .apply { originalUrl.query?.let { encodedQuery(it) } }
                .build()
            chain.proceed(chain.request().newBuilder().url(rewritten).build())
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(redirectInterceptor)
            .build()

        manager = OAuthManager(
            context = mockk<Context>(relaxed = true),
            settingsStore = settings,
            okHttpClient = client,
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `400 invalid_grant trips kill-switch and throws sentinel`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    "{\"error\":\"invalid_grant\"," +
                        "\"error_description\":\"Refresh token revoked\"}",
                ),
        )

        try {
            manager.refreshSpotifyTokenWithConfig("test-client-id")
            fail("Expected SpotifyReauthRequiredException")
        } catch (_: SpotifyReauthRequiredException) {
            // expected
        }

        assertTrue(
            "kill-switch should be exposed via StateFlow",
            manager.spotifyReauthRequired.value,
        )
        assertEquals("first call must hit the endpoint", 1, server.requestCount)
        // #243: the dead refresh token MUST be discarded so it's never
        // re-poked on the next launch (the kill-switch is process-scoped).
        coVerify(exactly = 1) { settings.clearSpotifyTokens() }
    }

    @Test
    fun `invalid_client on 401 is terminal - discards tokens and trips kill-switch`() = runBlocking {
        // Desktop-parity widening (#243): not just 400 invalid_grant.
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_client\"}"),
        )

        try {
            manager.refreshSpotifyTokenWithConfig("test-client-id")
            fail("Expected SpotifyReauthRequiredException")
        } catch (_: SpotifyReauthRequiredException) {
            // expected
        }

        assertTrue(manager.spotifyReauthRequired.value)
        coVerify(exactly = 1) { settings.clearSpotifyTokens() }
    }

    @Test
    fun `transient 5xx keeps the token - no discard, no kill-switch`() = runBlocking {
        // A Spotify outage must NOT log the user out.
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"server_error\"}"),
        )

        val result = manager.refreshSpotifyTokenWithConfig("test-client-id")
        assertFalse("transient failure should return false", result)
        assertFalse(
            "kill-switch must NOT trip for a transient 5xx",
            manager.spotifyReauthRequired.value,
        )
        coVerify(exactly = 0) { settings.clearSpotifyTokens() }
    }

    @Test
    fun `parallel refreshes after invalid_grant only fire one HTTP call`() = runBlocking {
        // Only enqueue ONE response. If the kill-switch fails to engage,
        // a second call hits the endpoint and the server returns its
        // default 404, the test still passes the requestCount assertion
        // but the StateFlow won't trip on the second call's 404 — so we
        // pin both behaviors below.
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    "{\"error\":\"invalid_grant\"," +
                        "\"error_description\":\"Refresh token revoked\"}",
                ),
        )

        // Trip the kill-switch on the first call.
        try {
            manager.refreshSpotifyTokenWithConfig("test-client-id")
            fail("Expected SpotifyReauthRequiredException on first call")
        } catch (_: SpotifyReauthRequiredException) {
            // expected
        }
        assertEquals(1, server.requestCount)

        // Now race 8 concurrent callers — desktop logs showed ~8 worker
        // threads all retrying refresh in the bug repro. Every one should
        // short-circuit via the sentinel without touching the network.
        val errors = coroutineScope {
            (1..8).map {
                async {
                    runCatching { manager.refreshSpotifyTokenWithConfig("test-client-id") }
                        .exceptionOrNull()
                }
            }.awaitAll()
        }

        errors.forEachIndexed { index, error ->
            assertNotNull("caller #$index expected to throw", error)
            assertTrue(
                "caller #$index expected SpotifyReauthRequiredException, got $error",
                error is SpotifyReauthRequiredException,
            )
        }
        assertEquals(
            "no additional HTTP requests should reach the token endpoint",
            1,
            server.requestCount,
        )
    }

    @Test
    fun `non-invalid_grant 400 does NOT trip kill-switch and returns false`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"unsupported_grant_type\"}"),
        )

        val result = manager.refreshSpotifyTokenWithConfig("test-client-id")
        assertFalse("transient failure should return false", result)
        assertFalse(
            "kill-switch must NOT trip for non-invalid_grant errors",
            manager.spotifyReauthRequired.value,
        )
        coVerify(exactly = 0) { settings.clearSpotifyTokens() }
    }
}
