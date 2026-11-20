package com.parachord.shared.repository

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fresh Drops keeps three months back to 14 days ahead (#687, desktop parachord#1421). */
class FreshDropsWindowTest {
    private val today = LocalDate(2026, 11, 15)
    private fun drop(date: String?) = FreshDrop(title = "T $date", artist = "A", date = date)

    @Test fun `the window runs three months back to 14 days ahead`() {
        assertEquals(FreshDropsWindow("2026-08-15", "2026-11-29"), freshDropsWindow(today))
    }

    @Test fun `both edge dates are kept`() {
        val kept = withinFreshDropsWindow(listOf(drop("2026-08-15"), drop("2026-11-29")), today)
        assertEquals(listOf("2026-08-15", "2026-11-29"), kept.map { it.date })
    }

    @Test fun `older than three months and more than 14 days ahead are dropped`() {
        val kept = withinFreshDropsWindow(listOf(drop("2026-08-14"), drop("2026-10-01"), drop("2026-11-30")), today)
        assertEquals(listOf("2026-10-01"), kept.map { it.date })
    }

    @Test fun `undated releases are dropped`() {
        assertTrue(withinFreshDropsWindow(listOf(drop(null), drop("")), today).isEmpty())
    }

    @Test fun `partial dates compare as strings as on desktop`() {
        val w = freshDropsWindow(today)
        // A partial date sorts before every full date of its period.
        assertTrue(w.contains("2026-09"))
        assertTrue(w.contains("2026-11"))
        assertFalse(w.contains("2026-08"))
        assertFalse(w.contains("2026-12"))
        assertFalse(w.contains("2026"))
    }

    @Test fun `order is kept`() {
        val kept = withinFreshDropsWindow(listOf(drop("2026-09-01"), drop("2026-11-01"), drop("2026-10-01")), today)
        assertEquals(listOf("2026-09-01", "2026-11-01", "2026-10-01"), kept.map { it.date })
    }

    // uniqueReleases: desktop's rule (src/renderer/lib/fresh-drops-releases.js)

    private fun rel(id: String?, artist: String, title: String) = FreshDrop(mbid = id, title = title, artist = artist, date = "2026-11-01")

    @Test fun `a collaboration found under two artists keeps the first copy by release-group id`() {
        val out = uniqueReleases(listOf(
            rel("rg-1", "Mondo Cozmo, Jim James", "Sweet Forever"),
            rel("rg-1", "Mondo Cozmo", "Sweet Forever"),
        ))
        assertEquals(listOf("Mondo Cozmo, Jim James"), out.map { it.artist })
    }

    @Test fun `artist and title match case-insensitively and cover releases with no id`() {
        val out = uniqueReleases(listOf(
            rel(null, "Artist", "Title"),
            rel("rg-2", "artist", "TITLE"),
            rel(null, "ARTIST", "title"),
            rel("rg-3", "Other", "Title"),
        ))
        assertEquals(listOf(null, "rg-3"), out.map { it.mbid })
    }

    @Test fun `distinct releases keep their order`() {
        val out = uniqueReleases(listOf(rel("b", "B", "Two"), rel("a", "A", "One"), rel("", "C", "Three"), rel("", "D", "Four")))
        assertEquals(listOf("B", "A", "C", "D"), out.map { it.artist })
    }
}
