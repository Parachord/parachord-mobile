package com.parachord.shared.metadata

import com.parachord.shared.cache.PersistentJsonCache
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Album tracklists and discographies are persisted (desktop keeps both 30 days),
 * but only complete answers: a provider that failed must not leave a partial
 * tracklist or discography cached for a month.
 */
class MetadataServiceCacheTest {

    private class FakeProvider(override val name: String, override val priority: Int) : MetadataProvider {
        var fail = false
        var albumCalls = 0
        var discographyCalls = 0
        override suspend fun isAvailable() = true
        override suspend fun searchTracks(query: String, limit: Int) = emptyList<TrackSearchResult>()
        override suspend fun searchAlbums(query: String, limit: Int) = emptyList<AlbumSearchResult>()
        override suspend fun searchArtists(query: String, limit: Int) = emptyList<ArtistInfo>()
        override suspend fun getArtistInfo(artistName: String): ArtistInfo? = null
        override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? {
            albumCalls++
            if (fail) error("503")
            return AlbumDetail(albumTitle, artistName, tracks = listOf(TrackSearchResult("Song", artistName)), provider = name)
        }
        override suspend fun getArtistAlbums(artistName: String, limit: Int): List<AlbumSearchResult> {
            discographyCalls++
            if (fail) error("503")
            return listOf(AlbumSearchResult("Album $name", artistName))
        }
    }

    private var tracksDisk: String? = null
    private var albumsDisk: String? = null
    private val mb = FakeProvider("musicbrainz", 0)
    private val lastFm = FakeProvider("lastfm", 10)

    private fun kotlinx.coroutines.test.TestScope.service() = MetadataService(
        providers = listOf(mb, lastFm),
        getDisabledProviders = { emptySet() },
        albumTracksCache = PersistentJsonCache(
            AlbumDetail.serializer(), { tracksDisk }, { tracksDisk = it },
            ttlMs = 1_000_000L, maxEntries = 10, scope = backgroundScope,
        ),
        artistAlbumsCache = PersistentJsonCache(
            ListSerializer(AlbumSearchResult.serializer()), { albumsDisk }, { albumsDisk = it },
            ttlMs = 1_000_000L, maxEntries = 10, scope = backgroundScope,
        ),
    )

    @Test
    fun aCompleteTracklistIsServedFromTheCache() = runTest {
        val s = service()
        s.getAlbumTracks("OK Computer", "Radiohead")
        s.getAlbumTracks("OK Computer", "Radiohead")
        assertEquals(1, mb.albumCalls)
    }

    @Test
    fun aTracklistMissingAFailedProviderIsNotCached() = runTest {
        val s = service()
        lastFm.fail = true
        s.getAlbumTracks("OK Computer", "Radiohead")
        lastFm.fail = false
        s.getAlbumTracks("OK Computer", "Radiohead")
        assertEquals(2, mb.albumCalls, "the partial answer must not be reused")
    }

    @Test
    fun aCompleteDiscographyIsServedFromTheCache() = runTest {
        val s = service()
        s.getArtistAlbums("Radiohead")
        s.getArtistAlbums("Radiohead")
        assertEquals(1, mb.discographyCalls)
    }

    @Test
    fun aPartialDiscographyIsNotCached() = runTest {
        val s = service()
        mb.fail = true
        assertEquals(listOf("Album lastfm"), s.getArtistAlbums("Radiohead").map { it.title })
        mb.fail = false
        s.getArtistAlbums("Radiohead")
        assertEquals(2, lastFm.discographyCalls)
    }

    @Test
    fun anUnavailableDiscographyIsNotCached() = runTest {
        val s = service()
        mb.fail = true; lastFm.fail = true
        assertFailsWith<DiscographyUnavailableException> { s.getArtistAlbums("Radiohead") }
        mb.fail = false; lastFm.fail = false
        assertEquals(2, s.getArtistAlbums("Radiohead").size)
    }
}
