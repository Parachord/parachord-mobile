package com.parachord.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/**
 * iOS SQLite driver — creates/opens "parachord.db" in the app container.
 * New iOS installations start with the v12-equivalent schema directly.
 */
actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver = NativeSqliteDriver(
            schema = ParachordDb.Schema,
            name = "parachord.db",
        )
        // Idempotent bootstrap migrations for EXISTING installs — MIRRORS Android's
        // AndroidModule ALTER/CREATE bootstrap exactly. Fresh installs get all of
        // this from ParachordDb.Schema's CREATEs; existing iOS installs were created
        // before the N-way tables + columns were added and need explicit re-runs
        // (SQLite has no ADD COLUMN IF NOT EXISTS). Without it, the playlist `getAll`
        // SELECT references playlists.writable (etc.) on a DB that lacks it →
        // "no such column" → SIGABRT on the playlists screen. (iOS previously only
        // migrated tracks.isrc, so the rest were missing.)
        //
        // Tables — CREATE TABLE IF NOT EXISTS is inherently idempotent.
        driver.execute(null, "CREATE TABLE IF NOT EXISTS sync_playlist_link (localPlaylistId TEXT NOT NULL, providerId TEXT NOT NULL, externalId TEXT NOT NULL, syncedAt INTEGER NOT NULL, PRIMARY KEY (localPlaylistId, providerId))", 0)
        driver.execute(null, "CREATE TABLE IF NOT EXISTS sync_playlist_source (localPlaylistId TEXT NOT NULL PRIMARY KEY, providerId TEXT NOT NULL, externalId TEXT NOT NULL, snapshotId TEXT, ownerId TEXT, syncedAt INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE IF NOT EXISTS sync_playlist_baseline (localPlaylistId TEXT NOT NULL PRIMARY KEY, baseline TEXT NOT NULL, baselineSyncedAt INTEGER NOT NULL)", 0)
        driver.execute(null, "CREATE TABLE IF NOT EXISTS sync_playlist_nway (localPlaylistId TEXT NOT NULL, providerId TEXT NOT NULL, changeToken TEXT, editedAt INTEGER, lastSyncedAt INTEGER NOT NULL, PRIMARY KEY (localPlaylistId, providerId))", 0)
        driver.execute(null, "CREATE TABLE IF NOT EXISTS track_provider_id_cache (identityKey TEXT NOT NULL, providerId TEXT NOT NULL, resolvedId TEXT, lastAttemptAt INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (identityKey, providerId))", 0)
        // Columns — PRAGMA-checked, so nothing is logged on the already-migrated path.
        addColumnIfMissing(driver, table = "playlists", column = "sourceUrl", type = "TEXT")
        addColumnIfMissing(driver, table = "playlists", column = "sourceContentHash", type = "TEXT")
        addColumnIfMissing(driver, table = "playlists", column = "localOnly", type = "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(driver, table = "playlists", column = "writable", type = "INTEGER NOT NULL DEFAULT 1")
        addColumnIfMissing(driver, table = "sync_playlist_link", column = "snapshotId", type = "TEXT")
        addColumnIfMissing(driver, table = "sync_playlist_link", column = "pendingAction", type = "TEXT")
        addColumnIfMissing(driver, table = "playlist_tracks", column = "trackIsrc", type = "TEXT")
        addColumnIfMissing(driver, table = "playlist_tracks", column = "trackRecordingMbid", type = "TEXT")
        addColumnIfMissing(driver, table = "tracks", column = "crossResolverEnrichedAt", type = "INTEGER")
        addColumnIfMissing(driver, table = "tracks", column = "isrc", type = "TEXT")
        return driver
    }

    /** Add [column] to [table] only when it isn't already present — no exception,
     *  so nothing is logged on the (normal) already-migrated path. */
    private fun addColumnIfMissing(driver: SqlDriver, table: String, column: String, type: String) {
        val exists = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor: SqlCursor ->
                var found = false
                // PRAGMA table_info columns: cid(0), name(1), type(2), …
                while (cursor.next().value) {
                    if (cursor.getString(1) == column) { found = true; break }
                }
                QueryResult.Value(found)
            },
            parameters = 0,
        ).value
        if (!exists) {
            driver.execute(null, "ALTER TABLE $table ADD COLUMN $column $type", 0)
        }
    }
}
