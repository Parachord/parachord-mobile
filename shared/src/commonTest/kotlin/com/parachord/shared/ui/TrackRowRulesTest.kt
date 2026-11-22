package com.parachord.shared.ui

import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.ResolverBadge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Desktop's `tests/renderer/track-duration.test.js` vectors, in ms, plus the row state and icon column. */
class TrackRowRulesTest {
    private val order = listOf("spotify", "soundcloud", "bandcamp")

    private fun src(resolver: String, durationS: Long?, confidence: Double? = 1.0, noMatch: Boolean = false) =
        ResolvedSource(
            url = "", sourceType = "stream", resolver = resolver, confidence = confidence,
            noMatch = noMatch, matchedDurationMs = durationS?.times(1000),
        )

    @Test
    fun `the track's own duration wins`() {
        assertEquals(200_000, TrackRowRules.durationMs(200_000, listOf(src("spotify", 199)), order))
    }

    @Test
    fun `no duration on the track — a resolved source knows it`() {
        assertEquals(245_000, TrackRowRules.durationMs(null, listOf(src("spotify", 245)), order))
    }

    @Test
    fun `a zero or negative duration on the track falls through to the sources`() {
        for (d in listOf(0L, -1L, null)) {
            assertEquals(180_000, TrackRowRules.durationMs(d, listOf(src("soundcloud", 180)), order))
        }
    }

    @Test
    fun `prefers the highest-confidence source with a duration`() {
        val sources = listOf(src("spotify", 210, 0.95), src("bandcamp", 212, 1.0))
        assertEquals(212_000, TrackRowRules.durationMs(null, sources, order))
    }

    @Test
    fun `ties go to the source order — a resolver not in the order goes last`() {
        val sources = listOf(src("other", 1), src("bandcamp", 3), src("soundcloud", 2))
        assertEquals(2_000, TrackRowRules.durationMs(null, sources, order))
        assertEquals(1_000, TrackRowRules.durationMs(null, sources, emptyList())) // no order: first among equals
    }

    @Test
    fun `skips sources without a duration and no-match sentinels`() {
        val sources = listOf(src("spotify", 999, noMatch = true), src("soundcloud", 0), src("bandcamp", 230, 0.95))
        assertEquals(230_000, TrackRowRules.durationMs(null, sources, order))
    }

    @Test
    fun `null when nothing knows the length`() {
        assertNull(TrackRowRules.durationMs(null, null, order))
        assertNull(TrackRowRules.durationMs(null, emptyList(), order))
        assertNull(TrackRowRules.durationMs(null, listOf(src("spotify", null)), order))
    }

    @Test
    fun `m ss for a known length`() {
        assertEquals("4:05", TrackRowRules.formatDuration(245_000))
        assertEquals("0:59", TrackRowRules.formatDuration(59_900))
        assertEquals("60:00", TrackRowRules.formatDuration(3_600_000))
    }

    @Test
    fun `blank — never 0 00 — when the length is unknown`() {
        for (v in listOf(null, 0L, -1L)) assertEquals("", TrackRowRules.formatDuration(v))
        assertEquals("", TrackRowRules.durationLabel(null, null, order))
    }

    @Test
    fun `labels an imported track with its source length`() {
        assertEquals("5:01", TrackRowRules.durationLabel(null, listOf(src("soundcloud", 301, 0.95)), order))
    }

    @Test
    fun `row state — resolving until anything comes back`() {
        assertEquals(TrackRowState.RESOLVING, TrackRowRules.state(null, emptyList()))
    }

    @Test
    fun `row state — unplayable once the resolve came back with no icon`() {
        assertEquals(TrackRowState.UNPLAYABLE, TrackRowRules.state(emptyList(), emptyList()))
        assertEquals(TrackRowState.UNPLAYABLE, TrackRowRules.state(listOf(src("spotify", 1, noMatch = true)), emptyList()))
        assertEquals(true, TrackRowState.UNPLAYABLE.dimmed)
    }

    @Test
    fun `row state — playable with an icon`() {
        assertEquals(TrackRowState.PLAYABLE, TrackRowRules.state(null, listOf(ResolverBadge("spotify", 1.0))))
        assertEquals(TrackRowState.PLAYABLE, trackRowState(listOf(src("spotify", 1)), order))
        assertEquals(TrackRowState.UNPLAYABLE, trackRowState(listOf(src("spotify", 1)), order, disabled = listOf("spotify")))
    }

    @Test
    fun `the icon column fits one icon per service — at least two — at most 140`() {
        assertEquals(44, TrackRowRules.sourceColumnWidth(0))
        assertEquals(44, TrackRowRules.sourceColumnWidth(2))
        assertEquals(92, TrackRowRules.sourceColumnWidth(4))
        assertEquals(140, TrackRowRules.sourceColumnWidth(6))
        assertEquals(140, TrackRowRules.sourceColumnWidth(12))
    }
}
