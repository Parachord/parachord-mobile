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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MusicBrainz rate-limit compliance (#375).
 *
 * MetaBrainz's 2026-08 bot mitigation started enforcing the published
 * **1 req/sec/IP** ceiling. This client previously ran on the permissive
 * default — `Semaphore(2)` + a 150ms gap, i.e. ~6–13 req/sec — so a global
 * search (three `/ws/2/` queries, re-fired per keystroke) reliably drew
 * 429/503, and the throttled response was rendered as an empty result set,
 * making a rate-limited search look like a broken one.
 *
 * Virtual time: `runTest` makes `delay()` instant while advancing
 * `testScheduler.currentTime`, so the enforced gaps are asserted exactly
 * without the suite actually sleeping seconds.
 */
class MusicBrainzThrottleTest {

    private val appConfig = AppConfig(userAgent = "Parachord/test ( Android; https://parachord.com )")
    private val json = Json { ignoreUnknownKeys = true }
    private val stubAuth = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val stubRefresher = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    private val emptyRecordingSearch = """{"count":0,"offset":0,"recordings":[]}"""

    /** The gate's cooldown clock MUST be the test scheduler's, or a virtual
     *  `delay()` advances time the gate can't see and every retry assertion
     *  becomes vacuous. */
    private fun kotlinx.coroutines.test.TestScope.client(mock: MockEngine) = MusicBrainzClient(
        HttpClient(mock) { installSharedPlugins(json, appConfig, stubAuth, stubRefresher) },
        nowMs = { testScheduler.currentTime },
    )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.okJson() = respond(
        emptyRecordingSearch,
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )

    // ── serialization + 1 req/sec ────────────────────────────────────

    @Test
    fun `concurrent searches are serialized at no more than 1 per second`() = runTest {
        val startTimes = mutableListOf<Long>()
        val mock = MockEngine {
            startTimes += testScheduler.currentTime
            okJson()
        }
        val mb = client(mock)

        // The exact shape of a global search: three categories fired at once.
        listOf(
            async { mb.searchRecordings("radiohead") },
            async { mb.searchRecordings("radiohead") },
            async { mb.searchRecordings("radiohead") },
        ).awaitAll()

        assertEquals(3, startTimes.size)
        // Parallelism must NOT translate into parallel requests.
        for (i in 1 until startTimes.size) {
            val gap = startTimes[i] - startTimes[i - 1]
            assertTrue(
                gap >= MusicBrainzClient.MB_MIN_REQUEST_GAP_MS,
                "requests $i and ${i - 1} were ${gap}ms apart, under the " +
                    "${MusicBrainzClient.MB_MIN_REQUEST_GAP_MS}ms MusicBrainz floor",
            )
        }
    }

    // ── 429/503 → wait and retry, not an empty result ────────────────

    @Test
    fun `a 503 with Retry-After is waited out and retried, and the call succeeds`() = runTest {
        var calls = 0
        val mock = MockEngine {
            calls++
            if (calls == 1) {
                respond("", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "1"))
            } else {
                okJson()
            }
        }
        val mb = client(mock)

        val result = mb.searchRecordings("radiohead")

        assertTrue(result.recordings.isEmpty())
        assertEquals(2, calls, "should have retried after the 503")
        assertFalse(mb.rateLimited.value, "a recovered throttle must not leave the flag set")
    }

    @Test
    fun `a plain 429 is retried too - MB may switch from 503`() = runTest {
        var calls = 0
        val mock = MockEngine {
            calls++
            if (calls == 1) respond("", HttpStatusCode.TooManyRequests) else okJson()
        }

        client(mock).searchRecordings("radiohead")

        assertEquals(2, calls)
    }

    @Test
    fun `a sustained throttle gives up bounded and reports rate-limited, not empty`() = runTest {
        var calls = 0
        val mock = MockEngine {
            calls++
            respond("", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "1"))
        }
        val mb = client(mock)

        var threw = false
        try {
            mb.searchRecordings("radiohead")
        } catch (_: MusicBrainzRateLimitedException) {
            threw = true
        }

        // The caller must be TOLD it was throttled. Returning an empty result
        // here is the actual bug: the UI cannot tell it apart from "no matches".
        assertTrue(threw, "an unrecoverable throttle must surface, not return empty")
        assertTrue(mb.rateLimited.value, "UI needs this to say 'rate-limited' instead of 'No results'")
        assertEquals(
            1 + MusicBrainzClient.MB_MAX_RATE_LIMIT_RETRIES,
            calls,
            "1 initial attempt + MB_MAX_RATE_LIMIT_RETRIES",
        )
    }

    @Test
    fun `an absurd Retry-After is not waited out - it surfaces instead`() = runTest {
        var calls = 0
        val mock = MockEngine {
            calls++
            // 10 minutes. Parking a user-facing search for that long is worse
            // than telling them it's throttled.
            respond("", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "600"))
        }
        val mb = client(mock)

        try {
            mb.searchRecordings("radiohead")
        } catch (_: MusicBrainzRateLimitedException) { /* expected */ }

        assertEquals(1, calls, "must give up immediately, not retry into a 10-minute wait")
    }

    @Test
    fun `rate-limited clears once MusicBrainz recovers`() = runTest {
        var failing = true
        val mock = MockEngine {
            if (failing) respond("", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "1"))
            else okJson()
        }
        val mb = client(mock)

        try {
            mb.searchRecordings("radiohead")
        } catch (_: MusicBrainzRateLimitedException) { /* expected */ }
        assertTrue(mb.rateLimited.value)

        failing = false
        mb.searchRecordings("radiohead")

        assertFalse(mb.rateLimited.value, "a success must clear the sticky rate-limited state")
    }
}
