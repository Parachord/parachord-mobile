package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.deeplink.ProtocolTrack
import com.parachord.shared.platform.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "PoolRefiller"

/**
 * Refill loop for Mode C (pool-based) spinoff
 * (`parachord://play/radio?refill=…`).
 *
 * Encapsulates the rate-limit, empty-counter, dedup, and ID-stamping
 * state so [PlaybackController] only needs to wire the trigger and
 * append the result.
 *
 * Behavior (matches issue #121 Task 5 spec):
 *  - **Trigger:** caller invokes [tryRefill] when the pool drops below
 *    its threshold (currently 3). [canRefill] gates the actual fetch.
 *  - **Soft rate-limit:** minimum 5 seconds between fetches
 *    ([rateLimitMs]). The boundary is `>=` so two fetches at exactly
 *    5s apart both go through cleanly without an off-by-one.
 *  - **Stop condition:** 3 consecutive empty/error refills ([maxEmpty])
 *    halts the loop. The counter resets to 0 on any fresh tracks.
 *    HTTP errors / fetcher exceptions count as empty (the catch in
 *    [tryRefill] swallows non-cancellation throws and treats them as a
 *    zero-length fetch result, which then trivially "all dedupes" and
 *    increments the empty counter).
 *  - **Dedup against existing pool:** by `recordingMbid` (lowercase)
 *    →  `(artist|title)` (lowercase). [TrackEntity] has no `isrc`
 *    field, so ISRC dedup is documented-not-implemented — fresh tracks
 *    that share an MBID-and-only-MBID with the pool's existing rows
 *    will be filtered, but ISRC-only matches go through (callers can
 *    upgrade if/when [TrackEntity] grows an `isrc` column). If every
 *    refilled track dedupes, the result counts as empty.
 *  - **State reset:** [resetCounters] clears the empty counter +
 *    last-fetch timestamp. [PlaybackController] calls this from both
 *    `startPoolBasedSpinoff()` (so a fresh pool doesn't inherit the
 *    previous pool's stop condition / rate-limit window) and
 *    `exitSpinoff()` (which also nulls `refillUrl` directly). The
 *    [fetcher] reference is process-lifetime — set once by
 *    `PlayRadioDispatcher` at construction and preserved across
 *    spinoff exit / re-enter.
 *
 * The [clock] parameter is a `() -> Long` injection seam so tests can
 * advance time deterministically without sleeping. Production callers
 * pass `System::currentTimeMillis`.
 */
class PoolRefiller(
    private val clock: () -> Long = System::currentTimeMillis,
    val rateLimitMs: Long = REFILL_RATE_LIMIT_MS,
    val maxEmpty: Int = REFILL_MAX_EMPTY,
) {
    // Written from outside [tryRefill] (PlaybackController.startPoolBasedSpinoff
    // / setPoolFetcher), read inside [canRefill] / [tryRefill]. Keep
    // @Volatile for cross-thread visibility.
    @Volatile var fetcher: (suspend (String) -> List<ProtocolTrack>)? = null
    @Volatile var refillUrl: String? = null

    // Mutated only inside [tryRefill]. The mutex below provides both
    // atomicity (no torn read-modify-write on emptyCount += 1) AND
    // visibility, so @Volatile would be redundant.
    private var emptyCount: Int = 0
    private var lastFetchTs: Long = 0L

    // Serializes the gate-check + state-update + fetch path so concurrent
    // callers can't race a compound emptyCount/lastFetchTs update. In
    // practice the rate-limit gate already serializes calls in production,
    // but the mutex makes the contract explicit.
    private val refillMutex = Mutex()

    /** Visible for tests + log surfaces; not part of the operational API. */
    val emptyCountForTest: Int get() = emptyCount
    val lastFetchTsForTest: Long get() = lastFetchTs

    /**
     * Reset only the per-pool counters (empty counter + last-fetch
     * timestamp). Call from `startPoolBasedSpinoff()` so a fresh pool
     * doesn't inherit the previous pool's stop condition or rate-limit
     * window — but the [fetcher] reference (process-lifetime) and any
     * already-set [refillUrl] (caller will overwrite anyway) are
     * preserved.
     *
     * `exitSpinoff()` clears the per-pool state directly (sets
     * [refillUrl] to null + calls this method) and intentionally does
     * NOT clear the [fetcher] — it's process-lifetime, set once by
     * `PlayRadioDispatcher` and reused across spinoff exit/re-enter.
     */
    fun resetCounters() {
        emptyCount = 0
        lastFetchTs = 0L
    }

    /**
     * True iff a fetch is allowed to fire at [clock]'s current value.
     *
     * Gates: refillUrl + fetcher both non-null, rate-limit window has
     * elapsed, and we haven't hit the consecutive-empty stop condition.
     */
    fun canRefill(): Boolean {
        if (refillUrl == null || fetcher == null) return false
        if (emptyCount >= maxEmpty) return false
        val elapsed = clock() - lastFetchTs
        return elapsed >= rateLimitMs
    }

    /**
     * Attempt one refill. Returns the deduped + tagged entities ready
     * for the caller to append to the spinoff pool, or `null` if:
     *   - rate-limited / stopped / not configured ([canRefill] returned
     *     false), OR
     *   - the fetch returned (or threw to) zero fresh tracks, OR
     *   - all returned tracks deduped against [currentPoolSnapshot].
     *
     * Side-effects:
     *   - [lastFetchTs] is set to `clock()` BEFORE the fetch starts
     *     (rate-limit applies even on failed fetches).
     *   - [emptyCount] increments on empty/error/fully-deduped results,
     *     and resets to 0 on a non-empty deduped result.
     *
     * Caller is responsible for actually appending the returned list to
     * the spinoff pool and pre-warming via `TrackResolverCache`.
     */
    suspend fun tryRefill(currentPoolSnapshot: List<TrackEntity>): List<TrackEntity>? =
        refillMutex.withLock {
            if (!canRefill()) return@withLock null
            val url = refillUrl ?: return@withLock null
            val f = fetcher ?: return@withLock null

            // Set timestamp BEFORE the fetch — a failing fetch still
            // burns the rate-limit budget. Without this, an HTTP timeout
            // in a busy refill loop would re-fire immediately on the
            // next pool-drop tick.
            lastFetchTs = clock()

            val fresh: List<ProtocolTrack> = try {
                f(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pool refill fetch failed: ${e.message}")
                emptyList()
            }

            val deduped = dedupAgainstPool(fresh, currentPoolSnapshot)
            if (deduped.isEmpty()) {
                emptyCount += 1
                Log.d(TAG, "Pool refill: empty (count=$emptyCount/$maxEmpty)")
                return@withLock null
            }

            emptyCount = 0
            val ts = clock()
            val entities = deduped.mapIndexed { idx, t ->
                TrackEntity(
                    id = "protocol-radio-$ts-$idx",
                    title = t.title,
                    artist = t.artist,
                    album = t.album,
                )
            }
            Log.d(TAG, "Pool refill: ${entities.size} fresh tracks ready to append")
            entities
        }

    companion object {
        const val REFILL_RATE_LIMIT_MS: Long = 5_000L
        const val REFILL_MAX_EMPTY: Int = 3

        /**
         * Filter [fresh] to entries not already present in
         * [pool] by:
         *   1. `recordingMbid` (case-insensitive), then
         *   2. `(artist|title)` (case-insensitive).
         *
         * [TrackEntity] has no `isrc` field, so ISRC-only dedup is
         * not implemented — see KDoc on the class.
         */
        fun dedupAgainstPool(
            fresh: List<ProtocolTrack>,
            pool: List<TrackEntity>,
        ): List<ProtocolTrack> {
            if (fresh.isEmpty()) return emptyList()
            val mbids: Set<String> = pool
                .mapNotNull { it.recordingMbid?.lowercase() }
                .toSet()
            val titleArtists: Set<String> = pool
                .map { "${it.artist.lowercase()}|${it.title.lowercase()}" }
                .toSet()
            return fresh.filter { t ->
                val mbid = t.mbid
                when {
                    mbid != null && mbid.lowercase() in mbids -> false
                    "${t.artist.lowercase()}|${t.title.lowercase()}" in titleArtists -> false
                    else -> true
                }
            }
        }
    }
}
