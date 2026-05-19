package com.parachord.android.playback.handlers

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.concurrent.Volatile

/**
 * Concurrency + rate-limit gate for Apple Music catalog API calls made
 * through the MusicKit JS bridge.
 *
 * Apple's catalog API (`api.music.apple.com/v1/catalog/{storefront}/...`) is
 * aggressively throttled per dev-token / IP. Once a burst trips the throttle,
 * subsequent calls — including the `/songs/{id}` lookup that
 * [MusicKitWebBridge.play] does internally — fail with
 * `MusicDataRequest.Error 1`, killing the active play path too.
 *
 * Mirrors desktop's `nativeMusicKitLimiter` (parachord-desktop/CLAUDE.md
 * "Apple Music catalog API IS rate-limited — throttle parallel calls"):
 *
 *  - **Concurrency cap** ([MAX_CONCURRENT]): at most 3 in-flight catalog calls.
 *  - **Inter-request delay** ([INTER_REQUEST_DELAY_MS]): 150 ms minimum gap
 *    between starts. Spreads bursts thin enough that Apple's edge throttle
 *    doesn't see a single-second spike.
 *  - **Circuit breaker**: after [CONSECUTIVE_429_THRESHOLD] consecutive
 *    rate-limit signals, opens for [COOLDOWN_MS] (5 minutes). In-cooldown
 *    [runThrottled] calls short-circuit returning null without hitting the
 *    bridge. Auto-resets after the cooldown expires.
 *
 * The kill-switch is **time-bound**, not session-permanent. One transient
 * catalog throttle shouldn't disable Apple Music for the rest of the
 * session — that's the corollary from the desktop docs.
 */
class MusicKitCatalogLimiter {

    companion object {
        private const val TAG = "MusicKitCatalogLimiter"
        const val MAX_CONCURRENT: Int = 3
        const val INTER_REQUEST_DELAY_MS: Long = 150L
        const val CONSECUTIVE_429_THRESHOLD: Int = 3
        const val COOLDOWN_MS: Long = 5L * 60L * 1000L  // 5 min
    }

    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val timestampMutex = Mutex()

    /** Last permit-acquisition epoch-ms; the next acquirer sleeps to honor [INTER_REQUEST_DELAY_MS]. */
    @Volatile
    private var lastStartEpochMs: Long = 0L

    @Volatile
    private var consecutive429s: Int = 0

    @Volatile
    private var cooldownUntilMs: Long = 0L

    /**
     * Run [block] under the gate. Returns null when the kill-switch is active
     * (the caller treats that as "no result"). Otherwise acquires a permit,
     * sleeps to honor the inter-request gap, and runs [block].
     *
     * On a rate-limit signal in [block]'s result, the caller invokes
     * [reportRateLimit]. On success, [reportSuccess]. The limiter doesn't
     * inspect [block]'s return value itself.
     */
    suspend fun <T> runThrottled(block: suspend () -> T): T? {
        val now = currentTimeMillis()
        if (now < cooldownUntilMs) {
            val remaining = cooldownUntilMs - now
            Log.d(TAG, "Catalog limiter in cooldown for ${remaining / 1000}s — short-circuiting")
            return null
        }
        return semaphore.withPermit {
            // Inter-request delay. Synchronized via a mutex so two coroutines
            // that grab permits simultaneously space themselves correctly.
            timestampMutex.withLock {
                val sinceLast = currentTimeMillis() - lastStartEpochMs
                if (sinceLast < INTER_REQUEST_DELAY_MS) {
                    delay(INTER_REQUEST_DELAY_MS - sinceLast)
                }
                lastStartEpochMs = currentTimeMillis()
            }
            block()
        }
    }

    /**
     * Caller invokes when [block]'s result indicates a rate-limit (the JS
     * response carried `MusicDataRequest.Error 1`, `429`, or `rate-limit`-ish
     * error text). Trips the circuit breaker after [CONSECUTIVE_429_THRESHOLD]
     * consecutive signals.
     */
    fun reportRateLimit() {
        consecutive429s += 1
        if (consecutive429s >= CONSECUTIVE_429_THRESHOLD) {
            val until = currentTimeMillis() + COOLDOWN_MS
            cooldownUntilMs = until
            Log.w(TAG, "Catalog rate-limit threshold reached ($consecutive429s consecutive) — opening cooldown until $until (${COOLDOWN_MS / 1000}s)")
        }
    }

    /** Caller invokes on success to reset the 429 counter. */
    fun reportSuccess() {
        if (consecutive429s > 0) {
            Log.d(TAG, "Catalog success after $consecutive429s consecutive rate-limit(s) — reset")
        }
        consecutive429s = 0
    }

    /**
     * Heuristic: does this response string look like a rate-limit signal?
     * MusicKit JS surfaces three distinct strings depending on which layer
     * tripped (the catalog HTTP 429, MusicKit's internal `MusicDataRequest`
     * error wrapping, or a JS-side network timeout that often correlates
     * with throttling).
     */
    fun looksLikeRateLimit(errorString: String?): Boolean {
        if (errorString.isNullOrBlank()) return false
        val lc = errorString.lowercase()
        return "musicdatarequest.error 1" in lc
            || "429" in lc
            || "rate-limit" in lc
            || "rate limit" in lc
            || "too many requests" in lc
    }

    /** Test hook — exposed for unit tests that need to inspect internal state. */
    internal val consecutive429sForTest: Int get() = consecutive429s
    internal val cooldownUntilMsForTest: Long get() = cooldownUntilMs
}
