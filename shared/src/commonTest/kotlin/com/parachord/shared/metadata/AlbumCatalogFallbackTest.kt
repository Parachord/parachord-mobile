package com.parachord.shared.metadata

import com.parachord.shared.cache.PersistentJsonCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #424: album pages and search fall back to the plugins' album catalogs. */
class AlbumCatalogFallbackTest {

    private class Provider(
        override val name: String,
        val delayMs: Long = 0,
        val tracks: List<String> = emptyList(),
        val albums: Int = 0,
    ) : MetadataProvider {
        override val priority = 0
        override suspend fun isAvailable() = true
        override suspend fun searchTracks(query: String, limit: Int) = emptyList<TrackSearchResult>()
        override suspend fun searchAlbums(query: String, limit: Int) =
            List(albums) { AlbumSearchResult("MB Album $it", "Wild Pink", provider = name) }
        override suspend fun searchArtists(query: String, limit: Int) = emptyList<ArtistInfo>()
        override suspend fun getArtistInfo(artistName: String): ArtistInfo? = null
        override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? {
            delay(delayMs)
            if (tracks.isEmpty()) return null
            return AlbumDetail(albumTitle, artistName, tracks = tracks.map { TrackSearchResult(it, artistName) }, provider = name)
        }
    }

    private var catalogSearches = 0
    private var catalogDelayMs = 0L
    private var catalogEmpty = false
    private val searchJson = """[{"name":"Dulling the Horns","artist":"Wild Pink","url":"https://music.apple.com/us/album/x/1"}]"""
    private val lookupJson = """{"album":{"name":"Dulling the Horns","artist":"Wild Pink","url":"u","tracks":[""" +
        """{"title":"Q. Corinthians","duration":200,"trackNumber":1}]},"resolverId":"applemusic"}"""

    // The loader runs on the test's scheduler so virtual time doesn't race past it.
    private fun kotlinx.coroutines.test.TestScope.loader() = AlbumCatalogLoader(
        resolvers = { listOf("applemusic" to "Apple Music") },
        searchAlbums = { _, _, _, _ -> catalogSearches++; delay(catalogDelayMs); if (catalogEmpty) "[]" else searchJson },
        lookupAlbumFrom = { _, _ -> lookupJson },
        scope = backgroundScope,
    )

    private var tracksDisk: String? = null

    private fun kotlinx.coroutines.test.TestScope.service(vararg providers: Provider, cached: Boolean = false) =
        MetadataService(
            providers.toList(),
            getDisabledProviders = { emptySet() },
            albumCatalog = loader(),
            albumTracksCache = if (!cached) null else PersistentJsonCache(
                AlbumDetail.serializer(), { tracksDisk }, { tracksDisk = it },
                ttlMs = 1_000_000L, maxEntries = 10, scope = backgroundScope,
            ),
        )

    @Test
    fun noProviderHasTheAlbum_theCatalogListIsShownLabelled() = runTest {
        val emitted = service(Provider("musicbrainz")).getAlbumTracksWithCatalogFallback("Dulling the Horns", "Wild Pink").toList()
        val last = emitted.last()
        assertEquals("Apple Music", last.catalogSource)
        assertEquals(listOf("Q. Corinthians"), last.tracks.map { it.title })
        // The requested spelling wins, so track identity matches the provider path.
        assertEquals("Wild Pink", last.tracks.single().artist)
    }

    @Test
    fun theCatalogRacesMusicBrainz_itsListShowsFirst_thenMusicBrainzReplacesIt() = runTest {
        // Well under the old 5 s wait: the catalog is asked at once (#486).
        val emitted = service(Provider("musicbrainz", delayMs = 1_500, tracks = listOf("Q. Corinthians", "The Wind Was Like")))
            .getAlbumTracksWithCatalogFallback("Dulling the Horns", "Wild Pink").toList()
        assertEquals(listOf("Apple Music", null), emitted.map { it.catalogSource })
        assertEquals(2, emitted.last().tracks.size)
        assertEquals(1, catalogSearches)
    }

    @Test
    fun aProviderAnsweringFirst_theCatalogListIsNeverShown() = runTest {
        catalogDelayMs = 2_000
        val emitted = service(Provider("musicbrainz", delayMs = 500, tracks = listOf("a")))
            .getAlbumTracksWithCatalogFallback("Dulling the Horns", "Wild Pink").toList()
        assertEquals(1, emitted.size)
        assertNull(emitted.single().catalogSource)
    }

    @Test
    fun anEmptyProviderAnswerDoesNotBlankTheCatalogList() = runTest {
        val emitted = service(Provider("musicbrainz", delayMs = 1_000), Provider("lastfm", delayMs = 1_000, tracks = emptyList()))
            .getAlbumTracksWithCatalogFallback("Dulling the Horns", "Wild Pink").toList()
        assertEquals("Apple Music", emitted.last().catalogSource)
    }

    @Test
    fun aCachedAlbum_theCatalogIsNeverAsked() = runTest {
        val mb = Provider("musicbrainz", delayMs = 500, tracks = listOf("Q. Corinthians"))
        catalogDelayMs = 2_000
        val s = service(mb, cached = true)
        // The first open caches MusicBrainz's complete list.
        s.getAlbumTracksWithCatalogFallback("Dulling the Horns", "Wild Pink").toList()
        catalogSearches = 0

        val emitted = s.getAlbumTracksWithCatalogFallback("Dulling the Horns", "Wild Pink").toList()
        assertEquals(listOf("Q. Corinthians"), emitted.single().tracks.map { it.title })
        assertNull(emitted.single().catalogSource)
        assertEquals(0, catalogSearches)
    }

    @Test
    fun aCatalogListIsNeverCached() = runTest {
        val s = service(Provider("musicbrainz"), cached = true)
        s.getAlbumTracksWithCatalogFallback("Dulling the Horns", "Wild Pink").toList()
        catalogSearches = 0
        val emitted = s.getAlbumTracksWithCatalogFallback("Dulling the Horns", "Wild Pink").toList()
        // Shown again from the catalog, not from the track-list cache.
        assertEquals("Apple Music", emitted.last().catalogSource)
        assertEquals(1, catalogSearches)
    }

    @Test
    fun searchAddsCatalogAlbums_onlyWhenMusicBrainzFindsFewerThanThree() = runTest {
        val few = service(Provider("musicbrainz", albums = 1)).searchAlbums("dulling the horns")
        assertTrue(few.any { it.provider == "applemusic" })

        catalogSearches = 0
        val many = service(Provider("musicbrainz", albums = 5)).searchAlbums("dulling the horns")
        assertTrue(many.none { it.provider == "applemusic" })
        assertEquals(0, catalogSearches)
    }

    @Test
    fun anAlbumFoundBySearchOpensFromItsResolverWithoutSearchingAgain() = runTest {
        val l = loader()
        l.search("dulling")
        val searchesAfterSearch = catalogSearches
        assertEquals("Apple Music", l.load("Wild Pink", "Dulling the Horns")?.resolverName)
        assertEquals(searchesAfterSearch, catalogSearches)
    }

    // ── Album actions: the first list wins (desktop parachord#1202) ──

    @Test
    fun anActionTakesTheCatalogsListWhenMusicBrainzIsSlow() = runTest {
        val detail = service(Provider("musicbrainz", delayMs = 6_000, tracks = listOf("Q. Corinthians", "The Wind Was Like")))
            .loadAlbumTrackList("Dulling the Horns", "Wild Pink")!!
        assertEquals("Apple Music", detail.catalogSource)
        assertEquals(listOf("Q. Corinthians"), detail.tracks.map { it.title })
        assertTrue(testScheduler.currentTime < 6_000, "didn't wait for MusicBrainz: ${testScheduler.currentTime}")
    }

    @Test
    fun anActionTakesTheProvidersListWhenItComesFirst_andStopsWaitingForTheCatalog() = runTest {
        catalogDelayMs = 2_000
        val detail = service(Provider("musicbrainz", delayMs = 500, tracks = listOf("Q. Corinthians", "The Wind Was Like")))
            .loadAlbumTrackList("Dulling the Horns", "Wild Pink")!!
        assertNull(detail.catalogSource)
        assertEquals(2, detail.tracks.size)
        assertEquals(500, testScheduler.currentTime)
    }

    @Test
    fun anEmptyProviderAnswerLetsTheCatalogWin() = runTest {
        catalogDelayMs = 2_000
        val detail = service(Provider("musicbrainz", delayMs = 100)).loadAlbumTrackList("Dulling the Horns", "Wild Pink")!!
        assertEquals("Apple Music", detail.catalogSource)
    }

    @Test
    fun anActionOnACachedAlbumDoesNotRace() = runTest {
        val s = service(Provider("musicbrainz", delayMs = 500, tracks = listOf("Q. Corinthians")), cached = true)
        s.getAlbumTracks("Dulling the Horns", "Wild Pink")
        catalogSearches = 0
        assertNull(s.loadAlbumTrackList("Dulling the Horns", "Wild Pink")!!.catalogSource)
        assertEquals(0, catalogSearches)
    }

    @Test
    fun anActionGetsNullWhenNeitherSideHasAList() = runTest {
        catalogEmpty = true
        assertNull(service(Provider("musicbrainz", delayMs = 100)).loadAlbumTrackList("Dulling the Horns", "Wild Pink"))
    }
}
