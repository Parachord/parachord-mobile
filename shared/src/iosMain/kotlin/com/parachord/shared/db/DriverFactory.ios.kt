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
        // Idempotent column migrations for EXISTING installs (mirrors Android's
        // AndroidModule ALTER-TABLE bootstrap; the schema CREATE covers fresh
        // installs). We CHECK the column first via PRAGMA rather than
        // ALTER-and-catch: the catch worked, but SQLiter logs the thrown
        // "duplicate column" SQLiteException to the console on EVERY launch
        // (a scary stack trace for a non-error).
        //   #238: local-files ISRC. iOS has no local-file scanner yet, so the
        //   column stays null here — but the shared DAO reads it, so it must
        //   exist or `toTrack()` would crash on an upgraded DB.
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
