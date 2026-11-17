package com.parachord.shared.db

/**
 * The one-time step that took local files out of the collection (#705). Both
 * apps run it right after adding `tracks.inCollection` to an existing
 * database, so it runs once, on the launch that adds the column.
 *
 * Before #705 a local file scan saved every file into the collection, so each
 * one showed a lit heart and counted as a love (and love push's backfill sent
 * them all to ListenBrainz / Last.fm). A scanned file's heart was always on, so
 * none of them was hearted on purpose: every scanned row (Android `local-`,
 * iOS `local-` and `fileimport-`) becomes library-only. Rows from sync or a
 * user's heart (`spotify-…`, `applemusic-…`, catalog ids) are untouched.
 */
object LocalLibraryMigration {
    const val ADD_COLUMN = "ALTER TABLE tracks ADD COLUMN inCollection INTEGER NOT NULL DEFAULT 1"

    const val MARK_SCANNED_LOCAL_FILES_LIBRARY_ONLY =
        "UPDATE tracks SET inCollection = 0 WHERE resolver = 'localfiles' " +
            "AND (id LIKE 'local-%' OR id LIKE 'fileimport-%')"
}
