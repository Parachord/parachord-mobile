package com.parachord.shared.home

import com.parachord.shared.platform.Log
import kotlinx.coroutines.CancellationException
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
 * Each artist is asked once a session: one already found, already tried
 * (even if it threw or found nothing), or being looked up is skipped. A
 * failure is left out of [images], so the card keeps its fallback
 * ([concertPhotoUrl]). A cancelled [load] stops before its next batch; the
 * artists it didn't get to (and those cut off mid-lookup) aren't counted as
 * tried, so a later load asks for them.
 */
class ConcertArtistImages(private val artistImage: suspend (artistName: String) -> String?) {
    companion object {
        /** Desktop's batch size. */
        const val BATCH_SIZE = 5
        private const val TAG = "ConcertArtistImages"
    }

    private val _images = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Found photos by [concertArtistKey], http(s) only. */
    val images: StateFlow<Map<String, String>> = _images.asStateFlow()

    private val mutex = Mutex()
    private val tried = mutableSetOf<String>()
    private val inFlight = mutableSetOf<String>()

    /** Looks up the photos of [artistNames] not yet found, tried or in flight. Returns when every batch is done. */
    suspend fun load(artistNames: List<String>) {
        val todo = mutex.withLock {
            val seen = mutableSetOf<String>()
            artistNames.map { it.trim() }.filter { name ->
                val key = concertArtistKey(name)
                key.isNotEmpty() && seen.add(key) && key !in _images.value && key !in tried && key !in inFlight
            }.also { names -> names.forEach { inFlight += concertArtistKey(it) } }
        }
        if (todo.isEmpty()) return
        try {
            for (batch in todo.chunked(BATCH_SIZE)) {
                coroutineContext.ensureActive()
                coroutineScope {
                    for (name in batch) launch { lookUp(name) }
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { todo.forEach { inFlight -= concertArtistKey(it) } }
            }
        }
    }

    private suspend fun lookUp(name: String) {
        val key = concertArtistKey(name)
        val url = try {
            artistImage(name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Artist photo lookup failed: ${e.message}")
            null
        }
        mutex.withLock {
            tried += key
            inFlight -= key
        }
        val usable = url?.trim()?.takeIf { isHttpUrl(it) }
        if (usable != null) _images.update { it + (key to usable) }
    }
}
