package com.parachord.shared.db

import app.cash.sqldelight.db.SqlDriver
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.store.KvStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-time repair of collection tracks saved with `addedAt = 0` (#508), which
 * sank to the bottom of the collection and Recent Loves (both sorted newest
 * first) as if added in 1970. Two paths wrote 0:
 *
 * - Hearting the playing track (Now Playing, the mini player, Android Auto)
 *   kept the queued track's `addedAt`. New loves are now dated now
 *   (`collectionTrack`); these get the repair's time, since their real dates
 *   were never saved.
 * - Apple Music library sync, for a song Apple gives no `dateAdded`. It now
 *   uses the sync time, as desktop does; these get their sync date, the
 *   earliest `sync_sources.syncedAt` (when the song was last synced, since
 *   the first sync time wasn't kept), or the repair's time without one.
 *
 * Local files are left alone. The [DONE_KEY] flag makes it run once.
 */
object TrackAddedAtRepair {
    const val DONE_KEY = "migration_track_added_at_v1"

    /** Runs the repair unless it already ran. Returns true when it ran. */
    suspend fun runOnce(driver: SqlDriver, kvStore: KvStore, nowMs: Long = currentTimeMillis()): Boolean {
        if (kvStore.getBoolean(DONE_KEY)) return false
        run(driver, nowMs)
        kvStore.setBoolean(DONE_KEY, true)
        return true
    }

    suspend fun run(driver: SqlDriver, nowMs: Long) = withContext(Dispatchers.Default) {
        driver.execute(
            null,
            "UPDATE tracks SET addedAt = COALESCE(" +
                "(SELECT MIN(s.syncedAt) FROM sync_sources s " +
                "WHERE s.itemId = tracks.id AND s.itemType = 'track' AND s.syncedAt > 0), $nowMs) " +
                "WHERE addedAt = 0 AND resolver IS NOT 'localfiles'",
            0,
        )
    }
}
