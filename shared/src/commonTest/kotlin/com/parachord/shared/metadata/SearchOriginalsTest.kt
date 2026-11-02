package com.parachord.shared.metadata

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #488, desktop parachord#1204: Search ranks originals above covers. The
 * ranking cases are desktop's `search-catalog.test.js`, on mobile's scale.
 */
class SearchOriginalsTest {

    private fun close(expected: Double, actual: Double?, tolerance: Double = 1e-6) =
        assertTrue(actual != null && abs(expected - actual) < tolerance, "expected $expected, got $actual")

    private fun album(title: String, artist: String, score: Int, popularity: Double? = null) =
        AlbumSearchResult(title, artist, provider = "musicbrainz", score = score, popularity = popularity)

    // ── Ranking ─────────────────────────────────────────────────────

    // 2026-11-02: "Mazzy Star" listed covers whose titles start with the name
    // above Mazzy Star's own albums.
    @Test
    fun anArtistSearchRanksTheArtistsOwnAlbumsAboveCoversThatMentionTheName() {
        val ranked = SearchRanking.rankAlbums(
            "Mazzy Star",
            listOf(
                album("Mazzy Star Covers", "Mersilde", 100),
                album("Into Dust (Mazzy Star Cover)", "Indigo Raven", 95),
                album("fade into you (mazzy star cover)", "bleach bath", 90),
                album("Among My Swan", "Mazzy Star", 100),
                album("So Tonight That I Might See", "Mazzy Star", 100),
            ),
        )
        assertEquals(listOf("Mazzy Star", "Mazzy Star"), ranked.take(2).map { it.artist })
    }

    @Test
    fun aTitleSearchStillFindsTheTitleFirst() {
        val ranked = SearchRanking.rankAlbums(
            "abbey road",
            listOf(
                album("Pink Moon", "Nick Drake", 100),
                album("Abbey Road", "The Beatles", 100),
                album("Abbey Road Revisited", "Various Artists", 90),
            ),
        )
        assertEquals("Abbey Road", ranked.first().title)
        assertEquals("Pink Moon", ranked.last().title)
    }

    // 2026-11-02, live on desktop: a tribute band named Abbey Road topped
    // "Abbey Road" with "Enjoy The Beatles!" because only the band was named.
    @Test
    fun abbeyRoad_theBeatlesAlbumOutranksATributeBandNamedAbbeyRoad() {
        val ranked = SearchRanking.rankAlbums(
            "Abbey Road",
            listOf(
                album("Enjoy The Beatles!", "Abbey Road", 100, popularity = 30.0),
                album("Abbey Road", "The Beatles", 100, popularity = 99.0),
                album("Abbey Road Medley", "Dream Theater", 95, popularity = 70.0),
            ),
        )
        assertEquals("The Beatles", ranked.first().artist)
    }

    @Test
    fun portisheadRoadsStillPutsPortisheadsTrackAboveACoverTitledWithBoth() {
        val ranked = SearchRanking.rankTracks(
            "portishead roads",
            listOf(
                TrackSearchResult("Portishead - Roads", "Some Covers Band", provider = "musicbrainz", score = 100),
                TrackSearchResult("Roads", "Portishead", provider = "musicbrainz", score = 90),
            ),
        )
        assertEquals("Portishead", ranked.first().artist)
    }

    // Every exact-title cover used to tie with the original on relevance; the
    // artist's listens now separate them.
    @Test
    fun fadeIntoYou_theOriginalOutranksCoversWithTheSameTitle() {
        val mazzy = SearchRanking.popularityFromListens(4_191_963, SearchRanking.ARTIST_POPULARITY_REFERENCE_LISTENS)
        val melt = SearchRanking.popularityFromListens(18_536, SearchRanking.ARTIST_POPULARITY_REFERENCE_LISTENS)
        val ranked = SearchRanking.rankTracks(
            "Fade Into You",
            listOf(
                TrackSearchResult("Fade Into You", "Melt", provider = "musicbrainz", score = 100, popularity = melt),
                TrackSearchResult("Fade Into You", "Sylf", provider = "spotify", score = 100),
                TrackSearchResult("Fade Into You", "Mazzy Star", provider = "musicbrainz", score = 100, popularity = mazzy),
            ),
        )
        assertEquals("Mazzy Star", ranked.first().artist)
    }

    // ── Naming the row ──────────────────────────────────────────────

    @Test
    fun queryNamesArtist_wholeWordsAnywhere_accentsAndALeadingTheIgnored() {
        assertTrue(SearchRanking.queryNamesArtist("Mazzy Star", "Mazzy Star"))
        assertTrue(SearchRanking.queryNamesArtist("mazzy star among my swan", "Mazzy Star"))
        assertTrue(SearchRanking.queryNamesArtist("roads portishead", "Portishead"))
        assertTrue(SearchRanking.queryNamesArtist("beatles abbey road", "The Beatles"))
        assertTrue(SearchRanking.queryNamesArtist("the beatles", "Beatles"))
        assertTrue(SearchRanking.queryNamesArtist("beyonce lemonade", "Beyoncé"))
        assertFalse(SearchRanking.queryNamesArtist("simon garfunkel", "Simon & Garfunkel")) // "and" is a word
        assertTrue(SearchRanking.queryNamesArtist("simon and garfunkel", "Simon & Garfunkel"))
    }

    @Test
    fun queryNamesArtist_notPartOfAWord_notAnEmptyName() {
        assertFalse(SearchRanking.queryNamesArtist("airplane", "Air"))
        assertFalse(SearchRanking.queryNamesArtist("mazzy", "Mazzy Star"))
        assertFalse(SearchRanking.queryNamesArtist("anything", ""))
    }

    @Test
    fun queryNamesRow_theArtistOrTheExactTitle_editionSuffixesAside() {
        assertTrue(SearchRanking.queryNamesRow("Abbey Road", "The Beatles", "Abbey Road"))
        assertTrue(SearchRanking.queryNamesRow("Abbey Road", "The Beatles", "Abbey Road (Remastered 2009)"))
        assertFalse(SearchRanking.queryNamesRow("Abbey Road", "The Beatles", "Abbey Road (2019 Mix)"))
        assertTrue(SearchRanking.queryNamesRow("Abbey Road", "Abbey Road", "Enjoy The Beatles!"))
        assertFalse(SearchRanking.queryNamesRow("Abbey Road", "Dream Theater", "Abbey Road Medley"))
        assertTrue(SearchRanking.queryNamesRow("Mazzy Star", "Haytham", "Mazzy Star - Single"))
    }

    // ── Popularity ──────────────────────────────────────────────────

    @Test
    fun listenCountsMapToZeroToHundredOnALogScale() {
        close(0.0, SearchRanking.popularityFromListens(0))
        close(100.0, SearchRanking.popularityFromListens(1_000_000))
        close(100.0, SearchRanking.popularityFromListens(1_000_000_000))
        assertTrue(SearchRanking.popularityFromListens(609_887)!! > 95) // Dummy
        assertTrue(SearchRanking.popularityFromListens(50)!! < 30) // an obscure cover
        assertNull(SearchRanking.popularityFromListens(null))
        assertNull(SearchRanking.popularityFromListens(-1))
    }

    @Test
    fun artistTotalsUseATenMillionScaleThatSeparatesAKnownArtistFromASmallOne() {
        val mazzy = SearchRanking.popularityFromListens(4_191_963, SearchRanking.ARTIST_POPULARITY_REFERENCE_LISTENS)!!
        val melt = SearchRanking.popularityFromListens(18_536, SearchRanking.ARTIST_POPULARITY_REFERENCE_LISTENS)!!
        assertTrue(mazzy > 93 && mazzy < 96, "$mazzy")
        assertTrue(melt > 58 && melt < 64, "$melt")
    }

    @Test
    fun theMoreListenedRowRanksFirst_aRowWithoutPopularityCountsAsTheMedian() {
        val rows = listOf(
            TrackSearchResult("Roads", "Obscure", score = 100, popularity = 20.0),
            TrackSearchResult("Roads", "Famous", score = 100, popularity = 95.0),
            TrackSearchResult("Roads", "Unknown", score = 100),
        )
        // The fuzzy title scores tie, so only popularity separates them.
        assertEquals(listOf("Famous", "Unknown", "Obscure"), SearchRanking.rankTracks("roads", rows).map { it.artist })
        // Unknown stands at the median of 20 and 95.
        val unknown = FuzzyMatch.combinedScore(1.0, 100, 57.5)
        val famous = FuzzyMatch.combinedScore(1.0, 100, 95.0)
        close(((100 + 95) / 2.0 - (100 + 57.5) / 2) / 100 * 0.4, famous - unknown)
    }

    @Test
    fun gracefulDegradation_noPopularityAnywhereRanksAsBefore_andLackingItIsNeutral() {
        val plain = listOf(
            TrackSearchResult("Roads", "A", score = 90),
            TrackSearchResult("Roads", "B", score = 100),
        )
        assertEquals(listOf("B", "A"), SearchRanking.rankTracks("roads", plain).map { it.artist })
        close(FuzzyMatch.combinedScore(1.0, 90), FuzzyMatch.combinedScore(1.0, 90, null))
        // One provider sends popularity (Spotify), another doesn't: the row
        // without it stands at the median, level with the one that has it.
        val mixed = listOf(
            TrackSearchResult("Roads", "Spotify mid", provider = "spotify", score = 100, popularity = 50.0),
            TrackSearchResult("Roads", "Apple", provider = "applemusic", score = 100),
        )
        assertEquals(listOf("Spotify mid", "Apple"), SearchRanking.rankTracks("roads", mixed).map { it.artist }) // stable tie
    }

    @Test
    fun mergingKeepsTheIdsAndPopularityEitherSideHad() {
        val mb = TrackSearchResult("Roads", "Portishead", provider = "musicbrainz", score = 100, artistMbid = MAZZY)
        val sp = TrackSearchResult("Roads", "Portishead", provider = "spotify", score = 95, popularity = 71.0)
        val merged = mb.mergeWith(sp)
        assertEquals(MAZZY, merged.artistMbid)
        assertEquals(71.0, merged.popularity)
        val a = AlbumSearchResult("Dummy", "Portishead", provider = "musicbrainz", releaseGroupMbid = DUMMY, popularity = 96.0)
        val b = AlbumSearchResult("Dummy", "Portishead", provider = "spotify")
        assertEquals(DUMMY, b.mergeWith(a).releaseGroupMbid)
        assertEquals(96.0, b.mergeWith(a).popularity)
    }

    // ── SearchPopularity (ListenBrainz) ─────────────────────────────

    private val calls = mutableListOf<Pair<String, List<String>>>()

    private fun popularity(delayMs: Long = 0, fail: Boolean = false, now: () -> Long = { 0L }) = SearchPopularity(
        fetchListens = { kind, ids ->
            calls += kind to ids
            delay(delayMs)
            if (fail) error("HTTP 503")
            ids.associateWith { id -> if (id == MAZZY) 4_191_963L else if (id == DUMMY) 609_887L else null }
        },
        nowMs = now,
    )

    @Test
    fun tracksGetTheirArtistsPopularity_albumsTheirReleaseGroups() = runTest {
        val p = popularity()
        val tracks = p.tracks(
            listOf(
                TrackSearchResult("Fade Into You", "Mazzy Star", artistMbid = MAZZY),
                TrackSearchResult("Fade Into You", "Nobody", artistMbid = NOBODY),
                TrackSearchResult("Fade Into You", "No id"),
            ),
        )
        assertTrue(tracks[0].popularity!! > 93)
        assertNull(tracks[1].popularity)
        assertNull(tracks[2].popularity)
        assertEquals(listOf("artist" to listOf(MAZZY, NOBODY)), calls)

        val albums = p.albums(listOf(AlbumSearchResult("Dummy", "Portishead", releaseGroupMbid = DUMMY)))
        assertTrue(albums.single().popularity!! > 95)
        assertEquals("release-group", calls.last().first)
    }

    @Test
    fun answersAreKeptForADay_unknownIdsToo() = runTest {
        var now = 0L
        val p = popularity(now = { now })
        val rows = listOf(TrackSearchResult("a", "Mazzy Star", artistMbid = MAZZY), TrackSearchResult("b", "x", artistMbid = NOBODY))
        p.tracks(rows)
        val again = p.tracks(rows)
        assertEquals(1, calls.size)
        assertTrue(again[0].popularity!! > 93)
        now = 24L * 60 * 60 * 1000
        p.tracks(rows)
        assertEquals(2, calls.size)
    }

    @Test
    fun aSlowAnswerIsCappedAt800ms_andLeavesRowsUnscored() = runTest {
        val p = popularity(delayMs = 2_000)
        val start = testScheduler.currentTime
        val rows = p.tracks(listOf(TrackSearchResult("a", "Mazzy Star", artistMbid = MAZZY)))
        assertNull(rows.single().popularity)
        assertEquals(800, testScheduler.currentTime - start)
    }

    @Test
    fun aFailureLeavesRowsUnscored_andIsntRemembered() = runTest {
        val p = popularity(fail = true)
        val rows = listOf(TrackSearchResult("a", "Mazzy Star", artistMbid = MAZZY))
        assertNull(p.tracks(rows).single().popularity)
        p.tracks(rows)
        assertEquals(2, calls.size)
    }

    // ── Search wiring ───────────────────────────────────────────────

    private class Provider(
        override val name: String,
        val tracks: List<TrackSearchResult>,
    ) : MetadataProvider {
        override val priority = 0
        override suspend fun isAvailable() = true
        override suspend fun searchTracks(query: String, limit: Int) = tracks
        override suspend fun searchAlbums(query: String, limit: Int) = emptyList<AlbumSearchResult>()
        override suspend fun searchArtists(query: String, limit: Int) = emptyList<ArtistInfo>()
        override suspend fun getArtistInfo(artistName: String): ArtistInfo? = null
        override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? = null
    }

    @Test
    fun searchGivesMusicBrainzRowsTheirPopularity_otherProvidersKeepTheirOwn() = runTest {
        val service = MetadataService(
            listOf(
                Provider("musicbrainz", listOf(TrackSearchResult("Fade Into You", "Mazzy Star", provider = "musicbrainz", score = 100, artistMbid = MAZZY))),
                Provider("spotify", listOf(TrackSearchResult("Fade Into You", "Sylf", provider = "spotify", popularity = 40.0))),
            ),
            getDisabledProviders = { emptySet() },
            searchPopularity = popularity(),
        )
        val rows = service.searchTracksProgressively("Fade Into You").last()
        assertTrue(rows.first { it.artist == "Mazzy Star" }.popularity!! > 93)
        assertEquals(40.0, rows.first { it.artist == "Sylf" }.popularity)
        assertEquals(listOf("artist" to listOf(MAZZY)), calls) // only MusicBrainz's rows are looked up
    }

    // Any well-formed MBIDs; the fake fetch decides their counts.
    private companion object {
        const val MAZZY = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        const val NOBODY = "e4fb4ab5-da0c-4a5c-a5a6-e5a3d7ffe6b1"
        const val DUMMY = "76df3287-6cda-33eb-8e9a-044b5e15ffdd"
    }
}
