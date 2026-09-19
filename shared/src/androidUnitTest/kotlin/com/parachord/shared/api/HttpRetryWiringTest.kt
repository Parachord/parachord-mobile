package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end wiring for the network-handoff retry.
 *
 * [TransientNetworkErrorTest] pins the predicate; this pins that the shared
 * HttpClient is actually wired to ACT on it — the two can drift apart, and a
 * correct predicate nobody consults fixes nothing.
 *
 * Lives in androidUnitTest: it needs real `java.net` exceptions (commonTest
 * must also compile for iOS) AND visibility of the internal
 * [installSharedPlugins], which :app does not have.
 */
class HttpRetryWiringTest {

    private val appConfig = AppConfig(userAgent = "Parachord/test", isDebug = false)
    private val json = Json { ignoreUnknownKeys = true }
    private val stubProvider = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val stubRefresher = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    private fun clientThatFails(times: Int, error: () -> Throwable, calls: AtomicInteger) =
        HttpClient(
            MockEngine {
                if (calls.incrementAndGet() <= times) throw error()
                respond("ok", HttpStatusCode.OK)
            },
        ) { installSharedPlugins(json, appConfig, stubProvider, stubRefresher) }

    @Test
    fun `a DNS blip is retried and the call succeeds`() = runBlocking {
        val calls = AtomicInteger(0)
        val client = clientThatFails(1, { UnknownHostException("api.spotify.com") }, calls)

        val resp = client.get("https://api.spotify.com/v1/x")

        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(2, calls.get(), "should have retried exactly once")
    }

    @Test
    fun `an aborted socket is retried`() = runBlocking {
        val calls = AtomicInteger(0)
        val client = clientThatFails(1, { java.net.SocketException("Software caused connection abort") }, calls)

        assertEquals(HttpStatusCode.OK, client.get("https://api.discogs.com/x").status)
        assertEquals(2, calls.get())
    }

    @Test
    fun `retries are bounded - a persistent outage gives up`() = runBlocking {
        val calls = AtomicInteger(0)
        // Always fails. maxRetries = 2 means 1 initial + 2 retries = 3 attempts.
        val client = clientThatFails(Int.MAX_VALUE, { UnknownHostException("down") }, calls)

        runCatching { client.get("https://example.com/x") }
        assertEquals(3, calls.get(), "1 initial attempt + 2 retries")
    }

    @Test
    fun `a read timeout is NOT retried - it may have reached the server`() = runBlocking {
        val calls = AtomicInteger(0)
        val client = clientThatFails(Int.MAX_VALUE, { SocketTimeoutException("timeout") }, calls)

        runCatching { client.get("https://example.com/x") }
        assertEquals(1, calls.get(), "must not replay a request the server may have acted on")
    }

    @Test
    fun `an HTTP error status is NOT retried - RateLimitGate owns that`() = runBlocking {
        val calls = AtomicInteger(0)
        val client = HttpClient(
            MockEngine { calls.incrementAndGet(); respond("slow down", HttpStatusCode.TooManyRequests) },
        ) { installSharedPlugins(json, appConfig, stubProvider, stubRefresher) }

        val resp = client.get("https://api.spotify.com/v1/x")

        assertEquals(HttpStatusCode.TooManyRequests, resp.status)
        assertEquals(1, calls.get(), "re-poking a 429 is how Spotify bans the account (#176/#177)")
    }
}
