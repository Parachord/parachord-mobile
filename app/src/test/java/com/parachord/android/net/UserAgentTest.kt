package com.parachord.android.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the MetaBrainz User-Agent contract (#375).
 *
 * MetaBrainz blocks poorly-identified clients. The failure mode this pins is
 * subtle: a header that LOOKS compliant but names a domain that doesn't
 * resolve is, for their purposes, no better than no contact at all — and it
 * shipped that way.
 */
class UserAgentTest {

    private val ua = parachordUserAgent("0.9.7")

    @Test
    fun `matches MusicBrainz's App-version ( contact ) shape`() {
        assertTrue("was: $ua", Regex("""^Parachord/\S+ \( .+ \)$""").matches(ua))
    }

    @Test
    fun `carries the app version`() {
        assertTrue(ua.startsWith("Parachord/0.9.7 "))
    }

    @Test
    fun `contact is an https URL`() {
        assertTrue("was: $ua", Regex("""https://[^\s)]+""").containsMatchIn(ua))
    }

    @Test
    fun `never reintroduces the dead parachord app domain`() {
        // The specific regression: parachord.app does not resolve at all.
        assertFalse(
            "parachord.app does not resolve — use $PARACHORD_CONTACT_URL",
            ua.contains("parachord.app"),
        )
    }

    @Test
    fun `is not a default library UA`() {
        assertFalse(ua.lowercase().startsWith("okhttp"))
        assertFalse(ua.lowercase().startsWith("ktor"))
    }
}
