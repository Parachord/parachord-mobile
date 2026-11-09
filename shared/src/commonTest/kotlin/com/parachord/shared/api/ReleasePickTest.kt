package com.parachord.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Which release of an album to show (#560). Desktop's
 * `tests/renderer/release-pick.test.js` (parachord#1290), case for case: both
 * apps must open the same edition.
 */
class ReleasePickTest {

    private fun rel(id: String, media: List<Pair<String, Int>>, date: String?) = MbReleaseDetail(
        id = id,
        title = id,
        date = date,
        media = media.map { (format, count) -> MbMedia(format = format, trackCount = count) },
    )

    @Test
    fun `The Faith Tour — the 18-track digital release and not a 9-track CD listed first`() {
        val picked = pickRelease(
            listOf(
                rel("cd-a", listOf("CD" to 9), "2026-11-06"),
                rel("digital", listOf("Digital Media" to 18), "2026-11-06"),
                rel("cd-b", listOf("CD" to 9), "2026-11-06"),
            ),
        )
        assertEquals("digital", picked?.id)
    }

    @Test
    fun `Abbey Road — the standard 17 tracks and not a 40-track super deluxe`() {
        val picked = pickRelease(
            listOf(
                rel("vinyl", listOf("12\" Vinyl" to 17), "1969-09-26"),
                rel("deluxe", listOf("Digital Media" to 17, "Digital Media" to 17, "Digital Media" to 6), "2019-09-27"),
                rel("digital-2009", listOf("Digital Media" to 17), "2009-09-09"),
                rel("digital-2015", listOf("Digital Media" to 17), "2015-12-24"),
                rel("cd", listOf("CD" to 17), "1987-08-24"),
            ),
        )
        assertEquals("digital-2009", picked?.id)
    }

    @Test
    fun `no digital release — the most common track count and the earliest first`() {
        val picked = pickRelease(
            listOf(
                rel("expanded", listOf("CD" to 24), "2005-01-01"),
                rel("cd-late", listOf("CD" to 12), "1999-01-01"),
                rel("vinyl", listOf("12\" Vinyl" to 12), "1990-01-01"),
            ),
        )
        assertEquals("vinyl", picked?.id)
    }

    @Test
    fun `a tie between counts goes to the count with the earliest release`() {
        val picked = pickRelease(
            listOf(
                rel("deluxe", listOf("Digital Media" to 14), "2012-05-01"),
                rel("standard", listOf("Digital Media" to 12), "2010-05-01"),
            ),
        )
        assertEquals("standard", picked?.id)
    }

    @Test
    fun `a release with no date loses a tie to one with a date`() {
        val picked = pickRelease(
            listOf(
                rel("undated", listOf("CD" to 10), null),
                rel("dated", listOf("CD" to 10), "2001"),
            ),
        )
        assertEquals("dated", picked?.id)
    }

    @Test
    fun `a mixed CD and digital release is not digital-only`() {
        val picked = pickRelease(
            listOf(
                rel("mixed", listOf("CD" to 10, "Digital Media" to 10), "2000"),
                rel("cd", listOf("CD" to 10), "2001"),
            ),
        )
        assertEquals("mixed", picked?.id)
    }

    @Test
    fun `a single release or none`() {
        val only = rel("only", listOf("CD" to 9), "2020")
        assertSame(only, pickRelease(listOf(only)))
        assertNull(pickRelease(emptyList()))
    }

    @Test
    fun `releases with no media information keep MusicBrainz order`() {
        val picked = pickRelease(listOf(MbReleaseDetail(id = "a", title = "a"), MbReleaseDetail(id = "b", title = "b")))
        assertEquals("a", picked?.id)
    }

    @Test
    fun `releaseTrackCount counts tracks when track-count is missing`() {
        val tracks = List(3) { MbTrack(id = "t$it", title = "t$it") }
        val release = MbReleaseDetail(
            id = "r",
            title = "r",
            media = listOf(MbMedia(tracks = tracks), MbMedia(trackCount = 2)),
        )
        assertEquals(5, releaseTrackCount(release))
        assertEquals(0, releaseTrackCount(MbReleaseDetail(id = "e", title = "e")))
    }
}
