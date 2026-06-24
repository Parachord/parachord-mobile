package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [RateLimitGate]'s escalating circuit breaker (opt-in).
 *
 * Background: Spotify's abuse-mode ban routinely outlasts the 1h `maxCooldownMs`
 * cap. With a flat cooldown, every time our local cooldown lapses the next call
 * re-pokes the still-banned account and re-extends Spotify's window — so it
 * never clears. Escalation doubles the cooldown on each consecutive 429 (reset
 * on the first success) so a persistent ban quickly pushes our local cooldown
 * past Spotify's window and we stop poking.
 */
class RateLimitGateTest {

    private class TestRateLimited(val retryAfter: Long?) : Exception()

    private suspend fun response(status: HttpStatusCode, retryAfterSec: Long?): HttpResponse {
        val engine = MockEngine {
            respond(
                content = "",
                status = status,
                headers = if (retryAfterSec != null) headersOf("Retry-After", retryAfterSec.toString()) else headersOf(),
            )
        }
        return HttpClient(engine).get("http://example.test/")
    }

    private fun gate(escalate: Boolean, now: () -> Long) = RateLimitGate(
        tag = "test",
        defaultCooldownSec = 60L,
        maxCooldownMs = 6L * 60L * 60L * 1000L, // 6h cap
        escalateOnRepeat = escalate,
        nowMs = now,
    )

    /** Gate matching MusicBrainz's opt-in: a positive [minCooldown] floor and a
     *  non-escalating, low default. */
    private fun flooredGate(minCooldown: Long, now: () -> Long) = RateLimitGate(
        tag = "test",
        defaultCooldownSec = 30L,
        minCooldownMs = minCooldown,
        nowMs = now,
    )

    private fun RateLimitGate.feed429(resp: HttpResponse) {
        assertFailsWith<TestRateLimited> {
            handleResponse(resp, exceptionFactory = { TestRateLimited(it) })
        }
    }

    /** Feeds a response through the MusicBrainz-style 429-OR-503 predicate (the
     *  floor tests exercise 503, which the default 429-only predicate ignores). */
    private fun RateLimitGate.feed503(resp: HttpResponse) {
        assertFailsWith<TestRateLimited> {
            handleResponse(
                resp,
                isRateLimited = { it.status.value == 429 || it.status.value == 503 },
                exceptionFactory = { TestRateLimited(it) },
            )
        }
    }

    @Test
    fun rolling_window_delays_calls_over_budget() = runTest {
        // Budget: 3 requests per 1000ms window. nowMs reads the test scheduler's
        // virtual clock so the gate's window and `delay()` advance together.
        val clock = testScheduler
        val gate = RateLimitGate(
            tag = "test",
            interRequestDelayMs = 0L,            // isolate the window from the inter-request gap
            maxRequestsPerWindow = 3,
            requestWindowMs = 1000L,
            nowMs = { clock.currentTime },
        )
        val ranAt = mutableListOf<Long>()
        repeat(4) {
            gate.withPermit(exceptionFactory = { TestRateLimited(it) }) { ranAt.add(clock.currentTime) }
        }
        // First 3 fit at t=0; the 4th waits until the oldest ages out at t=1000.
        assertEquals(listOf(0L, 0L, 0L, 1000L), ranAt)
    }

    @Test
    fun rolling_window_off_by_default_no_delay() = runTest {
        // No maxRequestsPerWindow → the window cap is inert; many calls, no wait.
        val clock = testScheduler
        val gate = RateLimitGate(tag = "test", interRequestDelayMs = 0L, nowMs = { clock.currentTime })
        var count = 0
        repeat(50) { gate.withPermit(exceptionFactory = { TestRateLimited(it) }) { count++ } }
        assertEquals(50, count)
        assertEquals(0L, clock.currentTime)   // nothing delayed
    }

    @Test
    fun escalating_gate_doubles_cooldown_on_consecutive_429s() = runTest {
        var now = 0L
        val g = gate(escalate = true, now = { now })
        val r = response(HttpStatusCode.TooManyRequests, 60L) // 60s base

        g.feed429(r)
        val c1 = g.remainingCooldownMs()
        now += c1 + 1 // let the cooldown lapse, as a real re-poke would

        g.feed429(r)
        val c2 = g.remainingCooldownMs()
        now += c2 + 1

        g.feed429(r)
        val c3 = g.remainingCooldownMs()

        assertEquals(60_000L, c1)
        assertEquals(120_000L, c2)
        assertEquals(240_000L, c3)
        assertTrue(c3 > c2 && c2 > c1)
    }

    @Test
    fun success_resets_the_escalation() = runTest {
        var now = 0L
        val g = gate(escalate = true, now = { now })
        val r429 = response(HttpStatusCode.TooManyRequests, 60L)

        g.feed429(r429)
        now += g.remainingCooldownMs() + 1
        g.feed429(r429) // strike 2 → 120s
        assertEquals(120_000L, g.remainingCooldownMs())
        now += g.remainingCooldownMs() + 1

        // A successful (non-rate-limited) response resets the strike counter.
        g.handleResponse(response(HttpStatusCode.OK, null), exceptionFactory = { TestRateLimited(it) })

        g.feed429(r429) // back to base
        assertEquals(60_000L, g.remainingCooldownMs())
    }

    @Test
    fun non_escalating_gate_stays_flat() = runTest {
        var now = 0L
        val g = gate(escalate = false, now = { now })
        val r = response(HttpStatusCode.TooManyRequests, 60L)

        g.feed429(r)
        val c1 = g.remainingCooldownMs()
        now += c1 + 1
        g.feed429(r)
        val c2 = g.remainingCooldownMs()

        assertEquals(60_000L, c1)
        assertEquals(60_000L, c2) // unchanged — default behavior preserved
    }

    // ── Minimum backoff floor (#273) ──────────────────────────────────

    @Test
    fun floor_applies_when_503_has_no_retry_after() = runTest {
        // MusicBrainz 503 with NO Retry-After header. Without the floor this
        // computes defaultCooldownSec; with an explicit zero (next test) it
        // would be 0. The floor guarantees at least 1s of short-circuit window.
        var now = 0L
        val g = flooredGate(minCooldown = 1_000L, now = { now })
        g.feed503(response(HttpStatusCode.ServiceUnavailable, retryAfterSec = null))
        assertTrue(
            g.remainingCooldownMs() >= 1_000L,
            "a no-Retry-After 503 must still arm a window (got ${g.remainingCooldownMs()}ms)",
        )
    }

    @Test
    fun floor_rescues_an_explicit_zero_retry_after() = runTest {
        // The on-device bug (#273): `Retry-After: 0` → 0ms backoff → window
        // defeated → tight-loop of `Backing off 0s`. The floor lifts it to 1s.
        var now = 0L
        val g = flooredGate(minCooldown = 1_000L, now = { now })
        g.feed503(response(HttpStatusCode.ServiceUnavailable, retryAfterSec = 0L))
        assertEquals(
            1_000L,
            g.remainingCooldownMs(),
            "Retry-After: 0 must be floored to the 1s minimum, not 0",
        )
    }

    @Test
    fun zero_retry_after_without_floor_yields_zero_window() = runTest {
        // Documents the pre-fix behavior the floor protects against: with NO
        // floor (default), `Retry-After: 0` produces a 0ms window — calls hit
        // the service immediately and re-trip the throttle.
        var now = 0L
        val g = flooredGate(minCooldown = 0L, now = { now })
        g.feed503(response(HttpStatusCode.ServiceUnavailable, retryAfterSec = 0L))
        assertEquals(0L, g.remainingCooldownMs())
    }

    @Test
    fun larger_server_retry_after_still_wins_over_floor() = runTest {
        // The floor is a MINIMUM, not a cap: a generous server window is honored.
        var now = 0L
        val g = flooredGate(minCooldown = 1_000L, now = { now })
        g.feed503(response(HttpStatusCode.ServiceUnavailable, retryAfterSec = 45L))
        assertEquals(45_000L, g.remainingCooldownMs())
    }

    @Test
    fun escalation_is_capped_at_maxCooldown() = runTest {
        var now = 0L
        val g = gate(escalate = true, now = { now })
        val r = response(HttpStatusCode.TooManyRequests, 3600L) // 1h base
        // 1h → 2h → 4h → 6h(cap) → 6h(cap)
        val seen = mutableListOf<Long>()
        repeat(5) {
            g.feed429(r)
            val rem = g.remainingCooldownMs()
            seen.add(rem)
            now += rem + 1
        }
        assertEquals(6L * 60L * 60L * 1000L, seen.last())
        assertTrue(seen.all { it <= 6L * 60L * 60L * 1000L })
    }
}
