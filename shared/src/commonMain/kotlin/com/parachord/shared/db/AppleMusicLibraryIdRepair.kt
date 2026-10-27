package com.parachord.shared.db

import app.cash.sqldelight.db.SqlDriver
import com.parachord.shared.store.KvStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-time repair of Apple Music LIBRARY ids saved where a CATALOG id belongs
 * (#265). Apple Music sync used to store `playParams.id`, which for a library
 * item is the library id (`i.…`, older `l.…`), as the track's Apple Music id.
 * The catalog rejects those, so the ISRC lookup found nothing and iOS couldn't
 * play the track through Apple Music. One install had 2045 of them.
 *
 * Sync now stores `playParams.catalogId`, but existing rows only refresh when
 * their playlist changes on Apple Music. This clears the library ids from
 * `playlist_tracks.trackAppleMusicId` and `tracks.appleMusicId`, leaving those
 * rows like any other track without an Apple Music id: the resolver pipeline
 * finds the catalog id by search and back-fills it (null-only), and playback
 * resolves on the fly meanwhile.
 *
 * Only the Apple Music id column is touched. Row ids and sync_sources keep the
 * library id on purpose: that's the sync key, and deleting from the library
 * needs it.
 *
 * Idempotent: once cleared, nothing matches again. The [DONE_KEY] flag only
 * saves the UPDATEs on later launches.
 */
object AppleMusicLibraryIdRepair {
    const val DONE_KEY = "migration_am_library_ids_v1"

    /** SQL predicate: [column] holds an Apple Music library id (`i.…` / `l.…`). */
    private fun isLibraryId(column: String) = "($column LIKE 'i.%' OR $column LIKE 'l.%')"

    /** Runs the repair unless it already ran. Returns true when it ran. */
    suspend fun runOnce(driver: SqlDriver, kvStore: KvStore): Boolean {
        if (kvStore.getBoolean(DONE_KEY)) return false
        run(driver)
        kvStore.setBoolean(DONE_KEY, true)
        return true
    }

    /** The two UPDATEs. Safe to call repeatedly. */
    suspend fun run(driver: SqlDriver) = withContext(Dispatchers.Default) {
        driver.execute(
            null,
            "UPDATE playlist_tracks SET trackAppleMusicId = NULL WHERE " + isLibraryId("trackAppleMusicId"),
            0,
        )
        driver.execute(
            null,
            "UPDATE tracks SET appleMusicId = NULL WHERE " + isLibraryId("appleMusicId"),
            0,
        )
    }
}
