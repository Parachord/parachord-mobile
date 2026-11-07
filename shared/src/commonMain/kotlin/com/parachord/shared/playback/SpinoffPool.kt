package com.parachord.shared.playback

import com.parachord.shared.api.LastFmClient
import com.parachord.shared.resolver.normalizeStr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * The tracks a spinoff plays (#534). A port of desktop's
 * `src/renderer/lib/spinoff-pool.js` (parachord#1281 / PR #1283), shared so
 * Android and iOS build the same pool.
 *
 * Spinoff starts from Last.fm `track.getsimilar`. Some of the most played songs
 * have no similar-track data there ("Shape of You" answers an empty list), so
 * when it returns fewer than [MIN_SIMILAR_TRACKS] the pool is topped up from
 * the seed artist's similar artists: each one's top tracks, dealt round-robin
 * so no two tracks in a row are by the same artist.
 *
 * A failed request is not the same as an empty answer. Only an empty answer (or
 * Last.fm's error 6, "not found") falls back; a failure stops there, so an
 * outage costs one request rather than ten.
 */

const val MIN_SIMILAR_TRACKS = 5 // below this, top up from similar artists
const val SIMILAR_TRACKS_LIMIT = 20
const val FALLBACK_ARTISTS = 8
const val TOP_TRACKS_PER_ARTIST = 3

/** Last.fm's "not found": an empty answer, not a failure. */
const val LASTFM_NOT_FOUND = 6

const val SPINOFF_SOURCE_SIMILAR = "lastfm-similar"
const val SPINOFF_SOURCE_SIMILAR_ARTIST = "lastfm-similar-artist"

/** Desktop's toast when the similar-tracks request itself fails. */
const val SPINOFF_FETCH_FAILED_MESSAGE = "Failed to fetch similar tracks"

/** One pool track. [match] is Last.fm's 0–1 match times 100. */
data class SpinoffCandidate(
    val title: String,
    val artist: String,
    val match: Int,
    val source: String,
)

/**
 * [failed] means the similar-tracks request itself failed, so the caller can
 * say so ([SPINOFF_FETCH_FAILED_MESSAGE]) rather than "no similar tracks".
 */
data class SpinoffPoolResult(
    val tracks: List<SpinoffCandidate>,
    val failed: Boolean,
    val usedFallback: Boolean,
)

/** Thrown by callers that report a failed pool as an exception. */
class SpinoffFetchFailedException : Exception(SPINOFF_FETCH_FAILED_MESSAGE)

data class SpinoffLfmTrack(val title: String, val artist: String?, val match: Double?)
data class SpinoffLfmArtist(val name: String, val match: Double?)

/**
 * Last.fm as the pool sees it. Each call returns the list, an empty list for
 * an empty answer or error 6, or `null` when the request failed (network,
 * non-JSON, any other Last.fm error).
 */
interface SpinoffLastFm {
    suspend fun similarTracks(artist: String, title: String, limit: Int): List<SpinoffLfmTrack>?
    suspend fun similarArtists(artist: String, limit: Int): List<SpinoffLfmArtist>?
    suspend fun topTracks(artist: String, limit: Int): List<SpinoffLfmTrack>?
}

/** [SpinoffLastFm] over the shared [LastFmClient] (its rate-limit gate included). */
class LastFmSpinoffSource(
    private val client: LastFmClient,
    private val apiKey: String,
) : SpinoffLastFm {

    override suspend fun similarTracks(artist: String, title: String, limit: Int): List<SpinoffLfmTrack>? =
        ask {
            val r = client.getSimilarTracks(track = title, artist = artist, apiKey = apiKey, limit = limit)
            listOrNull(r.error) {
                r.similartracks?.track.orEmpty().map { SpinoffLfmTrack(it.name, it.artist?.name, it.match) }
            }
        }

    override suspend fun similarArtists(artist: String, limit: Int): List<SpinoffLfmArtist>? =
        ask {
            val r = client.getSimilarArtists(artist = artist, apiKey = apiKey, limit = limit)
            listOrNull(r.error) {
                r.similarartists?.artist.orEmpty().map { SpinoffLfmArtist(it.name, it.match?.toDoubleOrNull()) }
            }
        }

    override suspend fun topTracks(artist: String, limit: Int): List<SpinoffLfmTrack>? =
        ask {
            val r = client.getArtistTopTracks(artist = artist, apiKey = apiKey, limit = limit)
            listOrNull(r.error) {
                r.toptracks?.track.orEmpty().map { SpinoffLfmTrack(it.name, it.artist?.name, null) }
            }
        }

    private suspend fun <T> ask(call: suspend () -> List<T>?): List<T>? {
        if (apiKey.isBlank()) return null
        return try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network, rate limit, a body that isn't Last.fm's JSON: a failure.
            null
        }
    }

    private inline fun <T> listOrNull(error: Int?, list: () -> List<T>): List<T>? = when (error) {
        null -> list()
        LASTFM_NOT_FOUND -> emptyList()
        else -> null
    }
}

private fun matchPercent(m: Double?): Int =
    if (m == null || m.isNaN() || m.isInfinite()) 0 else (m * 100).roundToInt()

/** Desktop's dedup key: [normalizeStr] on each side, trimmed lower case when that comes out empty. */
internal fun spinoffTrackKey(artist: String?, title: String?): String {
    val a = normalizeStr(artist).ifEmpty { artist.orEmpty().trim().lowercase() }
    val t = normalizeStr(title).ifEmpty { title.orEmpty().trim().lowercase() }
    return "$a|$t"
}

private fun <T> shuffle(list: List<T>, rng: () -> Double): List<T> {
    val out = list.toMutableList()
    for (i in out.size - 1 downTo 1) {
        val j = floor(rng() * (i + 1)).toInt().coerceIn(0, i)
        val tmp = out[i]; out[i] = out[j]; out[j] = tmp
    }
    return out
}

/** One artist's tracks, most played first. */
data class SpinoffArtistGroup(val artist: String, val tracks: List<SpinoffCandidate>)

/**
 * One track from each artist per round, the artist order shuffled every round.
 * When a round would start with the artist that ended the last one, its first
 * two artists swap. Runs only happen once a single artist has tracks left.
 */
fun dealRoundRobin(
    groups: List<SpinoffArtistGroup>,
    rng: () -> Double = { Random.nextDouble() },
): List<SpinoffCandidate> {
    val queues = groups.map { ArrayDeque(it.tracks) }.filter { it.isNotEmpty() }
    val out = mutableListOf<SpinoffCandidate>()
    while (queues.any { it.isNotEmpty() }) {
        val order = shuffle(queues.filter { it.isNotEmpty() }, rng).toMutableList()
        val last = out.lastOrNull()
        if (last != null && order.size > 1 && order[0].first().artist == last.artist) {
            val tmp = order[0]; order[0] = order[1]; order[1] = tmp
        }
        for (q in order) out += q.removeFirst()
    }
    return out
}

private suspend fun similarArtistGroups(lastFm: SpinoffLastFm, artist: String): List<SpinoffArtistGroup> {
    val artists = lastFm.similarArtists(artist, FALLBACK_ARTISTS)
    if (artists.isNullOrEmpty()) return emptyList()
    val picked = artists.filter { it.name.isNotBlank() }.take(FALLBACK_ARTISTS)
    // In parallel, as desktop does; LastFmClient's gate keeps it to 2 at a time.
    val lists = coroutineScope {
        picked.map { a -> async { lastFm.topTracks(a.name, TOP_TRACKS_PER_ARTIST) } }.awaitAll()
    }
    return picked.mapIndexed { i, a ->
        SpinoffArtistGroup(
            artist = a.name,
            tracks = lists[i].orEmpty().filter { it.title.isNotBlank() }.take(TOP_TRACKS_PER_ARTIST).map {
                SpinoffCandidate(
                    title = it.title,
                    artist = it.artist?.takeIf { n -> n.isNotBlank() } ?: a.name,
                    match = matchPercent(a.match),
                    source = SPINOFF_SOURCE_SIMILAR_ARTIST,
                )
            },
        )
    }
}

/**
 * The spinoff pool for a seed track: Last.fm's similar tracks (shuffled), then,
 * when there are fewer than [MIN_SIMILAR_TRACKS], the similar artists' top
 * tracks dealt round-robin. The seed and duplicates are left out.
 */
suspend fun buildSpinoffPool(
    lastFm: SpinoffLastFm,
    artist: String,
    title: String,
    rng: () -> Double = { Random.nextDouble() },
): SpinoffPoolResult {
    if (artist.isBlank() || title.isBlank()) return SpinoffPoolResult(emptyList(), failed = true, usedFallback = false)
    val raw = lastFm.similarTracks(artist, title, SIMILAR_TRACKS_LIMIT)
        ?: return SpinoffPoolResult(emptyList(), failed = true, usedFallback = false)

    val seen = mutableSetOf(spinoffTrackKey(artist, title))
    val keep = { t: SpinoffCandidate -> seen.add(spinoffTrackKey(t.artist, t.title)) }

    val similar = shuffle(
        raw.filter { it.title.isNotBlank() }.map {
            SpinoffCandidate(
                title = it.title,
                artist = it.artist?.takeIf { n -> n.isNotBlank() } ?: "Unknown Artist",
                match = matchPercent(it.match),
                source = SPINOFF_SOURCE_SIMILAR,
            )
        },
        rng,
    ).filter(keep)

    if (similar.size >= MIN_SIMILAR_TRACKS) return SpinoffPoolResult(similar, failed = false, usedFallback = false)

    val dealt = dealRoundRobin(similarArtistGroups(lastFm, artist), rng).filter(keep)
    return SpinoffPoolResult(similar + dealt, failed = false, usedFallback = dealt.isNotEmpty())
}

/**
 * Whether the Spinoff button should be enabled: any similar track, or failing
 * that any similar artist. A failed request answers false and asks nothing more.
 */
suspend fun checkSpinoffAvailable(lastFm: SpinoffLastFm, artist: String, title: String): Boolean {
    if (artist.isBlank() || title.isBlank()) return false
    val tracks = lastFm.similarTracks(artist, title, 1) ?: return false
    if (tracks.isNotEmpty()) return true
    return !lastFm.similarArtists(artist, 1).isNullOrEmpty()
}
