package com.parachord.shared.db.dao

import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.Sync_sources
import com.parachord.shared.model.SyncSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [SyncSourceQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class SyncSourceDao(private val db: ParachordDb) {

    private val queries get() = db.syncSourceQueries

    /* ---- Mapping ---- */

    private fun Sync_sources.toSyncSource() = SyncSource(
        itemId = itemId,
        itemType = itemType,
        providerId = providerId,
        externalId = externalId,
        addedAt = addedAt,
        syncedAt = syncedAt,
    )

    /* ---- Suspend one-shot reads ---- */

    suspend fun getByItem(itemId: String, itemType: String): List<SyncSource> = withContext(Dispatchers.Default) {
        queries.getByItem(itemId, itemType).executeAsList().map { it.toSyncSource() }
    }

    suspend fun getByProvider(providerId: String, itemType: String): List<SyncSource> =
        withContext(Dispatchers.Default) {
            queries.getByProvider(providerId, itemType).executeAsList().map { it.toSyncSource() }
        }

    /** Every provider's sync sources of one type (library sync's row matching, #682). */
    suspend fun getByType(itemType: String): List<SyncSource> = withContext(Dispatchers.Default) {
        queries.getByType(itemType).executeAsList().map { it.toSyncSource() }
    }

    suspend fun getAllByProvider(providerId: String): List<SyncSource> = withContext(Dispatchers.Default) {
        queries.getAllByProvider(providerId).executeAsList().map { it.toSyncSource() }
    }

    suspend fun countByProvider(providerId: String, itemType: String): Int = withContext(Dispatchers.Default) {
        queries.countByProvider(providerId, itemType).executeAsOne().toInt()
    }

    suspend fun get(itemId: String, itemType: String, providerId: String): SyncSource? =
        withContext(Dispatchers.Default) {
            queries.getByKey(itemId, itemType, providerId).executeAsOneOrNull()?.toSyncSource()
        }

    suspend fun getMostRecentByProvider(providerId: String, itemType: String): SyncSource? =
        withContext(Dispatchers.Default) {
            queries.getMostRecentByProvider(providerId, itemType).executeAsOneOrNull()?.toSyncSource()
        }

    /* ---- Writes ---- */

    suspend fun insert(syncSource: SyncSource): Unit = withContext(Dispatchers.Default) {
        queries.insert(
            itemId = syncSource.itemId,
            itemType = syncSource.itemType,
            providerId = syncSource.providerId,
            externalId = syncSource.externalId,
            addedAt = syncSource.addedAt,
            syncedAt = syncSource.syncedAt,
        )
    }

    suspend fun insertAll(syncSources: List<SyncSource>): Unit = withContext(Dispatchers.Default) {
        queries.transaction {
            for (syncSource in syncSources) {
                queries.insert(
                    itemId = syncSource.itemId,
                    itemType = syncSource.itemType,
                    providerId = syncSource.providerId,
                    externalId = syncSource.externalId,
                    addedAt = syncSource.addedAt,
                    syncedAt = syncSource.syncedAt,
                )
            }
        }
    }

    suspend fun delete(syncSource: SyncSource): Unit = withContext(Dispatchers.Default) {
        queries.deleteByKey(
            itemId = syncSource.itemId,
            itemType = syncSource.itemType,
            providerId = syncSource.providerId,
        )
    }

    suspend fun deleteByKey(itemId: String, itemType: String, providerId: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteByKey(itemId, itemType, providerId)
    }

    suspend fun deleteAllForItem(itemId: String, itemType: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteAllForItem(itemId, itemType)
    }

    suspend fun deleteAllForProvider(providerId: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteAllForProvider(providerId)
    }

    /** Remove orphaned sync_sources for tracks that no longer exist. */
    suspend fun deleteOrphanedTrackSources(): Int = withContext(Dispatchers.Default) {
        queries.deleteOrphanedTrackSources()
        // SQLDelight void mutation doesn't return affected row count.
        0
    }

    suspend fun deleteByProviderAndType(providerId: String, itemType: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteByProviderAndType(providerId, itemType)
    }
}
