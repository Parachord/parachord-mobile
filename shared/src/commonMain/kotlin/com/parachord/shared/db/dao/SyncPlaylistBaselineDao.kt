package com.parachord.shared.db.dao

import com.parachord.shared.db.ParachordDb
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.sync.TrackKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

/**
 * Concrete DAO wrapping SQLDelight SyncPlaylistBaselineQueries.
 *
 * The N-way 3-way-merge ancestor per playlist. [Baseline.tracks] is the ordered
 * list of per-track [TrackKeys] (isrc/mbid/norm) — richer than a single key
 * string so the ancestor retains all tiers for cross-copy unification (Phase 4).
 * Persisted as a JSON array of TrackKeys objects. Keyed by localPlaylistId.
 *
 * Format note: the Phase-1/2 format stored a JSON array of single key STRINGS;
 * those decode to empty here and the migration re-seeds them (see
 * SyncEngine.migratePlaylistToNway).
 */
class SyncPlaylistBaselineDao(private val db: ParachordDb) {

    private val queries get() = db.syncPlaylistBaselineQueries
    private val json = Json { ignoreUnknownKeys = true }
    private val keyListSerializer = ListSerializer(TrackKeys.serializer())

    data class Baseline(
        val localPlaylistId: String,
        val tracks: List<TrackKeys>,
        val baselineSyncedAt: Long,
    )

    suspend fun selectAll(): List<Baseline> = withContext(Dispatchers.Default) {
        queries.selectAll().executeAsList().map { it.toBaseline() }
    }

    suspend fun selectForLocal(localPlaylistId: String): Baseline? =
        withContext(Dispatchers.Default) {
            queries.selectForLocal(localPlaylistId).executeAsOneOrNull()?.toBaseline()
        }

    suspend fun upsert(
        localPlaylistId: String,
        tracks: List<TrackKeys>,
        baselineSyncedAt: Long = currentTimeMillis(),
    ): Unit = withContext(Dispatchers.Default) {
        queries.upsert(localPlaylistId, json.encodeToString(keyListSerializer, tracks), baselineSyncedAt)
    }

    suspend fun deleteForLocal(localPlaylistId: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteForLocal(localPlaylistId)
    }

    private fun com.parachord.shared.db.Sync_playlist_baseline.toBaseline() = Baseline(
        localPlaylistId = localPlaylistId,
        tracks = runCatching { json.decodeFromString(keyListSerializer, baseline) }.getOrDefault(emptyList()),
        baselineSyncedAt = baselineSyncedAt,
    )
}
