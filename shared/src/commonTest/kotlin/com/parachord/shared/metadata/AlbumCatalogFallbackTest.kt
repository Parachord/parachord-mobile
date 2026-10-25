package com.parachord.shared.metadata

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
    private val searchJson = """[{"name":"Dulling the Horns","artist":"Wild Pink","url":"https://music.apple.com/us/album/x/1"}]"""
    private val lookupJson = """{"album":{"name":"Dulling the Horns","artist":"Wild Pink","url":"u","tracks":[""" +
        """{"title":"Q. Corinthians","duration":200,"trackNumber":1}]},"resolverId":"applemusic"}"""

    // The loader runs on the test's scheduler so virtual time doesn't race past it.
    private fun kotlinx.coroutines.test.TestScope.loader() = AlbumCatalogLoader(
        resolvers = { listOf("applemusic" to "Apple Music") },
        searchAlbums = { _, _, _, _ -> catalogSearches++; searchJson },
        lookupAlbumFrom = { _, _ -> lookupJson },
        scope = backgroundScope,
    )

    private fun kotlinx.coroutines.test.TestScope.service(vararg providers: Provider) =
        MetadataService(providers.toList(), getDisabledProviders = { emptySet() }, albumCatalog = loader())

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
    fun slowMusicBrainz_theCatalogListShowsMeanwhile_thenMusicBrainzReplacesIt() = runTest {
        val emitted = service(Provider("musicbrainz", delayMs = 12_000, tracks = listOf("Q. Corinthians", "The Wind Was Like")))
            .getAlbumTracksWithCatalogFallback("Dulling the Horns", "Wild Pink").toList()
        assertEquals(listOf("Apple Music", null), emitted.map { it.catalogSource })
        assertEquals(2, emitted.last().tracks.size)
    }

    @Test
    fun aProviderAnsweringInTime_theCatalogIsNeverAsked() = runTest {
        val emitted = service(Provider("musicbrainz", delayMs = 1_000, tracks = listOf("a")))
            .getAlbumTracksWithCatalogFallback("Dulling the Horns", "Wild Pink").toList()
        assertEquals(1, emitted.size)
        assertNull(emitted.single().catalogSource)
        assertEquals(0, catalogSearches)
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
}
