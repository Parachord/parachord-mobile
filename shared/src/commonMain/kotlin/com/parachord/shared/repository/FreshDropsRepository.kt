package com.parachord.shared.repository

import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.MbReleaseGroupEntry
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.metadata.ImageEnrichmentService
import com.parachord.shared.metadata.MusicBrainzProvider
import com.parachord.shared.model.Resource
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository for Fresh Drops — new releases from artists the user listens to.
 *
 * Mirrors the desktop app's `loadNewReleases()` approach:
 * 1. Gather artists from library + Last.fm/ListenBrainz top artists
 * 2. For each artist, resolve MBID (mapper first, then MusicBrainz search fallback)
 * 3. Browse their release-groups and filter to recent releases (last 6 months)
 * 4. Assign Cover Art Archive URLs
 * 5. Cache with 6-hour TTL
 *
 * Constructor takes file I/O + MBID-lookup as suspend lambdas instead of an
 * Android `Context` and the (Android-only) `MbidEnrichmentService` class. The
 * Android Koin binding wires `Context.filesDir`-backed reads/writes for the
 * two cache files (`fresh_drops_cache.json`, `fresh_drops_rotation.json`)
 * and forwards to `MbidEnrichmentService.getCachedArtistMbid` /
 * `getArtistMbid` for the two enrichment lookups. iOS will wire equivalents
 * once that target lights up. This keeps the shared class platform-agnostic
 * even though `MbidEnrichmentService` itself is still Android-only (its disk
 * cache layer hasn't been ported yet).
 *
 * Date math switched from `java.time` to `kotlinx-datetime`. Display
 * formatters (`displayDate`, `isUpcoming`) moved to Android-only
 * extension properties since `MMM d, yyyy` formatting is locale-dependent
 * and doesn't have a clean KMP equivalent.
 */
class FreshDropsRepository(
    private val musicBrainzClient: MusicBrainzClient,
    private val lastFmClient: LastFmClient,
    private val listenBrainzClient: ListenBrainzClient,
    private val settingsStore: SettingsStore,
    private val trackDao: TrackDao,
    /** Read JSON from `fresh_drops_cache.json`; null if missing/fails. */
    private val cacheRead: suspend () -> String?,
    /** Write JSON to `fresh_drops_cache.json`. Failures are swallowed. */
    private val cacheWrite: suspend (String) -> Unit,
    /** Read JSON from `fresh_drops_rotation.json`. */
    private val rotationRead: suspend () -> String?,
    /** Write JSON to `fresh_drops_rotation.json`. */
    private val rotationWrite: suspend (String) -> Unit,
    /**
     * Look up an artist's MBID in the disk-backed enrichment cache.
     * Wraps `MbidEnrichmentService.getCachedArtistMbid` on Android. Returns
     * null on cache miss (the mapper-based lookup below is the next step).
     */
    private val mbidLookupCached: suspend (artistName: String) -> String?,
    /**
     * Resolve an artist's MBID via the ListenBrainz MBID Mapper using a
     * known (artist, title) pair from the user's library. Wraps
     * `MbidEnrichmentService.getArtistMbid` on Android.
     */
    private val mbidLookupViaMapper: suspend (artist: String, title: String) -> String?,
    /**
     * Used by [verifyAndRepairArt] to HEAD-check Cover Art Archive URLs
     * after [mergeAndDedupe] eagerly assigns them, and to fall through
     * to the full Last.fm + Spotify cascade for release groups whose
     * CAA front cover hasn't been uploaded.
     */
    private val imageEnrichmentService: ImageEnrichmentService,
    private val lastFmApiKey: String,
) {
    companion object {
        private const val TAG = "FreshDropsRepo"
        private const val MAX_ARTISTS_TO_CHECK = 50
        private const val STALE_THRESHOLD = 6 * 60 * 60 * 1000L // 6 hours

        /**
         * Bounded concurrency for the post-merge art-verify pass. CAA's
         * CDN handles modest fan-out fine, but 50-wide parallel HEAD
         * requests would be impolite (and could trigger throttling on
         * the IA-hosted backend). 4 keeps the verify pass under ~5s
         * for a typical 20-50 release refresh.
         */
        private const val ART_VERIFY_CONCURRENCY = 4
    }

    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** In-memory cache, initialized from disk on first access. */
    private var cachedReleases: List<FreshDrop>? = null
    private var lastFetchedAt: Long = 0L
    private var diskCacheLoaded = false

    /** Round-robin rotation: tracks when each artist was last checked. */
    private var artistLastChecked: MutableMap<String, Long> = mutableMapOf()
    private var rotationLoaded = false

    /**
     * Cached releases available without a coroutine context (e.g. ViewModel
     * `init` blocks setting an initial Resource). Returns whatever's in
     * memory; first disk-cache load is triggered lazily by the next
     * `getFreshDrops()` call. Same trade-off as `ConcertsRepository.cached`.
     */
    val cached: List<FreshDrop>? get() = cachedReleases

    val isCacheStale: Boolean get() = currentTimeMillis() - lastFetchedAt > STALE_THRESHOLD

    /** In-memory MBID cache for this session: artist name (lowercase) → MBID. */
    private val mbidCache = mutableMapOf<String, String>()

    /** Load cache from disk on first access. */
    private suspend fun loadDiskCache() {
        diskCacheLoaded = true
        try {
            val body = cacheRead() ?: return
            val wrapper = diskJson.decodeFromString<FreshDropsDiskCache>(body)
            cachedReleases = wrapper.releases
            lastFetchedAt = wrapper.fetchedAt
            Log.d(TAG, "Loaded ${wrapper.releases.size} cached fresh drops from disk (age: ${(currentTimeMillis() - wrapper.fetchedAt) / 1000}s)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load disk cache", e)
        }
    }

    /** Persist cache to disk. */
    /**
     * A cover in the list stopped loading and [BrokenArtRetry] found a working
     * one: swap it in memory and on disk, so the dead URL isn't restored from
     * the cache on the next visit.
     */
    suspend fun replaceArt(dead: String, fresh: String) {
        if (!diskCacheLoaded) loadDiskCache()
        val releases = cachedReleases ?: return
        if (releases.none { it.albumArt == dead }) return
        val patched = releases.map { if (it.albumArt == dead) it.copy(albumArt = fresh) else it }
        cachedReleases = patched
        saveDiskCache(patched, lastFetchedAt)
    }

    private suspend fun saveDiskCache(releases: List<FreshDrop>, fetchedAt: Long) {
        try {
            val wrapper = FreshDropsDiskCache(releases = releases, fetchedAt = fetchedAt)
            cacheWrite(diskJson.encodeToString(wrapper))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save disk cache", e)
        }
    }

    /** Load round-robin rotation state from disk. */
    private suspend fun loadRotation() {
        rotationLoaded = true
        try {
            val body = rotationRead() ?: return
            val map = diskJson.decodeFromString<Map<String, Long>>(body)
            artistLastChecked = map.toMutableMap()
            Log.d(TAG, "Loaded rotation state for ${artistLastChecked.size} artists")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load rotation state", e)
        }
    }

    /** Persist round-robin rotation state to disk. */
    private suspend fun saveRotation() {
        try {
            rotationWrite(diskJson.encodeToString(artistLastChecked.toMap()))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save rotation state", e)
        }
    }

    /** Record that an artist was checked at the given time. */
    private fun markArtistChecked(artistName: String) {
        artistLastChecked[artistName.lowercase().trim()] = currentTimeMillis()
    }

    /**
     * Resolve artist MBID using the shared MbidEnrichmentService, falling back
     * to MusicBrainz search.
     *
     * The enrichment service uses the MBID Mapper (~4ms, no rate limits) with a
     * 90-day disk cache. We only fall back to rate-limited MusicBrainz search for
     * artists without library tracks (e.g. Last.fm/ListenBrainz-only artists).
     */
    private suspend fun resolveArtistMbid(
        artistName: String,
        libraryTracks: List<com.parachord.shared.model.Track>,
    ): MbidResolution {
        val key = artistName.lowercase().trim()

        // 1. Check session cache
        mbidCache[key]?.let { return MbidResolution(it, usedMbSearch = false) }

        // 2. Check shared enrichment cache (disk-backed, 90-day TTL)
        mbidLookupCached(artistName)?.let { mbid ->
            mbidCache[key] = mbid
            return MbidResolution(mbid, usedMbSearch = false)
        }

        // 3. Try MBID Mapper via enrichment service using a library track
        val trackByArtist = libraryTracks.firstOrNull { it.artist.lowercase().trim() == key }
        if (trackByArtist != null) {
            val mbid = mbidLookupViaMapper(trackByArtist.artist, trackByArtist.title)
            if (mbid != null) {
                mbidCache[key] = mbid
                Log.d(TAG, "MBID Mapper enriched '$artistName' → $mbid")
                return MbidResolution(mbid, usedMbSearch = false)
            }
        }

        // 4. Fall back to MusicBrainz artist search (rate-limited)
        try {
            val results = musicBrainzClient.searchArtists(artistName, limit = 1)
            val artist = results.artists.firstOrNull()
            if (artist != null) {
                mbidCache[key] = artist.id
                Log.d(TAG, "MB search resolved '$artistName' → ${artist.id}")
                return MbidResolution(artist.id, usedMbSearch = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "MB search failed for '$artistName'", e)
        }

        return MbidResolution(null, usedMbSearch = true)
    }

    /**
     * Get fresh drops with progressive loading.
     *
     * Matches the desktop's loadNewReleases() approach:
     * - Show cached data immediately while refreshing
     * - Emit progressive results as each artist is processed
     * - Merge new results with prior cache (each fetch only covers a subset
     *   of the user's library due to the artist cap + shuffling)
     * - Cache results in memory for the session
     */
    fun getFreshDrops(forceRefresh: Boolean = false): Flow<Resource<List<FreshDrop>>> = flow {
        // Ensure disk cache is loaded before checking staleness
        if (!diskCacheLoaded) loadDiskCache()

        val now = currentTimeMillis()
        val isStale = now - lastFetchedAt > STALE_THRESHOLD

        // Return cache if fresh
        if (!forceRefresh && !isStale && cachedReleases != null) {
            Log.d(TAG, "Using ${cachedReleases!!.size} cached fresh drops")
            emit(Resource.Success(cachedReleases!!))
            // No art pass on a cache hit. It used to HEAD every Cover Art
            // Archive URL and, for each one without a cover, run the whole
            // lookup again — about 2 MusicBrainz calls per coverless release on
            // every visit, since the lookup usually landed on the same CAA URL.
            // A cover that fails to load is now retried once by the image view
            // (BrokenArtRetry), which patches this cache via [replaceArt].
            return@flow
        }

        // Show stale cache immediately while refreshing (desktop pattern)
        if (cachedReleases != null) {
            Log.d(TAG, "Showing ${cachedReleases!!.size} stale cached releases while refreshing...")
            emit(Resource.Success(cachedReleases!!))
        } else {
            emit(Resource.Loading)
        }

        try {
            // 1. Gather artists from multiple sources
            val libraryTracks = try { trackDao.getRecentSync(200) } catch (_: Exception) { emptyList() }
            val artists = gatherArtists(libraryTracks)
            if (artists.isEmpty()) {
                Log.d(TAG, "No artists found in collection or history")
                if (cachedReleases == null) {
                    emit(Resource.Error("Add artists to your library or connect Last.fm/ListenBrainz to discover new releases"))
                }
                return@flow
            }

            Log.d(TAG, "Checking ${artists.size} artists for new releases...")

            // Snapshot prior cached releases before fetch starts (desktop pattern:
            // merge new + prior so we don't lose results from artists not in this
            // run's selection — the artist cap means each fetch only covers a subset)
            val priorCachedReleases = cachedReleases?.toList() ?: emptyList()

            // 2. Fetch releases for each artist from MusicBrainz
            val sixMonthsAgo = Clock.System.todayIn(TimeZone.currentSystemDefault())
                .minus(6, DateTimeUnit.MONTH)
            val allReleases = mutableListOf<FreshDrop>()

            for ((index, artist) in artists.withIndex()) {
                try {
                    val releases = fetchReleasesForArtist(artist.name, sixMonthsAgo, libraryTracks)
                    val withSource = releases.map { it.copy(artistSource = artist.source) }
                    allReleases.addAll(withSource)

                    // Record this artist as checked for round-robin rotation
                    markArtistChecked(artist.name)

                    // Emit progressive results every 5 artists (desktop pattern:
                    // show releases as they come in, merged with prior cache)
                    if (allReleases.isNotEmpty() && (index + 1) % 5 == 0) {
                        val progressMerged = mergeAndDedupe(allReleases, priorCachedReleases)
                        // Save partial results so the cache survives if the collector
                        // is cancelled — but keep the PREVIOUS fetch time. Stamping
                        // `now` here made a scan abandoned 10 artists in look like a
                        // complete refresh, so the other artists were skipped for the
                        // whole 6h TTL. The rotation is saved too, so the next scan
                        // starts with the artists this one didn't reach.
                        cachedReleases = progressMerged
                        saveDiskCache(progressMerged, lastFetchedAt)
                        saveRotation()
                        emit(Resource.Success(progressMerged))
                    }

                    // Only rate-limit if we used MusicBrainz search (mapper is fast/unlimited)
                    // The browse call always hits MB, so always delay for that
                    delay(1100)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Re-throw cancellation so the loop terminates cleanly instead
                    // of catching it in the generic Exception handler below and
                    // continuing into 20 phantom "failed" iterations.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch releases for '${artist.name}'", e)
                }
            }

            // Persist rotation state so next refresh picks up where we left off
            saveRotation()

            // 3. Final merge: new results + prior cache, deduplicated and sorted
            val finalReleases = mergeAndDedupe(allReleases, priorCachedReleases)

            Log.d(TAG, "Found ${allReleases.size} fresh + ${priorCachedReleases.size} cached → ${finalReleases.size} unique releases from ${artists.size} artists")

            cachedReleases = finalReleases
            lastFetchedAt = currentTimeMillis()
            saveDiskCache(finalReleases, lastFetchedAt)
            emit(Resource.Success(finalReleases))

            // Stage 4: HEAD-verify each release's Cover Art Archive URL.
            // CAA returns 404 for release groups whose front cover
            // hasn't been uploaded — those rendered as broken-image
            // placeholders before this pass. For each 404, fall through
            // to the metadata cascade (Last.fm + Spotify) for a richer
            // provider's URL. Bounded concurrency keeps a refresh from
            // slamming CAA with 50 simultaneous HEADs.
            val repaired = verifyAndRepairArt(finalReleases)
            if (repaired != finalReleases) {
                cachedReleases = repaired
                saveDiskCache(repaired, lastFetchedAt)
                emit(Resource.Success(repaired))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load fresh drops", e)
            if (cachedReleases == null) {
                emit(Resource.Error("Failed to load new releases"))
            }
        }
    }

    /**
     * For each release with a Cover Art Archive URL, verify the URL
     * actually serves an image; on 404 fall through to the metadata
     * cascade for a Last.fm/Spotify alternative. Releases with no
     * `albumArt` (no MBID was found) also get a cascade attempt — no
     * eager URL means the album sat with a placeholder, exactly the
     * case the user reported.
     *
     * Bounded at [ART_VERIFY_CONCURRENCY] (4) to avoid slamming CAA's
     * CDN with 50 simultaneous HEAD requests on a refresh.
     *
     * Releases whose URL is non-CAA (e.g. previously cascade-resolved
     * to a Last.fm CDN URL) skip the verify step — those URLs are
     * already from a reliable provider, no need to re-check on every
     * refresh.
     */
    private suspend fun verifyAndRepairArt(
        releases: List<FreshDrop>,
    ): List<FreshDrop> = coroutineScope {
        val sem = Semaphore(ART_VERIFY_CONCURRENCY)
        releases.map { release ->
            async {
                sem.withPermit {
                    val current = release.albumArt
                    val needsCheck = current == null || current.contains("coverartarchive.org")
                    if (!needsCheck) return@withPermit release

                    // CAA hint present → verify, replace on 404.
                    // No hint at all → straight cascade.
                    val replacement = if (current != null) {
                        imageEnrichmentService.resolveAlbumArtUrl(
                            albumTitle = release.title,
                            artistName = release.artist,
                            hint = current,
                        )
                    } else {
                        imageEnrichmentService.lookupAlbumArt(
                            albumTitle = release.title,
                            artistName = release.artist,
                        )
                    }
                    if (replacement != null && replacement != current) {
                        release.copy(albumArt = replacement)
                    } else {
                        release
                    }
                }
            }
        }.awaitAll()
    }

    /**
     * Merge fresh releases with prior cached releases, deduplicate, assign
     * Cover Art Archive URLs, and sort by date descending.
     * Fresh results take priority (listed first before dedup).
     */
    private fun mergeAndDedupe(
        freshReleases: List<FreshDrop>,
        priorReleases: List<FreshDrop>,
    ): List<FreshDrop> {
        val merged = freshReleases + priorReleases
        val seen = mutableSetOf<String>()
        return merged
            .filter { release ->
                val key = "${release.artist.lowercase()}|${release.title.lowercase()}"
                seen.add(key)
            }
            .map { release ->
                if (release.albumArt == null && release.mbid != null) {
                    release.copy(albumArt = MusicBrainzProvider.releaseGroupArtUrl(release.mbid))
                } else release
            }
            .sortedByDescending { it.date ?: "" }
    }

    /**
     * Gather unique artists from multiple sources (matching desktop's gatherNewReleasesArtists):
     * 1. Library tracks (distinct artists)
     * 2. Last.fm top artists
     * 3. ListenBrainz top artists
     *
     * Deduplicates by lowercased name, then sorts by round-robin rotation
     * (least-recently-checked first) so every artist eventually gets checked
     * across successive refreshes. Caps at MAX_ARTISTS_TO_CHECK.
     */
    private suspend fun gatherArtists(
        libraryTracks: List<com.parachord.shared.model.Track>,
    ): List<ArtistSource> {
        if (!rotationLoaded) loadRotation()

        val seen = mutableSetOf<String>()
        val artists = mutableListOf<ArtistSource>()

        // Source 1: Library artists
        try {
            val libraryArtists = libraryTracks
                .map { it.artist }
                .distinct()
                .filter { seen.add(it.lowercase().trim()) }
                .map { ArtistSource(name = it, source = "library") }
            artists.addAll(libraryArtists)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get library artists", e)
        }

        // Source 2: Last.fm top artists
        try {
            val lfmUsername = settingsStore.getLastFmUsername()
            if (lfmUsername != null) {
                val topArtists = lastFmClient.getUserTopArtists(
                    user = lfmUsername, apiKey = lastFmApiKey, limit = 50, period = "6month",
                )
                val lfmArtists = topArtists.topartists?.artist
                    ?.map { it.name }
                    ?.filter { seen.add(it.lowercase().trim()) }
                    ?.map { ArtistSource(name = it, source = "history") }
                    ?: emptyList()
                artists.addAll(lfmArtists)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Last.fm top artists", e)
        }

        // Source 3: ListenBrainz top artists
        try {
            val lbUsername = settingsStore.getListenBrainzUsername()
            if (lbUsername != null) {
                val lbArtists = listenBrainzClient.getUserTopArtists(lbUsername, "half_yearly", 50)
                    .map { it.name }
                    .filter { seen.add(it.lowercase().trim()) }
                    .map { ArtistSource(name = it, source = "history") }
                artists.addAll(lbArtists)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get ListenBrainz top artists", e)
        }

        // Round-robin: sort by last-checked time (never-checked = 0, i.e. highest priority).
        // Within same last-checked time (e.g. all never-checked), shuffle to avoid
        // always processing the same source order.
        val sorted = artists
            .groupBy { artistLastChecked[it.name.lowercase().trim()] ?: 0L }
            .entries
            .sortedBy { it.key }
            .flatMap { (_, group) -> group.shuffled() }

        Log.d(TAG, "Gathered ${sorted.size} unique artists, checking ${minOf(sorted.size, MAX_ARTISTS_TO_CHECK)}")
        return sorted.take(MAX_ARTISTS_TO_CHECK)
    }

    /**
     * Fetch recent releases for an artist from MusicBrainz.
     * Matches desktop's fetchReleasesForArtists() logic:
     * 1. Search for artist MBID
     * 2. Browse release-groups
     * 3. Filter to releases after cutoff date
     * 4. Exclude compilations, broadcasts, live
     */
    private suspend fun fetchReleasesForArtist(
        artistName: String,
        cutoffDate: LocalDate,
        libraryTracks: List<com.parachord.shared.model.Track>,
    ): List<FreshDrop> {
        // Resolve MBID: mapper first (~4ms), MB search fallback (rate-limited)
        val resolution = resolveArtistMbid(artistName, libraryTracks)
        val mbid = resolution.mbid ?: return emptyList()

        // Browse release-groups with pagination — MusicBrainz browse results are
        // NOT ordered by date, so with limit=100 we can miss the newest releases
        // for prolific artists whose discography exceeds one page.
        val allReleaseGroups = mutableListOf<MbReleaseGroupEntry>()
        var offset = 0
        var totalCount = Int.MAX_VALUE
        var lastPageSize: Int
        val pageSize = 100
        do {
            val response = musicBrainzClient.browseReleaseGroups(mbid, limit = pageSize, offset = offset)
            allReleaseGroups.addAll(response.releaseGroups)
            totalCount = response.releaseGroupCount
            lastPageSize = response.releaseGroups.size
            offset += lastPageSize
            // Rate-limit between pages (MusicBrainz 1 req/sec policy)
            if (offset < totalCount && lastPageSize == pageSize) {
                delay(1100)
            }
        } while (offset < totalCount && offset < 500 && lastPageSize == pageSize)

        // `kotlinx.datetime.LocalDate.toString()` produces ISO-8601 (`yyyy-MM-dd`),
        // matching the previous `DateTimeFormatter.ISO_LOCAL_DATE` output.
        val cutoffStr = cutoffDate.toString()

        return allReleaseGroups
            .filter { rg ->
                // Must have a release date
                val date = rg.firstReleaseDate ?: return@filter false
                // MusicBrainz returns dates as "YYYY-MM-DD", "YYYY-MM", or "YYYY".
                // Pad partial dates to full precision for comparison — use the
                // earliest possible day so we INCLUDE releases whose partial date
                // *could* be after the cutoff (e.g. "2025" → "2025-01-01" keeps
                // any 2025 release if the cutoff year is 2025).
                val comparable = when (date.length) {
                    4 -> "$date-01-01"     // "2025" → "2025-01-01"
                    7 -> "$date-01"        // "2025-09" → "2025-09-01"
                    else -> date           // "2025-09-22" already full
                }
                // Must be after cutoff (last 6 months)
                comparable >= cutoffStr
                    // Exclude broadcast as primary type
                    && rg.primaryType?.lowercase() != "broadcast"
                    // Exclude compilations, broadcasts, live, DJ-mix, remix, etc.
                    && rg.secondaryTypes.none { type ->
                    type.lowercase() in listOf(
                        "compilation", "broadcast", "live",
                        "dj-mix", "remix", "mixtape/street",
                    )
                }
            }
            .map { rg ->
                FreshDrop(
                    mbid = rg.id,
                    title = rg.title,
                    artist = rg.artistName.ifBlank { artistName },
                    date = rg.firstReleaseDate,
                    releaseType = rg.primaryType?.lowercase() ?: "album",
                    secondaryTypes = rg.secondaryTypes,
                )
            }
    }
}

/** An artist from one of the discovery sources. */
private data class ArtistSource(
    val name: String,
    val source: String, // "library", "history"
)

/** Result of resolving an artist MBID (mapper or MB search). */
private data class MbidResolution(
    val mbid: String?,
    val usedMbSearch: Boolean,
)

/** Disk cache wrapper for JSON serialization. */
@Serializable
private data class FreshDropsDiskCache(
    val releases: List<FreshDrop>,
    val fetchedAt: Long,
)

/**
 * A new release (fresh drop).
 *
 * Display formatters (`displayDate`, `isUpcoming`) live as Android-only
 * extension properties in `app/data/repository/FreshDropsRepository.kt`
 * since locale-aware month-name formatting (`MMM d, yyyy`) doesn't have a
 * clean KMP equivalent. Call sites (`FreshDropsScreen`, the unit test)
 * use `release.displayDate` / `drop.isUpcoming` syntax, which works
 * transparently with extensions in the same package.
 */
@Serializable
data class FreshDrop(
    val mbid: String? = null,
    val title: String,
    val artist: String,
    val artistSource: String = "",
    val date: String? = null,
    val releaseType: String = "album",
    val secondaryTypes: List<String> = emptyList(),
    val albumArt: String? = null,
)
