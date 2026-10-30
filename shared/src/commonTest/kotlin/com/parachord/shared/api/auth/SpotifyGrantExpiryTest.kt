package com.parachord.shared.api.auth

import com.parachord.shared.api.auth.SpotifyGrantExpiry.LIFETIME_MS
import com.parachord.shared.api.auth.SpotifyGrantExpiry.WARNING_WINDOW_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyGrantExpiryTest {

    private val day = 24L * 60 * 60 * 1000
    private val authorizedAt = 1_780_000_000_000L
    private val windowStart = authorizedAt + LIFETIME_MS - WARNING_WINDOW_MS

    @Test
    fun noWarningWithoutARecordedAuthorization() {
        assertFalse(SpotifyGrantExpiry.shouldWarn(0, 0, windowStart + day))
    }

    @Test
    fun noWarningBeforeTheLastSevenDays() {
        assertFalse(SpotifyGrantExpiry.shouldWarn(authorizedAt, 0, windowStart - 1))
        assertFalse(SpotifyGrantExpiry.shouldWarn(authorizedAt, 0, authorizedAt + 30 * day))
    }

    @Test
    fun warnsInTheLastSevenDays() {
        assertTrue(SpotifyGrantExpiry.shouldWarn(authorizedAt, 0, windowStart))
        assertTrue(SpotifyGrantExpiry.shouldWarn(authorizedAt, 0, authorizedAt + LIFETIME_MS - 1))
    }

    @Test
    fun aDismissedWarningComesBackAfterADay() {
        val now = windowStart + 2 * day
        assertFalse(SpotifyGrantExpiry.shouldWarn(authorizedAt, now - day + 1, now))
        assertTrue(SpotifyGrantExpiry.shouldWarn(authorizedAt, now - day, now))
    }

    @Test
    fun aNewAuthorizationResetsTheClock() {
        val now = windowStart + day
        assertTrue(SpotifyGrantExpiry.shouldWarn(authorizedAt, 0, now))
        assertFalse(SpotifyGrantExpiry.shouldWarn(now, 0, now))
    }

    @Test
    fun daysLeftRoundsUpAndStopsAtZero() {
        assertEquals(7, SpotifyGrantExpiry.daysLeft(authorizedAt, windowStart))
        assertEquals(1, SpotifyGrantExpiry.daysLeft(authorizedAt, authorizedAt + LIFETIME_MS - 1))
        assertEquals(0, SpotifyGrantExpiry.daysLeft(authorizedAt, authorizedAt + LIFETIME_MS))
        assertEquals(0, SpotifyGrantExpiry.daysLeft(authorizedAt, authorizedAt + LIFETIME_MS + 5 * day))
    }

    @Test
    fun messageNamesTheDays() {
        assertEquals("Your Spotify sign-in expires in 7 days. Reconnect now to avoid interruption.", SpotifyGrantExpiry.message(7))
        assertEquals("Your Spotify sign-in expires tomorrow. Reconnect now to avoid interruption.", SpotifyGrantExpiry.message(1))
        assertEquals("Your Spotify sign-in expires today. Reconnect now to avoid interruption.", SpotifyGrantExpiry.message(0))
    }
}
