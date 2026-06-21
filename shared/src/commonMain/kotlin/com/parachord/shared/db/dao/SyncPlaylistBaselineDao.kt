package com.parachord.shared.db.dao

import com.parachord.shared.db.ParachordDb
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Concrete DAO wrapping SQLDelight SyncPlaylistBaselineQueries.
 *
 * The N-way 3-way-merge ancestor per playlist (Phase 1). [Baseline.tracks] is
 * the ordered list of canonical track keys; persisted as a JSON string array.
 * Keyed by localPlaylistId alone (one baseline per playlist).
 */
class SyncPlaylistBaselineDao(private val db: ParachordDb) {

    private val queries get() = db.syncPlaylistBaselineQueries
    private val json = Json { ignoreUnknownKeys = true }
    private val keyListSerializer = ListSerializer(String.serializer())

    data class Baseline(
        val localPlaylistId: String,
        val tracks: List<String>,
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
        tracks: List<String>,
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
