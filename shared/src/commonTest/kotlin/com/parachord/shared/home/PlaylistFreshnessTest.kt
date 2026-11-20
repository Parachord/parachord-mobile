package com.parachord.shared.home

import com.parachord.shared.recommendations.trackSlug
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Port of desktop's `tests/main-process/playlist-freshness.test.js` (the
 * `trackKey`, `countAdded` and changed / unchanged / reorder / duplicate
 * decisions). Desktop's split-store and renderer cases have no mobile
 * counterpart: the stamp is applied in [com.parachord.shared.db.dao.PlaylistTrackDao].
 */
class PlaylistFreshnessTest {

    private val now = 1_700_000_000_000L + 86_400_000L
    private val t0 = 1_700_000_000_000L

    private fun keys(vararg pairs: Pair<String, String>) = pairs.map { (a, t) -> PlaylistFreshness.trackKey(a, t) }

    private val a = PlaylistFreshness.trackKey("A", "One")
    private val b = PlaylistFreshness.trackKey("B", "Two")
    private val c = PlaylistFreshness.trackKey("C", "Three")

    // ── track identity ──

    @Test
    fun `the key folds accents and keeps the dash between artist and title`() {
        // Desktop's slugPart drops spaces, so "Sigur Rós" folds to "sigurros".
        assertEquals("sigurros-hoppipolla", PlaylistFreshness.trackKey("Sigur Rós", "Hoppípolla"))
    }

    @Test
    fun `the key is desktop's two-argument trackSlug`() {
        assertEquals(trackSlug("The Flaming Lips", "Do You Realize??"), PlaylistFreshness.trackKey("The Flaming Lips", "Do You Realize??"))
        assertEquals(trackSlug("Король и Шут", "Лесник"), PlaylistFreshness.trackKey("Король и Шут", "Лесник"))
    }

    @Test
    fun `the key folds case and accents and punctuation`() {
        assertEquals(PlaylistFreshness.trackKey("BEYONCE", "halo"), PlaylistFreshness.trackKey("Beyoncé", "Halo!"))
    }

    @Test
    fun `keys follow the content so ids that are positions still see a swap`() {
        val before = listOf(
            PlaylistFreshness.trackKey("A", "One", "listenbrainz-track-m-0"),
            PlaylistFreshness.trackKey("B", "Two", "listenbrainz-track-m-1"),
        )
        val after = listOf(
            PlaylistFreshness.trackKey("C", "Three", "listenbrainz-track-m-0"),
            PlaylistFreshness.trackKey("D", "Four", "listenbrainz-track-m-1"),
        )
        assertNotEquals(before, after)
    }

    @Test
    fun `a track with no artist or title falls back to its id`() {
        assertEquals("id:x", PlaylistFreshness.trackKey(null, null, "x"))
        assertEquals("id:spotify:track:1", PlaylistFreshness.trackKey("", "", "spotify:track:1"))
    }

    @Test
    fun `a track with nothing at all has an empty key`() {
        assertEquals("", PlaylistFreshness.trackKey(null, null, null))
        assertEquals("", PlaylistFreshness.trackKey(null, null, ""))
    }

    @Test
    fun `a title alone still keys by name and not by id`() {
        assertEquals("-one", PlaylistFreshness.trackKey(null, "One", "x"))
    }

    // ── countAdded ──

    @Test
    fun `countAdded counts the tracks next has that prev lacks — copies included`() {
        assertEquals(0, PlaylistFreshness.countAdded(listOf(a, b), listOf(b, a)))
        assertEquals(2, PlaylistFreshness.countAdded(listOf(a), listOf(a, b, c)))
        assertEquals(1, PlaylistFreshness.countAdded(listOf(a), listOf(a, a)))
        assertEquals(0, PlaylistFreshness.countAdded(listOf(a, b, c), listOf(a)))
        assertEquals(3, PlaylistFreshness.countAdded(emptyList(), listOf(a, b, c)))
    }

    // ── stamp ──

    @Test
    fun `the same tracks in the same order is not a change`() {
        assertNull(PlaylistFreshness.stamp(listOf(a, b), listOf(a, b), now))
        assertNull(PlaylistFreshness.stamp(emptyList(), emptyList(), now))
    }

    @Test
    fun `the same tracks reordered is a change that added nothing`() {
        assertEquals(PlaylistFreshness.Stamp(now, 0), PlaylistFreshness.stamp(listOf(a, b), listOf(b, a), now))
        assertEquals(PlaylistFreshness.Stamp(now, 0), PlaylistFreshness.stamp(listOf(a, b, c), listOf(c, a, b), now))
    }

    @Test
    fun `two tracks added gives lastChangeAdded 2`() {
        val d = PlaylistFreshness.trackKey("D", "Four")
        val e = PlaylistFreshness.trackKey("E", "Five")
        assertEquals(PlaylistFreshness.Stamp(now, 2), PlaylistFreshness.stamp(listOf(a, b, c), listOf(a, b, c, d, e), now))
    }

    @Test
    fun `a removal stamps with nothing added and a swap counts the new ones`() {
        assertEquals(PlaylistFreshness.Stamp(now, 0), PlaylistFreshness.stamp(listOf(a, b, c), listOf(b, c), now))
        val z = PlaylistFreshness.trackKey("Z", "New")
        assertEquals(PlaylistFreshness.Stamp(now, 1), PlaylistFreshness.stamp(listOf(a, b, c), listOf(a, z), now))
    }

    @Test
    fun `a second copy of a track already there counts as added`() {
        assertEquals(PlaylistFreshness.Stamp(now, 1), PlaylistFreshness.stamp(listOf(a), listOf(a, a), now))
    }

    @Test
    fun `no earlier tracks counts every track as added`() {
        assertEquals(PlaylistFreshness.Stamp(now, 3), PlaylistFreshness.stamp(null, listOf(a, b, c), now))
        assertEquals(PlaylistFreshness.Stamp(now, 4), PlaylistFreshness.stamp(null, keys("W" to "1", "X" to "2", "Y" to "3", "Z" to "4"), now))
    }

    @Test
    fun `metadata a track gains later is not a different track`() {
        // Keys are artist and title only: album, art and ids don't enter.
        assertEquals(PlaylistFreshness.trackKey("A", "One", "x"), PlaylistFreshness.trackKey("A", "One", "y"))
    }
}
