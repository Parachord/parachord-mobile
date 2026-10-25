package com.parachord.shared.metadata

import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Artist info found this session, shared by every caller — the artist page,
 * recommendations, the AI suggestions and chat cards. Port of desktop's
 * `createArtistBioCache` (parachord#1133, mobile #429): `getArtistInfo` ran the
 * whole provider cascade on every artist-page view, so a bio one screen had
 * just loaded could come back empty on the next if a source was down by then.
 *
 * - Only an answer WITH a bio is kept. "No bio" may mean a source was down.
 *   A fetch that throws rejects every waiter and isn't kept either.
 * - A Wikipedia bio (the preferred source) is kept [ttlMs] (6 h, as desktop).
 *   Any other bio is kept only [fallbackTtlMs]: it may be standing in for a
 *   Wikipedia bio a MusicBrainz or Wikidata outage hid. This is mobile's
 *   version of desktop keying the bio by mbid — desktop skips Wikipedia
 *   without an mbid, mobile always tries it.
 * - At most [maxEntries], oldest first out. Concurrent loads of one artist
 *   share a fetch.
 */
class ArtistInfoCache(
    private val ttlMs: Long = 6L * 60 * 60 * 1000,
    private val fallbackTtlMs: Long = 10L * 60 * 1000,
    private val maxEntries: Int = 300,
    private val nowMs: () -> Long = { currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private class Entry(val info: ArtistInfo, val at: Long, val ttl: Long)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    private val inFlight = mutableMapOf<String, Deferred<ArtistInfo?>>()

    suspend fun load(artistName: String, fetch: suspend () -> ArtistInfo?): ArtistInfo? {
        val key = artistName.trim().lowercase()
        if (key.isEmpty()) return null
        val deferred = mutex.withLock {
            entries[key]?.let { e ->
                if (nowMs() - e.at < e.ttl) return e.info
                entries.remove(key)
            }
            inFlight[key] ?: scope.async {
                try {
                    fetch().also { info -> if (info != null && !info.bio.isNullOrBlank()) remember(key, info) }
                } finally {
                    mutex.withLock { inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        return deferred.await()
    }

    private suspend fun remember(key: String, info: ArtistInfo) = mutex.withLock {
        val ttl = if (info.bioSource == "wikipedia") ttlMs else fallbackTtlMs
        entries.remove(key)
        entries[key] = Entry(info, nowMs(), ttl)
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    val size: Int get() = entries.size
}
