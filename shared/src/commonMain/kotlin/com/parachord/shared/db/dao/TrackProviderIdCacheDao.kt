package com.parachord.shared.db.dao

import com.parachord.shared.db.ParachordDb
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight TrackProviderIdCacheQueries.
 *
 * Negative cache for provider-id hydration (incremental-materialization Task 7).
 * Per (identityKey, providerId) it remembers the last hydration attempt — the
 * resolved native id (or null on miss), when, and how many consecutive failures —
 * so [HydrationCoordinator] can short-circuit a known id, honor a cooldown on a
 * repeated miss, and never re-search a track every cycle. See
 * TrackProviderIdCache.sq.
 */
class TrackProviderIdCacheDao(private val db: ParachordDb) {

    private val queries get() = db.trackProviderIdCacheQueries

    data class Entry(
        val identityKey: String,
        val providerId: String,
        val resolvedId: String?,
        val lastAttemptAt: Long,
        val attempts: Long,
    )

    suspend fun select(identityKey: String, providerId: String): Entry? =
        withContext(Dispatchers.Default) {
            queries.selectForKey(identityKey, providerId).executeAsOneOrNull()?.toEntry()
        }

    suspend fun upsert(
        identityKey: String,
        providerId: String,
        resolvedId: String?,
        lastAttemptAt: Long = currentTimeMillis(),
        attempts: Long,
    ): Unit = withContext(Dispatchers.Default) {
        queries.upsert(identityKey, providerId, resolvedId, lastAttemptAt, attempts)
    }

    /**
     * Stale unresolved (resolvedId IS NULL) entries past [cutoff], fewest-failed
     * first. The swap's background drain uses this to top-up hydration off the hot
     * sync path. [limit] bounds the batch.
     */
    suspend fun selectStale(cutoff: Long, limit: Long): List<Entry> =
        withContext(Dispatchers.Default) {
            queries.selectStaleCandidates(cutoff, limit).executeAsList().map { it.toEntry() }
        }

    suspend fun deleteForProvider(providerId: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteForProvider(providerId)
    }

    private fun com.parachord.shared.db.Track_provider_id_cache.toEntry() = Entry(
        identityKey = identityKey,
        providerId = providerId,
        resolvedId = resolvedId,
        lastAttemptAt = lastAttemptAt,
        attempts = attempts,
    )
}
