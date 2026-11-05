package com.parachord.shared.ai.tools

import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.model.Track
import com.parachord.shared.resolver.selectBestMatch
import com.parachord.shared.search.LibraryTrackSearch

/**
 * How the AI tools turn a requested artist + title into a track (#520, desktop
 * parachord#1241). A search can answer with only other songs, and nothing later can
 * refuse one: a result resolves against its own names. So a tool takes
 * [selectBestMatch]'s answer or reports the track as not found, never the first
 * result. Shared so `play` and `queue_add` behave the same in both executors.
 *
 * The catalogs don't know a track that exists only as a local file (a private
 * recording, a demo), and nothing answers with no network, so the local files join
 * the candidates (#529). They go after the catalog's rows: when both have the exact
 * track, the catalog's row wins, since its ids spare the resolver a search. The
 * resolver then finds the file by the row's names, and the person's source order
 * still decides what plays.
 */
const val DJ_TRACK_SEARCH_LIMIT = 20

const val DJ_NONE_FOUND_ERROR = "Could not find any of the requested tracks"

fun djTrackNotFoundError(artist: String, title: String): String = "Could not find \"$title\" by $artist"

/** The requested track among what the search found, or null when it isn't there. */
suspend fun findRequestedTrack(
    artist: String,
    title: String,
    localFiles: suspend () -> List<Track>,
    search: suspend (query: String, limit: Int) -> List<TrackSearchResult>,
): TrackSearchResult? = pickRequestedTrack(artist, title, localFiles(), search)

private suspend fun pickRequestedTrack(
    artist: String,
    title: String,
    localFiles: List<Track>,
    search: suspend (query: String, limit: Int) -> List<TrackSearchResult>,
): TrackSearchResult? = selectBestMatch(
    search("$artist $title", DJ_TRACK_SEARCH_LIMIT) + localCandidates(title, localFiles),
    title,
    artist,
    titleOf = { it.title },
    artistOf = { it.artist },
)

/**
 * The local files [selectBestMatch] should look at, found by title alone. Asking for
 * "artist title" would need every word of the artist as written, and miss a file
 * tagged "A & B" for a request for "A and B", which the match itself accepts.
 */
private fun localCandidates(title: String, localFiles: List<Track>): List<TrackSearchResult> =
    LibraryTrackSearch.search(localFiles, title).map { track ->
        TrackSearchResult(
            title = track.title,
            artist = track.artist,
            album = track.album,
            duration = track.duration,
            artworkUrl = track.artworkUrl,
            spotifyId = track.spotifyId,
            mbid = track.recordingMbid,
            provider = "localfiles",
        )
    }

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
    localFiles: suspend () -> List<Track>,
    search: suspend (query: String, limit: Int) -> List<TrackSearchResult>,
): RequestedTracks {
    val local = localFiles()
    val found = mutableListOf<TrackSearchResult>()
    val notFound = mutableListOf<Map<String, String>>()
    for (track in tracks) {
        val artist = track["artist"] as? String ?: continue
        val title = track["title"] as? String ?: continue
        val match = pickRequestedTrack(artist, title, local, search)
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
