package com.parachord.shared.db.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.Playlists
import com.parachord.shared.model.Playlist
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [PlaylistQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class PlaylistDao(private val db: ParachordDb) {

    private val queries get() = db.playlistQueries

    /* ---- Mapping ---- */

    private fun Playlists.toPlaylist() = Playlist(
        id = id,
        name = name,
        description = description,
        artworkUrl = artworkUrl,
        trackCount = trackCount.toInt(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        spotifyId = spotifyId,
        snapshotId = snapshotId,
        lastModified = lastModified,
        locallyModified = locallyModified != 0L,
        ownerName = ownerName,
        sourceUrl = sourceUrl,
        sourceContentHash = sourceContentHash,
        localOnly = localOnly != 0L,
        writable = writable != 0L,
    )

    /* ---- Queries returning Flow ---- */

    fun getAll(): Flow<List<Playlist>> =
        queries.getAll().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toPlaylist() } }

    fun getByIdFlow(id: String): Flow<Playlist?> =
        queries.getById(id).asFlow().mapToOneOrNull(Dispatchers.Default).map { it?.toPlaylist() }

    /* ---- Suspend one-shot reads ---- */

    suspend fun getById(id: String): Playlist? = withContext(Dispatchers.Default) {
        queries.getById(id).executeAsOneOrNull()?.toPlaylist()
    }

    /**
     * Read-only view joining a playlist against its Spotify `sync_playlist_link` row.
     * Per Decision 5 of the multi-provider sync plan: we keep the legacy
     * `playlists.spotifyId` / `playlists.snapshotId` scalar columns for UI and share
     * code, but new sync paths should prefer this view — link-table values win when
     * present, falling back to the legacy scalars only when no link row exists.
     *
     * This keeps `sync_playlist_link` as the single source of truth for the Spotify
     * mirror state without forcing every reader to JOIN.
     */
    data class PlaylistWithLink(
        val entity: Playlist,
        private val linkSpotifyId: String?,
        private val linkSnapshotId: String?,
    ) {
        val spotifyId: String? get() = linkSpotifyId ?: entity.spotifyId
        val snapshotId: String? get() = linkSnapshotId ?: entity.snapshotId
    }

    suspend fun getByIdWithSpotifyLink(id: String): PlaylistWithLink? = withContext(Dispatchers.Default) {
        queries.getByIdWithSpotifyLink(
            id,
        ) { rowId, name, description, artworkUrl, trackCount, createdAt, updatedAt,
            spotifyId, snapshotId, lastModified, locallyModified, ownerName, sourceUrl,
            sourceContentHash, localOnly, writable, linkSpotifyId, linkSnapshotId ->
            PlaylistWithLink(
                entity = Playlist(
                    id = rowId,
                    name = name,
                    description = description,
                    artworkUrl = artworkUrl,
                    trackCount = trackCount.toInt(),
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    spotifyId = spotifyId,
                    snapshotId = snapshotId,
                    lastModified = lastModified,
                    locallyModified = locallyModified != 0L,
                    ownerName = ownerName,
                    sourceUrl = sourceUrl,
                    sourceContentHash = sourceContentHash,
                    localOnly = localOnly != 0L,
                    writable = writable != 0L,
                ),
                linkSpotifyId = linkSpotifyId,
                linkSnapshotId = linkSnapshotId,
            )
        }.executeAsOneOrNull()
    }

    suspend fun getBySpotifyId(spotifyId: String): Playlist? = withContext(Dispatchers.Default) {
        queries.getBySpotifyId(spotifyId).executeAsOneOrNull()?.toPlaylist()
    }

    suspend fun getAllSync(): List<Playlist> = withContext(Dispatchers.Default) {
        queries.getAll().executeAsList().map { it.toPlaylist() }
    }

    /* ---- Writes ---- */

    suspend fun insert(playlist: Playlist): Unit = withContext(Dispatchers.Default) {
        queries.insert(
            id = playlist.id,
            name = playlist.name,
            description = playlist.description,
            artworkUrl = playlist.artworkUrl,
            trackCount = playlist.trackCount.toLong(),
            createdAt = playlist.createdAt,
            updatedAt = playlist.updatedAt,
            spotifyId = playlist.spotifyId,
            snapshotId = playlist.snapshotId,
            lastModified = playlist.lastModified,
            locallyModified = if (playlist.locallyModified) 1L else 0L,
            ownerName = playlist.ownerName,
            sourceUrl = playlist.sourceUrl,
            sourceContentHash = playlist.sourceContentHash,
            localOnly = if (playlist.localOnly) 1L else 0L,
            writable = if (playlist.writable) 1L else 0L,
        )
    }

    /** #269: refresh the writable flag in place (the pull skips unchanged rows). */
    suspend fun setWritable(id: String, writable: Boolean): Unit = withContext(Dispatchers.Default) {
        queries.setWritable(if (writable) 1L else 0L, id)
    }

    suspend fun getHosted(): List<Playlist> = withContext(Dispatchers.Default) {
        queries.getHosted().executeAsList().map { it.toPlaylist() }
    }

    /**
     * `getHosted` uses `SELECT * FROM playlists WHERE sourceUrl IS NOT NULL`, which
     * makes SQLDelight generate a bespoke `GetHosted` row type with `sourceUrl: String`
     * (non-null) instead of reusing `Playlists`. Map back through the same shape as
     * `Playlists.toPlaylist()` so every reader returns the same `Playlist`.
     */
    private fun com.parachord.shared.db.GetHosted.toPlaylist() = Playlist(
        id = id,
        name = name,
        description = description,
        artworkUrl = artworkUrl,
        trackCount = trackCount.toInt(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        spotifyId = spotifyId,
        snapshotId = snapshotId,
        lastModified = lastModified,
        locallyModified = locallyModified != 0L,
        ownerName = ownerName,
        sourceUrl = sourceUrl,
        sourceContentHash = sourceContentHash,
        localOnly = localOnly != 0L,
        writable = writable != 0L,
    )

    suspend fun updateHostedSnapshot(
        playlistId: String,
        contentHash: String,
        trackCount: Int,
        lastModified: Long = currentTimeMillis(),
    ): Unit = withContext(Dispatchers.Default) {
        queries.updateHostedSnapshot(
            sourceContentHash = contentHash,
            trackCount = trackCount.toLong(),
            lastModified = lastModified,
            updatedAt = lastModified,
            id = playlistId,
        )
    }

    /** INSERT OR REPLACE — same as insert since the .sq uses INSERT OR REPLACE. */
    suspend fun update(playlist: Playlist): Unit = insert(playlist)

    suspend fun updateArtworkById(id: String, artworkUrl: String): Unit = withContext(Dispatchers.Default) {
        queries.updateArtworkById(artworkUrl = artworkUrl, id = id)
    }

    suspend fun clearArtworkById(id: String): Unit = withContext(Dispatchers.Default) {
        queries.clearArtworkById(id = id)
    }

    suspend fun backfillLastModified(): Unit = withContext(Dispatchers.Default) {
        queries.backfillLastModified()
    }

    /** All playlists with `locallyModified = 1`. Used by Phase 3's post-push clear. */
    suspend fun getLocallyModified(): List<Playlist> = withContext(Dispatchers.Default) {
        queries.getLocallyModified().executeAsList().map { it.toPlaylist() }
    }

    /** Unset `locallyModified` for the given playlist without touching any other column. */
    suspend fun clearLocallyModified(id: String): Unit = withContext(Dispatchers.Default) {
        queries.clearLocallyModified(id)
    }

    suspend fun delete(playlist: Playlist): Unit = withContext(Dispatchers.Default) {
        queries.deleteById(playlist.id)
    }
}
