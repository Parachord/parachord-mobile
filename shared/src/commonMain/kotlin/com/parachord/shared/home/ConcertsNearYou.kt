package com.parachord.shared.home

import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.settings.SettingsStore
import kotlinx.datetime.LocalDate
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Earth's radius in miles, desktop's `haversineDistance`. */
private const val EARTH_RADIUS_MILES = 3958.8

/** Home's Concerts near you shows at most this many. */
const val CONCERTS_NEAR_YOU_MAX = 4

/** Great-circle distance in miles (desktop's `haversineDistance`). */
fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1) * PI / 180
    val dLon = (lon2 - lon1) * PI / 180
    val s1 = sin(dLat / 2)
    val s2 = sin(dLon / 2)
    val a = s1 * s1 + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * s2 * s2
    return EARTH_RADIUS_MILES * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * Home's "Concerts near you" (desktop's `homeConcerts`): the Concerts page's
 * location filter applied to [events], upcoming shows only (the date on or
 * after [today]), soonest first, at most [limit].
 *
 * Near means within the location's radius when both the location and the
 * venue have coordinates; otherwise the location's city (its first comma
 * part, lowercased and trimmed) contains the venue's city or state. No
 * location (no city and no coordinates) gives nothing.
 */
fun concertsNearYou(
    events: List<ConcertEvent>,
    location: SettingsStore.ConcertLocation?,
    today: LocalDate,
    limit: Int = CONCERTS_NEAR_YOU_MAX,
): List<ConcertEvent> {
    if (location == null) return emptyList()
    val locLat = location.latitude
    val locLon = location.longitude
    val hasCoords = locLat != null && locLon != null
    val locCity = (location.city ?: "").lowercase().trim().split(',')[0].trim()
    if (!hasCoords && locCity.isEmpty()) return emptyList()
    val todayKey = today.toString()

    fun near(event: ConcertEvent): Boolean {
        val vLat = event.venueLatitude
        val vLon = event.venueLongitude
        if (locLat != null && locLon != null && vLat != null && vLon != null) {
            return haversineMiles(locLat, locLon, vLat, vLon) <= location.radiusMiles
        }
        val city = (event.city ?: "").lowercase().trim()
        val region = (event.state ?: "").lowercase().trim()
        return (city.isNotEmpty() && locCity.contains(city)) ||
            (region.isNotEmpty() && locCity.contains(region))
    }

    return events
        .filter { e -> val d = e.date; d != null && d.take(10) >= todayKey && near(e) }
        .sortedBy { it.date }
        .take(limit)
}
