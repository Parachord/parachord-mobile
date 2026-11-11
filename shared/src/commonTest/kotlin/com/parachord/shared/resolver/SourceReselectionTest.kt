package com.parachord.shared.resolver

import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #587: what a track with nothing resolved plays, and what the source switcher offers. */
class SourceReselectionTest {

    private val dailyBrewTrack = Track(
        id = "t", title = "Adrenaline Nightshift", artist = "Japandroids",
        resolver = "spotify", spotifyId = "4uvx2HRZqGFmSkBc5JapW2", appleMusicId = "1651324049",
    )

    private fun src(resolver: String, confidence: Double?, noMatch: Boolean = false, url: String = resolver) =
        ResolvedSource(url = url, sourceType = "stream", resolver = resolver, confidence = confidence, noMatch = noMatch)

    @Test
    fun bothStoredIdsBecomeSourcesWhenBothServicesAreConnected() {
        val s = SourceReselection.storedIdSources(dailyBrewTrack, spotifyConnected = true, appleMusicConnected = true)
        assertEquals(listOf("spotify", "applemusic"), s.map { it.resolver })
        assertEquals("spotify:track:4uvx2HRZqGFmSkBc5JapW2", s.first().spotifyUri)
        assertEquals("1651324049", s.last().appleMusicId)
        assertTrue(s.all { it.confidence == 1.0 })
    }

    @Test
    fun aDisconnectedServiceIsLeftOut() {
        assertEquals(listOf("spotify"), SourceReselection.storedIdSources(dailyBrewTrack, true, false).map { it.resolver })
        assertEquals(listOf("applemusic"), SourceReselection.storedIdSources(dailyBrewTrack, false, true).map { it.resolver })
        assertTrue(SourceReselection.storedIdSources(dailyBrewTrack, false, false).isEmpty())
    }

    @Test
    fun aSpotifyUriAloneIsEnough() {
        val t = dailyBrewTrack.copy(spotifyId = null, spotifyUri = "spotify:track:abc", appleMusicId = null)
        val s = SourceReselection.storedIdSources(t, true, true).single()
        assertEquals("abc", s.spotifyId)
    }

    @Test
    fun anAppleMusicLibraryIdIsNotAUsableSource() {
        val lib = dailyBrewTrack.copy(appleMusicId = "i.abc123")
        assertEquals(listOf("spotify"), SourceReselection.storedIdSources(lib, true, true).map { it.resolver })
    }

    @Test
    fun aStreamingResolverMovesToTheHigherPriorityStoredId() {
        val order = listOf("applemusic", "spotify")
        assertTrue(SourceReselection.shouldRerouteToStoredId("spotify", "applemusic", order))
        assertTrue(SourceReselection.shouldRerouteToStoredId(null, "applemusic", order))
        assertFalse(SourceReselection.shouldRerouteToStoredId("applemusic", "applemusic", order))
    }

    @Test
    fun aLocalFileOrBandcampSourceStaysUnlessAStoredIdOutranksIt() {
        val order = listOf("applemusic", "spotify", "localfiles", "bandcamp")
        assertTrue(SourceReselection.shouldRerouteToStoredId("localfiles", "applemusic", order))
        assertFalse(SourceReselection.shouldRerouteToStoredId("localfiles", "spotify", listOf("localfiles", "spotify")))
        assertFalse(SourceReselection.shouldRerouteToStoredId("bandcamp", "spotify", listOf("bandcamp", "spotify")))
        // A resolver missing from the order never outranks a stored ID that's in it.
        assertTrue(SourceReselection.shouldRerouteToStoredId("qobuz", "spotify", listOf("spotify")))
    }

    @Test
    fun theSwitcherOffersOnlySourcesThatCanPlay() {
        val offered = SourceReselection.switchableSources(
            listOf(src("spotify", 0.50), src("applemusic", 0.95, noMatch = true), src("localfiles", 0.95), src("soundcloud", null)),
            listOf("spotify", "applemusic", "soundcloud", "localfiles"),
        )
        assertEquals(listOf("localfiles"), offered.map { it.resolver })
    }

    @Test
    fun theSwitcherOffersOnePerResolverInTheUsersOrder() {
        val offered = SourceReselection.switchableSources(
            listOf(
                src("tidal", 0.99), src("localfiles", 0.95), src("spotify", 0.70, url = "weak"),
                src("spotify", 0.95, url = "strong"), src("applemusic", 0.90),
            ),
            listOf("spotify", "applemusic", "localfiles"),
        )
        assertEquals(listOf("spotify", "applemusic", "localfiles", "tidal"), offered.map { it.resolver })
        assertEquals("strong", offered.first().url)
    }

    @Test
    fun withNoOrderTheSwitcherSortsByConfidence() {
        val offered = SourceReselection.switchableSources(listOf(src("a", 0.70), src("b", 0.95)), emptyList())
        assertEquals(listOf("b", "a"), offered.map { it.resolver })
    }

    @Test
    fun aSwitchPlaysThatResolversBestPlayableSource() {
        val sources = listOf(
            src("spotify", 0.95, noMatch = true, url = "nomatch"), src("spotify", 0.70, url = "weak"),
            src("spotify", 0.90, url = "good"), src("applemusic", 0.95),
        )
        assertEquals("good", SourceReselection.sourceForSwitch(sources, "spotify")?.url)
        assertNull(SourceReselection.sourceForSwitch(listOf(src("spotify", 0.95, noMatch = true)), "spotify"))
        assertNull(SourceReselection.sourceForSwitch(sources, "soundcloud"))
    }
}
