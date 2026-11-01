package com.parachord.shared.metadata

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #475, desktop parachord#1186: shared search ranking (Android Search, Android Auto, iOS). */
class SearchRankingTest {

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected, got $actual")

    @Test
    fun aQueryNamingArtistAndTitleRanksTheRealTrackAboveACoverTitledAfterBoth() {
        val real = FuzzyMatch.trackScore("portishead roads", title = "Roads", artist = "Portishead")
        val cover = FuzzyMatch.trackScore("portishead roads", title = "Portishead - Roads", artist = "Piano Tribute Players")
        assertClose(1.0, real)
        assertTrue(real > cover, "real $real should beat cover $cover")
    }

    @Test
    fun aTitleOnlyQueryStillScoresTheTitle() {
        assertClose(1.0, FuzzyMatch.trackScore("roads", title = "Roads", artist = "Portishead"))
    }

    @Test
    fun anArtistOnlyQueryScoresEveryTrackByTheArtist() {
        // "Portishead Roads" starts with the query.
        assertClose(0.95, FuzzyMatch.trackScore("portishead", title = "Roads", artist = "Portishead"))
        assertClose(1.0, FuzzyMatch.trackScore("portishead", title = "Portishead", artist = "Someone Else"))
    }

    @Test
    fun rankTracksPutsTheRealTrackFirst() {
        val ranked = SearchRanking.rankTracks(
            "portishead roads",
            listOf(
                TrackSearchResult("Portishead - Roads", "Piano Tribute Players", provider = "musicbrainz"),
                TrackSearchResult("Roads", "Portishead", provider = "musicbrainz"),
            ),
        )
        assertEquals(listOf("Roads", "Portishead - Roads"), ranked.map { it.title })
    }

    @Test
    fun aMatchFromAHigherPriorityProviderWinsATie() {
        val ranked = SearchRanking.rankArtists(
            "massive attack",
            listOf(ArtistInfo("Massive Attack", provider = "spotify"), ArtistInfo("Massive Attack", provider = "musicbrainz")),
        )
        assertEquals(listOf("musicbrainz", "spotify"), ranked.map { it.provider })
    }

    @Test
    fun unmatchedSpotifyRowsAreDropped() {
        // Spotify's source score is 0, so a row with no fuzzy match scores 0.
        val ranked = SearchRanking.rankAlbums("qqqq", listOf(AlbumSearchResult("Mezzanine", "Massive Attack", provider = "spotify")))
        assertTrue(ranked.isEmpty())
    }
}
