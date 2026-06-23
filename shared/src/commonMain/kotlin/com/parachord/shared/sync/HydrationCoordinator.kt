package com.parachord.shared.sync

import com.parachord.shared.db.dao.TrackProviderIdCacheDao
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException

/**
 * Per-cycle, budgeted, cached, cooldown-gated provider-id resolution
 * (incremental-materialization Task 7b).
 *
 * The materialize executor needs a `resolveNativeId: (PlaylistTrack) -> String?`
 * lambda to hydrate ADD tracks to a provider's native id. The naive version
 * re-searches EVERY unresolved track on EVERY sync — no memory of failure — which
 * fans out one catalog search per track per cycle and trips Spotify's / iTunes's
 * account-wide abuse window. This coordinator is the persistent, budgeted
 * substrate that fixes that. The swap (next task) creates ONE coordinator per
 * `runNwayPropagation` cycle and passes [resolve] as `resolveNativeId`.
 *
 * Resolution order for [resolve]:
 *  1. **Already on the row** — `provider.nativeIdOf(track)` non-blank ⇒ done, no
 *     search, no cache touch.
 *  2. **Cache identity** — `canonicalTrackKey(track)` (isrc>mbid>norm). The SAME
 *     song keys identically across services, so a hit found via Spotify on one
 *     cycle is reused for the same track on a later cycle.
 *  3. **Cache hit** — a stored non-blank `resolvedId` ⇒ return it, no search.
 *  4. **Cooldown** — a prior MISS within the (2-step 7d→30d) cooldown window
 *     ⇒ return null WITHOUT re-searching. This is the death-spiral fix: an
 *     un-findable track is asked about less and less often, not every cycle.
 *  5. **Per-cycle budget** — at most [maxInlineLookups] live searches per
 *     coordinator instance; over budget ⇒ return null (the track stays pending and
 *     a later cycle / background drain picks it up).
 *  6. **Search** — `provider.searchForTrackId(...)`. A 429 surfaces as null here
 *     (the provider's own RateLimitGate already gates the burst); we record the
 *     miss + bump `attempts` so the cooldown grows.
 *  7. **Cache write + additive row backfill** — always stamp the attempt; on a hit
 *     also call [persistRowId] (the null-only COALESCE backfill onto the row).
 *
 * Not thread-safe by design — one instance per sync cycle, driven from the
 * single-threaded propagation loop.
 */
class HydrationCoordinator(
    private val cache: TrackProviderIdCacheDao,
    /** Max live catalog searches per coordinator instance (per sync cycle). */
    private val maxInlineLookups: Int = 12,
    /** Clock source — injectable so the cooldown is unit-testable. */
    private val nowMs: () -> Long = { currentTimeMillis() },
    /**
     * Additive null-only persistence of a resolved id onto the track row (e.g.
     * `PlaylistTrackDao.backfillResolverIds`). Default no-op so the coordinator is
     * usable from contexts that don't persist (tests, the cache being enough).
     */
    private val persistRowId: suspend (track: PlaylistTrack, providerId: String, nativeId: String) -> Unit = { _, _, _ -> },
) {
    private var inlineUsed = 0

    /** Resolve [track] to [provider]'s native id, or null (pending / not found). */
    suspend fun resolve(provider: SyncProvider, track: PlaylistTrack): String? {
        // 1. Already on the row — nothing to do.
        provider.nativeIdOf(track)?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. Identity key (strongest tier isrc>mbid>norm).
        val key = canonicalTrackKey(track)
        val entry = cache.select(key, provider.id)

        // 3. Cache hit — a previously-resolved id, reused across cycles.
        if (!entry?.resolvedId.isNullOrBlank()) return entry!!.resolvedId

        // 4. Cooldown — a recent MISS is NOT re-searched within its window.
        if (entry != null && nowMs() - entry.lastAttemptAt < cooldownMs(entry.attempts)) return null

        // 5. Per-cycle budget — over budget, leave it pending for a later cycle.
        if (inlineUsed >= maxInlineLookups) return null
        inlineUsed++

        val attempts = (entry?.attempts ?: 0L) + 1L
        // 6. Live search. A 429 surfaces as null (the provider's RateLimitGate
        //    already throttled the burst); we still record the miss so the
        //    cooldown escalates. CancellationException must propagate.
        val id = try {
            provider.searchForTrackId(track.trackTitle, track.trackArtist, track.trackAlbum, track.trackIsrc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        // 7. Always stamp the attempt (records a miss for the cooldown); on a hit
        //    also additively backfill the row.
        cache.upsert(key, provider.id, id, nowMs(), attempts)
        if (!id.isNullOrBlank()) persistRowId(track, provider.id, id)
        return id
    }

    companion object {
        /**
         * Cooldown after a miss, as a 2-step cliff so an un-findable track is
         * re-tried less often: 7 days on the first miss (attempts ≤ 1), then 30
         * days for every miss thereafter. Not a continuously-growing curve.
         */
        fun cooldownMs(attempts: Long): Long =
            if (attempts <= 1L) 7L * 24 * 3600_000 else 30L * 24 * 3600_000
    }
}
