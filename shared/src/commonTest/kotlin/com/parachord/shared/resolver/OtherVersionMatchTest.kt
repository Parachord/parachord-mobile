package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The exact-name and not-another-version tiers of [selectBestMatch], and
 * [isOtherVersion]. Cases ported from desktop's
 * `tests/resolver/bandcamp-version-variants.test.js` (parachord#1115 / #1122).
 */
class OtherVersionMatchTest {

    private data class R(val artist: String, val title: String)

    private fun pick(results: List<R>, artist: String, title: String): R? =
        selectBestMatch(results, title, artist, { it.title }, { it.artist })

    // ── isOtherVersion: desktop's table ──────────────────────────────────

    @Test fun liveCutIsAnotherVersion() =
        assertTrue(isOtherVersion("Cleopatra (Live from Wrigley Field)", "The Lumineers", "Cleopatra", "The Lumineers"))

    @Test fun coverArtistIsAnotherVersion() =
        assertTrue(isOtherVersion("Dear Rosemary", "Steven Gilpin (covering the Foo Fighters)", "Dear Rosemary", "Foo Fighters"))

    @Test fun coverByCreditIsAnotherVersion() =
        assertTrue(isOtherVersion("Lonestar", "Norah Jones, cover by Alex Morf", "Lonestar", "Norah Jones"))

    @Test fun anniversaryEditionIsNotAnotherVersion() =
        assertFalse(isOtherVersion("Boyfriend (10th Anniversary Edition)", "Best Coast", "Boyfriend", "Best Coast"))

    @Test fun aVersionWordInTheRequestedTitleIsNotAReason() =
        assertFalse(isOtherVersion("Live Forever", "Oasis", "Live Forever", "Oasis"))

    @Test fun accentedTitleIsNotAnotherVersion() =
        assertFalse(isOtherVersion("La Vie en rose", "Edith Piaf", "La Vie en rose", "Edith Piaf"))

    @Test fun aSecondVersionWordTheTargetLacksStillCounts() =
        assertTrue(isOtherVersion("Take Me Out (Live) [Remix]", "Franz Ferdinand", "Take Me Out (Live)", "Franz Ferdinand"))

    @Test fun wordsMatchWholeWordsOnly() {
        // "Delivery" contains "live" but not as a word; "Demons" isn't "demo".
        assertFalse(isOtherVersion("Special Delivery", "X", "Special Delivery", "X"))
        assertFalse(isOtherVersion("Demons", "X", "Demons", "X"))
    }

    // ── selectBestMatch name tiers ──────────────────────────────────────

    @Test fun prefersTheExactTrackOverALiveVersionRankedAboveIt() {
        val results = listOf(
            R("Lauren Steiner", "Angela - The Lumineers - Cleopatra"),
            R("The Lumineers", "Cleopatra (Live from Wrigley Field)"),
            R("The Lumineers", "Ophelia"),
            R("The Lumineers", "Cleopatra"),
        )
        assertEquals("Cleopatra", pick(results, "The Lumineers", "Cleopatra")?.title)
    }

    @Test fun prefersAContainmentMatchThatIsNotAnotherVersion() {
        val results = listOf(
            R("Radiohead", "Creep (Acoustic)"),
            R("Radiohead", "Creep - Remastered 2008"),
        )
        assertEquals("Creep - Remastered 2008", pick(results, "Radiohead", "Creep")?.title)
    }

    @Test fun stillResolvesWhenOnlyOtherVersionsExist() {
        // Tier 5 keeps desktop's behavior: a variant beats no match at all.
        val results = listOf(R("Sparta", "Lines In Sand (Acoustic)"))
        assertEquals("Lines In Sand (Acoustic)", pick(results, "Sparta", "Lines In Sand")?.title)
    }

    @Test fun keepsAVersionTheTargetNames() {
        val results = listOf(R("Franz Ferdinand", "Take Me Out"), R("Franz Ferdinand", "Take Me Out (Live)"))
        assertEquals("Take Me Out (Live)", pick(results, "Franz Ferdinand", "Take Me Out (Live)")?.title)
    }

    @Test fun nonLatinNamesPreferTheResultThatIsNotAnotherVersion() {
        val results = listOf(R("ХЛЕБ", "Яйца (live)"), R("ХЛЕБ", "Яйца"))
        assertEquals("Яйца", pick(results, "ХЛЕБ", "Яйца")?.title)
    }

    @Test fun identityTiersStillComeFirst() {
        data class I(val artist: String, val title: String, val isrc: String?)
        val results = listOf(I("A", "Song", null), I("A", "Song (Live)", "USRC17607839"))
        val best = selectBestMatch(
            results, "Song", "A", { it.title }, { it.artist },
            targetIsrc = "USRC17607839", isrcOf = { it.isrc },
        )
        assertEquals("Song (Live)", best?.title)
    }
}
