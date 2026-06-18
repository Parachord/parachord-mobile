package com.parachord.shared.repository

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the shared weekly-playlist display helpers used by both platforms
 * (#205): HTML stripping in the description and the creation-date label.
 */
class WeeklyPlaylistFormatTest {

    @Test
    fun `stripHtml removes the p wrapper LB annotations carry`() {
        assertEquals(
            "The ListenBrainz Weekly Jams playlist features songs you've heard before.",
            stripHtml("<p>The ListenBrainz Weekly Jams playlist features songs you've heard before.</p>"),
        )
    }

    @Test
    fun `stripHtml strips anchor tags and collapses whitespace`() {
        assertEquals(
            "See more here.",
            stripHtml("See <a href=\"https://listenbrainz.org\">more</a>   here."),
        )
    }

    @Test
    fun `stripHtml decodes the common entities`() {
        assertEquals("Rock & Roll \"hits\" <2026>", stripHtml("Rock &amp; Roll &quot;hits&quot; &lt;2026&gt;"))
    }

    @Test
    fun `stripHtml leaves plain text untouched`() {
        assertEquals("Already clean", stripHtml("Already clean"))
        assertEquals("", stripHtml(""))
    }

    @Test
    fun `formatWeeklyPlaylistDate formats a bare ISO date`() {
        assertEquals("Jun 15, 2026", formatWeeklyPlaylistDate("2026-06-15"))
        assertEquals("Jan 1, 2026", formatWeeklyPlaylistDate("2026-01-01"))
        assertEquals("Dec 31, 2025", formatWeeklyPlaylistDate("2025-12-31"))
    }

    @Test
    fun `formatWeeklyPlaylistDate handles a date with a time suffix`() {
        assertEquals("Jun 11, 2026", formatWeeklyPlaylistDate("2026-06-11T00:00:00+00:00"))
        assertEquals("Jun 11, 2026", formatWeeklyPlaylistDate("2026-06-11 00:00:00"))
    }

    @Test
    fun `formatWeeklyPlaylistDate returns empty for blank or unparseable input`() {
        assertEquals("", formatWeeklyPlaylistDate(""))
        assertEquals("", formatWeeklyPlaylistDate("not-a-date"))
    }
}
