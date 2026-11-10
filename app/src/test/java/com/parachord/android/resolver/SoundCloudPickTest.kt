package com.parachord.android.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which SoundCloud search result the native resolver plays (#582). It took the
 * first streamable result, so Ramones "Judy Is a Punk" resolved to the
 * preview-only "1975 Demo"; matching by containment alone would take "ROTD-Ep100
 * Judy Is a Punk" by "Ramones of the Day", a 29-minute podcast. The result
 * lists are SoundCloud's, logged on a Pixel 9a (Nov 2026).
 */
class SoundCloudPickTest {

    private var nextId = 1L
    private fun sc(title: String, user: String, access: String? = "playable", streamable: Boolean = true) =
        ScTrack(id = nextId++, title = title, user = ScUser(username = user), streamable = streamable, access = access)

    private val judyResults = listOf(
        sc("Judy Is A Punk (1975 Demo)", "Ramones", access = "preview"),
        sc("The Ramones - Judy Is A Punk", "xander_90"),
        sc("Sheena Is a Punk Rocker (2005 Remaster)", "Ramones", access = "preview"),
        sc("Judy Is A Punk (40th Anniversary Mono Mix)", "Rhino Records"),
        sc("Judy Is A Punk", "RAMONOS (Ramones Tribute)"),
        sc("ROTD-Ep100 Judy Is a Punk", "Ramones of the Day"),
        sc("Judy is a Punk - Ramones - Cover", "Jacob Sherrard"),
        sc("Judy Is A Punk (Ramones Cover)", "karl neudert segundo"),
        sc("Judy Is a Punk", "PEARS", access = "preview"),
    )

    private val herMajestyResults = listOf(
        sc("Her Majesty - The Beatles", "Unay Okami"),
        sc("Her Majesty (Beatles Cover Arranged by Black Francis)", "Art Brut", access = "preview"),
        sc("The Beatles - Her Majesty (Long Version)", "J.J."),
        sc("The Beatles - Her Majesty", "Lucas Whitehead"),
        sc("Her Majesty - The Beatles", "Wellsworth-Records"),
        sc("147. Her Majesty / Here Comes The Sun (Abbey Road, deel 6)", "Fab4Cast - The Dutch Beatles Podcast"),
    )

    @Test fun `neither the preview-only demo nor a podcast whose uploader contains the artist's name`() {
        val pick = pickSoundCloudTrack(judyResults, "Judy Is a Punk", "Ramones")
        assertEquals("The Ramones - Judy Is A Punk", pick?.track?.title)
        // Reported as the upload's own artist and title, so the confidence check scores it.
        assertEquals("The Ramones", pick?.artist)
        assertEquals("Judy Is A Punk", pick?.title)
    }

    @Test fun `an Artist - Title upload of the song beats a longer version`() {
        val pick = pickSoundCloudTrack(herMajestyResults, "Her Majesty", "The Beatles")
        assertEquals("The Beatles - Her Majesty", pick?.track?.title)
        assertEquals("Lucas Whitehead", pick?.track?.user?.username)
    }

    @Test fun `the artist's own upload comes first`() {
        val own = sc("Judy Is a Punk", "Ramones")
        val pick = pickSoundCloudTrack(listOf(sc("The Ramones - Judy Is A Punk", "xander_90"), own), "Judy Is a Punk", "Ramones")
        assertEquals(own, pick?.track)
        assertEquals("Ramones", pick?.artist)
    }

    @Test fun `a playable demo by the artist still plays when it's the only match`() {
        val demo = sc("Judy Is A Punk (1975 Demo)", "Ramones")
        assertEquals(demo, pickSoundCloudTrack(listOf(sc("ROTD-Ep100 Judy Is a Punk", "Ramones of the Day"), demo), "Judy Is a Punk", "Ramones")?.track)
    }

    @Test fun `with nothing by the artist there's no SoundCloud source`() {
        val only = listOf(
            sc("ROTD-Ep100 Judy Is a Punk", "Ramones of the Day"),
            sc("Judy Is A Punk", "RAMONOS (Ramones Tribute)"),
            sc("Judy Is A Punk (1975 Demo)", "Ramones", access = "preview"),
        )
        assertNull(pickSoundCloudTrack(only, "Judy Is a Punk", "Ramones"))
    }

    @Test fun `tracks that can't be played are never picked`() {
        val blocked = sc("Judy Is a Punk", "Ramones", access = "blocked")
        val notStreamable = sc("Judy Is a Punk", "Ramones", streamable = false)
        assertNull(pickSoundCloudTrack(listOf(blocked, notStreamable), "Judy Is a Punk", "Ramones"))
    }

    @Test fun `without a target the first playable track is picked`() {
        val first = sc("A", "B")
        assertEquals(first, pickSoundCloudTrack(listOf(sc("P", "Q", access = "preview"), first), null, null)?.track)
    }
}
