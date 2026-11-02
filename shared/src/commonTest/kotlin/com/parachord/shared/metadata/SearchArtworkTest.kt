package com.parachord.shared.metadata

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Search covers for rows without one (desktop fetchSearchAlbumArt / serviceArtFor). */
class SearchArtworkTest {

    private val group = "https://coverartarchive.org/release-group/g/front-250"
    private val release = "https://coverartarchive.org/release/r/front-250"
    private val spotify = "https://i.scdn.co/image/abc"

    private val checks = mutableListOf<String>()

    private fun artwork(serving: Set<String>, checkMs: Long = 0) = SearchArtwork(
        servesImage = { url ->
            checks += url
            delay(checkMs)
            url in serving
        },
    )

    @Test
    fun aCoverArtArchiveCoverShowsOnlyOnceItServesAnImage() = runTest {
        assertEquals(release, artwork(setOf(release)).firstWorking(listOf(group, release)))
        assertEquals(listOf(group, release), checks)
        assertNull(artwork(emptySet()).firstWorking(listOf(group, release)))
    }

    @Test
    fun aProvidersCoverIsTrustedWithoutACheck() = runTest {
        assertEquals(spotify, artwork(emptySet()).firstWorking(listOf(spotify)))
        assertEquals(emptyList(), checks)
    }

    @Test
    fun checksAreRemembered() = runTest {
        val a = artwork(setOf(group))
        a.firstWorking(listOf(group))
        a.firstWorking(listOf(group))
        assertEquals(1, checks.size)
    }

    @Test
    fun theCheckedCoverWinsWhenItAnswersInTime() = runTest {
        val url = artwork(setOf(group), checkMs = 500).settle(listOf(group)) { spotify }
        assertEquals(group, url)
    }

    @Test
    fun aSlowCheckLetsTheServicesCoverShowAfterTwoSeconds() = runTest {
        val url = artwork(setOf(group), checkMs = 5_000).settle(listOf(group)) { spotify }
        assertEquals(spotify, url)
        assertEquals(SearchArtwork.FALLBACK_MS, testScheduler.currentTime)
    }

    @Test
    fun noCoverArtArchiveCover_theServicesCoverWhenItArrives() = runTest {
        val url = artwork(emptySet()).settle(listOf(group, release)) { delay(3_000); spotify }
        assertEquals(spotify, url)
        assertEquals(3_000, testScheduler.currentTime)
    }

    @Test
    fun nothingAnywhere_givesUpAfterTheWait() = runTest {
        assertNull(SearchArtwork(servesImage = { false }, waitMs = 5_000).settle(listOf(group)) { awaitCancellation() })
        assertNull(artwork(emptySet()).settle(emptyList()) { null })
    }

    @Test
    fun rowsWaitingForAServicesCoverDontHoldUpOtherRowsChecks() = runTest {
        val a = artwork(setOf(group))
        // More waiting rows than there are slots.
        repeat(SearchArtwork.CONCURRENCY + 2) {
            backgroundScope.launch { a.settle(emptyList()) { awaitCancellation() } }
        }
        testScheduler.advanceTimeBy(100)
        assertEquals(group, a.settle(listOf(group)) { null })
        assertEquals(group, a.lookup { group })
    }

    // ── Rows carry their candidates ─────────────────────────────────

    @Test
    fun aMergedRowKeepsTheOtherProvidersCoverAsAFallback() {
        val mb = AlbumSearchResult("Dummy", "Portishead", artworkUrl = group, artworkCandidates = listOf(release), provider = "musicbrainz")
        val sp = AlbumSearchResult("Dummy", "Portishead", artworkUrl = spotify, provider = "spotify")
        val merged = mb.mergeWith(sp)
        assertEquals(group, merged.artworkUrl)
        assertEquals(listOf(release, spotify), merged.artworkCandidates)

        val track = TrackSearchResult("Roads", "Portishead", artworkCandidates = listOf(group), provider = "musicbrainz")
            .mergeWith(TrackSearchResult("Roads", "Portishead", artworkUrl = spotify, provider = "spotify"))
        assertEquals(spotify, track.artworkUrl)
        assertEquals(listOf(group), track.artworkCandidates)
    }
}
