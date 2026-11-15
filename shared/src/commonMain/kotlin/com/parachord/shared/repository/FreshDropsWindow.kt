package com.parachord.shared.repository

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Which release dates Fresh Drops keeps: three months back to 14 days ahead,
 * desktop's window (`src/renderer/lib/fresh-drops-releases.js`, parachord#1421).
 * It applies to everything Fresh Drops keeps, not only to what a scan fetches:
 * the cache as it loads, the prior cache a scan merges with, and the fetch
 * itself. Without the first two, a release stayed in the cache for good once a
 * scan found it, and the list grew with every scan (#687).
 */
const val FRESH_DROPS_MONTHS_BACK = 3
const val FRESH_DROPS_DAYS_AHEAD = 14

/** The window's two ends as `YYYY-MM-DD`, both included. */
data class FreshDropsWindow(val from: String, val to: String) {
    /**
     * Whether a MusicBrainz date falls inside. Dates can be `YYYY`, `YYYY-MM`
     * or `YYYY-MM-DD` and are compared as strings, as desktop does, so a
     * partial date sorts before every full date of its period: "2026-08" is
     * before "2026-08-01". A missing or empty date is outside.
     */
    fun contains(date: String?): Boolean =
        !date.isNullOrEmpty() && date >= from && date <= to
}

fun freshDropsWindow(today: LocalDate): FreshDropsWindow = FreshDropsWindow(
    from = today.minus(FRESH_DROPS_MONTHS_BACK, DateTimeUnit.MONTH).toString(),
    to = today.plus(FRESH_DROPS_DAYS_AHEAD, DateTimeUnit.DAY).toString(),
)

/** The releases dated inside the window, in their order; undated ones go. */
fun withinFreshDropsWindow(releases: List<FreshDrop>, today: LocalDate): List<FreshDrop> {
    val window = freshDropsWindow(today)
    return releases.filter { window.contains(it.date) }
}
