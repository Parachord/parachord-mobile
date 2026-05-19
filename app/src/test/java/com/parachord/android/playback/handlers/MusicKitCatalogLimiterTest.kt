package com.parachord.android.playback.handlers

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MusicKitCatalogLimiterTest {

    @Test
    fun runThrottled_returnsBlockResult_whenNoCooldown() = runTest {
        val limiter = MusicKitCatalogLimiter()
        val result = limiter.runThrottled { 42 }
        assertEquals(42, result)
    }

    @Test
    fun runThrottled_skipsBlock_whenCooldownActive() = runTest {
        val limiter = MusicKitCatalogLimiter()
        // Trip the breaker
        repeat(MusicKitCatalogLimiter.CONSECUTIVE_429_THRESHOLD) { limiter.reportRateLimit() }
        var ran = false
        val result = limiter.runThrottled {
            ran = true
            "should not run"
        }
        assertNull(result)
        assertFalse("block must not execute while cooldown is open", ran)
    }

    @Test
    fun reportRateLimit_underThreshold_doesNotOpenCooldown() = runTest {
        val limiter = MusicKitCatalogLimiter()
        // 2 of 3 — still under
        limiter.reportRateLimit()
        limiter.reportRateLimit()
        var ran = false
        val result = limiter.runThrottled {
            ran = true
            "ok"
        }
        assertEquals("ok", result)
        assertTrue(ran)
    }

    @Test
    fun reportRateLimit_atThreshold_opensCooldown() = runTest {
        val limiter = MusicKitCatalogLimiter()
        repeat(MusicKitCatalogLimiter.CONSECUTIVE_429_THRESHOLD) { limiter.reportRateLimit() }
        assertTrue(
            "cooldownUntil must be in the future",
            limiter.cooldownUntilMsForTest > System.currentTimeMillis(),
        )
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
        limiter.reportRateLimit()  // 2 of 3 again — should still be open
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
