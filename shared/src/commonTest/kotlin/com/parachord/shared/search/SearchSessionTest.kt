package com.parachord.shared.search

import com.parachord.shared.metadata.AlbumSearchResult
import com.parachord.shared.metadata.ArtistInfo
import com.parachord.shared.metadata.SearchArtwork
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.model.Album
import com.parachord.shared.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Search screen's shared rules (#590). */
class SearchSessionTest {

    private val asked = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private val localAsked = mutableListOf<String>()

    private var tracksFor: (String) -> List<TrackSearchResult> = { listOf(TrackSearchResult(title = it, artist = "Band", artworkUrl = "https://i.scdn.co/t")) }
    private var albumsFor: (String) -> List<AlbumSearchResult> = { emptyList() }
    private var artistsFor: (String) -> List<ArtistInfo> = { emptyList() }
    private var remoteMs = 100L
    private var hangTracks = false
    private val artistImages = mutableMapOf<String, String>()

    private val catalog = object : SearchCatalog {
        override fun tracks(query: String): Flow<List<TrackSearchResult>> = flow {
            asked += query
            try {
                if (hangTracks) awaitCancellation()
                delay(remoteMs)
                emit(tracksFor(query))
            } catch (e: CancellationException) {
                cancelled += query
                throw e
            }
        }
        override fun albums(query: String) = flow { delay(remoteMs); emit(albumsFor(query)) }
        override fun artists(query: String) = flow { delay(remoteMs); emit(artistsFor(query)) }
        override suspend fun artistImage(name: String): String? = artistImages[name]
    }

    private fun TestScope.session(
        resolvedTrackArt: (suspend (String, String) -> String?)? = null,
        settled: (List<TrackSearchResult>) -> Unit = {},
    ) = SearchSession(
        scope = backgroundScope,
        catalog = catalog,
        artwork = SearchArtwork(servesImage = { false }),
        localTracks = { q -> localAsked += q; flowOf(listOf(Track(id = "t-$q", title = q, artist = "Local"))) },
        localAlbums = { q -> flowOf(listOf(Album(id = "a-$q", title = q, artist = "Local"))) },
        resolvedTrackArt = resolvedTrackArt,
        onRemoteTracksSettled = settled,
    )

    // The session runs in backgroundScope, which advanceUntilIdle doesn't wait for.
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test
    fun aQuerySearchesFromTwoCharactersAfterTrimming() {
        assertFalse(SearchSession.isSearchable(""))
        assertFalse(SearchSession.isSearchable("a"))
        assertFalse(SearchSession.isSearchable("  a  "))
        assertTrue(SearchSession.isSearchable("ab"))
        assertTrue(SearchSession.isSearchable(" ab "))
    }

    @Test
    fun artKeysIgnoreCaseAndSpaces() {
        assertEquals("track|creep|radiohead", SearchSession.trackArtKey(" Creep ", "RADIOHEAD"))
        assertEquals("album|ok computer|radiohead", SearchSession.albumArtKey("OK Computer", "Radiohead"))
        assertEquals("artist|radiohead|", SearchSession.artistArtKey("Radiohead "))
    }

    @Test
    fun oneCharacterSearchesNothing() = runTest {
        val s = session()
        s.query("a")
        settle()
        assertEquals(emptyList(), asked)
        assertEquals(emptyList(), localAsked)
        assertEquals(SearchState(), s.state.value)
    }

    @Test
    fun theSearchWaitsForThePauseAfterTheLastKeystroke() = runTest {
        val s = session()
        s.query("ra")
        advanceTimeBy(200)
        s.query("rad")
        advanceTimeBy(SearchSession.DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(emptyList(), asked)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("rad"), asked)
        assertEquals(listOf("rad"), localAsked)
    }

    @Test
    fun theTrimmedQueryIsSearchedAndATrailingSpaceDoesntSearchAgain() = runTest {
        val s = session()
        s.query("  creep")
        settle()
        s.query("  creep ")
        settle()
        assertEquals(listOf("creep"), asked)
        assertEquals("creep", s.state.value.query)
    }

    @Test
    fun aNewKeystrokeCancelsTheRunningSearch() = runTest {
        hangTracks = true
        val s = session()
        s.query("ra")
        advanceTimeBy(SearchSession.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(listOf("ra"), asked)
        s.query("rad")
        runCurrent()
        assertEquals(listOf("ra"), cancelled, "cancelled at the keystroke, not after the pause")
    }

    @Test
    fun clearingTheQueryClearsTheResults() = runTest {
        val s = session()
        s.query("creep")
        settle()
        assertTrue(s.state.value.hasAnyResults)
        s.query("c")
        assertEquals(SearchState(), s.state.value)
    }

    @Test
    fun libraryTracksAndAlbumsAndRemoteRowsShow() = runTest {
        albumsFor = { listOf(AlbumSearchResult(title = "Pablo Honey", artist = "Radiohead", artworkUrl = "https://i.scdn.co/a")) }
        artistsFor = { listOf(ArtistInfo(name = "Radiohead", imageUrl = "https://i.scdn.co/p")) }
        val s = session()
        s.query("creep")
        advanceTimeBy(SearchSession.DEBOUNCE_MS + 1)
        runCurrent()
        val searching = s.state.value
        assertTrue(searching.isSearching)
        assertFalse(searching.hasSearched)
        assertEquals(listOf("creep"), searching.localTracks.map { it.title })
        assertEquals(listOf("creep"), searching.localAlbums.map { it.title })
        settle()
        val done = s.state.value
        assertFalse(done.isSearching)
        assertTrue(done.hasSearched)
        assertEquals(listOf("creep"), done.remoteTracks.map { it.title })
        assertEquals(listOf("Pablo Honey"), done.remoteAlbums.map { it.title })
        assertEquals("Radiohead", done.artists.first().name)
    }

    @Test
    fun theRemoteTracksAreHandedOnOnceTheirSearchFinishes() = runTest {
        val settled = mutableListOf<List<String>>()
        val s = session(settled = { rows -> settled += rows.map { it.title } })
        s.query("creep")
        settle()
        assertEquals(listOf(listOf("creep")), settled)
    }

    @Test
    fun aTrackWithoutACoverGetsTheResolvedSourcesCover() = runTest {
        tracksFor = { listOf(TrackSearchResult(title = "Creep", artist = "Radiohead")) }
        val s = session(resolvedTrackArt = { _, _ -> delay(50); "https://resolved/creep" })
        s.query("creep")
        settle()
        assertEquals("https://resolved/creep", s.state.value.art[SearchSession.trackArtKey("Creep", "Radiohead")])
    }

    @Test
    fun aTrackWithoutACoverGetsAnotherProvidersCover() = runTest {
        tracksFor = {
            listOf(
                TrackSearchResult(
                    title = "Creep",
                    artist = "Radiohead",
                    artworkCandidates = listOf("https://coverartarchive.org/release/r/front-250", "https://i.scdn.co/merged"),
                ),
            )
        }
        val s = session()
        s.query("creep")
        settle()
        assertEquals("https://i.scdn.co/merged", s.state.value.art[SearchSession.trackArtKey("Creep", "Radiohead")])
    }

    @Test
    fun anArtistWithoutAPhotoGetsOneAndARowWithACoverIsLeftAlone() = runTest {
        artistsFor = { listOf(ArtistInfo(name = "Radiohead")) }
        artistImages["Radiohead"] = "https://photo/radiohead"
        val s = session(resolvedTrackArt = { _, _ -> "https://never" })
        s.query("radiohead")
        settle()
        val art = s.state.value.art
        assertEquals("https://photo/radiohead", art[SearchSession.artistArtKey("Radiohead")])
        assertNull(art[SearchSession.trackArtKey("radiohead", "Band")], "the track row has its own cover")
    }

    @Test
    fun anOldQuerysCoverLookupsAreDropped() = runTest {
        artistsFor = { listOf(ArtistInfo(name = "Slow $it")) }
        val s = SearchSession(
            scope = backgroundScope,
            catalog = object : SearchCatalog by catalog {
                override suspend fun artistImage(name: String): String? { delay(10_000); return "https://photo/$name" }
            },
            artwork = SearchArtwork(servesImage = { false }),
            localTracks = { flowOf(emptyList()) },
            localAlbums = { flowOf(emptyList()) },
        )
        s.query("first")
        advanceTimeBy(SearchSession.DEBOUNCE_MS + 500)
        s.query("second")
        settle()
        val art = s.state.value.art
        assertNull(art[SearchSession.artistArtKey("Slow first")])
        assertEquals("https://photo/Slow second", art[SearchSession.artistArtKey("Slow second")])
    }
}
