package com.parachord.shared.db

import app.cash.sqldelight.db.SqlDriver
import com.parachord.shared.store.KvStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-time repair of track lengths saved in **seconds** into fields that are
 * milliseconds (#349). Before the fix, the ListenBrainz weekly/home builders, the
 * Last.fm recommendations feed and Apple Music playlist sync stored seconds, and
 * those values were persisted:
 *
 * - `playlist_tracks.trackDuration` — Apple Music-synced playlists, saved weekly
 *   playlists;
 * - `tracks.duration` — tracks hearted from a weekly playlist or a recommendation.
 *
 * It matters beyond display: playback seeds the engine's length from these
 * (Apple Music's stall-vs-end guard, Spotify's near-end check), so 262 "ms" for a
 * 4:22 song can turn a mid-song stall into a skip.
 *
 * **The rule:** multiply values in `[SECONDS_FLOOR, SECONDS_CEILING)` = [10, 10 000)
 * by 1000. A song length in seconds lands there (10 s – 2.8 h); a real
 * millisecond length only does for a track under 10 s. Local files are skipped:
 * both scanners store the OS's millisecond length and short clips are common there.
 *
 * **Idempotent by construction:** a converted value is ≥ 10 000, outside the
 * range, so a second run changes nothing. The [DONE_KEY] flag only saves the two
 * UPDATEs on later launches. Values 1–9 are left alone on purpose — converting
 * them would land inside the range and break idempotency, and a sub-10-second
 * song is negligible.
 */
object DurationUnitMigration {
    const val DONE_KEY = "migration_duration_ms_v1"
    const val SECONDS_FLOOR = 10L
    const val SECONDS_CEILING = 10_000L

    /** Runs the repair unless it already ran. Returns true when it ran. */
    suspend fun runOnce(driver: SqlDriver, kvStore: KvStore): Boolean {
        if (kvStore.getBoolean(DONE_KEY)) return false
        run(driver)
        kvStore.setBoolean(DONE_KEY, true)
        return true
    }

    /** The two UPDATEs. Safe to call repeatedly (see the class KDoc). */
    suspend fun run(driver: SqlDriver) = withContext(Dispatchers.Default) {
        driver.execute(
            null,
            """
            UPDATE playlist_tracks SET trackDuration = trackDuration * 1000
            WHERE trackDuration >= $SECONDS_FLOOR AND trackDuration < $SECONDS_CEILING
              AND COALESCE(trackResolver, '') != 'localfiles'
            """.trimIndent(),
            0,
        )
        driver.execute(
            null,
            """
            UPDATE tracks SET duration = duration * 1000
            WHERE duration >= $SECONDS_FLOOR AND duration < $SECONDS_CEILING
              AND COALESCE(resolver, '') != 'localfiles'
            """.trimIndent(),
            0,
        )
    }
}
