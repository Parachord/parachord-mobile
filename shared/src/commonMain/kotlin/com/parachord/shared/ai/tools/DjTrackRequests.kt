package com.parachord.shared.ai.tools

import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.resolver.selectBestMatch

/**
 * How the AI tools turn a requested artist + title into a track (#520, desktop
 * parachord#1241). A search can answer with only other songs, and nothing later can
 * refuse one: a result resolves against its own names. So a tool takes
 * [selectBestMatch]'s answer or reports the track as not found, never the first
 * result. Shared so `play` and `queue_add` behave the same in both executors.
 */
const val DJ_TRACK_SEARCH_LIMIT = 20

const val DJ_NONE_FOUND_ERROR = "Could not find any of the requested tracks"

fun djTrackNotFoundError(artist: String, title: String): String = "Could not find \"$title\" by $artist"

/** The requested track among what the search found, or null when it isn't there. */
suspend fun findRequestedTrack(
    artist: String,
    title: String,
    search: suspend (query: String, limit: Int) -> List<TrackSearchResult>,
): TrackSearchResult? = selectBestMatch(
    search("$artist $title", DJ_TRACK_SEARCH_LIMIT),
    title,
    artist,
    titleOf = { it.title },
    artistOf = { it.artist },
)

/**
 * What became of a tool's list of requested tracks. [notFound] goes back to the AI,
 * so it can tell the person which tracks were skipped instead of reporting success.
 */
class RequestedTracks(
    val found: List<TrackSearchResult>,
    val notFound: List<Map<String, String>>,
)

/** Looks up each entry in order. An entry with no artist or title is skipped. */
suspend fun findRequestedTracks(
    tracks: List<Map<String, Any?>>,
    search: suspend (query: String, limit: Int) -> List<TrackSearchResult>,
): RequestedTracks {
    val found = mutableListOf<TrackSearchResult>()
    val notFound = mutableListOf<Map<String, String>>()
    for (track in tracks) {
        val artist = track["artist"] as? String ?: continue
        val title = track["title"] as? String ?: continue
        val match = findRequestedTrack(artist, title, search)
        if (match != null) found += match else notFound += mapOf("artist" to artist, "title" to title)
    }
    return RequestedTracks(found, notFound)
}

/**
 * `queue_add` once its tracks are looked up. With none found, nothing changes. With
 * [playFirst] the queue is replaced and the first track FOUND starts, even when the
 * first one requested is the one missing: a request to play something shouldn't
 * end in silence. Desktop starts nothing there (parachord#1241's fix); this is a
 * deliberate difference.
 */
suspend fun <T> runQueueAdd(
    requested: RequestedTracks,
    playFirst: Boolean,
    toTrack: suspend (TrackSearchResult) -> T,
    clearQueue: () -> Unit,
    playTrack: suspend (T) -> Unit,
    addToQueue: (List<T>) -> Unit,
): Map<String, Any?> {
    if (requested.found.isEmpty()) {
        return buildMap {
            put("success", false)
            put("error", DJ_NONE_FOUND_ERROR)
            if (requested.notFound.isNotEmpty()) put("notFound", requested.notFound)
        }
    }
    val tracks = requested.found.map { toTrack(it) }
    if (playFirst) {
        clearQueue()
        playTrack(tracks.first())
    }
    val toQueue = if (playFirst) tracks.drop(1) else tracks
    if (toQueue.isNotEmpty()) addToQueue(toQueue)
    return buildMap {
        put("success", true)
        put("queued", tracks.size)
        put("nowPlaying", playFirst)
        put("tracks", requested.found.map { mapOf("artist" to it.artist, "title" to it.title) })
        if (requested.notFound.isNotEmpty()) put("notFound", requested.notFound)
    }
}
