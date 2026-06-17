package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression guard for the ISRC capture/walk contract (#217, desktop parachord#894).
 * Byte-equivalence with desktop's `validateIsrc` / `pickTrackIsrc` is the
 * requirement — divergence produces different MBID-fallback hit rates per client.
 */
class IsrcTest {

    private fun source(resolver: String, isrc: String?, noMatch: Boolean = false) =
        ResolvedSource(url = "u", sourceType = resolver, resolver = resolver, isrc = isrc, noMatch = noMatch)

    // ── validateIsrc ────────────────────────────────────────────────

    @Test fun validateIsrc_acceptsCanonical() {
        assertEquals("USRC12500400", validateIsrc("USRC12500400"))
    }

    @Test fun validateIsrc_trimsAndUppercases() {
        assertEquals("USSM12203074", validateIsrc("  ussm12203074 "))
    }

    @Test fun validateIsrc_rejectsMalformed() {
        assertNull(validateIsrc(null))
        assertNull(validateIsrc(""))
        assertNull(validateIsrc("not-an-isrc"))
        assertNull(validateIsrc("US-SM1-22-03074"))      // hyphenated form not accepted
        assertNull(validateIsrc("USSM1220307"))          // too short (6 digits)
        assertNull(validateIsrc("USSM122030745"))        // too long (8 digits)
        assertNull(validateIsrc("1USM12203074"))         // country must be 2 letters
    }

    // ── pickTrackIsrc (precedence + validation + noMatch skip) ───────

    @Test fun pickTrackIsrc_prefersTopLevel() {
        val picked = pickTrackIsrc(
            "USRC12500400",
            listOf(source("spotify", "GBUM71029604")),
        )
        assertEquals("USRC12500400", picked)
    }

    @Test fun pickTrackIsrc_walksSourcesWhenNoTopLevel() {
        val picked = pickTrackIsrc(
            null,
            listOf(source("spotify", null), source("applemusic", "GBUM71029604")),
        )
        assertEquals("GBUM71029604", picked)
    }

    @Test fun pickTrackIsrc_skipsNoMatchSources() {
        val picked = pickTrackIsrc(
            null,
            listOf(source("spotify", "GBUM71029604", noMatch = true), source("applemusic", "USRC12500400")),
        )
        assertEquals("USRC12500400", picked)
    }

    @Test fun pickTrackIsrc_normalizesAndDropsMalformed() {
        // First source's value is malformed → skipped; second normalizes.
        val picked = pickTrackIsrc(
            "garbage",
            listOf(source("spotify", "junk-isrc"), source("applemusic", " usrc12500400 ")),
        )
        assertEquals("USRC12500400", picked)
    }

    @Test fun pickTrackIsrc_nullWhenNoneValid() {
        assertNull(pickTrackIsrc(null, listOf(source("spotify", null), source("applemusic", "bad"))))
    }
}
