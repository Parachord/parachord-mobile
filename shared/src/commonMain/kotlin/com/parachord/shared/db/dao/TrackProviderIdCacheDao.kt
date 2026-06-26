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
        val missingStreak: Long = 0,
        val lastSeenAt: Long? = null,
    )

    suspend fun select(identityKey: String, providerId: String): Entry? =
        withContext(Dispatchers.Default) {
            queries.selectForKey(identityKey, providerId).executeAsOneOrNull()?.toEntry()
        }

    /**
     * Write/refresh the resolved-id + cooldown fields. PRESERVES the presence
     * fields (missingStreak / lastSeenAt) by reading the prior entry and carrying
     * them forward — INSERT OR REPLACE rewrites the whole row, and a hydration
     * re-stamp must never reset a streak. (Matches desktop's upsert-preserve.)
     */
    suspend fun upsert(
        identityKey: String,
        providerId: String,
        resolvedId: String?,
        lastAttemptAt: Long = currentTimeMillis(),
        attempts: Long,
    ): Unit = withContext(Dispatchers.Default) {
        // read-prev + write in ONE transaction so the carry-forward of the presence
        // fields can't lose a concurrent recordMissing increment (INSERT OR REPLACE
        // rewrites the whole row; the read must be atomic with the write).
        db.transaction {
            val prev = queries.selectForKey(identityKey, providerId).executeAsOneOrNull()
            queries.upsert(
                identityKey, providerId, resolvedId, lastAttemptAt, attempts,
                prev?.missingStreak ?: 0L,
                prev?.lastSeenAt,
            )
        }
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

    /**
     * Presence vote (missingStreak gate). A COMPLETE provider fetch HELD this
     * track → reset the streak + stamp [now]. No-op when there's no entry (a
     * never-materialized key is already protected by the resolvedId-null check).
     */
    suspend fun recordSeen(identityKey: String, providerId: String, now: Long = currentTimeMillis()): Unit =
        withContext(Dispatchers.Default) {
            queries.recordSeen(now, identityKey, providerId)
        }

    /**
     * Presence vote. A COMPLETE provider fetch OMITTED this track → step the
     * streak. No-op when there's no entry.
     */
    suspend fun recordMissing(identityKey: String, providerId: String): Unit =
        withContext(Dispatchers.Default) {
            queries.recordMissing(identityKey, providerId)
        }

    private fun com.parachord.shared.db.Track_provider_id_cache.toEntry() = Entry(
        identityKey = identityKey,
        providerId = providerId,
        resolvedId = resolvedId,
        lastAttemptAt = lastAttemptAt,
        attempts = attempts,
        missingStreak = missingStreak,
        lastSeenAt = lastSeenAt,
    )
}
