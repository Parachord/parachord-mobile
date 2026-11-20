package com.parachord.shared.home

import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.repository.CriticsPickAlbum
import com.parachord.shared.repository.FreshDrop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeLowerTest {
    @Test
    fun `release type label is EP or the type capitalised`() {
        assertEquals("EP", releaseTypeLabel("ep"))
        assertEquals("EP", releaseTypeLabel("EP"))
        assertEquals("Album", releaseTypeLabel("album"))
        assertEquals("Single", releaseTypeLabel("Single"))
        assertNull(releaseTypeLabel(""))
        assertNull(releaseTypeLabel(null))
    }

    @Test
    fun `fresh cards carry the release type and the release group`() {
        val cards = freshHalfCards(
            listOf(
                FreshDrop(mbid = "rg1", title = "A", artist = "X", releaseType = "ep", albumArt = "u"),
                FreshDrop(title = "B", artist = "Y", releaseType = "album"),
                FreshDrop(title = "C", artist = "Z"),
                FreshDrop(title = "D", artist = "W"),
            ),
        )
        assertEquals(HOME_HALF_ROW_CARDS, cards.size)
        assertEquals("EP", cards[0].releaseType)
        assertEquals("ep", cards[0].releaseTypeKey)
        assertEquals("rg1", cards[0].releaseGroupMbid)
        assertEquals("u", cards[0].imageUrl)
        assertNull(cards[0].review)
        assertEquals("Album", cards[1].releaseType)
        assertEquals(cards.size, cards.map { it.key }.toSet().size)
    }

    @Test
    fun `critics cards carry the review only when there is one`() {
        val cards = criticalHalfCards(
            listOf(
                CriticsPickAlbum(id = "1", title = "A", artist = "X", blurb = "  Glorious.  "),
                CriticsPickAlbum(id = "2", title = "B", artist = "Y", blurb = " "),
            ),
        )
        assertEquals("Glorious.", cards[0].review)
        assertNull(cards[1].review)
        assertNull(cards[0].releaseType)
    }

    @Test
    fun `card keys stay unique with repeated ids`() {
        val cards = chartHalfCards(listOf(ChartAlbum("x", "A", "B"), ChartAlbum("x", "A", "B")))
        assertEquals(2, cards.map { it.key }.toSet().size)
    }

    @Test
    fun `half body shows cards then error then skeleton`() {
        assertEquals(HomeHalfBody.CARDS, homeHalfBody(2, loading = true, failed = true))
        assertEquals(HomeHalfBody.ERROR, homeHalfBody(0, loading = false, failed = true))
        assertEquals(HomeHalfBody.LOADING, homeHalfBody(0, loading = true, failed = true))
        assertEquals(HomeHalfBody.LOADING, homeHalfBody(0, loading = false, failed = false))
    }

    @Test
    fun `ask field starts on the example muted`() {
        val f = AskField.seeded("Songs that sound like A")
        assertTrue(f.muted)
        assertEquals("Songs that sound like A", f.prompt)
    }

    @Test
    fun `a new example replaces only an untouched field`() {
        val f = AskField.seeded("Make me a playlist for right now")
        assertEquals("A late night mix with B", f.withExample("A late night mix with B").text)
        val edited = f.edited("my own words")
        assertFalse(edited.muted)
        val after = edited.withExample("A late night mix with B")
        assertEquals("my own words", after.text)
        assertEquals("A late night mix with B", after.example)
    }

    @Test
    fun `blank sends nothing and long text is capped`() {
        assertNull(AskField.seeded("").edited("   ").prompt)
        val long = AskField.seeded("").edited("x".repeat(600))
        assertEquals(500, long.text.length)
        assertEquals(500, long.prompt?.length)
        assertEquals("hi", AskField.seeded("").edited("  hi  ").prompt)
    }

    @Test
    fun `an edit that matches the example is no longer muted`() {
        val f = AskField.seeded("abc").edited("abc")
        assertFalse(f.muted)
    }

    // ── half cards' looked-up covers ─────────────────────────────────

    private fun card(title: String, artist: String, image: String? = null) =
        HomeHalfCard(key = "0:$title", title = title, artist = artist, imageUrl = image)

    @Test
    fun `a coverless half card takes the cover found for its album`() {
        val bare = card("Mount Zero", "Swapmeet")
        val own = card("OK Computer", "Radiohead", "own")
        val found = mapOf(halfCardArtKey(card(" mount zero", "SWAPMEET ")) to "found", halfCardArtKey(own) to "other")
        val out = withHalfCardArt(listOf(bare, own), found)
        assertEquals(listOf("found", "own"), out.map { it.imageUrl })
        val nothing = listOf(bare)
        assertTrue(withHalfCardArt(nothing, emptyMap()) === nothing)
    }

    @Test
    fun `only a coverless card with both names asks for a cover`() {
        assertTrue(halfCardNeedsArt(card("A", "B")))
        assertTrue(halfCardNeedsArt(card("A", "B", "")))
        assertFalse(halfCardNeedsArt(card("A", "B", "x")))
        assertFalse(halfCardNeedsArt(card(" ", "B")))
        assertFalse(halfCardNeedsArt(card("A", "")))
    }
}
