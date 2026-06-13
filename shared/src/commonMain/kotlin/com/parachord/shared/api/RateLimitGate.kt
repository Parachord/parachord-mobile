package com.parachord.shared.api

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import io.ktor.client.statement.HttpResponse
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Per-client rate-limit gate. Provides a single source of truth for
 *   1. **Cooldown** — when the upstream service has signaled a 429 (or 503
 *      for some APIs), all in-flight callers fail fast against this gate
 *      until the suggested `Retry-After` window elapses.
 *   2. **Concurrency limit** — a [Semaphore] caps simultaneous calls to a
 *      conservative number (default 2). This prevents the post-cooldown
 *      burst that would otherwise re-trip the throttle as soon as queued
 *      tasks all fire at once.
 *   3. **Inter-request delay** — a 150ms sleep before each call (after
 *      acquiring the permit). Same pacing AM uses in
 *      `AppleMusicSyncProvider.searchForTrackId` (commit `16884d1`).
 *
 * **Why this exists.** The KMP migration's Phase 9E.1.* cutovers (Apr 28,
 * 2026) swapped Retrofit/OkHttp for Ktor across SpotifyClient,
 * LastFmClient, MusicBrainzClient, and others. The Retrofit interceptor
 * chain had implicit 429/5xx retry on each. Ktor doesn't, so 429s now
 * surface as `NoTransformationFoundException` from the body parser
 * (empty 429 bodies don't deserialize into the expected typed body).
 * This gate is the typed mid-tier follow-up that
 * `SpotifySyncProvider.withRetry`'s KDoc explicitly promised after the
 * cutover. Mirrors the AM-side `ItunesRateLimitedException` pattern from
 * commit `16884d1`.
 *
 * **Design choices:**
 *  - `MAX_COOLDOWN_MS = 1 hour`: respect the upstream's `Retry-After`
 *    even when it's 30+ minutes. Capping below it is actively
 *    counterproductive — if Spotify says wait 1997s and we cap at 120s,
 *    the next call after 120s trips a fresh 429 (Spotify's window hasn't
 *    closed) and we loop indefinitely. 1 hour is a safety guardrail, not
 *    a typical case.
 *  - Single global cooldown (not per-method): rate limits are usually
 *    account/IP-wide, so one endpoint's 429 means all calls 429.
 *  - Logs first 429 of each cooldown cycle only, never per-call thereafter
 *    — a 288-track resolver fan-out would otherwise emit hundreds of
 *    identical lines.
 *
 * @param tag log tag for diagnostics (typically the client name).
 * @param maxConcurrent simultaneous in-flight permit count. Lower for
 *   stricter providers (MusicBrainz publishes 1 RPS; we use 1 + a longer
 *   delay there).
 * @param interRequestDelayMs sleep between successive calls inside the
 *   permit. Helps stagger bursts.
 * @param defaultCooldownSec used when the response has no `Retry-After`.
 * @param maxCooldownMs hard cap (defense against misbehaving servers).
 * @param loadCooldownEpochMs optional callback invoked once at construction
 *   to rehydrate the cooldown across process restarts. When the upstream
 *   service hands us a long `Retry-After` (Spotify's abuse window can be
 *   3600s), an in-memory-only gate would erase the cooldown on the next
 *   process restart and probe the server cold — Spotify often responds with
 *   a *fresh* 3600s, restarting the user's punishment clock. Persisting the
 *   epoch-ms timestamp lets the gate honor the original window across
 *   restarts and stop pestering an already-angry upstream. Pass null on
 *   tests / clients where persistence isn't needed.
 * @param saveCooldownEpochMs optional callback fired (fire-and-forget) on
 *   every cooldown write. Paired with [loadCooldownEpochMs].
 */
class RateLimitGate(
    private val tag: String,
    maxConcurrent: Int = 2,
    private val interRequestDelayMs: Long = 150L,
    private val defaultCooldownSec: Long = 30L,
    private val maxCooldownMs: Long = 60L * 60L * 1000L,
    loadCooldownEpochMs: (() -> Long)? = null,
    private val saveCooldownEpochMs: (suspend (Long) -> Unit)? = null,
    /**
     * Escalating circuit breaker. When true, each CONSECUTIVE rate-limited
     * response doubles the backoff (`base * 2^(strikes-1)`, capped at
     * [maxCooldownMs]); the first non-rate-limited response resets the
     * counter. Default false → flat backoff (the original behavior;
     * LastFm/MusicBrainz keep it).
     *
     * Why Spotify opts in: its abuse-mode ban routinely outlasts a flat 1h
     * cooldown. With a flat cap, every time our local cooldown lapses the
     * next call re-pokes the still-banned account and Spotify re-extends its
     * window — so it never clears. Escalation pushes our local cooldown past
     * Spotify's window after a few consecutive strikes, so we stop poking and
     * the ban can decay. Pair with a higher [maxCooldownMs] (e.g. 6h).
     */
    private val escalateOnRepeat: Boolean = false,
    /**
     * PROACTIVE rolling-window rate cap (opt-in). When non-null, the gate keeps
     * a sliding window of recent request timestamps and DELAYS the next call
     * until it would no longer exceed [maxRequestsPerWindow] within
     * [requestWindowMs] — so we stay under a service's published ceiling
     * *before* a 429 instead of reacting after one.
     *
     * Why Spotify opts in: Developer-Mode limit is a rolling-30s window that
     * caps around ~180–200 req/min. The reactive cooldown + 150ms gap alone
     * permit ~400/min sustained, so a cold-cache browse can blow the ceiling.
     * Sized conservatively (e.g. 75 / 30s ≈ 150/min) to leave headroom — the
     * limit is on the SHARED `client_id` across the whole Parachord fleet, so a
     * single device must under-shoot to leave room for other clients. This is a
     * per-device backstop; the primary lever is fewer calls (#211 hint-skip).
     * Null → off (LastFm/MusicBrainz keep the reactive-only behavior).
     */
    private val maxRequestsPerWindow: Int? = null,
    private val requestWindowMs: Long = 30_000L,
    /**
     * Clock source (epoch ms). Injectable so tests can drive the cooldown
     * deterministically; production passes the real [currentTimeMillis].
     */
    private val nowMs: () -> Long = { currentTimeMillis() },
) {
    private val limiter = Semaphore(maxConcurrent)

    /** Sliding window of recent request epoch-ms timestamps for the proactive
     *  [maxRequestsPerWindow] cap. Guarded by [windowMutex] — reserving a slot
     *  (and any wait for one) is serialized, which IS the throttle. Bounded by
     *  [maxRequestsPerWindow] (we prune before adding), so it stays tiny. */
    private val windowMutex = Mutex()
    private val requestTimestamps = mutableListOf<Long>()

    /** Consecutive rate-limited responses with no intervening success.
     *  Drives [escalateOnRepeat]. Reset to 0 on any non-rate-limited response. */
    @Volatile
    private var consecutiveStrikes: Int = 0

    /** Background scope for fire-and-forget cooldown persistence writes.
     *  Kept tiny — only ever does a single `setLong` per 429 cycle. */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Epoch-ms timestamp at which calls may resume. `@Volatile` so writes from
     *  one coroutine are seen on others without a memory-model surprise.
     *  Hydrated from disk via [loadCooldownEpochMs] at construction so
     *  process restarts inherit any active server-side cooldown rather than
     *  cold-probing into a fresh punishment cycle. */
    @Volatile
    private var cooldownUntilMs: Long = loadCooldownEpochMs?.invoke() ?: 0L

    /**
     * Run [block] under the gate. Throws via [exceptionFactory] without
     * making a network call if the cooldown is active. Otherwise acquires
     * a permit, sleeps the inter-request delay, runs [block], and on a
     * rate-limited response (per [isRateLimited]) sets the cooldown +
     * throws.
     *
     * @param isRateLimited matches 429 by default; pass a different
     *   predicate for APIs that signal throttling differently (e.g.
     *   MusicBrainz uses 503 + `Retry-After`).
     * @param exceptionFactory builds the typed exception to throw. Receives
     *   the `Retry-After` value in seconds (or null if absent), so callers
     *   can encode it in their own typed exceptions.
     */
    suspend fun <T> withPermit(
        isRateLimited: (HttpResponse) -> Boolean = { it.status.value == 429 },
        exceptionFactory: (retryAfterSeconds: Long?) -> Exception,
        block: suspend () -> T,
    ): T {
        checkCooldown(exceptionFactory)
        return limiter.withPermit {
            checkCooldown(exceptionFactory)
            awaitWindowSlot()
            delay(interRequestDelayMs)
            block()
        }
    }

    /**
     * Proactive rolling-window throttle (opt-in via [maxRequestsPerWindow]).
     * Reserves a slot in the sliding [requestWindowMs] window, DELAYING until
     * the window has room so the request rate stays under the cap. No-op when
     * [maxRequestsPerWindow] is null. The window-full wait is held under
     * [windowMutex] so concurrent callers serialize on the same budget.
     */
    private suspend fun awaitWindowSlot() {
        val max = maxRequestsPerWindow ?: return
        windowMutex.withLock {
            while (true) {
                val now = nowMs()
                val cutoff = now - requestWindowMs
                // Prune timestamps that have aged out of the window (a request
                // exactly requestWindowMs old is outside the rolling window).
                while (requestTimestamps.isNotEmpty() && requestTimestamps[0] <= cutoff) {
                    requestTimestamps.removeAt(0)
                }
                if (requestTimestamps.size < max) {
                    requestTimestamps.add(now)
                    return
                }
                // Window full — wait until the oldest entry ages out, then recheck.
                val waitMs = requestTimestamps[0] + requestWindowMs - now
                if (waitMs > 0) delay(waitMs)
            }
        }
    }

    /**
     * Check if the most recent response indicates rate-limiting, and if
     * so set the cooldown. Call this AFTER the request returns (inside the
     * [withPermit] block), passing the response. Returns `false` if NOT
     * rate-limited (caller proceeds normally) or throws if rate-limited
     * (caller doesn't reach the `body()` decode step).
     */
    fun handleResponse(
        response: HttpResponse,
        isRateLimited: (HttpResponse) -> Boolean = { it.status.value == 429 },
        exceptionFactory: (retryAfterSeconds: Long?) -> Exception,
    ) {
        if (!isRateLimited(response)) {
            // A clean response ends any consecutive-strike streak. Critical for
            // the escalating breaker: a recovered call must drop the backoff
            // back to base so a single later 429 doesn't inherit a long cooldown.
            consecutiveStrikes = 0
            return
        }
        consecutiveStrikes++
        val retryAfterSec = response.headers["Retry-After"]?.toLongOrNull()
        val baseMs = (retryAfterSec ?: defaultCooldownSec) * 1000L
        // Escalate: double per consecutive strike (base * 2^(strikes-1)), capped.
        // Shift is bounded so the Long can't overflow before the cap clamps it.
        val backoffMs = if (escalateOnRepeat) {
            val shift = (consecutiveStrikes - 1).coerceIn(0, 32)
            (baseMs shl shift).coerceAtMost(maxCooldownMs)
        } else {
            baseMs.coerceAtMost(maxCooldownMs)
        }
        val wasAlreadyLimited = nowMs() < cooldownUntilMs
        val newCooldown = nowMs() + backoffMs
        cooldownUntilMs = newCooldown
        // Persist (fire-and-forget) so the cooldown survives a process
        // restart. See class KDoc for why this matters with abuse-window
        // upstreams that hand out 3600s `Retry-After` values.
        saveCooldownEpochMs?.let { save ->
            persistScope.launch { runCatching { save(newCooldown) } }
        }
        if (!wasAlreadyLimited) {
            val strikeNote = if (escalateOnRepeat && consecutiveStrikes > 1) " (strike #$consecutiveStrikes, escalated)" else ""
            Log.w(tag, "$tag rate-limited (HTTP ${response.status.value}). Backing off ${backoffMs / 1000}s$strikeNote; subsequent calls in this window will short-circuit.")
        }
        throw exceptionFactory(retryAfterSec)
    }

    private fun checkCooldown(exceptionFactory: (retryAfterSeconds: Long?) -> Exception) {
        val now = nowMs()
        if (now < cooldownUntilMs) {
            throw exceptionFactory(((cooldownUntilMs - now + 999L) / 1000L).coerceAtLeast(1L))
        }
    }

    /**
     * Milliseconds remaining on the active cooldown, or 0 when clear.
     *
     * Read-only — does NOT acquire a permit or make a call. Lets UNGATED
     * call paths (e.g. Spotify Connect device/playback endpoints, which
     * don't run through [withPermit]) honor the same cooldown the gated
     * search path enforces. Without this, those paths keep hitting an
     * already-penalized account during an abuse window, and Spotify's
     * rolling window keeps handing back a fresh `Retry-After` that re-arms
     * the cooldown — so it never clears. See the ResolverManager
     * `ensureTokensFresh` KDoc for the original Android post-mortem.
     */
    fun remainingCooldownMs(): Long = (cooldownUntilMs - nowMs()).coerceAtLeast(0L)
}
