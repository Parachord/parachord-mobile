package com.parachord.shared.api

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Apple Music catalog limiter both platforms use (#241). Moved from the
 * Android-only test when the class moved to shared, so it now runs on the JVM
 * and the iOS simulator. The clock is injected so cooldown expiry is testable.
 */
class MusicKitCatalogLimiterTest {

    /** A hand-advanced clock. */
    private class FakeClock(var now: Long = 1_000_000L)

    @Test
    fun runThrottled_returnsBlockResult_whenNoCooldown() = runTest {
        assertEquals(42, MusicKitCatalogLimiter().runThrottled { 42 })
    }

    @Test
    fun runThrottled_skipsBlock_whenCooldownActive() = runTest {
        val limiter = MusicKitCatalogLimiter()
        repeat(MusicKitCatalogLimiter.CONSECUTIVE_429_THRESHOLD) { limiter.reportRateLimit() }
        var ran = false
        assertNull(limiter.runThrottled { ran = true; "should not run" })
        assertFalse(ran, "block must not execute while cooldown is open")
    }

    @Test
    fun reportRateLimit_underThreshold_doesNotOpenCooldown() = runTest {
        val limiter = MusicKitCatalogLimiter()
        limiter.reportRateLimit()
        limiter.reportRateLimit() // 2 of 3
        var ran = false
        assertEquals("ok", limiter.runThrottled { ran = true; "ok" })
        assertTrue(ran)
    }

    @Test
    fun reportRateLimit_atThreshold_opensCooldownForFiveMinutes() = runTest {
        val clock = FakeClock()
        val limiter = MusicKitCatalogLimiter(nowMs = { clock.now })
        repeat(MusicKitCatalogLimiter.CONSECUTIVE_429_THRESHOLD) { limiter.reportRateLimit() }
        assertEquals(clock.now + MusicKitCatalogLimiter.COOLDOWN_MS, limiter.cooldownUntilMsForTest)
    }

    @Test
    fun theCooldownIsTimeBound_notSessionPermanent() = runTest {
        // One throttle must not disable Apple Music for the rest of the session.
        val clock = FakeClock()
        val limiter = MusicKitCatalogLimiter(nowMs = { clock.now })
        repeat(MusicKitCatalogLimiter.CONSECUTIVE_429_THRESHOLD) { limiter.reportRateLimit() }

        clock.now += MusicKitCatalogLimiter.COOLDOWN_MS - 1
        assertNull(limiter.runThrottled { "still cooling down" })

        clock.now += 1
        assertEquals("open again", limiter.runThrottled { "open again" })
    }

    @Test
    fun reportSuccess_resetsCounter() = runTest {
        val limiter = MusicKitCatalogLimiter()
        limiter.reportRateLimit()
        limiter.reportRateLimit()
        assertEquals(2, limiter.consecutive429sForTest)
        limiter.reportSuccess()
        assertEquals(0, limiter.consecutive429sForTest)
    }

    @Test
    fun reportSuccess_afterReset_threshold_takes3MoreToTrip() = runTest {
        val limiter = MusicKitCatalogLimiter()
        limiter.reportRateLimit()
        limiter.reportRateLimit()  // 2 of 3
        limiter.reportSuccess()    // resets to 0
        limiter.reportRateLimit()
        limiter.reportRateLimit()  // 2 of 3 again — still open
        assertNotNull(limiter.runThrottled { "still open" })
        limiter.reportRateLimit()  // 3 of 3 — now closed
        assertNull(limiter.runThrottled { "should skip" })
    }

    @Test
    fun looksLikeRateLimit_detectsKnownStrings() {
        val limiter = MusicKitCatalogLimiter()
        assertTrue(limiter.looksLikeRateLimit("MusicKit.MusicDataRequest.Error 1"))
        assertTrue(limiter.looksLikeRateLimit("musicdatarequest.error 1"))
        assertTrue(limiter.looksLikeRateLimit("HTTP 429 Too Many Requests"))
        assertTrue(limiter.looksLikeRateLimit("rate-limit exceeded"))
        assertTrue(limiter.looksLikeRateLimit("Rate Limit"))
        assertTrue(limiter.looksLikeRateLimit("Too Many Requests"))
    }

    @Test
    fun looksLikeRateLimit_returnsFalseForUnrelatedErrors() {
        val limiter = MusicKitCatalogLimiter()
        assertFalse(limiter.looksLikeRateLimit(null))
        assertFalse(limiter.looksLikeRateLimit(""))
        assertFalse(limiter.looksLikeRateLimit("No results found"))
        assertFalse(limiter.looksLikeRateLimit("Invalid storefront"))
        assertFalse(limiter.looksLikeRateLimit("Network error"))
    }
}
