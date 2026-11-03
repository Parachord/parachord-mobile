package com.parachord.android.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.CollectionAddedAtRepair
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.AlbumDao
import com.parachord.shared.db.dao.ArtistDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.model.Album
import com.parachord.shared.model.Artist
import com.parachord.shared.model.Track
import com.parachord.shared.store.KvStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #508 — collection rows saved with addedAt = 0: user adds get the repair's date, synced rows their sync date, local files nothing. */
class CollectionAddedAtRepairTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
    private val db = ParachordDb(driver)
    private val trackDao = TrackDao(db, driver)
    private val albumDao = AlbumDao(db)
    private val artistDao = ArtistDao(db)
    private val now = 1_800_000_000_000L

    private suspend fun addedAt(id: String) = trackDao.getById(id)?.addedAt

    @Test
    fun zeroDatesAreRedated() = runBlocking {
        trackDao.insert(Track(id = "heart", title = "Run Away From the Mob", artist = "Queens of the Stone Age", resolver = "applemusic"))
        trackDao.insert(Track(id = "heart-no-resolver", title = "June", artist = "Flipturn"))
        trackDao.insert(Track(id = "applemusic-i.abc", title = "Synced", artist = "A", resolver = "applemusic"))
        driver.execute(null, "INSERT INTO sync_sources (itemId, itemType, providerId, externalId, syncedAt) VALUES ('applemusic-i.abc', 'track', 'applemusic', 'i.abc', 1750000000000)", 0)
        driver.execute(null, "INSERT INTO sync_sources (itemId, itemType, providerId, externalId, syncedAt) VALUES ('applemusic-i.abc', 'track', 'spotify', 'sp', 1760000000000)", 0)
        trackDao.insert(Track(id = "applemusic-i.never", title = "Never synced stamp", artist = "D", resolver = "applemusic"))
        driver.execute(null, "INSERT INTO sync_sources (itemId, itemType, providerId, externalId) VALUES ('applemusic-i.never', 'track', 'applemusic', 'i.never')", 0)
        trackDao.insert(Track(id = "local", title = "Local", artist = "B", resolver = "localfiles"))
        trackDao.insert(Track(id = "dated", title = "Dated", artist = "C", resolver = "spotify", addedAt = 1_700_000_000_000L))

        CollectionAddedAtRepair.run(driver, now)

        assertEquals(now, addedAt("heart"))
        assertEquals(now, addedAt("heart-no-resolver"))
        assertEquals(1_750_000_000_000L, addedAt("applemusic-i.abc")) // earliest sync
        assertEquals(now, addedAt("applemusic-i.never")) // synced, but no sync time recorded
        assertEquals(0L, addedAt("local"))
        assertEquals(1_700_000_000_000L, addedAt("dated"))
    }

    @Test
    fun runOnceRunsOnlyTheFirstTime() = runBlocking {
        val kv = KvStore(MapSettings())
        assertTrue(CollectionAddedAtRepair.runOnce(driver, kv, now))
        assertFalse(CollectionAddedAtRepair.runOnce(driver, kv, now))
    }

    @Test
    fun albumsAndArtistsAreRedatedToo() = runBlocking {
        albumDao.insert(Album(id = "album-1-2", title = "Added from a card", artist = "A"))
        albumDao.insert(Album(id = "local-album-9", title = "Local", artist = "B"))
        artistDao.insert(Artist(id = "spotify-xyz", name = "Followed"))
        driver.execute(null, "INSERT INTO sync_sources (itemId, itemType, providerId, externalId, syncedAt) VALUES ('spotify-xyz', 'artist', 'spotify', 'xyz', 1750000000000)", 0)
        artistDao.insert(Artist(id = "manual-1", name = "Hearted"))

        CollectionAddedAtRepair.run(driver, now)

        assertEquals(now, albumDao.getById("album-1-2")?.addedAt)
        assertEquals(0L, albumDao.getById("local-album-9")?.addedAt)
        assertEquals(1_750_000_000_000L, artistDao.getById("spotify-xyz")?.addedAt)
        assertEquals(now, artistDao.getById("manual-1")?.addedAt)
    }
}
