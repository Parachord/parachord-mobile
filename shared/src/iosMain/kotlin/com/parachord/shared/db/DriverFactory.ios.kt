package com.parachord.shared.db

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
        // installs, where this then throws "duplicate column" and is swallowed).
        //   #238: local-files ISRC. iOS has no local-file scanner yet, so the
        //   column stays null here — but the shared DAO reads it, so it must exist
        //   or `toTrack()` would crash on an upgraded DB.
        try {
            driver.execute(null, "ALTER TABLE tracks ADD COLUMN isrc TEXT", 0)
        } catch (_: Exception) {
            // Column already present.
        }
        return driver
    }
}
