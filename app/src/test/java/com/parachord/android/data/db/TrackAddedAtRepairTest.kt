package com.parachord.android.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.TrackAddedAtRepair
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.model.Track
import com.parachord.shared.store.KvStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #508 — tracks saved with addedAt = 0: hearts get the repair's date, synced songs their sync date, local files nothing. */
class TrackAddedAtRepairTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
    private val trackDao = TrackDao(ParachordDb(driver), driver)
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

        TrackAddedAtRepair.run(driver, now)

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
        assertTrue(TrackAddedAtRepair.runOnce(driver, kv, now))
        assertFalse(TrackAddedAtRepair.runOnce(driver, kv, now))
    }
}
