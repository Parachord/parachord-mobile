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
import com.parachord.shared.metadata.PlaylistMosaicPaths
import com.parachord.shared.model.PlaylistTrack
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The launch pass (`regenerateAllPlaylistMosaics`) against a real database:
 * which stored covers it keeps, re-points, or rebuilds, and that a rebuilt
 * mosaic is actually stored so the next launch leaves it alone.
 */
class MosaicContainerMoveTest {
    private val oldDir = "/Containers/Data/Application/1EAFF74A/Library/Application Support/playlist_mosaics"
    private val newDir = "/Containers/Data/Application/2FF16718/Library/Application Support/playlist_mosaics"

    /** [onDisk] is the fake filesystem; the composer "writes" `<dir>/<id>.jpg`
     *  into it and returns [composedUrl] for that path. */
    private class Setup(
        onDisk: Set<String>,
        private val currentDir: String,
        private val composedUrl: (path: String) -> String = { "file://$it" },
    ) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val db = ParachordDb(driver)
        val tracks = PlaylistTrackDao(db)
        val files = onDisk.toMutableSet()
        val composed = mutableListOf<String>()
        val service = ImageEnrichmentService(
            metadataService = MetadataService(emptyList(), getDisabledProviders = { emptySet() }),
            artistDao = ArtistDao(db), albumDao = AlbumDao(db), trackDao = TrackDao(db, driver),
            playlistDao = PlaylistDao(db), playlistTrackDao = tracks,
            httpClient = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            composeMosaic = { id, _ ->
                composed += id
                val path = "$currentDir/$id.jpg"
                files += path
                composedUrl(path)
            },
            mosaicDir = { currentDir },
            fileExists = { it in files },
        )

        fun insert(id: String, art: String?, trackArt: List<String> = emptyList()) = runBlocking {
            db.playlistQueries.insert(
                id = id, name = id, description = null, artworkUrl = art, trackCount = 0,
                createdAt = 1L, updatedAt = 1L, spotifyId = null, snapshotId = null,
                lastModified = 1L, locallyModified = 0L, ownerName = null, sourceUrl = null,
                sourceContentHash = null, localOnly = 0L, writable = 1L,
            )
            tracks.insertAll(
                trackArt.mapIndexed { i, url ->
                    PlaylistTrack(playlistId = id, position = i, trackTitle = "t$i", trackArtist = "a$i", trackArtworkUrl = url)
                },
            )
        }

        fun art(id: String): String? = db.playlistQueries.getById(id).executeAsOneOrNull()?.artworkUrl
    }

    private val twoCovers = listOf("https://img/a.jpg", "https://img/b.jpg")
    private val sig = PlaylistMosaicPaths.coverSignature(twoCovers)

    @Test
    fun `a mosaic that moved with the container is re-pointed at today's directory`() = runBlocking {
        val s = Setup(onDisk = setOf("$newDir/a.jpg"), currentDir = newDir)
        s.insert("a", "file://$oldDir/a.jpg?v=$sig", twoCovers)
        s.service.regenerateAllPlaylistMosaics()
        assertEquals("file://$newDir/a.jpg?v=$sig", s.art("a"))
        assertEquals(emptyList<String>(), s.composed)
    }

    @Test
    fun `a mosaic that matches its tracks is untouched`() = runBlocking {
        val s = Setup(onDisk = setOf("$newDir/a.jpg"), currentDir = newDir)
        s.insert("a", "file://$newDir/a.jpg?v=$sig", twoCovers)
        s.service.regenerateAllPlaylistMosaics()
        assertEquals("file://$newDir/a.jpg?v=$sig", s.art("a"))
        assertEquals(emptyList<String>(), s.composed)
    }

    @Test
    fun `a mosaic gone everywhere is rebuilt and stored in place of the dead URL`() = runBlocking {
        val s = Setup(onDisk = emptySet(), currentDir = newDir)
        s.insert("a", "file://$oldDir/a.jpg?v=$sig", twoCovers)
        s.service.regenerateAllPlaylistMosaics()
        assertEquals("file://$newDir/a.jpg?v=$sig", s.art("a"))
    }

    /** Android stores what `composeMosaicAndroid` returns: `File.toURI()`, which
     *  is `file:/data/…` with ONE slash. It must count as a present mosaic, or
     *  every launch rebuilds every mosaic (seen on a Pixel 9a: 168 mosaic files
     *  rewritten in the first 75 s after a cold start). */
    @Test
    fun `an Android file-colon-single-slash mosaic that exists is not rebuilt`() = runBlocking {
        val androidDir = "/data/user/0/com.parachord.android/files/playlist_mosaics"
        val uri = java.io.File("$androidDir/spotify-abc.jpg").toURI().toString()
        assertEquals("file:$androidDir/spotify-abc.jpg", uri)   // the form under test
        val s = Setup(onDisk = setOf("$androidDir/spotify-abc.jpg"), currentDir = androidDir)
        s.insert("spotify-abc", "$uri?v=$sig", twoCovers)
        s.service.regenerateAllPlaylistMosaics()
        assertEquals(emptyList<String>(), s.composed)
        assertEquals("$uri?v=$sig", s.art("spotify-abc"))
    }

    /** The pass was meant to replace provider stock art with a mosaic (53916b22),
     *  but the result was only ever stored over blank art: stock art stayed,
     *  and its mosaic was rebuilt at every launch. */
    @Test
    fun `stock provider art is replaced by the mosaic once and then left alone`() = runBlocking {
        val androidDir = "/data/user/0/com.parachord.android/files/playlist_mosaics"
        val s = Setup(onDisk = emptySet(), currentDir = androidDir, composedUrl = { java.io.File(it).toURI().toString() })
        s.insert("spotify-abc", "https://mosaic.scdn.co/640/stock", twoCovers)

        s.service.regenerateAllPlaylistMosaics()
        assertEquals("file:$androidDir/spotify-abc.jpg?v=$sig", s.art("spotify-abc"))
        assertEquals(listOf("spotify-abc"), s.composed)

        s.service.regenerateAllPlaylistMosaics()   // next launch
        assertEquals(listOf("spotify-abc"), s.composed)
    }

    // ── The mosaic follows its tracks ──────────────────────────────────────

    @Test
    fun `a mosaic built before covers were fingerprinted is rebuilt once`() = runBlocking {
        val s = Setup(onDisk = setOf("$newDir/a.jpg"), currentDir = newDir)
        s.insert("a", "file://$newDir/a.jpg", twoCovers)
        s.service.regenerateAllPlaylistMosaics()
        assertEquals("file://$newDir/a.jpg?v=$sig", s.art("a"))
        s.service.regenerateAllPlaylistMosaics()
        assertEquals(listOf("a"), s.composed)
    }

    @Test
    fun `changing the tracks rebuilds the mosaic with the new covers`() = runBlocking {
        val s = Setup(onDisk = setOf("$newDir/a.jpg"), currentDir = newDir)
        s.insert("a", "file://$newDir/a.jpg?v=$sig", twoCovers)
        val newCovers = listOf("https://img/c.jpg", "https://img/a.jpg", "https://img/d.jpg")
        s.tracks.replaceAll("a", newCovers.mapIndexed { i, url ->
            PlaylistTrack(playlistId = "a", position = i, trackTitle = "n$i", trackArtist = "n$i", trackArtworkUrl = url)
        })

        assertEquals(true, s.service.refreshPlaylistMosaic("a"))
        assertEquals("file://$newDir/a.jpg?v=${PlaylistMosaicPaths.coverSignature(newCovers)}", s.art("a"))
        assertEquals(false, s.service.refreshPlaylistMosaic("a"))   // now current
    }

    @Test
    fun `reordering so different covers lead rebuilds - same covers in the same order don't`() = runBlocking {
        val four = listOf("https://img/1.jpg", "https://img/2.jpg", "https://img/3.jpg", "https://img/4.jpg", "https://img/5.jpg")
        val s = Setup(onDisk = setOf("$newDir/a.jpg"), currentDir = newDir)
        val sig4 = PlaylistMosaicPaths.coverSignature(four.take(4))
        s.insert("a", "file://$newDir/a.jpg?v=$sig4", four)

        // A rewrite of the same tracks (a sync re-pull) changes nothing.
        s.tracks.replaceAll("a", s.tracks.getByPlaylistIdSync("a"))
        assertEquals(false, s.service.refreshPlaylistMosaic("a"))

        // Moving track 5 to the top changes which four covers lead.
        val moved = listOf(four[4]) + four.take(4)
        s.tracks.replaceAll("a", moved.mapIndexed { i, url ->
            PlaylistTrack(playlistId = "a", position = i, trackTitle = "n$i", trackArtist = "n$i", trackArtworkUrl = url)
        })
        assertEquals(true, s.service.refreshPlaylistMosaic("a"))
    }

    @Test
    fun `a track write while the app runs updates the mosaic without a relaunch`() = runBlocking {
        val s = Setup(onDisk = setOf("$newDir/a.jpg"), currentDir = newDir)
        s.insert("a", "file://$newDir/a.jpg?v=$sig", twoCovers)
        s.service.watchTrackChanges(debounceMs = 10)

        s.tracks.insertAll(listOf(
            PlaylistTrack(playlistId = "a", position = 2, trackTitle = "c", trackArtist = "c", trackArtworkUrl = "https://img/c.jpg"),
        ))
        val expected = "file://$newDir/a.jpg?v=${PlaylistMosaicPaths.coverSignature(twoCovers + "https://img/c.jpg")}"
        withTimeout(5_000) { while (s.art("a") != expected) delay(20) }
        assertEquals(listOf("a"), s.composed)
    }
}
