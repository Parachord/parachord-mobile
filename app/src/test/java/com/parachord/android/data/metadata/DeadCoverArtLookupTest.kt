package com.parachord.android.data.metadata

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.AlbumDao
import com.parachord.shared.db.dao.ArtistDao
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.metadata.AlbumDetail
import com.parachord.shared.metadata.AlbumSearchResult
import com.parachord.shared.metadata.ArtistInfo
import com.parachord.shared.metadata.ImageEnrichmentService
import com.parachord.shared.metadata.MetadataProvider
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.model.Album
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MusicBrainz builds a Cover Art Archive URL whether or not a cover was ever
 * uploaded, and the album-art lookups returned (and stored) it unchecked, so
 * a coverless release kept a dead URL forever.
 */
class DeadCoverArtLookupTest {
    private val caa = "https://coverartarchive.org/release/0000/front-250"
    private val lastFm = "https://lastfm.freetls.fastly.net/i/u/300x300/abc.jpg"

    private class FakeProvider(val detailArt: String?, val searchArt: String?) : MetadataProvider {
        override val name = "fake"
        override val priority = 0
        override suspend fun isAvailable() = true
        override suspend fun searchTracks(query: String, limit: Int) = emptyList<TrackSearchResult>()
        override suspend fun searchAlbums(query: String, limit: Int) =
            listOfNotNull(searchArt?.let { AlbumSearchResult(title = "Album", artist = "Artist", artworkUrl = it) })
        override suspend fun searchArtists(query: String, limit: Int) = emptyList<ArtistInfo>()
        override suspend fun getArtistInfo(artistName: String): ArtistInfo? = null
        override suspend fun getAlbumTracks(albumTitle: String, artistName: String) =
            AlbumDetail(title = albumTitle, artist = artistName, artworkUrl = detailArt)
    }

    private class Setup(provider: MetadataProvider, caaStatus: HttpStatusCode) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val albumDao = AlbumDao(db)
        val service = ImageEnrichmentService(
            metadataService = MetadataService(listOf(provider), getDisabledProviders = { emptySet() }),
            artistDao = ArtistDao(db), albumDao = albumDao, trackDao = TrackDao(db, driver),
            playlistDao = PlaylistDao(db), playlistTrackDao = PlaylistTrackDao(db),
            httpClient = HttpClient(MockEngine { respond("", caaStatus) }),
            composeMosaic = { _, _ -> null },
        )
    }

    @Test
    fun `a Cover Art Archive URL with no cover falls through to the next provider`() = runBlocking {
        val s = Setup(FakeProvider(detailArt = caa, searchArt = lastFm), HttpStatusCode.NotFound)
        assertEquals(lastFm, s.service.lookupAlbumArt("Album", "Artist"))
    }

    @Test
    fun `a Cover Art Archive URL that serves an image is kept`() = runBlocking {
        val s = Setup(FakeProvider(detailArt = caa, searchArt = lastFm), HttpStatusCode.OK)
        assertEquals(caa, s.service.lookupAlbumArt("Album", "Artist"))
    }

    @Test
    fun `the dead URL being retried is never returned`() = runBlocking {
        val s = Setup(FakeProvider(detailArt = lastFm, searchArt = null), HttpStatusCode.OK)
        assertNull(s.service.lookupAlbumArt("Album", "Artist", exclude = lastFm))
    }

    @Test
    fun `enrichment doesn't store a dead Cover Art Archive URL`() = runBlocking {
        val s = Setup(FakeProvider(detailArt = caa, searchArt = null), HttpStatusCode.NotFound)
        s.albumDao.insert(Album(id = "a1", title = "Album", artist = "Artist"))
        assertNull(s.service.enrichAlbumArt("Album", "Artist"))
        assertNull(s.albumDao.getById("a1")?.artworkUrl)
    }

    @Test
    fun `a dead URL is swapped on every row that stored it`() = runBlocking {
        val s = Setup(FakeProvider(detailArt = null, searchArt = null), HttpStatusCode.OK)
        s.albumDao.insert(Album(id = "a1", title = "Album", artist = "Artist", artworkUrl = caa))
        s.albumDao.insert(Album(id = "a2", title = "Other", artist = "Artist", artworkUrl = lastFm))
        s.albumDao.replaceArtworkUrl(caa, "https://new/art.jpg")
        assertEquals("https://new/art.jpg", s.albumDao.getById("a1")?.artworkUrl)
        assertEquals(lastFm, s.albumDao.getById("a2")?.artworkUrl)
    }
}
