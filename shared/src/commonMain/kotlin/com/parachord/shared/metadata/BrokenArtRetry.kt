package com.parachord.shared.metadata

import com.parachord.shared.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recover when a stored cover URL stops loading. Port of desktop's
 * `lib/broken-art.js` (parachord#1101 / #1102).
 *
 * Cover URLs are stored for a long time — on library rows, in the curated
 * list caches, in the artist-image cache — and can die well before anything
 * would refresh them: a Bandcamp image id changes when the artist replaces
 * the cover, archive.org mirrors move, and MusicBrainz builds Cover Art
 * Archive URLs whether or not a cover was ever uploaded. The image views
 * answered a failed load with the placeholder, so the dead URL kept winning.
 *
 * An image view that fails to load calls [reportAlbumArt] / [reportArtistImage].
 * That looks the art up again, skipping the dead URL, and on success hands
 * `(dead, fresh)` to [onReplaced] so every stored copy is patched, then returns
 * the fresh URL for the view to show. One attempt per album (or artist) per
 * session, shared by every view showing it, so a replacement that also fails
 * can't loop.
 *
 * Pure apart from the injected lookups, so it's unit-tested directly.
 */
class BrokenArtRetry(
    /** Look the album's art up again, never returning [dead]. */
    private val refetchAlbumArt: suspend (artist: String, album: String, dead: String) -> String?,
    /** Look the artist's image up again, never returning [dead]. */
    private val refetchArtistImage: suspend (artist: String, dead: String) -> String?,
    /** Patch every stored copy of [dead] to [fresh] (DB rows, curated caches). */
    private val onReplaced: suspend (dead: String, fresh: String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val attempts = mutableMapOf<String, Deferred<String?>>()

    /** A cover for [album] by [artist] at [url] failed to load. Returns a working URL, or null. */
    suspend fun reportAlbumArt(url: String?, artist: String?, album: String?): String? {
        if (url.isNullOrBlank() || artist.isNullOrBlank() || album.isNullOrBlank()) return null
        val key = "album|${artist.lowercase().trim()}|${album.lowercase().trim()}"
        return attempt(key, url) { refetchAlbumArt(artist, album, url) }
    }

    /** [artist]'s image at [url] failed to load. Returns a working URL, or null. */
    suspend fun reportArtistImage(url: String?, artist: String?): String? {
        if (url.isNullOrBlank() || artist.isNullOrBlank()) return null
        val key = "artist|${artist.lowercase().trim()}"
        return attempt(key, url) { refetchArtistImage(artist, url) }
    }

    private suspend fun attempt(key: String, dead: String, refetch: suspend () -> String?): String? {
        val deferred = mutex.withLock {
            attempts.getOrPut(key) {
                scope.async {
                    val fresh = try {
                        refetch()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "art retry failed for $key: ${e.message}")
                        null
                    }
                    if (fresh.isNullOrBlank() || fresh == dead) return@async null
                    try {
                        onReplaced(dead, fresh)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "couldn't patch stored copies of $dead: ${e.message}")
                    }
                    fresh
                }
            }
        }
        return deferred.await()
    }

    private companion object {
        const val TAG = "BrokenArtRetry"
    }
}
