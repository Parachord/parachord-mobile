package com.parachord.shared.metadata

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.MbidLookupOutcome
import com.parachord.shared.api.MbidLookupSource
import com.parachord.shared.api.MbidMapperResult
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Background MBID enrichment service using the ListenBrainz MBID Mapper.
 *
 * Attaches MusicBrainz identifiers (recording, artist, release MBIDs) to tracks
 * in the collection. The MBID Mapper resolves in ~4ms with no strict rate limits,
 * making it suitable for batch enrichment.
 *
 * Lookups go mapper-first with a fallback to the labs `acr-lookup` dataset when
 * the mapper can't be reached (#394; see [ListenBrainzClient.resolveRecordingMbid]).
 * Caching distinguishes THREE outcomes, because during the 2026-09 mapper
 * outage every failed request was recorded as a 90-day miss:
 *  - hit            → cached 90 days (matching desktop's cache_mbid_mapper)
 *  - mapper miss    → cached 90 days (as null MBIDs) to avoid repeated lookups
 *  - acr-lookup miss→ cached 6 hours only — it's the stricter matcher, so a
 *                     mapper that comes back might still match
 *  - unreachable    → NEVER cached, never counted against the track
 * Source-less misses recorded since the outage began are purged once on load.
 *
 * Used by:
 * - FreshDropsRepository (artist MBID for release-group browsing)
 * - PlaybackController (enrich current track on playback)
 * - LibraryRepository (enrich on import)
 *
 * KMP migration notes:
 *  - `Context+File` disk cache replaced with `cacheRead`/`cacheWrite` suspend
 *    lambdas (same pattern as the repository moves).
 *  - `ConcurrentHashMap.newKeySet()` → `Mutex` + `MutableSet<String>` (JVM-only
 *    concurrent collections aren't in commonMain).
 *  - `Dispatchers.IO` → `Dispatchers.Default` (only `Default` and `Main` exist
 *    in commonMain).
 *  - Previously-synchronous lookups (`getCachedArtistMbid`, `getCanonicalNames`)
 *    are now `suspend` so the disk cache can be loaded lazily through the
 *    suspend `cacheRead` lambda. Both call sites already run from suspend
 *    context (FreshDropsRepository's lambda; ScrobbleManager.refreshTrackMbids).
 */
class MbidEnrichmentService(
    private val listenBrainzClient: ListenBrainzClient,
    private val trackDao: TrackDao,
    /** Read JSON from `mbid_mapper_cache.json`; null if missing/fails. */
    private val cacheRead: suspend () -> String?,
    /** Write JSON to `mbid_mapper_cache.json`. Failures are swallowed. */
    private val cacheWrite: suspend (String) -> Unit,
    /**
     * Service-agnostic ISRC → recording-MBID fallback. When set, [getRecordingMbid]
     * falls back to MusicBrainz `/isrc/{isrc}` if the ListenBrainz mapper is
     * unavailable / returns no match and the caller supplied an ISRC. Nullable so
     * tests + callers without MusicBrainz can omit it.
     */
    private val musicBrainzClient: MusicBrainzClient? = null,
    /** Clock source — injectable so the per-source TTLs and the outage purge are unit-testable. */
    private val nowMs: () -> Long = { currentTimeMillis() },
    /** Debounce for saving the disk cache after a lookup (a burst = one write). */
    private val saveDelayMs: Long = 2_000L,
) {
    companion object {
        private const val TAG = "MbidEnrichment"
        private const val CACHE_TTL = 90L * 24 * 60 * 60 * 1000 // 90 days

        /** An acr-lookup miss is trusted for 6 h only (stricter matcher than the mapper). */
        private const val ACR_MISS_TTL = 6L * 60 * 60 * 1000

        /**
         * 2026-09-21T00:00Z — the day mapper.listenbrainz.org went unreachable.
         * Misses written before this change carry no `source`; any such miss
         * recorded on or after this instant is an outage artifact (a failed
         * request cached as "no match"), not a verdict, and is dropped on load.
         * Misses written by the new code always carry a source, so the purge is
         * naturally one-shot.
         */
        internal const val OUTAGE_START_MS = 1_789_948_800_000L
    }

    // Background enrichment is best-effort — a mapper network failure (e.g. Ktor
    // SocketTimeoutException from `enrichTrack`) must NEVER crash the app. On a
    // handler-less scope an uncaught exception aborts the process: SIGABRT on
    // Kotlin/Native (iOS, via the ObjC bridge), default-uncaught-handler crash on
    // Android. The launch bodies below use try/finally without a catch, so this
    // handler is the backstop that keeps a timeout from taking the app down.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e ->
                Log.w(TAG, "Enrichment coroutine failed (ignored): ${e.message}")
            },
    )
    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** In-flight deduplication: don't enrich the same track concurrently. */
    private val inFlightMutex = Mutex()
    private val inFlight = mutableSetOf<String>()

    private suspend fun tryClaim(trackId: String): Boolean = inFlightMutex.withLock {
        inFlight.add(trackId)
    }

    private suspend fun release(trackId: String) {
        inFlightMutex.withLock { inFlight.remove(trackId) }
    }

    /**
     * Persistent disk cache: "artist|title" → MbidCacheEntry.
     * Loaded lazily on first access.
     */
    private val cacheMutex = Mutex()
    private var diskCache: MutableMap<String, MbidCacheEntry> = mutableMapOf()
    private var diskCacheLoaded = false

    // --- Public API ---

    /**
     * Enrich a track with MBIDs in the background.
     * Safe to call from anywhere — deduplicates, caches, and persists to Room.
     */
    fun enrichInBackground(trackId: String, artist: String, title: String) {
        if (artist.isBlank() || title.isBlank()) return
        scope.launch {
            if (!tryClaim(trackId)) return@launch
            try {
                enrichTrack(trackId, artist, title)
            } finally {
                release(trackId)
            }
        }
    }

    /**
     * Enrich multiple tracks in the background (e.g. on bulk import).
     */
    fun enrichBatchInBackground(tracks: List<TrackEnrichmentRequest>) {
        scope.launch {
            for (req in tracks) {
                if (req.artist.isBlank() || req.title.isBlank()) continue
                if (!tryClaim(req.trackId)) continue
                try {
                    enrichTrack(req.trackId, req.artist, req.title)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e   // batch cancelled — stop, don't swallow
                } catch (e: Exception) {
                    // One track's mapper failure (timeout, parse) must not abort the
                    // whole batch or skip the disk-cache save below.
                    Log.w(TAG, "enrich failed for '${req.title}': ${e.message}")
                } finally {
                    release(req.trackId)
                }
            }
            saveDiskCache()
        }
    }

    /**
     * Synchronously enrich one track and return when the mapper lookup
     * + Room backfill is complete. Unlike [enrichInBackground], this
     * suspends until the work is done — required by the slow-trickle
     * cross-resolver visitor which needs the MBID populated before it
     * can submit to Achordion. Safe to call from any coroutine context;
     * dedup-friendly via the same [tryClaim]/[release] gate.
     */
    suspend fun enrichTrack(req: TrackEnrichmentRequest) {
        if (req.artist.isBlank() || req.title.isBlank()) return
        if (!tryClaim(req.trackId)) return
        try {
            enrichTrack(req.trackId, req.artist, req.title)
        } finally {
            release(req.trackId)
        }
    }

    /**
     * Look up an artist MBID from the cache or via the mapper.
     * Returns null if not found. Does not persist to Room (no trackId context).
     * Used by FreshDropsRepository for release-group browsing.
     */
    suspend fun getArtistMbid(artistName: String, recordingName: String): String? {
        val key = cacheKey(artistName, recordingName)
        val cached = getCachedEntry(key)
        if (cached != null) return cached.artistMbid

        val hit = lookup(artistName, recordingName) as? MbidLookupOutcome.Hit ?: return null
        putCache(key, hit.result)
        return hit.result.artistMbid
    }

    /**
     * Look up a recording MBID from the cache or via the mapper/acr-lookup chain,
     * with the ISRC fallback. Returns null if not found.
     * Does not persist to Room (no trackId context).
     *
     * Used by [com.parachord.android.bridge.NativeBridge.resolveMbidForLove]
     * to back the JS bridge's `window.resolveMbidForLove(track)` Promise, and by
     * the scrobblers. **Collapses NotFound and Unreachable to `null`** — fine for
     * a one-shot "do I have an MBID for this play?", but callers that feed a
     * negative cache must use [lookupRecordingMbid] instead.
     */
    suspend fun getRecordingMbid(
        artistName: String,
        recordingName: String,
        isrc: String? = null,
        album: String? = null,
    ): String? = (lookupRecordingMbid(artistName, recordingName, isrc, album) as? RecordingMbidLookup.Found)?.mbid

    /**
     * The three-outcome form of [getRecordingMbid]: mapper → acr-lookup → ISRC.
     *
     * The status is decided by the mapper/acr-lookup chain; the ISRC fallback
     * can only UPGRADE it to [RecordingMbidLookup.Found]. So "nothing answered
     * about this artist/title and the ISRC didn't rescue it" stays
     * [RecordingMbidLookup.Unreachable] — we never learned the pair is
     * unmappable, and the caller must not record it as such (#394).
     */
    suspend fun lookupRecordingMbid(
        artistName: String,
        recordingName: String,
        isrc: String? = null,
        album: String? = null,
    ): RecordingMbidLookup {
        val key = cacheKey(artistName, recordingName)
        val cached = getCachedEntry(key)
        if (cached != null) {
            // A cached entry is a prior ANSWER (hits and misses only; unreachable
            // is never cached), so a cached miss is a genuine NotFound.
            return cached.recordingMbid?.let { RecordingMbidLookup.Found(it) }
                ?: RecordingMbidLookup.NotFound
        }

        val outcome = lookup(artistName, recordingName, album)
        if (outcome is MbidLookupOutcome.Hit) {
            putCache(key, outcome.result)
            outcome.result.recordingMbid?.let { return RecordingMbidLookup.Found(it) }
        }

        // Fallback: the chain is unavailable (an outage) or has no confident
        // match. Resolve via the played source's ISRC through MusicBrainz
        // `/isrc/` — a different service, and exact (the ISRC is the recording
        // the user is streaming), so no fuzzy wrong-variant risk.
        //
        // Deliberately NOT cached: an ISRC-only result lacks canonical names +
        // artist/release MBIDs, and caching it (90-day TTL) would block the
        // richer mapper entry once the mapper recovers. The scrobble submit only
        // needs the bare MBID for this play.
        if (!isrc.isNullOrBlank() && musicBrainzClient != null) {
            try {
                musicBrainzClient.lookupRecordingMbidByIsrc(isrc)?.let { return RecordingMbidLookup.Found(it) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "ISRC MBID fallback failed for isrc=$isrc: ${e.message}")
            }
        }
        return if (outcome is MbidLookupOutcome.Unreachable) RecordingMbidLookup.Unreachable
        else RecordingMbidLookup.NotFound
    }

    /**
     * Fuzzy recording-MBID lookup via MusicBrainz `searchRecordings` (title +
     * artist), confidence-gated. The LAST-RESORT fallback for playlist
     * propagation when BOTH the LB mapper is down AND no ISRC is available
     * (e.g. an Apple-Music- or local-sourced track during the mapper outage).
     *
     * Deliberately NOT part of [getRecordingMbid] (which stays mapper→ISRC, both
     * exact) so the scrobble path keeps its no-wrong-variant guarantee — a fuzzy
     * title/artist match can pick the wrong recording variant, which is lower-
     * stakes for a playlist entry than for a scrobble. Gated on
     * [scoreConfidence] ≥ [ResolverScoring.MIN_CONFIDENCE_THRESHOLD] (both title
     * AND artist must match) and NOT cached (it's a best-effort variant guess;
     * caching would block the richer mapper entry once it recovers).
     */
    suspend fun searchRecordingMbid(artist: String, title: String): String? {
        val client = musicBrainzClient ?: return null
        return try {
            val query = "recording:\"$title\" AND artist:\"$artist\""
            val response = client.searchRecordings(query, limit = 5)
            val match = response.recordings.firstOrNull { rec ->
                com.parachord.shared.resolver.scoreConfidence(
                    targetTitle = title,
                    targetArtist = artist,
                    matchedTitle = rec.title,
                    matchedArtist = rec.artistName,
                ) >= com.parachord.shared.resolver.ResolverScoring.MIN_CONFIDENCE_THRESHOLD
            }
            match?.id
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Fuzzy MB recording search failed for '$artist' - '$title': ${e.message}")
            null
        }
    }

    /**
     * Check if we have a cached artist MBID for any track by this artist.
     * Faster than a full mapper call — scans the disk cache for a match.
     */
    suspend fun getCachedArtistMbid(artistName: String): String? {
        ensureDiskCacheLoaded()
        val prefix = artistName.lowercase().trim() + "|"
        return cacheMutex.withLock {
            diskCache.entries
                .firstOrNull { it.key.startsWith(prefix) && it.value.artistMbid != null }
                ?.value?.artistMbid
        }
    }

    /**
     * Get canonical (MusicBrainz-corrected) names for a track from the disk cache.
     * Returns a pair of (canonicalArtist, canonicalRecording), or null if not cached.
     * Used by ScrobbleManager for canonical name fallback on scrobble payloads.
     */
    suspend fun getCanonicalNames(artist: String, title: String): Pair<String, String>? {
        val key = cacheKey(artist, title)
        val entry = getCachedEntry(key) ?: return null
        val canonicalArtist = entry.canonicalArtistName ?: return null
        val canonicalRecording = entry.canonicalRecordingName ?: return null
        return Pair(canonicalArtist, canonicalRecording)
    }

    // --- Internal ---

    private suspend fun enrichTrack(trackId: String, artist: String, title: String) {
        val key = cacheKey(artist, title)

        // Check cache first
        val cached = getCachedEntry(key)
        if (cached != null) {
            // Persist to Room if we have MBIDs
            if (cached.hasAnyMbid) {
                trackDao.backfillMbids(trackId, cached.recordingMbid, cached.artistMbid, cached.releaseMbid)
            }
            return
        }

        // Mapper → acr-lookup. Three outcomes, and only the first two are cached:
        // an Unreachable means nothing answered, so there is no verdict to keep
        // and the track is simply retried next time (#394).
        val entry = when (val outcome = lookup(artist, title)) {
            is MbidLookupOutcome.Hit -> MbidCacheEntry(
                recordingMbid = outcome.result.recordingMbid,
                artistMbid = outcome.result.artistMbid,
                releaseMbid = outcome.result.releaseMbid,
                canonicalArtistName = outcome.result.artistCreditName,
                canonicalRecordingName = outcome.result.recordingName,
                source = outcome.result.source,
                cachedAt = nowMs(),
            )
            // Cache the miss. The source decides its TTL (see ttlMs).
            is MbidLookupOutcome.Miss -> MbidCacheEntry(source = outcome.source, cachedAt = nowMs())
            is MbidLookupOutcome.Unreachable -> return
        }

        putCache(key, entry)

        // Persist to Room
        if (entry.hasAnyMbid) {
            trackDao.backfillMbids(trackId, entry.recordingMbid, entry.artistMbid, entry.releaseMbid)
            Log.d(TAG, "Enriched '$artist - $title' → artist=${entry.artistMbid}, rec=${entry.recordingMbid}")
        }
    }

    /**
     * Look up the (artist, recording) pair via the mapper → acr-lookup chain.
     *
     * Never throws: the client already maps request failures to
     * [MbidLookupOutcome.Unreachable], and anything that still escapes (a bug,
     * a serialization surprise) is reported the same way — a failure to ASK is
     * never a "no match".
     *
     * Note arg order: `(artist, recording)` — matches the underlying
     * `ListenBrainzClient.resolveRecordingMbid` signature, NOT `(title, artist)`.
     */
    suspend fun lookup(artist: String, recording: String, album: String? = null): MbidLookupOutcome {
        return try {
            listenBrainzClient.resolveRecordingMbid(artist, recording, album)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "MBID lookup failed for '$artist' - '$recording'", e)
            MbidLookupOutcome.Unreachable(mapOf("lookup" to (e.message ?: "error")))
        }
    }

    private fun cacheKey(artist: String, title: String): String =
        "${artist.lowercase().trim()}|${title.lowercase().trim()}"

    private suspend fun getCachedEntry(key: String): MbidCacheEntry? {
        ensureDiskCacheLoaded()
        return cacheMutex.withLock {
            val entry = diskCache[key] ?: return@withLock null
            // TTL check — per-source for misses (see ttlMs).
            if (nowMs() - entry.cachedAt > ttlMs(entry)) {
                diskCache.remove(key)
                return@withLock null
            }
            entry
        }
    }

    /**
     * How long an entry is trusted. Hits and mapper misses keep the 90-day TTL;
     * an acr-lookup MISS is trusted for only 6 hours — acr-lookup is the stricter
     * matcher (no suffix tolerance), so a mapper that comes back might still
     * match the same pair.
     */
    private fun ttlMs(entry: MbidCacheEntry): Long =
        if (!entry.hasAnyMbid && entry.source == MbidLookupSource.ACR_LOOKUP) ACR_MISS_TTL else CACHE_TTL

    /**
     * A miss with no `source` recorded on/after the outage began: written by the
     * pre-#394 code, which cached a failed request exactly like "no match".
     */
    private fun isOutageArtifact(entry: MbidCacheEntry): Boolean =
        !entry.hasAnyMbid && entry.source == null && entry.cachedAt >= OUTAGE_START_MS

    private suspend fun putCache(key: String, result: MbidMapperResult) {
        putCache(key, MbidCacheEntry(
            recordingMbid = result.recordingMbid,
            artistMbid = result.artistMbid,
            releaseMbid = result.releaseMbid,
            canonicalArtistName = result.artistCreditName,
            canonicalRecordingName = result.recordingName,
            source = result.source,
            cachedAt = nowMs(),
        ))
    }

    private suspend fun putCache(key: String, entry: MbidCacheEntry) {
        cacheMutex.withLock { diskCache[key] = entry }
        // Every lookup path persists — it used to be only the bulk import
        // (`enrichBatchInBackground`). iOS never uses that path, so its
        // mbid_mapper_cache.json was never written at all, and on Android every
        // track enriched one at a time (playback, queueing, scrobbling) was
        // looked up again after a restart.
        scheduleSave()
    }

    private var saveJob: kotlinx.coroutines.Job? = null

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            kotlinx.coroutines.delay(saveDelayMs)
            saveDiskCache()
        }
    }

    private suspend fun ensureDiskCacheLoaded() {
        cacheMutex.withLock {
            if (diskCacheLoaded) return
            diskCacheLoaded = true
            try {
                val json = cacheRead() ?: return
                val entries = diskJson.decodeFromString<Map<String, MbidCacheEntry>>(json)
                val now = nowMs()
                var purged = 0
                diskCache = entries.filterValues { entry ->
                    if (isOutageArtifact(entry)) { purged++; return@filterValues false }
                    now - entry.cachedAt < ttlMs(entry)
                }.toMutableMap()
                Log.d(TAG, "Loaded ${diskCache.size} cached MBIDs from disk" +
                    if (purged > 0) " (purged $purged outage-era misses)" else "")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load MBID disk cache", e)
            }
        }
    }

    internal suspend fun saveDiskCache() {
        try {
            val data: Map<String, MbidCacheEntry> = cacheMutex.withLock { diskCache.toMap() }
            cacheWrite(diskJson.encodeToString(data))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save MBID disk cache", e)
        }
    }
}

/**
 * Outcome of [MbidEnrichmentService.lookupRecordingMbid]. Three cases so a
 * caller that keeps a negative cache can tell "no match" from "nobody answered".
 */
sealed class RecordingMbidLookup {
    data class Found(val mbid: String) : RecordingMbidLookup()
    /** A service answered: genuinely no match. Safe to count as a miss. */
    data object NotFound : RecordingMbidLookup()
    /** Nothing answered. NOT a miss — don't cache it, don't count it against the track. */
    data object Unreachable : RecordingMbidLookup()
}

/** Request to enrich a track with MBIDs. */
data class TrackEnrichmentRequest(
    val trackId: String,
    val artist: String,
    val title: String,
)

/** Persistent MBID cache entry with TTL. */
@Serializable
internal data class MbidCacheEntry(
    val recordingMbid: String? = null,
    val artistMbid: String? = null,
    val releaseMbid: String? = null,
    /** Canonical artist name from MusicBrainz (for scrobble name correction). */
    val canonicalArtistName: String? = null,
    /** Canonical recording name from MusicBrainz (for scrobble name correction). */
    val canonicalRecordingName: String? = null,
    /**
     * Which service produced this entry ("mapper" / "acr-lookup"). Decides a
     * miss's TTL. Null on entries written before #394 — which is how the
     * one-shot outage purge recognises them.
     */
    val source: String? = null,
    val cachedAt: Long = 0L,
) {
    val hasAnyMbid: Boolean get() = !recordingMbid.isNullOrBlank() || !artistMbid.isNullOrBlank() || !releaseMbid.isNullOrBlank()
}
