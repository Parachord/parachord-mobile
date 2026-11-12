package com.parachord.shared.api

import com.parachord.shared.platform.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Builds the Web API play request for one Spotify track: the track's album as
 * the context, starting at the track (`context_uri` + `offset.uri`), rather
 * than a bare `uris: [track]`.
 *
 * **Why (Nov 2026).** Spotify's Android app (9.2.0, on Android 17) accepts a
 * bare `uris` play with 204, drops whatever it was playing and then plays
 * nothing: `/v1/me/player` reports the phone's device active with no track,
 * or 204, for as long as anyone waits. The same request plays at once on
 * Spotify's Mac app, and a play with an album context plays at once on the
 * phone, idle or already playing. `uris` + `position_ms` fails the same way.
 * Parachord had always sent the bare form, so every Spotify play on such a
 * phone fell back to the next source (#452's safety net) or played nothing.
 *
 * **Cost.** The album comes from `GET /v1/tracks/{id}` ([lookupTrack], gated by
 * Spotify's rate limit), once per track per run: answers are kept in memory
 * (at most [maxEntries]). Call [prefetch] before a track is due to take it off
 * the play's critical path. When the lookup fails (offline, a rate-limit
 * cooldown, no album on the track) the play goes out as bare `uris`, which
 * still plays everywhere but on that Android app.
 *
 * **Side effect.** With an album context, Spotify carries on into the album's
 * next track when ours ends, rather than stopping or autoplaying. The players
 * already end a Spotify track themselves ~2 s early and start the queue's next
 * one, and stop Spotify when the queue is empty.
 *
 * **Relinking.** Spotify may answer the lookup with another id for the same
 * song (the version playable in the account's market). The offset then names
 * that id, the one the album holds.
 */
class SpotifyPlayRequests(
    private val lookupTrack: suspend (trackId: String) -> SpTrack,
    private val maxEntries: Int = 500,
) {
    private data class Context(val albumUri: String, val offsetUri: String)

    private val mutex = Mutex()
    // Insertion-ordered, oldest evicted first. Null = the track has no album.
    private val contexts = LinkedHashMap<String, Context?>()

    /** The play request for [trackUri] (`spotify:track:<id>`). Never throws, except on cancellation. */
    suspend fun forTrack(trackUri: String): SpPlaybackRequest = forTrack(trackUri, startMs = 0)

    /**
     * The same, starting [startMs] into the track (`position_ms`, on the album
     * body and the bare one alike), so a resumed track starts where it was
     * instead of at 0:00 and then seeking (#657, desktop parachord#1346).
     */
    suspend fun forTrack(trackUri: String, startMs: Long): SpPlaybackRequest {
        val position = startMs.takeIf { it > 0 }
        val trackId = spotifyTrackId(trackUri) ?: return bare(trackUri, position)
        val context = contextFor(trackId)
        return if (context != null) {
            SpPlaybackRequest(contextUri = context.albumUri, offset = SpPlaybackOffset(uri = context.offsetUri), positionMs = position)
        } else {
            bare(trackUri, position)
        }
    }

    /** Look up [trackUri]'s album ahead of its play. Never throws, except on cancellation. */
    suspend fun prefetch(trackUri: String) {
        spotifyTrackId(trackUri)?.let { contextFor(it) }
    }

    private suspend fun contextFor(trackId: String): Context? {
        mutex.withLock { if (contexts.containsKey(trackId)) return contexts[trackId] }
        val track = try {
            lookupTrack(trackId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not remembered: a failed lookup says nothing about the track.
            Log.d(TAG, "Album lookup for $trackId failed, playing it without a context: ${e.message}")
            return null
        }
        val albumId = track.album?.id?.takeIf { it.isNotBlank() }
        val playableId = track.id?.takeIf { it.isNotBlank() } ?: trackId
        val context = albumId?.let { Context("spotify:album:$it", "spotify:track:$playableId") }
        mutex.withLock {
            contexts[trackId] = context
            while (contexts.size > maxEntries) contexts.remove(contexts.keys.first())
        }
        return context
    }

    private fun bare(trackUri: String, positionMs: Long?) = SpPlaybackRequest(uris = listOf(trackUri), positionMs = positionMs)

    companion object {
        private const val TAG = "SpotifyPlayRequests"

        /** The id in `spotify:track:<id>`, or null for any other URI. */
        fun spotifyTrackId(uri: String): String? {
            val prefix = "spotify:track:"
            if (!uri.startsWith(prefix)) return null
            return uri.removePrefix(prefix).takeIf { id -> id.isNotEmpty() && id.all { it.isLetterOrDigit() } }
        }
    }
}
