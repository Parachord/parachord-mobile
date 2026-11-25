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
import com.parachord.shared.model.Track
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A cover lookup never takes another song's cover: the search's first result,
 * whatever it was, put Azealia Banks' "212" on Tundra 212's local files.
 */
class WrongCoverLookupTest {
    private class Search(val tracks: List<TrackSearchResult>, val albums: List<AlbumSearchResult> = emptyList()) : MetadataProvider {
        override val name = "fake"
        override val priority = 0
        override suspend fun isAvailable() = true
        override suspend fun searchTracks(query: String, limit: Int) = tracks
        override suspend fun searchAlbums(query: String, limit: Int) = albums
        override suspend fun searchArtists(query: String, limit: Int) = emptyList<ArtistInfo>()
        override suspend fun getArtistInfo(artistName: String): ArtistInfo? = null
        override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? = null
    }

    private class Setup(search: MetadataProvider) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val trackDao = TrackDao(db, driver)
        val service = ImageEnrichmentService(
            metadataService = MetadataService(listOf(search), getDisabledProviders = { emptySet() }),
            artistDao = ArtistDao(db), albumDao = AlbumDao(db), trackDao = trackDao,
            playlistDao = PlaylistDao(db), playlistTrackDao = PlaylistTrackDao(db),
            httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            composeMosaic = { _, _ -> null },
        )
    }

    private fun track(title: String, artist: String, art: String) =
        TrackSearchResult(title = title, artist = artist, artworkUrl = art, provider = "fake")

    private val sleeve = "https://example.com/212.jpg"

    @Test
    fun `a track no service has keeps the placeholder`() = runBlocking {
        val s = Setup(Search(listOf(track("212", "Azealia Banks", sleeve))))
        s.trackDao.insert(Track(id = "t", title = "Apples & Rain", artist = "Tundra 212", resolver = "localfiles"))
        assertNull(s.service.enrichTrackArt("t", "Apples & Rain", "Tundra 212", null))
        assertNull(s.trackDao.getById("t")?.artworkUrl)
    }

    @Test
    fun `the track's own cover is found behind another result`() = runBlocking {
        val s = Setup(Search(listOf(track("212", "Azealia Banks", sleeve), track("Roads", "Portishead", "https://example.com/dummy.jpg"))))
        assertEquals("https://example.com/dummy.jpg", s.service.enrichTrackArt("t", "Roads", "Portishead", null))
    }

    @Test
    fun `an album's cover is never another album's`() = runBlocking {
        val other = AlbumSearchResult(title = "212", artist = "Azealia Banks", artworkUrl = sleeve)
        assertNull(Setup(Search(listOf(track("212", "Azealia Banks", sleeve)), listOf(other))).service.lookupAlbumArt("Demos", "Tundra 212"))
        val own = AlbumSearchResult(title = "Dummy (Deluxe Edition)", artist = "Portishead", artworkUrl = "https://example.com/dummy.jpg")
        assertEquals("https://example.com/dummy.jpg", Setup(Search(emptyList(), listOf(other, own))).service.lookupAlbumArt("Dummy", "Portishead"))
    }

    @Test
    fun `only local files' looked-up covers are cleared`() = runBlocking {
        val s = Setup(Search(emptyList()))
        s.trackDao.insert(Track(id = "a", title = "A", artist = "X", resolver = "localfiles", artworkUrl = sleeve))
        s.trackDao.insert(Track(id = "b", title = "B", artist = "X", resolver = "localfiles", artworkUrl = "content://media/1"))
        s.trackDao.insert(Track(id = "c", title = "C", artist = "X", resolver = "spotify", artworkUrl = sleeve))
        s.trackDao.clearLookedUpLocalArtwork()
        assertEquals(listOf(null, "content://media/1", sleeve), listOf("a", "b", "c").map { s.trackDao.getById(it)?.artworkUrl })
    }
}
