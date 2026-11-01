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
    fun spotifysExactTopHitBeatsALooseMusicBrainzMatch() {
        // MusicBrainz's own scores for "portishead roads" put covers first; the
        // flat +0.4 MusicBrainz bonus used to keep them above Spotify's "Roads".
        val ranked = SearchRanking.rankTracks(
            "portishead roads",
            listOf(
                TrackSearchResult("Portishead - Roads", "The Otamatone Songbook", provider = "musicbrainz", score = 100),
                TrackSearchResult("Roads (Portishead Cover)", "The Last Embrace", provider = "musicbrainz", score = 89),
                TrackSearchResult("Roads", "Portishead", provider = "spotify", score = SearchRanking.rankScore(0, 1)),
            ),
        )
        assertEquals("Portishead", ranked.first().artist)
    }

    @Test
    fun theSourceScoreBreaksAFuzzyTie() {
        val ranked = SearchRanking.rankArtists(
            "massive attack",
            listOf(
                ArtistInfo("Massive Attack", provider = "spotify", score = 80),
                ArtistInfo("Massive Attack", provider = "musicbrainz", score = 100),
            ),
        )
        assertEquals(listOf("musicbrainz", "spotify"), ranked.map { it.provider })
    }

    @Test
    fun aRowWithNoFuzzyMatchStaysLast() {
        val ranked = SearchRanking.rankAlbums(
            "mezzanine",
            listOf(
                AlbumSearchResult("Qqqq", "Nobody", provider = "spotify", score = 100),
                AlbumSearchResult("Mezzanine", "Massive Attack", provider = "spotify", score = 95),
            ),
        )
        assertEquals(listOf("Mezzanine", "Qqqq"), ranked.map { it.title })
    }

    @Test
    fun rankScoreDropsFivePerPlaceAndPerProviderAhead() {
        assertEquals(100, SearchRanking.rankScore(0, 0))
        assertEquals(85, SearchRanking.rankScore(2, 1))
        assertEquals(0, SearchRanking.rankScore(30, 0))
    }

    @Test
    fun agreementBetweenProvidersAddsTheBonus_aSecondRecordingDoesNot() {
        assertEquals(105, mergedSearchScore(100, "musicbrainz", 95, "spotify"))
        assertEquals(100, mergedSearchScore(80, "musicbrainz", 100, "musicbrainz"))
        assertEquals(100, mergedSearchScore(100, "musicbrainz+spotify", 90, "spotify"))
        assertEquals(null, mergedSearchScore(null, "lastfm", null, "wikipedia"))
    }
}
