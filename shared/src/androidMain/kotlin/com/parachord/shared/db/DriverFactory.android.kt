package com.parachord.shared.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Android SQLite driver — opens the existing Room "parachord.db" file.
 *
 * The schema matches Room v12. Existing users' databases are opened
 * in-place without data migration — same tables, same columns, same file.
 *
 * **WAL + busy_timeout are load-bearing (the recurring sync-wedge fix).**
 * The default `AndroidSqliteDriver` opens the DB in rollback-journal mode with
 * an INFINITE busy-wait and a single serialized writer. The sync writer
 * (`SyncEngine.applyTrackDiff`'s `insertAll` / the dedup migration) and the
 * concurrent friend-refresh writer (`FriendsRepository.updateCachedTrack`, on
 * `Dispatchers.Default`, OUTSIDE `syncMutex`) race that single writer lock. The
 * loser parks inside `SQLiteConnection.nativeExecute` (JNI) — which is NOT a
 * coroutine suspension point — so `withTimeout` cannot cancel it, the sync
 * `finally` never runs, and `syncMutex` is held forever ("sync already in
 * progress" with no watchdog log).
 *  - **WAL** lets reader Flow re-queries stop blocking the writer, shrinking the
 *    contention window.
 *  - **`busy_timeout`** bounds the writer-lock wait so a contended write throws a
 *    fast, catchable `SQLITE_BUSY` instead of parking in native code forever.
 */
actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = ParachordDb.Schema,
            context = context,
            name = "parachord.db",
            callback = object : AndroidSqliteDriver.Callback(ParachordDb.Schema) {
                // Set both modes via raw PRAGMAs in onOpen (outside any transaction,
                // on the already-open connection). Do NOT use
                // `db.enableWriteAheadLogging()` in onConfigure — it re-enters
                // getWritableDatabase recursively and crashes the DB open. `query`
                // + `moveToFirst()` forces each PRAGMA to actually execute (a
                // returned-but-unread Cursor would no-op).
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    // WAL (persistent): readers don't block the single writer.
                    db.query("PRAGMA journal_mode=WAL;").use { it.moveToFirst() }
                    // busy_timeout (per-connection): a contended write waits up to
                    // 5s then throws a catchable SQLITE_BUSY instead of parking in
                    // native JNI forever (the non-cancellable sync wedge).
                    db.query("PRAGMA busy_timeout=5000;").use { it.moveToFirst() }
                }
            },
        )
}
