package com.parachord.shared.ai.tools

import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** #520: the cases the AI tools' track lookup must get right. */
class DjTrackRequestsTest {

    private fun result(artist: String, title: String) = TrackSearchResult(title = title, artist = artist)

    private suspend fun find(artist: String, title: String, vararg results: TrackSearchResult) =
        findRequestedTrack(artist, title, { emptyList() }) { _, _ -> results.toList() }

    // ── #529: a track only in the local library ──

    private fun localFile(artist: String, title: String) = Track(
        id = "$artist-$title", title = title, artist = artist, album = "Demos",
        duration = 1000, artworkUrl = "content://art", resolver = "localfiles",
    )

    private suspend fun findLocal(artist: String, title: String, local: List<Track>, vararg catalog: TrackSearchResult) =
        findRequestedTrack(artist, title, { local }) { _, _ -> catalog.toList() }

    @Test
    fun aTrackNoCatalogHasIsFoundInTheLocalLibrary() = runTest {
        val found = findLocal("Zzqart", "Basement Demo", listOf(localFile("Zzqart", "Basement Demo")))
        assertEquals("Basement Demo", found?.title)
        assertEquals("Zzqart", found?.artist)
        assertEquals("Demos", found?.album)
        assertEquals(1000L, found?.duration)
        assertEquals("content://art", found?.artworkUrl)
    }

    @Test
    fun aDifferentLocalTrackIsStillNotFound() = runTest {
        val local = listOf(localFile("Zzqart", "Other Demo"), localFile("Someone Else", "Basement Demo"))
        assertNull(findLocal("Zzqart", "Basement Demo", local, result("The Avett Brothers", "Untitled #4")))
    }

    @Test
    fun aTaggedLocalVariantIsAccepted() = runTest {
        assertEquals(
            "Basement Demo (Remastered)",
            findLocal("Zzqart", "Basement Demo", listOf(localFile("Zzqart", "Basement Demo (Remastered)")))?.title,
        )
    }

    @Test
    fun aLocalCreditJoinedDifferentlyIsAccepted() = runTest {
        assertEquals(
            "A & B",
            findLocal("A and B", "Basement Demo", listOf(localFile("A & B", "Basement Demo")))?.artist,
        )
    }

    /** The catalog's row carries ids the resolver can use without searching again. */
    @Test
    fun theCatalogRowWinsWhenBothHaveTheExactTrack() = runTest {
        val catalog = result("Portishead", "Roads").copy(spotifyId = "sp1")
        assertEquals("sp1", findLocal("Portishead", "Roads", listOf(localFile("Portishead", "Roads")), catalog)?.spotifyId)
    }

    @Test
    fun anExactLocalTrackBeatsAPartialCatalogMatch() = runTest {
        val found = findLocal(
            "Portishead", "Roads",
            listOf(localFile("Portishead", "Roads")),
            result("Portishead", "Roads - Live in NYC"),
        )
        assertEquals("Roads", found?.title)
    }

    @Test
    fun aPlainCatalogVersionBeatsALocalLiveCut() = runTest {
        val plain = result("Portishead", "Roads - Remastered")
        assertEquals(plain, findLocal("Portishead", "Roads", listOf(localFile("Portishead", "Roads (Live)")), plain))
    }

    @Test
    fun aListReadsTheLibraryOnce() = runTest {
        var reads = 0
        val requested = findRequestedTracks(
            listOf(mapOf("artist" to "A", "title" to "One"), mapOf("artist" to "A", "title" to "Two")),
            {
                reads++
                listOf(localFile("A", "One"), localFile("A", "Two"))
            },
        ) { _, _ -> emptyList() }
        assertEquals(listOf("One", "Two"), requested.found.map { it.title })
        assertEquals(1, reads)
    }

    private suspend fun assertRefused(artist: String, title: String, resultArtist: String, resultTitle: String) =
        assertNull(find(artist, title, result(resultArtist, resultTitle)), "$artist / $title")

    private suspend fun assertMatches(artist: String, title: String, resultArtist: String, resultTitle: String) {
        assertNotNull(find(artist, title, result(resultArtist, resultTitle)), "$artist / $title")
    }

    @Test
    fun anotherSongIsRefused() = runTest {
        assertRefused("Biohio", "Happy Me", "The Avett Brothers", "Untitled #4")
        assertRefused("Radiohead", "Creep", "Radio Dept.", "Creep")
        assertRefused("Radiohead", "Creep", "Radiohead", "Karma Police")
        assertRefused("Хлеб", "Яйца", "Хлеб", "Молоко")
    }

    @Test
    fun theSameSongWrittenDifferentlyMatches() = runTest {
        assertMatches("radiohead", "creep", "Radiohead", "Creep")
        assertMatches("Queen", "Don't Stop Me Now", "Queen", "Don’t Stop Me Now")
        assertMatches("Queen", "Don't Stop Me Now", "Queen", "Dont Stop Me Now")
        assertMatches("Beyonce", "Deja Vu", "Beyoncé", "Déjà Vu")
        assertMatches("REM", "Losing My Religion", "R.E.M.", "Losing My Religion")
        assertMatches("Queen", "Bohemian Rhapsody", "Queen", "Bohemian Rhapsody - Remastered 2011")
        assertMatches("Drake", "Take Care", "Drake feat. Rihanna", "Take Care")
        assertMatches("Tom Petty", "Free Fallin", "Tom Petty and the Heartbreakers", "Free Fallin")
        assertMatches("東京事変", "群青日和", "東京事変", "群青日和")
        // The ticket's known cost, gone since #522.
        assertMatches("Simon and Garfunkel", "The Boxer", "Simon & Garfunkel", "The Boxer")
    }

    @Test
    fun anExactMatchBeatsAnEarlierPartialOne() = runTest {
        val exact = result("Queen", "Bohemian Rhapsody")
        assertEquals(exact, find("Queen", "Bohemian Rhapsody", result("Queen", "Bohemian Rhapsody - Remastered"), exact))
    }

    @Test
    fun aPlainVersionBeatsAnEarlierLiveCut() = runTest {
        val plain = result("Queen", "Bohemian Rhapsody - Remastered")
        assertEquals(plain, find("Queen", "Bohemian Rhapsody", result("Queen", "Bohemian Rhapsody (Live)"), plain))
    }

    @Test
    fun aLiveCutStillPlaysWhenItsAllThereIs() = runTest {
        val live = result("Queen", "Bohemian Rhapsody (Live)")
        assertEquals(live, find("Queen", "Bohemian Rhapsody", live))
    }

    @Test
    fun anEmptySearchIsAMiss() = runTest {
        assertNull(find("Queen", "Bohemian Rhapsody"))
    }

    @Test
    fun everyLookupSearchesTheSameDepth() = runTest {
        val limits = mutableListOf<Int>()
        findRequestedTracks(listOf(mapOf("artist" to "A", "title" to "B")), { emptyList() }) { _, limit ->
            limits += limit
            emptyList()
        }
        assertEquals(listOf(DJ_TRACK_SEARCH_LIMIT), limits)
    }

    @Test
    fun anEntryWithNoNamesIsSkipped_notReported() = runTest {
        val requested = findRequestedTracks(
            listOf(mapOf("artist" to "A"), mapOf("artist" to "B", "title" to "C")),
            { emptyList() },
        ) { _, _ -> listOf(result("B", "C")) }
        assertEquals(listOf("C"), requested.found.map { it.title })
        assertEquals(emptyList(), requested.notFound)
    }
}
