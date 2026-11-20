package com.parachord.shared.home

import com.parachord.shared.platform.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Concert cards' artist photos, for Home's Concerts near you and the Concerts
 * page alike (one app-wide instance, so both share what's found). A port of
 * desktop's Concerts page loop (`app.js`): the artists' photos through the
 * artist-photo chain ([artistImage]: `MetadataService.getArtistImage`, which
 * keeps its own persistent cache), [BATCH_SIZE] at a time, each batch's
 * answers merged into [images] as they arrive, keyed by [concertArtistKey].
 *
 * Each artist is asked once a session once a lookup answers: one already
 * found, or answered with no photo, is skipped. A lookup that threw (offline,
 * a failed request) doesn't count as tried: it's left out of [images], so the
 * card keeps its fallback ([concertPhotoUrl]), and the next [load] call asks
 * again, as `ShownAlbumArtLookup` does. It's never retried within a load, and
 * a load waiting on it doesn't ask again either, so a failing lookup can't
 * loop.
 *
 * **Overlapping loads.** Each lookup in flight has a per-artist [CompletableDeferred]
 * owned by the load that started it. Another load that wants the same artist
 * awaits it instead of skipping it. If the owning load is cancelled (Home's
 * list changed, the Concerts page closed), its lookup is abandoned: the
 * artist goes back to unasked, the deferred completes, and every load
 * waiting on it takes the lookup over. So a cancelled load never strands
 * another load's artists. A cancelled load stops before its next batch; the
 * artists it didn't reach stay unasked.
 */
class ConcertArtistImages(private val artistImage: suspend (artistName: String) -> String?) {
    companion object {
        /** Desktop's batch size. */
        const val BATCH_SIZE = 5
        private const val TAG = "ConcertArtistImages"
    }

    private val _images = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Found photos by [concertArtistKey], https only ([concertPhotoHttpsUrl]). */
    val images: StateFlow<Map<String, String>> = _images.asStateFlow()

    private val mutex = Mutex()
    private val tried = mutableSetOf<String>()
    /**
     * The lookup in flight per artist; completed when it ends, with true when
     * it was abandoned (its load cancelled) so a waiting load takes it over.
     */
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()

    /**
     * Looks up the photos of [artistNames] not yet found or tried, joining any
     * already in flight. Returns when every batch is done.
     */
    suspend fun load(artistNames: List<String>) {
        val todo = mutex.withLock {
            val seen = mutableSetOf<String>()
            artistNames.map { it.trim() }.filter { name ->
                val key = concertArtistKey(name)
                key.isNotEmpty() && seen.add(key) && key !in _images.value && key !in tried
            }
        }
        for (batch in todo.chunked(BATCH_SIZE)) {
            coroutineContext.ensureActive()
            coroutineScope {
                for (name in batch) launch { ensureLookedUp(name) }
            }
        }
    }

    /** Runs [name]'s lookup, or waits for the one in flight and takes it over if it's abandoned. */
    private suspend fun ensureLookedUp(name: String) {
        val key = concertArtistKey(name)
        while (true) {
            val (deferred, owner) = mutex.withLock {
                if (key in tried || key in _images.value) return
                val running = pending[key]
                if (running != null) {
                    running to false
                } else {
                    CompletableDeferred<Boolean>().also { pending[key] = it } to true
                }
            }
            if (!owner) {
                // Abandoned: look again, and own it. Answered or failed: done
                // (a failure is asked again on the next load, not here).
                if (deferred.await()) continue
                return
            }
            var answered = false
            var finished = false
            try {
                try {
                    val usable = artistImage(name)?.trim()?.let { concertPhotoHttpsUrl(it) }
                    if (usable != null) _images.update { it + (key to usable) }
                    answered = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Artist photo lookup failed: ${e.message}")
                }
                finished = true
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (answered) tried += key
                        pending.remove(key)
                    }
                    deferred.complete(!finished)
                }
            }
            return
        }
    }
}
