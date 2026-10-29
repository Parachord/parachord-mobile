package com.parachord.android.playback

import com.parachord.shared.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With nothing resolved yet (an Android Auto cold start), a track is routed by
 * the IDs it already carries, in the user's resolver order — not by its stored
 * `resolver`, which is just whichever service first played or synced it (#452).
 */
class StoredIdRoutingTest {

    private val dailyBrewTrack = Track(
        id = "t", title = "Adrenaline Nightshift", artist = "Japandroids",
        resolver = "spotify", spotifyId = "4uvx2HRZqGFmSkBc5JapW2", appleMusicId = "1651324049",
    )

    @Test fun `both stored IDs become sources when both services are connected`() {
        val s = storedIdSources(dailyBrewTrack, spotifyConnected = true, appleMusicConnected = true)
        assertEquals(listOf("spotify", "applemusic"), s.map { it.resolver })
        assertEquals("spotify:track:4uvx2HRZqGFmSkBc5JapW2", s.first().spotifyUri)
        assertTrue(s.all { it.confidence == 1.0 })
    }

    @Test fun `a disconnected service is left out`() {
        assertEquals(listOf("spotify"), storedIdSources(dailyBrewTrack, true, false).map { it.resolver })
        assertEquals(listOf("applemusic"), storedIdSources(dailyBrewTrack, false, true).map { it.resolver })
    }

    @Test fun `an Apple Music library id is not a usable source`() {
        val lib = dailyBrewTrack.copy(appleMusicId = "i.abc123")
        assertEquals(listOf("spotify"), storedIdSources(lib, true, true).map { it.resolver })
    }

    @Test fun `a streaming resolver moves to the higher-priority stored ID`() {
        assertTrue(shouldRerouteToStoredId("spotify", "applemusic", listOf("applemusic", "spotify")))
        assertTrue(shouldRerouteToStoredId(null, "applemusic", listOf("applemusic", "spotify")))
        assertFalse(shouldRerouteToStoredId("applemusic", "applemusic", listOf("applemusic", "spotify")))
    }

    @Test fun `a local file or Bandcamp source stays unless a stored ID outranks it`() {
        val order = listOf("applemusic", "spotify", "localfiles", "bandcamp")
        assertTrue(shouldRerouteToStoredId("localfiles", "applemusic", order))
        assertFalse(shouldRerouteToStoredId("localfiles", "spotify", listOf("localfiles", "spotify")))
        assertFalse(shouldRerouteToStoredId("bandcamp", "spotify", listOf("bandcamp", "spotify")))
    }
}
