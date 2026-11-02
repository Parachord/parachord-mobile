package com.parachord.shared.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Covers for Search rows that came without a usable one (desktop
 * `fetchSearchAlbumArt` / `serviceArtFor`, parachord#1187 / #1193).
 * MusicBrainz's rows have only Cover Art Archive URLs, which it builds whether
 * or not a cover was uploaded, and Last.fm's rows usually have none.
 *
 * [settle] gives one row its cover:
 * 1. The first of [settle]'s `toCheck` URLs that actually serves an image
 *    (Cover Art Archive URLs are checked with [servesImage]; any other URL came
 *    from a real image listing and is trusted).
 * 2. If that finds nothing within [fallbackMs], or nothing at all, the
 *    service's cover from `fallback` (a merged provider row's, or the art of
 *    the source the track resolved to), whichever answers first.
 *
 * The first cover found is the row's, so it never swaps under the user.
 * Checks are remembered per URL, and at most [CONCURRENCY] run at once
 * ([lookup] too); waiting for a service's cover takes no slot, so rows
 * waiting on one never hold up other rows' checks. Desktop:
 * SEARCH_ART_FALLBACK_MS 2000, SEARCH_ART_LIMIT 10, SEARCH_ART_CONCURRENCY 4.
 */
class SearchArtwork(
    /** Whether [url] serves an image (a HEAD that ends in 2xx after redirects). */
    private val servesImage: suspend (url: String) -> Boolean,
    private val fallbackMs: Long = FALLBACK_MS,
    /** How long a row waits for any cover before giving up. */
    private val waitMs: Long = 20_000,
) {
    private val lock = Mutex()
    private val checked = mutableMapOf<String, Boolean>()
    private val slots = Semaphore(CONCURRENCY)

    /** Run another Search art lookup (an artist photo) in one of the shared slots. */
    suspend fun <T> lookup(block: suspend () -> T): T = slots.withPermit { block() }

    /** The first of [urls] that shows an image, checking Cover Art Archive URLs. */
    suspend fun firstWorking(urls: List<String>): String? {
        for (url in urls.distinct()) {
            if (url.isBlank()) continue
            if (!needsCheck(url) || works(url)) return url
        }
        return null
    }

    /** One row's cover (see the class doc); null when nothing turned up. */
    suspend fun settle(toCheck: List<String>, fallback: suspend () -> String?): String? = coroutineScope {
        val pipeline = async { firstWorking(toCheck) }
        try {
            withTimeoutOrNull(fallbackMs) { pipeline.await() }?.let { return@coroutineScope it }
            withTimeoutOrNull(waitMs) {
                merge(
                    flow { pipeline.await()?.let { emit(it) } },
                    flow { fallback()?.takeIf { it.isNotBlank() }?.let { emit(it) } },
                ).firstOrNull()
            }
        } finally {
            pipeline.cancel()
        }
    }

    private suspend fun works(url: String): Boolean {
        lock.withLock { checked[url] }?.let { return it }
        val ok = try { slots.withPermit { servesImage(url) } } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
        lock.withLock { checked[url] = ok }
        return ok
    }

    companion object {
        /** After this long, a service's cover shows if the check found none yet. */
        const val FALLBACK_MS = 2_000L

        /** Rows per category that get a cover looked up. */
        const val LIMIT = 10

        /** Rows looked up at once. */
        const val CONCURRENCY = 4

        /** MusicBrainz builds these whether or not a cover was uploaded. */
        fun needsCheck(url: String): Boolean = "coverartarchive.org" in url
    }
}
