package com.parachord.shared.db.dao

import com.parachord.shared.db.ParachordDb
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight SyncPlaylistNwayQueries.
 *
 * Per-(playlist, provider) N-way sync state (Phase 1) — change token + edit
 * time, kept separate from SyncPlaylistLinkDao so the live canonical-source
 * engine is untouched during migration/shadow mode. See SyncPlaylistNway.sq.
 */
class SyncPlaylistNwayDao(private val db: ParachordDb) {

    private val queries get() = db.syncPlaylistNwayQueries

    data class NwayState(
        val localPlaylistId: String,
        val providerId: String,
        val changeToken: String?,
        val editedAt: Long?,
        val lastSyncedAt: Long,
    )

    suspend fun selectAll(): List<NwayState> = withContext(Dispatchers.Default) {
        queries.selectAll().executeAsList().map { it.toState() }
    }

    suspend fun selectForLocal(localPlaylistId: String): List<NwayState> =
        withContext(Dispatchers.Default) {
            queries.selectForLocal(localPlaylistId).executeAsList().map { it.toState() }
        }

    suspend fun selectForLink(localPlaylistId: String, providerId: String): NwayState? =
        withContext(Dispatchers.Default) {
            queries.selectForLink(localPlaylistId, providerId).executeAsOneOrNull()?.toState()
        }

    suspend fun upsert(
        localPlaylistId: String,
        providerId: String,
        changeToken: String?,
        editedAt: Long?,
        lastSyncedAt: Long = currentTimeMillis(),
    ): Unit = withContext(Dispatchers.Default) {
        queries.upsert(localPlaylistId, providerId, changeToken, editedAt, lastSyncedAt)
    }

    suspend fun deleteForLink(localPlaylistId: String, providerId: String): Unit =
        withContext(Dispatchers.Default) {
            queries.deleteForLink(localPlaylistId, providerId)
        }

    suspend fun deleteForLocal(localPlaylistId: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteForLocal(localPlaylistId)
    }

    suspend fun deleteForProvider(providerId: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteForProvider(providerId)
    }

    private fun com.parachord.shared.db.Sync_playlist_nway.toState() = NwayState(
        localPlaylistId = localPlaylistId,
        providerId = providerId,
        changeToken = changeToken,
        editedAt = editedAt,
        lastSyncedAt = lastSyncedAt,
    )
}
