package com.parachord.shared.repository

import com.parachord.shared.api.SgEvent
import com.parachord.shared.api.SgLocation
import com.parachord.shared.api.SgVenue
import com.parachord.shared.api.TmEvent
import com.parachord.shared.api.TmEventEmbedded
import com.parachord.shared.api.TmLocation
import com.parachord.shared.api.TmVenue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConcertVenueCoordinatesTest {
    private fun tm(lat: String?, lon: String?) = TmEvent(
        id = "1", name = "Show",
        embedded = TmEventEmbedded(venues = listOf(TmVenue(location = TmLocation(lat, lon)))),
    ).toConcertEvent()

    private fun sg(lat: Double?, lon: Double?) = SgEvent(
        id = 1, title = "Show", venue = SgVenue(location = SgLocation(lat, lon)),
    ).toConcertEvent()

    @Test
    fun finiteTicketmasterCoordinatesParse() {
        val e = tm(" 40.7 ", "-73.9")
        assertEquals(40.7, e.venueLatitude)
        assertEquals(-73.9, e.venueLongitude)
    }

    @Test
    fun nonFiniteTicketmasterCoordinatesAreDropped() {
        val e = tm("NaN", "Infinity")
        assertNull(e.venueLatitude)
        assertNull(e.venueLongitude)
        assertNull(tm("-Infinity", "1").venueLatitude)
    }

    @Test
    fun nonFiniteSeatGeekCoordinatesAreDropped() {
        val e = sg(Double.NaN, Double.POSITIVE_INFINITY)
        assertNull(e.venueLatitude)
        assertNull(e.venueLongitude)
        assertEquals(1.5, sg(1.5, 2.5).venueLatitude)
    }
}
