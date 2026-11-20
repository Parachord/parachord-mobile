package com.parachord.shared.recommendations

import com.parachord.shared.platform.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "RecommendationImages"

/**
 * Covers and photos for the Recommendations page's album and artist cards
 * that came without one (Last.fm and ListenBrainz rarely send an image).
 * Desktop's `recommendationImages` (app.js): one lookup per item with no
 * image, kept in memory for the session. A lookup that answered (with a URL
 * or none) is remembered; one that threw stores nothing, so the next time
 * the card comes on screen it asks again. At most one lookup per key runs.
 *
 * The screens ask for the cards they show ([requestLookups]); the page
 * applies the answers on its next rebuild ([imageFor], [changes]).
 */
class RecommendationImages(
    /** An artist's photo: the shared artist-photo chain (`MetadataService.getArtistImage`). */
    private val artistImage: suspend (name: String) -> String?,
    /** An album's cover (`ImageEnrichmentService.lookupAlbumArt`). */
    private val albumArt: suspend (title: String, artist: String) -> String?,
) {
    private val mutex = Mutex()
    private val inFlight = mutableSetOf<String>()
    /** Answers by [imageKey]; a null value is an answered miss. */
    private val answers = mutableMapOf<String, String?>()
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    /** Emits after a lookup found an image. */
    val changes: Flow<Unit> get() = _changes

    /** The item's own image first, else a found one; null when neither. */
    suspend fun imageFor(item: RecommendationItem): String? {
        item.imageUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val key = imageKey(item) ?: return null
        return mutex.withLock { answers[key] }
    }

    /** Looks up each album or artist with no image and no answer yet, one at a time, in order. */
    suspend fun requestLookups(items: List<RecommendationItem>) {
        for (item in items) {
            if (!item.imageUrl.isNullOrBlank()) continue
            val key = imageKey(item) ?: continue
            val go = mutex.withLock {
                if (key in inFlight || key in answers) false else { inFlight.add(key); true }
            }
            if (!go) continue
            try {
                val url = try {
                    when (item.type) {
                        RecommendationType.ARTIST -> artistImage(item.name)
                        else -> albumArt(item.name, item.artist.orEmpty())
                    }?.takeIf { it.isNotBlank() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "image lookup failed for ${item.name}: ${e.message}")
                    continue
                }
                mutex.withLock { answers[key] = url }
                if (url != null) _changes.tryEmit(Unit)
            } finally {
                mutex.withLock { inFlight.remove(key) }
            }
        }
    }

    companion object {
        /** An artist by name, an album by artist and title; null for other types or a blank name. */
        fun imageKey(item: RecommendationItem): String? {
            if (item.name.isBlank()) return null
            return when (item.type) {
                RecommendationType.ARTIST -> "artist:" + item.name.trim().lowercase()
                RecommendationType.ALBUM -> "album:" + releaseDateKey(item.artist, item.name)
                else -> null
            }
        }
    }
}
