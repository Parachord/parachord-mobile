package com.parachord.android.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.DurationUnitMigration
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.Track
import com.parachord.shared.store.KvStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #349 — the one-time repair of track lengths saved in seconds into
 * millisecond fields, run against a real (in-memory) SQLite schema.
 */
class DurationUnitMigrationTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
    private val db = ParachordDb(driver)
    private val trackDao = TrackDao(db, driver)
    private val playlistTrackDao = PlaylistTrackDao(db)

    private fun track(id: String, duration: Long?, resolver: String? = null) =
        Track(id = id, title = id, artist = "A", duration = duration, resolver = resolver, addedAt = 1L)

    private fun pt(position: Int, duration: Long?, resolver: String? = null) = PlaylistTrack(
        playlistId = "applemusic-p1", position = position, trackTitle = "t$position", trackArtist = "A",
        trackDuration = duration, trackResolver = resolver,
    )

    private suspend fun durations() =
        trackDao.getById("seconds")?.duration to playlistTrackDao.getByPlaylistIdSync("applemusic-p1")
            .associate { it.position to it.trackDuration }

    @Test
    fun secondsBecomeMilliseconds_realMillisecondsAndLocalFilesAreLeftAlone() = runBlocking {
        trackDao.insert(track("seconds", 262))                       // hearted weekly track, 4:22
        trackDao.insert(track("ms", 262_000))                        // already correct
        trackDao.insert(track("local", 5_000, resolver = "localfiles")) // 5 s clip from MediaStore
        trackDao.insert(track("tiny", 5))                            // < floor: left alone on purpose
        trackDao.insert(track("unknown", null))
        playlistTrackDao.insertAll(
            listOf(pt(0, 301), pt(1, 301_000), pt(2, 4_000, resolver = "localfiles"), pt(3, 0)),
        )

        DurationUnitMigration.run(driver)

        assertEquals(262_000L, trackDao.getById("seconds")?.duration)
        assertEquals(262_000L, trackDao.getById("ms")?.duration)
        assertEquals(5_000L, trackDao.getById("local")?.duration)
        assertEquals(5L, trackDao.getById("tiny")?.duration)
        assertEquals(null, trackDao.getById("unknown")?.duration)
        assertEquals(
            mapOf(0 to 301_000L, 1 to 301_000L, 2 to 4_000L, 3 to 0L),
            playlistTrackDao.getByPlaylistIdSync("applemusic-p1").associate { it.position to it.trackDuration },
        )
    }

    @Test
    fun runningTwiceChangesNothing() = runBlocking {
        trackDao.insert(track("seconds", 12))   // 12 s → 12 000 ms, now outside the range
        playlistTrackDao.insertAll(listOf(pt(0, 9_999)))

        DurationUnitMigration.run(driver)
        val once = durations()
        DurationUnitMigration.run(driver)

        assertEquals(once, durations())
        assertEquals(12_000L to mapOf(0 to 9_999_000L), once)
    }

    @Test
    fun runOnceRunsOnlyTheFirstTime() = runBlocking {
        val kv = KvStore(MapSettings())
        assertTrue(DurationUnitMigration.runOnce(driver, kv))
        assertFalse(DurationUnitMigration.runOnce(driver, kv))
    }
}
