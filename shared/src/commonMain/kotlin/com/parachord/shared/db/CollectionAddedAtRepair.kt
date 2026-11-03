package com.parachord.shared.db

import app.cash.sqldelight.db.SqlDriver
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.store.KvStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-time repair of collection tracks, albums and artists saved with
 * `addedAt = 0` (#508), which sank to the bottom of newest-first lists
 * (Recent Loves, the collection) as if added in 1970. Paths that wrote 0:
 *
 * - User adds: hearting the playing track (Now Playing, the mini player,
 *   Android Auto) kept the queued track's `addedAt`; album and artist adds
 *   from cards and detail pages never set one. These now date the add
 *   (`collectionTrack`, `LibraryRepository.addAlbumToCollection` /
 *   `addArtistToCollection`), as desktop does. Old ones get the repair's
 *   time, since their real dates were never saved.
 * - Sync, where the provider gives no date: Spotify follows, Apple Music
 *   artists, and Apple Music library songs/albums without `dateAdded`. These
 *   now use the sync time, as desktop does. Old ones get their sync date, the
 *   earliest `sync_sources.syncedAt` (when the item was last synced; the
 *   first sync time wasn't kept), or the repair's time without one.
 *
 * Local files are left alone. The [DONE_KEY] flag makes it run once.
 */
object CollectionAddedAtRepair {
    const val DONE_KEY = "migration_collection_added_at_v1"

    /** Runs the repair unless it already ran. Returns true when it ran. */
    suspend fun runOnce(driver: SqlDriver, kvStore: KvStore, nowMs: Long = currentTimeMillis()): Boolean {
        if (kvStore.getBoolean(DONE_KEY)) return false
        run(driver, nowMs)
        kvStore.setBoolean(DONE_KEY, true)
        return true
    }

    suspend fun run(driver: SqlDriver, nowMs: Long) = withContext(Dispatchers.Default) {
        redate(driver, "tracks", "track", nowMs, "resolver IS NOT 'localfiles'")
        redate(driver, "albums", "album", nowMs, "id NOT LIKE 'local-%'")
        redate(driver, "artists", "artist", nowMs, null)
    }

    private fun redate(driver: SqlDriver, table: String, itemType: String, nowMs: Long, extra: String?) {
        driver.execute(
            null,
            "UPDATE $table SET addedAt = COALESCE(" +
                "(SELECT MIN(s.syncedAt) FROM sync_sources s " +
                "WHERE s.itemId = $table.id AND s.itemType = '$itemType' AND s.syncedAt > 0), $nowMs) " +
                "WHERE addedAt = 0" + (extra?.let { " AND $it" } ?: ""),
            0,
        )
    }
}
