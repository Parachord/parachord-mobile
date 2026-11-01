package com.parachord.shared.metadata

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #475 (desktop parachord#1186): a search category shows the fast providers'
 * rows before MusicBrainz, which waits its turn in the 1 req/s gate, answers.
 */
class ProgressiveSearchTest {

    private class Provider(
        override val name: String,
        val delayMs: Long,
        val titles: List<String>,
        val fails: Boolean = false,
    ) : MetadataProvider {
        override val priority = 0
        override suspend fun isAvailable() = true
        override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> {
            delay(delayMs)
            if (fails) error("$name is down")
            return titles.map { TrackSearchResult(it, "Massive Attack", provider = name) }
        }
        override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> {
            delay(delayMs)
            return titles.map { AlbumSearchResult(it, "Massive Attack", provider = name) }
        }
        override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> {
            delay(delayMs)
            return titles.map { ArtistInfo(name = it, provider = name) }
        }
        override suspend fun getArtistInfo(artistName: String): ArtistInfo? = null
    }

    private fun service(vararg providers: Provider) =
        MetadataService(providers.toList(), getDisabledProviders = { emptySet() })

    @Test
    fun fastProviderRowsShowBeforeMusicBrainzAnswers() = runTest {
        val svc = service(
            Provider("musicbrainz", delayMs = 3_000, titles = listOf("Angel", "Teardrop")),
            Provider("spotify", delayMs = 300, titles = listOf("Angel")),
        )
        val emittedAt = mutableListOf<Long>()
        val emissions = mutableListOf<List<TrackSearchResult>>()
        svc.searchTracksProgressively("massive attack angel").collect {
            emittedAt += testScheduler.currentTime
            emissions += it
        }

        assertEquals(listOf(300L, 3_000L), emittedAt)
        assertEquals(listOf("Angel"), emissions[0].map { it.title })
        assertEquals("spotify", emissions[0].single().provider)
        // The final list is merged in provider order, as before: MusicBrainz's
        // "Angel" absorbs Spotify's.
        assertEquals(listOf("Angel", "Teardrop"), emissions[1].map { it.title })
        assertEquals("musicbrainz+spotify", emissions[1][0].provider)
    }

    @Test
    fun rowsCarryDesktopSourceScores() = runTest {
        val tracks = service(
            Provider("musicbrainz", delayMs = 0, titles = listOf("Angel", "Teardrop")),
            Provider("lastfm", delayMs = 0, titles = listOf("X", "Y")),
            Provider("spotify", delayMs = 0, titles = listOf("Angel", "Z")),
        ).searchTracks("q").associate { it.title to it.score }

        assertEquals(null, tracks["Teardrop"]) // MusicBrainz's own score; this fake gives none
        assertEquals(100, tracks["X"]) // Last.fm: first provider after MusicBrainz, top hit
        assertEquals(95, tracks["Y"])
        assertEquals(90, tracks["Z"]) // Spotify: one provider ahead, second hit
        // Spotify agreeing with MusicBrainz: max(50, 95) + the agreement bonus.
        assertEquals(100, tracks["Angel"])
    }

    @Test
    fun theOneShotSearchReturnsTheFinalEmission() = runTest {
        val svc = service(
            Provider("musicbrainz", delayMs = 3_000, titles = listOf("Angel", "Teardrop")),
            Provider("spotify", delayMs = 300, titles = listOf("Angel")),
        )
        assertEquals(
            svc.searchTracksProgressively("q").toList().last(),
            svc.searchTracks("q"),
        )
        assertEquals(listOf("Angel", "Teardrop"), svc.searchAlbums("q").map { it.title })
        assertEquals(listOf("Angel", "Teardrop"), svc.searchArtists("q").map { it.name })
    }

    @Test
    fun anEmptyEarlyAnswerIsNotEmitted_butTheFinalOneAlwaysIs() = runTest {
        val nothingYet = service(
            Provider("spotify", delayMs = 100, titles = emptyList()),
            Provider("musicbrainz", delayMs = 2_000, titles = listOf("Angel")),
        ).searchTracksProgressively("q").toList()
        assertEquals(listOf(listOf("Angel")), nothingYet.map { list -> list.map { it.title } })

        val nothingAtAll = service(
            Provider("spotify", delayMs = 100, titles = emptyList()),
            Provider("musicbrainz", delayMs = 2_000, titles = emptyList()),
        ).searchTracksProgressively("q").toList()
        assertEquals(listOf(emptyList()), nothingAtAll)

        assertEquals(listOf(emptyList()), service().searchTracksProgressively("q").toList())
    }

    @Test
    fun aFailingProviderDoesNotHoldBackTheOthers() = runTest {
        val emissions = service(
            Provider("musicbrainz", delayMs = 1_000, titles = emptyList(), fails = true),
            Provider("spotify", delayMs = 200, titles = listOf("Angel")),
        ).searchTracksProgressively("q").toList()
        assertTrue(emissions.isNotEmpty())
        assertEquals(listOf("Angel"), emissions.last().map { it.title })
    }
}
