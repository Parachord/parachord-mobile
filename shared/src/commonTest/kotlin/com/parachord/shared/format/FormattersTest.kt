package com.parachord.shared.format

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Desktop's `formatTimeAgo` and play count text (#593). */
class FormattersTest {
    private val utc = TimeZone.UTC
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        LocalDateTime(y, mo, d, h, mi).toInstant(utc).toEpochMilliseconds()

    private val now = at(2026, 11, 12, 12, 0)

    @Test fun `under a minute is Just now`() {
        assertEquals("Just now", RelativeTime.ago(now - 59_000, now, utc))
        assertEquals("Just now", RelativeTime.ago(now + 5_000, now, utc)) // a clock a little ahead
    }

    @Test fun `minutes hours and days`() {
        assertEquals("1m ago", RelativeTime.ago(now - 60_000, now, utc))
        assertEquals("59m ago", RelativeTime.ago(now - 59 * 60_000, now, utc))
        assertEquals("1h ago", RelativeTime.ago(now - 60 * 60_000, now, utc))
        assertEquals("23h ago", RelativeTime.ago(now - 23 * 3_600_000L, now, utc))
        assertEquals("1d ago", RelativeTime.ago(now - 24 * 3_600_000L, now, utc))
        assertEquals("6d ago", RelativeTime.ago(now - 6 * 86_400_000L - 1, now, utc))
    }

    @Test fun `a week or more is the date — with the year only when it differs`() {
        assertEquals("Nov 5", RelativeTime.ago(at(2026, 11, 5), now, utc))
        assertEquals("Jan 1", RelativeTime.ago(at(2026, 1, 1, 0, 0), now, utc))
        assertEquals("Dec 31, 2025", RelativeTime.ago(at(2025, 12, 31), now, utc))
    }

    @Test fun `unknown is empty`() {
        assertEquals("", RelativeTime.ago(0, now, utc))
    }

    @Test fun `seconds are converted`() {
        assertEquals("5m ago", RelativeTime.agoSeconds((now - 5 * 60_000) / 1000, now))
    }

    @Test fun `play counts are written in full`() {
        assertEquals("1 play", playCountLabel(1))
        assertEquals("0 plays", playCountLabel(0))
        assertEquals("1234567 plays", playCountLabel(1_234_567))
    }

    @Test fun `history opens on 7 days`() {
        assertEquals("7day", DEFAULT_HISTORY_PERIOD)
    }
}
