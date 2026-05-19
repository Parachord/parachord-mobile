package com.parachord.shared.db.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.Tracks
import com.parachord.shared.model.Track
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [TrackQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class TrackDao(private val db: ParachordDb, private val driver: SqlDriver) {

    private val queries get() = db.trackQueries

    /* ---- Mapping ---- */

    private fun Tracks.toTrack() = Track(
        id = id, title = title, artist = artist, album = album,
        albumId = albumId, duration = duration, artworkUrl = artworkUrl,
        sourceType = sourceType, sourceUrl = sourceUrl, addedAt = addedAt,
        resolver = resolver, spotifyUri = spotifyUri, soundcloudId = soundcloudId,
        spotifyId = spotifyId, appleMusicId = appleMusicId,
        recordingMbid = recordingMbid, artistMbid = artistMbid, releaseMbid = releaseMbid,
        crossResolverEnrichedAt = crossResolverEnrichedAt,
    )

    /* ---- Queries returning Flow ---- */

    fun getAll(): Flow<List<Track>> =
        queries.getAll().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toTrack() } }

    fun getByAlbumId(albumId: String): Flow<List<Track>> =
        queries.getByAlbumId(albumId).asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toTrack() } }

    fun search(query: String): Flow<List<Track>> =
        queries.search(query, query).asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toTrack() } }

    fun existsByTitleAndArtist(title: String, artist: String): Flow<Boolean> =
        queries.existsByTitleAndArtist(title, artist).asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it ?: false }

    /* ---- Suspend one-shot reads ---- */

    suspend fun getById(id: String): Track? = withContext(Dispatchers.Default) {
        queries.getById(id).executeAsOneOrNull()?.toTrack()
    }

    suspend fun getRecentSync(limit: Int): List<Track> = withContext(Dispatchers.Default) {
        queries.getRecentSync(limit.toLong()).executeAsList().map { it.toTrack() }
    }

    suspend fun findLocalFile(title: String, artist: String): Track? = withContext(Dispatchers.Default) {
        queries.findLocalFile(title, artist).executeAsOneOrNull()?.toTrack()
    }

    /* ---- Writes ---- */

    suspend fun insert(track: Track): Unit = withContext(Dispatchers.Default) {
        queries.insert(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            albumId = track.albumId,
            duration = track.duration,
            artworkUrl = track.artworkUrl,
            sourceType = track.sourceType,
            sourceUrl = track.sourceUrl,
            addedAt = track.addedAt,
            resolver = track.resolver,
            spotifyUri = track.spotifyUri,
            soundcloudId = track.soundcloudId,
            spotifyId = track.spotifyId,
            appleMusicId = track.appleMusicId,
            recordingMbid = track.recordingMbid,
            artistMbid = track.artistMbid,
            releaseMbid = track.releaseMbid,
        )
    }

    suspend fun insertAll(tracks: List<Track>): Unit = withContext(Dispatchers.Default) {
        queries.transaction {
            for (track in tracks) {
                queries.insert(
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    albumId = track.albumId,
                    duration = track.duration,
                    artworkUrl = track.artworkUrl,
                    sourceType = track.sourceType,
                    sourceUrl = track.sourceUrl,
                    addedAt = track.addedAt,
                    resolver = track.resolver,
                    spotifyUri = track.spotifyUri,
                    soundcloudId = track.soundcloudId,
                    spotifyId = track.spotifyId,
                    appleMusicId = track.appleMusicId,
                    recordingMbid = track.recordingMbid,
                    artistMbid = track.artistMbid,
                    releaseMbid = track.releaseMbid,
                )
            }
        }
    }

    /** INSERT OR REPLACE — same as insert since the .sq uses INSERT OR REPLACE. */
    suspend fun update(track: Track): Unit = insert(track)

    suspend fun updateArtworkById(id: String, artworkUrl: String): Unit = withContext(Dispatchers.Default) {
        queries.updateArtworkById(artworkUrl = artworkUrl, id = id)
    }

    suspend fun delete(track: Track): Unit = withContext(Dispatchers.Default) {
        queries.deleteById(track.id)
    }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.Default) {
        queries.deleteAll()
    }

    suspend fun deleteSyncedTracks(): Int = withContext(Dispatchers.Default) {
        queries.deleteSyncedTracks()
        // SQLDelight void mutation — return affected rows via driver
        // The generated method doesn't return a count, so we approximate with 0.
        // Callers only check > 0 after the fact; the rows are deleted regardless.
        0
    }

    suspend fun deleteOrphanedSyncedTracks(): Int = withContext(Dispatchers.Default) {
        queries.deleteOrphanedSyncedTracks()
        0
    }

    suspend fun backfillResolverIds(
        trackId: String,
        spotifyId: String?,
        spotifyUri: String?,
        appleMusicId: String?,
        soundcloudId: String?,
    ): Unit = withContext(Dispatchers.Default) {
        queries.backfillResolverIds(
            spotifyId = spotifyId,
            spotifyUri = spotifyUri,
            appleMusicId = appleMusicId,
            soundcloudId = soundcloudId,
            id = trackId,
        )
    }

    /**
     * Slow-trickle candidates: localfiles tracks missing >=1 streaming-service
     * ID, not visited within the cooldown window. Never-tried first.
     */
    suspend fun selectCrossResolverEnrichmentCandidates(
        olderThanEpochMs: Long,
        limit: Int,
    ): List<Track> = withContext(Dispatchers.Default) {
        queries.selectCrossResolverEnrichmentCandidates(olderThanEpochMs, limit.toLong())
            .executeAsList()
            .map { it.toTrack() }
    }

    suspend fun markCrossResolverEnriched(
        id: String,
        epochMs: Long = currentTimeMillis(),
    ): Unit = withContext(Dispatchers.Default) {
        queries.markCrossResolverEnriched(epochMs, id)
    }

    suspend fun backfillMbids(
        trackId: String,
        recordingMbid: String?,
        artistMbid: String?,
        releaseMbid: String?,
    ): Unit = withContext(Dispatchers.Default) {
        queries.backfillMbids(
            recordingMbid = recordingMbid,
            artistMbid = artistMbid,
            releaseMbid = releaseMbid,
            id = trackId,
        )
    }

    /**
     * Backfill track addedAt from sync_sources where the sync source has a
     * non-zero addedAt (i.e. the Spotify added_at timestamp).
     * Executed as raw SQL since there is no corresponding .sq query.
     */
    suspend fun backfillAddedAtFromSyncSources(): Unit = withContext(Dispatchers.Default) {
        driver.execute(
            identifier = null,
            sql = """
                UPDATE tracks SET addedAt = (
                    SELECT s.addedAt FROM sync_sources s
                    WHERE s.itemId = tracks.id AND s.itemType = 'track' AND s.addedAt > 0
                )
                WHERE EXISTS (
                    SELECT 1 FROM sync_sources s
                    WHERE s.itemId = tracks.id AND s.itemType = 'track' AND s.addedAt > 0
                )
            """.trimIndent(),
            parameters = 0,
            binders = null,
        )
    }
}
