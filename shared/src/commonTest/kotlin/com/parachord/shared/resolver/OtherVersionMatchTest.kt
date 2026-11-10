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

    // ── Medleys: desktop's vectors (parachord#1306 / #1307) ─────────────
    // A title joining several songs with a slash, or naming a medley, is
    // another version: Bandcamp's "Her Majesty" was a nine-song medley (#582).

    @Test fun aSlashJoinedMedleyIsAnotherVersion() = assertTrue(
        isOtherVersion("You Never Give Me Your Money/Sun King/The End/Her Majesty", "The Beatles", "Her Majesty", "The Beatles"),
    )

    @Test fun aMedleyByNameIsAnotherVersion() =
        assertTrue(isOtherVersion("Abbey Road Medley", "The Beatles", "Her Majesty", "The Beatles"))

    @Test fun anArtistNamedMedleyIsNotAnotherVersion() =
        assertFalse(isOtherVersion("Brown Eyed Woman", "Bill Medley", "Brown Eyed Woman", "Bill Medley"))

    @Test fun aSlashInTheRequestedArtistIsNotAMedley() =
        assertFalse(isOtherVersion("AC/DC - Back in Black", "AC/DC", "Back in Black", "AC/DC"))

    @Test fun aSlashBetweenDigitsIsNotAMedley() =
        assertFalse(isOtherVersion("24/7", "Ghostface Killah", "24/7", "Ghostface Killah"))

    @Test fun withIsNotAMedley() =
        assertFalse(isOtherVersion("Paranoid (w/ B.o.B)", "Ty Dolla \$ign", "Paranoid", "Ty Dolla \$ign"))

    @Test fun aRequestedSlashIsNotAMedley() =
        assertFalse(isOtherVersion("Upside Down / Medley", "Diana Ross", "Upside Down / Medley", "Diana Ross"))

    @Test fun backedWithCountsAsTwoSongs() =
        assertTrue(isOtherVersion("Penny Lane b/w Strawberry Fields Forever", "The Beatles", "Penny Lane", "The Beatles"))

    @Test fun aSoleMedleyIsSkippedForTheSongWhenBothAreThere() {
        val results = listOf(
            R("The Beatles", "You Never Give Me Your Money/Sun King/Mean Mr. Mustard/Polythene Pam/She Came in Through the Bathroom Window/Golden Slumbers/Carry That Weight/The End/Her Majesty"),
            R("The Beatles", "Her Majesty - 2019 Mix"),
        )
        assertEquals("Her Majesty - 2019 Mix", pick(results, "The Beatles", "Her Majesty")?.title)
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

    // ── Remaster labels don't make a title less exact ────────────────────
    // Spotify labels remastered studio tracks ("- 2016 Remaster"), while a
    // live album or an outtakes set often carries the bare title. Comparing
    // raw titles, the exact-name tier picked those editions over the studio
    // recording (Spotify ids seen on a Pixel 9a, Nov 2026).

    @Test fun aRemasteredStudioTrackIsAnExactMatchForTheBareTitle() {
        val results = listOf(
            R("Ramones", "Judy Is a Punk - 2016 Remaster"), // 0vGCxnTwYZFtOAW3oz28kd, the studio recording
            R("Ramones", "Judy Is a Punk"),                 // 2OVGtm7PQ4uu7YN5BKLzEk, live on "It's Alive"
        )
        assertEquals("Judy Is a Punk - 2016 Remaster", pick(results, "Ramones", "Judy Is a Punk")?.title)
    }

    @Test fun aRemasterBeatsAnOuttakeRankedAboveIt() {
        val results = listOf(
            R("The Beatles", "Her Majesty - Takes 1-3"),      // 52hm7o5MPKymBUmSJadwve, 2019 Super Deluxe
            R("The Beatles", "Her Majesty - Remastered 2009"),
        )
        assertEquals("Her Majesty - Remastered 2009", pick(results, "The Beatles", "Her Majesty")?.title)
    }

    @Test fun remasterLabelsInTheirUsualForms() {
        for (title in listOf(
            "Creep - Remastered 2008", "Creep - Remastered", "Creep - 2016 Remaster", "Creep (Remastered)",
            "Creep (2011 Remaster)", "Creep [Remastered 2009]", "Creep - Remastered Version", "Creep - 2003 Digital Remaster",
        )) {
            // The competitor carries no version word, so only the exact tier tells them apart.
            assertEquals(title, pick(listOf(R("Radiohead", "Creep - Session Takes"), R("Radiohead", title)), "Radiohead", "Creep")?.title, title)
        }
    }

    @Test fun aRequestedRemasterStillMatchesItself() {
        val results = listOf(R("Radiohead", "Creep"), R("Radiohead", "Creep - Remastered 2008"))
        assertEquals("Creep - Remastered 2008", pick(results, "Radiohead", "Creep - Remastered 2008")?.title)
    }

    @Test fun onlyATrailingRemasterLabelIsIgnored() {
        // "Remaster" inside the title is part of the name, not a label.
        val results = listOf(R("X", "Remaster Me Baby"), R("X", "Remaster"))
        assertEquals("Remaster", pick(results, "X", "Remaster")?.title)
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
