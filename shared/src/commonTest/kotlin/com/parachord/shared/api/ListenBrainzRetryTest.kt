package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ListenBrainz retry matrix (#291), ported from desktop's
 * `tests/sync/lb-request-retry.test.js` (parachord#942). Virtual time:
 * `currentTime` is how long the retries waited.
 */
class ListenBrainzRetryTest {

    private class Rig(statuses: List<Pair<HttpStatusCode, String?>>) {
        var calls = 0
        val engine = MockEngine {
            val (status, retryAfter) = statuses.getOrElse(calls) { statuses.last() }
            calls++
            respond(
                "{}",
                status,
                if (retryAfter != null) headersOf(HttpHeaders.RetryAfter, retryAfter) else headersOf(),
            )
        }
        val client = ListenBrainzClient(HttpClient(engine))
        suspend fun call(): HttpStatusCode =
            client.executeWithRetry { HttpClient(engine).get("https://api.listenbrainz.org/1/x") }.status
    }

    private fun TestScope.rig(vararg statuses: Pair<HttpStatusCode, String?>) = Rig(statuses.toList())

    private val ok = HttpStatusCode.OK to null

    @Test
    fun aSuccessIsNotRetried() = runTest {
        val r = rig(ok)
        assertEquals(HttpStatusCode.OK, r.call())
        assertEquals(1, r.calls)
        assertEquals(0L, currentTime)
    }

    @Test
    fun a429WaitsRetryAfterThenRetries() = runTest {
        val r = rig(HttpStatusCode.TooManyRequests to "2", ok)
        assertEquals(HttpStatusCode.OK, r.call())
        assertEquals(2, r.calls)
        assertEquals(2_000L, currentTime)
    }

    @Test
    fun a429WithoutRetryAfterWaitsFiveSeconds() = runTest {
        val r = rig(HttpStatusCode.TooManyRequests to null, ok)
        assertEquals(HttpStatusCode.OK, r.call())
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun gatewayErrorsBackOffExponentially() = runTest {
        val r = rig(
            HttpStatusCode.BadGateway to null,
            HttpStatusCode.ServiceUnavailable to null,
            HttpStatusCode.GatewayTimeout to null,
            ok,
        )
        assertEquals(HttpStatusCode.OK, r.call())
        assertEquals(4, r.calls)
        assertEquals(1_000L + 2_000L + 4_000L, currentTime)
    }

    @Test
    fun afterThreeRetriesTheLastResponseIsReturned() = runTest {
        val r = rig(HttpStatusCode.ServiceUnavailable to null)
        assertEquals(HttpStatusCode.ServiceUnavailable, r.call())
        assertEquals(4, r.calls)
    }

    @Test
    fun notFoundIsNotRetried() = runTest {
        val r = rig(HttpStatusCode.NotFound to null)
        assertEquals(HttpStatusCode.NotFound, r.call())
        assertEquals(1, r.calls)
    }

    @Test
    fun unauthorizedIsNotRetried() = runTest {
        val r = rig(HttpStatusCode.Unauthorized to null)
        assertEquals(HttpStatusCode.Unauthorized, r.call())
        assertEquals(1, r.calls)
    }

    @Test
    fun otherServerErrorsAreNotRetried() = runTest {
        val r = rig(HttpStatusCode.InternalServerError to null)
        assertEquals(HttpStatusCode.InternalServerError, r.call())
        assertEquals(1, r.calls)
    }

    @Test
    fun aLongRetryAfterIsNotWaitedOut() = runTest {
        val r = rig(HttpStatusCode.TooManyRequests to "3600", ok)
        assertEquals(HttpStatusCode.TooManyRequests, r.call())
        assertEquals(1, r.calls)
        assertEquals(0L, currentTime)
    }
}
