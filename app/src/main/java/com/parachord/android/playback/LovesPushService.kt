package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.scrobbler.Scrobbler
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "LovesPushService"

/** Inter-request delay in the backfill loop. Mirrors desktop's 1 req/sec
 *  rate-limit (`runLoveBackfill` in app.js) — keeps both LB and LFM happy
 *  even when the user has hundreds of collection items. */
private const val BACKFILL_INTER_REQUEST_DELAY_MS = 1000L

/**
 * Orchestrates the loved-tracks push to per-service scrobblers (issue #125).
 *
 * Two entry points:
 * - [pushLoved] — single-track, fire-and-forget. Called from
 *   `LibraryRepository.addToCollection` when the user toggles a track to
 *   "loved." Skips silently when both per-service toggles are off OR the
 *   track has already been pushed to all enabled services (idempotency).
 * - [runBackfill] — bulk, sequential. Walks the user's existing collection
 *   tracks, applies the same per-service / per-track filter, paces requests
 *   at 1 per second, writes the idempotency cache after each success so
 *   resume-on-crash works. Exposes [backfillState] for the Settings UI.
 *
 * Architectural notes:
 * - One-way: love only, no unlove. Mirrors desktop's
 *   `2026-05-03-loved-tracks-scrobbler-push-design.md`.
 * - Per-service `Scrobbler.loveTrack` throws on hard errors. We catch +
 *   log + leave the idempotency cache untouched so the next sync gets
 *   another shot.
 * - LB requires a 36-char-UUID MBID; the LB scrobbler implementation
 *   handles the mapper fallback internally and silently skips if no MBID
 *   resolves. LFM only needs artist + title.
 * - Libre.fm has no equivalent endpoint; its scrobbler inherits the
 *   `Scrobbler.loveTrack` default no-op so it never appears in the
 *   per-service toggle UI.
 */
class LovesPushService constructor(
    private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
    private val settingsStore: SettingsStore,
) {
    /** Fire-and-forget scope. Survives ViewModel lifecycle so a love
     *  toggled mid-screen-rotation still finishes pushing. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes idempotency-cache reads/writes across concurrent loves
     *  + backfill. Without this, two concurrent calls could both read
     *  the cache, both write the same key, last-writer-wins overwrites
     *  the other's progress. */
    private val cacheMutex = Mutex()

    /** Backfill progress state for the Settings UI. */
    sealed class BackfillState {
        data object Idle : BackfillState()
        data class Running(val service: String, val pushed: Int, val skipped: Int, val failed: Int, val total: Int) : BackfillState()
        data class Done(val service: String, val pushed: Int, val skipped: Int, val failed: Int) : BackfillState()
    }

    private val _backfillState = MutableStateFlow<BackfillState>(BackfillState.Idle)
    val backfillState: StateFlow<BackfillState> = _backfillState.asStateFlow()

    /**
     * Push love for [track] to each enabled scrobbler that hasn't already
     * received it. Fire-and-forget — call from any coroutine; this returns
     * immediately and the actual push happens on the service's IO scope.
     *
     * No-op when:
     * - All per-service `LovePushEnabled` toggles are false.
     * - The track has already been pushed to every enabled service.
     */
    fun pushLoved(track: TrackEntity) {
        scope.launch {
            try {
                pushLovedInternal(track)
            } catch (e: Exception) {
                Log.w(TAG, "pushLoved('${track.title}' by ${track.artist}) failed: ${e.message}")
            }
        }
    }

    private suspend fun pushLovedInternal(track: TrackEntity) {
        val enabledMap = settingsStore.getLovePushEnabled()
        val enabledServices = enabledMap.filterValues { it }.keys
        if (enabledServices.isEmpty()) return

        val targetScrobblers = scrobblers.filter { it.id in enabledServices }
        if (targetScrobblers.isEmpty()) return

        val pushedKeys = settingsStore.getLovePushedKeys()
        val alreadyPushedFor = pushedKeys[track.id]?.keys.orEmpty()
        val toPush = targetScrobblers.filter { it.id !in alreadyPushedFor }
        if (toPush.isEmpty()) return

        for (scrobbler in toPush) {
            try {
                if (!scrobbler.isEnabled()) {
                    Log.d(TAG, "${scrobbler.displayName}: not authenticated; skipping love")
                    continue
                }
                scrobbler.loveTrack(track)
                writeIdempotencyKey(track.id, scrobbler.id)
            } catch (e: Exception) {
                Log.w(TAG, "${scrobbler.displayName}: love push failed for '${track.title}' by ${track.artist}: ${e.message}")
                // Leave cache untouched → retried next time.
            }
        }
    }

    /**
     * One-shot bulk backfill of [collectionTracks] to the named [service].
     * Sequential, 1 req/sec rate-limited, writes the idempotency cache
     * after each push so an interrupted run resumes correctly. Caller is
     * the Settings UI; observes [backfillState] for live progress.
     *
     * Already-pushed entries (per the cache) are silently skipped + counted
     * under `skipped`. Per-track failures are caught + counted under
     * `failed` and don't abort the loop — better N-1 successes than 0.
     *
     * Returns the final [BackfillState.Done] for callers that want a
     * synchronous final tally (also published on the StateFlow).
     */
    suspend fun runBackfill(
        service: String,
        collectionTracks: List<TrackEntity>,
    ): BackfillState.Done {
        val scrobbler = scrobblers.firstOrNull { it.id == service }
            ?: throw IllegalArgumentException("No scrobbler registered for service '$service'")
        if (!scrobbler.isEnabled()) {
            throw IllegalStateException("${scrobbler.displayName} not authenticated")
        }

        var pushed = 0
        var skipped = 0
        var failed = 0
        val total = collectionTracks.size
        _backfillState.value = BackfillState.Running(service, 0, 0, 0, total)

        // Snapshot the cache once at start; we'll consult it in-memory and
        // only re-read on writes (via writeIdempotencyKey, which goes
        // through the mutex + reads-modifies-writes the persistent map).
        val initialCache = settingsStore.getLovePushedKeys()

        for ((index, track) in collectionTracks.withIndex()) {
            if (initialCache[track.id]?.containsKey(service) == true) {
                skipped++
                _backfillState.value = BackfillState.Running(service, pushed, skipped, failed, total)
                continue
            }
            try {
                scrobbler.loveTrack(track)
                writeIdempotencyKey(track.id, service)
                pushed++
            } catch (e: Exception) {
                Log.w(TAG, "Backfill: $service love push failed for '${track.title}' by ${track.artist}: ${e.message}")
                failed++
            }
            _backfillState.value = BackfillState.Running(service, pushed, skipped, failed, total)
            // Pace 1 req/sec except after the last item.
            if (index < collectionTracks.lastIndex) {
                delay(BACKFILL_INTER_REQUEST_DELAY_MS)
            }
        }

        val done = BackfillState.Done(service, pushed, skipped, failed)
        _backfillState.value = done
        Log.d(TAG, "Backfill done for $service: pushed=$pushed skipped=$skipped failed=$failed (of $total)")
        return done
    }

    /** Reset the StateFlow back to Idle (e.g., after the user dismisses the
     *  Done toast in Settings). */
    fun resetBackfillState() {
        _backfillState.value = BackfillState.Idle
    }

    private suspend fun writeIdempotencyKey(trackId: String, service: String) {
        cacheMutex.withLock {
            val current = settingsStore.getLovePushedKeys().toMutableMap()
            val perService = current[trackId]?.toMutableMap() ?: mutableMapOf()
            perService[service] = currentTimeMillis()
            current[trackId] = perService
            settingsStore.setLovePushedKeys(current)
        }
    }
}
