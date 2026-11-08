package com.parachord.android.data.metadata

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.AlbumDao
import com.parachord.shared.db.dao.ArtistDao
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.metadata.ImageEnrichmentService
import com.parachord.shared.metadata.MetadataService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The launch repair against a real database: iOS moved the app's data
 * container, so stored mosaic URLs name a directory that's gone.
 */
class MosaicContainerMoveTest {
    private val oldDir = "/Containers/Data/Application/1EAFF74A/Library/Application Support/playlist_mosaics"
    private val newDir = "/Containers/Data/Application/2FF16718/Library/Application Support/playlist_mosaics"

    private class Setup(onDisk: Set<String>, currentDir: String) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val playlistDao = PlaylistDao(db)
        val service = ImageEnrichmentService(
            metadataService = MetadataService(emptyList(), getDisabledProviders = { emptySet() }),
            artistDao = ArtistDao(db), albumDao = AlbumDao(db), trackDao = TrackDao(db, driver),
            playlistDao = playlistDao, playlistTrackDao = PlaylistTrackDao(db),
            httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            composeMosaic = { _, _ -> null },
            mosaicDir = { currentDir },
            fileExists = { it in onDisk },
        )

        fun insert(id: String, art: String?) = db.playlistQueries.insert(
            id = id, name = id, description = null, artworkUrl = art, trackCount = 0,
            createdAt = 1L, updatedAt = 1L, spotifyId = null, snapshotId = null,
            lastModified = 1L, locallyModified = 0L, ownerName = null, sourceUrl = null,
            sourceContentHash = null, localOnly = 0L, writable = 1L,
        )

        fun art(id: String): String? = db.playlistQueries.getById(id).executeAsOneOrNull()?.artworkUrl
    }

    @Test
    fun `a mosaic that moved with the container is re-pointed at today's directory`() = runBlocking {
        val s = Setup(onDisk = setOf("$newDir/a.jpg"), currentDir = newDir)
        s.insert("a", "file://$oldDir/a.jpg?v=5")
        s.service.regenerateAllPlaylistMosaics()
        assertEquals("file://$newDir/a.jpg?v=5", s.art("a"))
    }

    @Test
    fun `a mosaic whose file is still there is untouched`() = runBlocking {
        val s = Setup(onDisk = setOf("$newDir/a.jpg"), currentDir = newDir)
        s.insert("a", "file://$newDir/a.jpg")
        s.service.regenerateAllPlaylistMosaics()
        assertEquals("file://$newDir/a.jpg", s.art("a"))
    }

    @Test
    fun `a mosaic gone everywhere is cleared so a new one can be stored`() = runBlocking {
        val s = Setup(onDisk = emptySet(), currentDir = newDir)
        s.insert("a", "file://$oldDir/a.jpg")
        s.service.regenerateAllPlaylistMosaics()
        // No tracks with art here, so nothing is built; the dead URL is gone
        // and the next launch tries again.
        assertNull(s.art("a"))
    }
}
