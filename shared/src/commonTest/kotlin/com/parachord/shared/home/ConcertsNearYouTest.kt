package com.parachord.shared.home

import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.repository.withFirstVenueCoordinates
import com.parachord.shared.settings.SettingsStore
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConcertsNearYouTest {
    private val today = LocalDate(2026, 11, 20)
    private val austinLat = 30.2672
    private val austinLon = -97.7431
    private val austin = SettingsStore.ConcertLocation(austinLat, austinLon, "Austin, TX", 50)

    private fun event(
        id: String,
        date: String? = "2026-12-01",
        lat: Double? = austinLat,
        lon: Double? = austinLon,
        city: String? = null,
        state: String? = null,
    ) = ConcertEvent(
        id = id, name = id, date = date, city = city, state = state,
        venueLatitude = lat, venueLongitude = lon,
    )

    @Test
    fun `haversine uses desktop's Earth radius in miles`() {
        // Austin to Dallas, about 182 miles.
        val d = haversineMiles(austinLat, austinLon, 32.7767, -96.7970)
        assertTrue(abs(d - 182.0) < 2.0, "got $d")
        assertEquals(0.0, haversineMiles(austinLat, austinLon, austinLat, austinLon))
    }

    @Test
    fun `a past event is dropped and today's is kept`() {
        val result = concertsNearYou(
            listOf(event("past", date = "2026-11-19"), event("today", date = "2026-11-20T20:00:00")),
            austin, today,
        )
        assertEquals(listOf("today"), result.map { it.id })
    }

    @Test
    fun `an event with no date is dropped`() {
        assertEquals(emptyList(), concertsNearYou(listOf(event("nodate", date = null)), austin, today))
    }

    @Test
    fun `a venue within the radius is kept and one beyond it dropped`() {
        // 0.1447 degrees of latitude is about 10 miles; 1.158 about 80.
        val near = event("near", lat = austinLat + 0.1447)
        val far = event("far", lat = austinLat + 1.158)
        assertEquals(listOf("near"), concertsNearYou(listOf(near, far), austin, today).map { it.id })
    }

    @Test
    fun `with coordinates on both sides the distance decides over the city`() {
        val farButSameCity = event("far", lat = austinLat + 1.158, city = "Austin")
        assertEquals(emptyList(), concertsNearYou(listOf(farButSameCity), austin, today))
    }

    @Test
    fun `a venue without coordinates matches by city`() {
        val inAustin = event("austin", lat = null, lon = null, city = "Austin")
        val inDallas = event("dallas", lat = null, lon = null, city = "Dallas")
        assertEquals(listOf("austin"), concertsNearYou(listOf(inAustin, inDallas), austin, today).map { it.id })
    }

    @Test
    fun `a location without coordinates matches by city`() {
        val cityOnly = SettingsStore.ConcertLocation(null, null, "Austin, TX", 50)
        val inAustin = event("austin", city = "AUSTIN ")
        val elsewhere = event("houston", city = "Houston")
        assertEquals(listOf("austin"), concertsNearYou(listOf(inAustin, elsewhere), cityOnly, today).map { it.id })
    }

    @Test
    fun `the region matches when the city does not`() {
        // locCity is only the first comma part, so a state matches only when it is in that part.
        val loc = SettingsStore.ConcertLocation(null, null, "Brooklyn NY", 50)
        val ny = event("ny", lat = null, lon = null, city = "New York", state = "NY")
        assertEquals(listOf("ny"), concertsNearYou(listOf(ny), loc, today).map { it.id })
    }

    @Test
    fun `a venue with a blank city and region never matches`() {
        val blank = event("blank", lat = null, lon = null, city = " ", state = "")
        assertEquals(emptyList(), concertsNearYou(listOf(blank), austin, today))
    }

    @Test
    fun `no location gives nothing`() {
        assertEquals(emptyList(), concertsNearYou(listOf(event("a")), null, today))
        val empty = SettingsStore.ConcertLocation(null, null, null, 50)
        assertEquals(emptyList(), concertsNearYou(listOf(event("a", city = "Austin")), empty, today))
        val blankCity = SettingsStore.ConcertLocation(null, null, "  ", 50)
        assertEquals(emptyList(), concertsNearYou(listOf(event("a", city = "Austin")), blankCity, today))
    }

    @Test
    fun `six qualifying events give the four soonest in date order`() {
        val events = listOf("2026-12-05", "2026-11-25", "2027-01-02", "2026-11-21", "2026-12-24", "2026-11-30")
            .mapIndexed { i, d -> event("e$i", date = d) }
        val result = concertsNearYou(events, austin, today)
        assertEquals(listOf("2026-11-21", "2026-11-25", "2026-11-30", "2026-12-05"), result.map { it.date })
    }

    @Test
    fun `a merged duplicate keeps the first coordinates either provider gave`() {
        val tm = event("tm", lat = null, lon = null)
        val sg = event("sg", lat = 1.0, lon = 2.0)
        val other = event("other", lat = 3.0, lon = 4.0)
        val filled = withFirstVenueCoordinates(tm, sg)
        assertEquals("tm", filled.id)
        assertEquals(1.0, filled.venueLatitude)
        assertEquals(2.0, filled.venueLongitude)
        assertEquals(sg, withFirstVenueCoordinates(sg, other))
        assertEquals(sg, withFirstVenueCoordinates(sg, tm))
    }
}
