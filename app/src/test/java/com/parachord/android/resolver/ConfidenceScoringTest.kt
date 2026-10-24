package com.parachord.android.resolver

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the confidence scoring functions in ResolverManager.kt.
 * These are pure functions with no dependencies — the highest-value unit tests.
 */
class ConfidenceScoringTest {

    // -- normalizeStr --

    @Test
    fun `normalizeStr lowercases and strips non-alphanumeric`() {
        assertEquals("helloworld", normalizeStr("Hello, World!"))
    }

    @Test
    fun `normalizeStr removes parenthetical suffixes`() {
        assertEquals("comeasyouarefeatdavegrohlremaster", normalizeStr("Come As You Are (feat. Dave Grohl) [Remaster]"))
    }

    @Test
    fun `normalizeStr handles null`() {
        assertEquals("", normalizeStr(null))
    }

    @Test
    fun `normalizeStr handles empty string`() {
        assertEquals("", normalizeStr(""))
    }

    @Test
    fun `normalizeStr folds unicode accents to the base letter`() {
        // #404: accented letters fold (NFKD + strip combining marks) instead of
        // being deleted. This test used to assert "bjrk", which pinned the bug.
        // Full desktop-parity vectors live in shared NormalizeStrTest.
        assertEquals("bjork", normalizeStr("Björk"))
    }

    @Test
    fun `normalizeStr handles numbers`() {
        assertEquals("1999", normalizeStr("1999"))
    }

    // -- scoreConfidence --

    @Test
    fun `scoreConfidence returns 0_95 when both title and artist match`() {
        assertEquals(0.95, scoreConfidence("Creep", "Radiohead", "Creep", "Radiohead"), 0.001)
    }

    @Test
    fun `scoreConfidence returns 0_95 for case-insensitive match`() {
        assertEquals(0.95, scoreConfidence("creep", "radiohead", "CREEP", "RADIOHEAD"), 0.001)
    }

    @Test
    fun `scoreConfidence returns 0_50 for title match only (artist mismatch is wrong song)`() {
        // Mirrors desktop's validateResolvedTrack — both axes must match.
        // A title-only match (e.g., Apple Music returning a different artist's
        // "Mariana") should be treated as noMatch so the 0.60 floor filters it
        // out before priority sort.
        assertEquals(0.50, scoreConfidence("Creep", "Radiohead", "Creep", "Stone Temple Pilots"), 0.001)
    }

    @Test
    fun `scoreConfidence returns 0_50 for artist match only (title mismatch is wrong song)`() {
        // Same artist, different track — also a wrong-song candidate.
        assertEquals(0.50, scoreConfidence("Creep", "Radiohead", "Karma Police", "Radiohead"), 0.001)
    }

    @Test
    fun `scoreConfidence returns 0_50 when neither match`() {
        assertEquals(0.50, scoreConfidence("Creep", "Radiohead", "Yesterday", "Beatles"), 0.001)
    }

    @Test
    fun `scoreConfidence handles containment match - target contains matched`() {
        // "Come As You Are (Remastered)" contains "Come As You Are"
        assertEquals(0.95, scoreConfidence(
            "Come As You Are (Remastered)", "Nirvana",
            "Come As You Are", "Nirvana"
        ), 0.001)
    }

    @Test
    fun `scoreConfidence handles containment match - matched contains target`() {
        assertEquals(0.95, scoreConfidence(
            "Come As You Are", "Nirvana",
            "Come As You Are (Remastered)", "Nirvana"
        ), 0.001)
    }

    @Test
    fun `scoreConfidence handles null matched values`() {
        assertEquals(0.50, scoreConfidence("Creep", "Radiohead", null, null), 0.001)
    }

    @Test
    fun `scoreConfidence handles special characters in titles`() {
        assertEquals(0.95, scoreConfidence(
            "Don't Look Back in Anger", "Oasis",
            "Don't Look Back In Anger", "Oasis"
        ), 0.001)
    }

    @Test
    fun `scoreConfidence handles featured artist in name`() {
        // "Drake" is contained in "Drake feat. Rihanna"
        assertEquals(0.95, scoreConfidence(
            "Take Care", "Drake",
            "Take Care", "Drake feat. Rihanna"
        ), 0.001)
    }
}
