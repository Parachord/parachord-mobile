package com.parachord.shared.home

import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.repository.TicketSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomePageTest {
    @Test
    fun `greeting follows the local hour`() {
        assertEquals("Good evening", homeGreeting(4))
        assertEquals("Good morning", homeGreeting(5))
        assertEquals("Good morning", homeGreeting(11))
        assertEquals("Good afternoon", homeGreeting(12))
        assertEquals("Good afternoon", homeGreeting(17))
        assertEquals("Good evening", homeGreeting(18))
        assertEquals("Good evening", homeGreeting(0))
    }

    @Test
    fun `halves keep fresh when it has cards`() {
        val h = homeHalves(3, false, 0, false, 5, false, false)
        assertTrue(h.fresh)
        assertFalse(h.charts)
    }

    @Test
    fun `charts take the fresh half when fresh is empty`() {
        assertTrue(homeHalves(0, false, 0, false, 5, false, false).charts)
        assertTrue(homeHalves(0, false, 0, false, 0, false, true).charts)
        assertTrue(homeHalves(0, false, 0, false, 0, true, false).charts)
    }

    @Test
    fun `charts stay out while fresh is loading`() {
        val h = homeHalves(0, true, 0, false, 5, false, false)
        assertTrue(h.fresh)
        assertFalse(h.charts)
    }

    @Test
    fun `critical shows with cards or loading`() {
        assertTrue(homeHalves(0, false, 2, false, 0, false, false).critical)
        assertTrue(homeHalves(0, false, 0, true, 0, false, false).critical)
        assertFalse(homeHalves(0, false, 0, false, 0, false, false).critical)
    }

    @Test
    fun `date badge reads a local date`() {
        assertEquals("Nov 14", concertDateBadge("2026-11-14"))
        assertNull(concertDateBadge("bad"))
        assertNull(concertDateBadge(null))
    }

    @Test
    fun `place line omits blanks`() {
        val e = ConcertEvent(id = "1", name = "n", city = "Austin", state = " ", country = "US")
        assertEquals("Austin, US", concertPlaceLine(e))
    }

    @Test
    fun `venue falls back to TBA`() {
        assertEquals("Venue TBA", concertVenue(ConcertEvent(id = "1", name = "n")))
        assertEquals("Moody", concertVenue(ConcertEvent(id = "1", name = "n", venueName = "Moody")))
    }

    @Test
    fun `tickets keep http links only and drop duplicates`() {
        val e = ConcertEvent(
            id = "1", name = "n",
            ticketSources = listOf(
                TicketSource("ticketmaster", "https://tm.example/a", "Ticketmaster"),
                TicketSource("seatgeek", "https://sg.example/a", "SeatGeek"),
                TicketSource("x", "javascript:alert(1)", "Bad"),
                TicketSource("z", "https://tm.example/a", "Again"),
            ),
        )
        val t = concertTickets(e)
        assertEquals(listOf("Ticketmaster", "SeatGeek"), t.map { it.label })
    }

    @Test
    fun `tickets fall back to the single url`() {
        val e = ConcertEvent(id = "1", name = "n", ticketUrl = "https://t.example/x")
        assertEquals(listOf(TicketLink("Tickets", "https://t.example/x", "ticketmaster")), concertTickets(e))
        assertTrue(concertTickets(ConcertEvent(id = "1", name = "n", ticketUrl = "ftp://x")).isEmpty())
    }

    @Test
    fun `copy plurals and pill`() {
        assertEquals("Your latest love", HomeCopy.lovesSubtitle(1))
        assertEquals("Your 3 latest loves", HomeCopy.lovesSubtitle(3))
        assertEquals("Finish setting up · 1 of 2", HomeCopy.setupPill(1, 2))
    }

    @Test
    fun `concert artist falls back to the event name`() {
        assertEquals("Wilco", concertArtist(ConcertEvent(id = "1", name = "Wilco Live", artistName = " Wilco ")))
        assertEquals("Wilco Live", concertArtist(ConcertEvent(id = "1", name = "Wilco Live", artistName = " ")))
        assertEquals("Wilco Live", concertArtist(ConcertEvent(id = "1", name = "Wilco Live")))
    }

    @Test
    fun `concert photo is an https url only`() {
        assertEquals("https://x/a.jpg", concertPhotoUrl(ConcertEvent(id = "1", name = "n", imageUrl = " https://x/a.jpg ")))
        // Cleartext is blocked on both apps, so an http photo counts as none.
        assertNull(concertPhotoUrl(ConcertEvent(id = "1", name = "n", imageUrl = "http://x/a.jpg")))
        assertNull(concertPhotoUrl(ConcertEvent(id = "1", name = "n", imageUrl = "content://x")))
        assertNull(concertPhotoUrl(ConcertEvent(id = "1", name = "n", imageUrl = "")))
        assertNull(concertPhotoUrl(ConcertEvent(id = "1", name = "n")))
    }

    @Test
    fun `an http artist photo falls back to the event's https image`() {
        val e = ConcertEvent(id = "1", name = "n", artistName = "Wilco", imageUrl = "https://x/event.jpg")
        assertEquals("https://x/event.jpg", concertPhotoUrl(e, mapOf("wilco" to "http://x/artist.jpg")))
        assertEquals("https://x/artist.jpg", concertPhotoUrl(e, mapOf("wilco" to "https://x/artist.jpg")))
    }
}
