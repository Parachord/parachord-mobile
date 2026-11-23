package com.parachord.shared.db

/**
 * Track rows whose playlist is gone. Before [com.parachord.shared.db.dao.PlaylistDao.delete]
 * deleted a playlist's tracks with it, removing a playlist left its tracks behind
 * (SQLite's foreign keys are off, so the schema's cascade never ran). Both apps
 * run [DELETE_ORPHANS] when the database is opened, before anything reads or
 * syncs, so the rows that are left go and a playlist can take an id an old one
 * used. Every writer of `playlist_tracks` writes to a stored playlist, so a
 * track row with no playlist is never in use.
 */
object OrphanPlaylistTracks {
    const val DELETE_ORPHANS =
        "DELETE FROM playlist_tracks WHERE playlistId NOT IN (SELECT id FROM playlists)"
}
