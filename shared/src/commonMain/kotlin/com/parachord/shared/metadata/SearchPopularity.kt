package com.parachord.shared.metadata

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ListenBrainz listen counts as Search popularity for MusicBrainz's rows
 * (desktop `withListenBrainzPopularity`, parachord#1204, #488). [SearchRanking]
 * blends it into each row's standing, so the original ranks above covers.
 *
 * - **Albums** use the release group's listens ([SearchRanking.POPULARITY_REFERENCE_LISTENS]).
 * - **Tracks** use their artist's total
 *   ([SearchRanking.ARTIST_POPULARITY_REFERENCE_LISTENS]). ListenBrainz splits a
 *   song's plays across duplicate recordings, so the recording MusicBrainz's
 *   search returns for an original often has no count, while a cover's does.
 *
 * One batched request per category, capped at [timeoutMs]. A slow or failed
 * request just leaves the rows without popularity, which [SearchRanking]
 * treats as neutral. Answers, an unknown id's null too, are kept for [ttlMs].
 *
 * @param fetchListens `ListenBrainzClient.getPopularity`: kind + ids → listens (null = none).
 */
class SearchPopularity(
    private val fetchListens: suspend (kind: String, mbids: List<String>) -> Map<String, Long?>,
    private val timeoutMs: Long = 800,
    private val ttlMs: Long = 24L * 60 * 60 * 1000,
    private val nowMs: () -> Long = ::currentTimeMillis,
) {
    private class Entry(val value: Double?, val at: Long)

    private val lock = Mutex()
    private val cache = mutableMapOf<String, Entry>()

    /** MusicBrainz track rows with their artist's popularity. */
    suspend fun tracks(rows: List<TrackSearchResult>): List<TrackSearchResult> {
        val found = popularity("artist", rows.mapNotNull { it.artistMbid }, SearchRanking.ARTIST_POPULARITY_REFERENCE_LISTENS)
        if (found.isEmpty()) return rows
        return rows.map { row -> found[row.artistMbid?.lowercase()]?.let { row.copy(popularity = row.popularity ?: it) } ?: row }
    }

    /** MusicBrainz album rows with their release group's popularity. */
    suspend fun albums(rows: List<AlbumSearchResult>): List<AlbumSearchResult> {
        val found = popularity("release-group", rows.mapNotNull { it.releaseGroupMbid }, SearchRanking.POPULARITY_REFERENCE_LISTENS)
        if (found.isEmpty()) return rows
        return rows.map { row -> found[row.releaseGroupMbid?.lowercase()]?.let { row.copy(popularity = row.popularity ?: it) } ?: row }
    }

    /** Popularity by lowercased id, for the ids that have one. */
    internal suspend fun popularity(kind: String, mbids: List<String>, reference: Long): Map<String, Double> {
        val out = mutableMapOf<String, Double>()
        val need = mutableListOf<String>()
        val now = nowMs()
        lock.withLock {
            for (id in mbids.map { it.lowercase() }.filter { com.parachord.shared.deeplink.isValidMbid(it) }.distinct()) {
                val hit = cache["$kind:$id"]
                if (hit != null && now - hit.at < ttlMs) hit.value?.let { out[id] = it } else need += id
            }
        }
        if (need.isEmpty()) return out
        val listens = try {
            withTimeoutOrNull(timeoutMs) { fetchListens(kind, need) }
                .also { if (it == null) Log.d(TAG, "ListenBrainz popularity ($kind) skipped: over ${timeoutMs}ms") }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(TAG, "ListenBrainz popularity ($kind) skipped: ${e.message}")
            null
        } ?: return out
        lock.withLock {
            for ((rawId, count) in listens) {
                val id = rawId.lowercase()
                val value = SearchRanking.popularityFromListens(count, reference)
                cache["$kind:$id"] = Entry(value, now)
                if (value != null) out[id] = value
            }
        }
        return out
    }

    private companion object {
        const val TAG = "SearchPopularity"
    }
}
