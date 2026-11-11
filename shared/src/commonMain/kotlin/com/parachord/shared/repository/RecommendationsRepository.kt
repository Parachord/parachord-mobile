package com.parachord.shared.repository

import com.parachord.shared.deeplink.isValidMbid
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.RecommendedArtist
import com.parachord.shared.model.RecommendedTrack
import com.parachord.shared.model.Resource
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.recommendations.ProviderRecommendations
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationType
import com.parachord.shared.recommendations.buildRecommendationSections
import com.parachord.shared.recommendations.normalizeRecommendationSections
import com.parachord.shared.resolver.validateIsrc
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A plugin that can recommend music. */
data class RecommendationProvider(val id: String, val name: String)

/**
 * Where recommendations come from. The real one is
 * [PluginRecommendationSource]; tests supply their own.
 */
interface RecommendationSource {
    /** The providers to ask, in the order the page shows them. */
    suspend fun providers(): List<RecommendationProvider>

    /**
     * One provider's raw `getRecommendations` answer (its sections, as JSON).
     * An answer with no sections is a miss. Throws when the provider couldn't
     * answer.
     */
    suspend fun fetch(providerId: String): String
}

/**
 * The plugins that declare `capabilities.recommendations` (desktop
 * parachord#1309): `lastfm.axe` and `listenbrainz.axe` today. A new provider
 * needs only a plugin.
 */
class PluginRecommendationSource(private val pluginManager: PluginManager) : RecommendationSource {
    override suspend fun providers(): List<RecommendationProvider> =
        pluginManager.recommendationResolvers().map { (id, name) -> RecommendationProvider(id, name) }

    override suspend fun fetch(providerId: String): String = pluginManager.getRecommendations(providerId)
}

/**
 * Recommendations, from the plugins that provide them (#606, desktop
 * parachord#1309). Each provider answers `getRecommendations` with sections of
 * typed items; the shared model (`RecommendationModel.kt`, a port of desktop's
 * `recommendation-model.js`) merges them, and this class hands the pages the
 * generic Songs and Artists buckets.
 *
 * - Providers are asked in parallel. One that fails keeps its last answer; a
 *   failure is never read as "nothing to recommend".
 * - Last.fm and ListenBrainz are asked only when a username is set (their
 *   plugins would answer empty without a request anyway).
 * - The Songs and Artists flows share one fetch ([SHARE_WINDOW_MS]).
 * - Titled sections, albums and playlists are merged by the model but not
 *   shown: no plugin the phones load sends them yet.
 *
 * File I/O for the disk cache goes through the `cacheRead` / `cacheWrite`
 * lambdas (as `CriticalDarlingsRepository` / `FreshDropsRepository`).
 */
class RecommendationsRepository(
    private val source: RecommendationSource,
    private val settingsStore: SettingsStore,
    private val metadataService: MetadataService,
    /** Read JSON from `recommendations_cache.json`; null if missing/fails. */
    private val cacheRead: suspend () -> String?,
    /** Write JSON to `recommendations_cache.json`. Failures are swallowed. */
    private val cacheWrite: suspend (String) -> Unit,
    private val nowMs: () -> Long = { currentTimeMillis() },
) {
    companion object {
        private const val TAG = "RecommendationsRepo"
        const val LASTFM = "lastfm"
        const val LISTENBRAINZ = "listenbrainz"
        const val NOT_CONFIGURED_MESSAGE = "Connect Last.fm or ListenBrainz in Settings to get recommendations"
        const val FAILED_MESSAGE = "Failed to load recommendations"

        /**
         * How long one fetch serves both flows. The pages collect Songs and
         * Artists together; each used to ask every service itself.
         */
        const val SHARE_WINDOW_MS = 10_000L
    }

    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** In-memory cache for stale-while-revalidate. */
    private var cachedTracks: List<RecommendedTrack>? = null
    private var cachedArtists: List<RecommendedArtist>? = null
    /** Each provider's last answer, by id. Kept when that provider fails. */
    private var providerResults: Map<String, ProviderRecommendations> = emptyMap()
    private var diskCacheLoaded = false

    private val loadMutex = Mutex()
    private var lastLoad: Loaded? = null

    /** Synchronous access to cached data (for ViewModel initial state). */
    val cachedTracksList: List<RecommendedTrack>?
        get() = cachedTracks
    val cachedArtistsList: List<RecommendedArtist>?
        get() = cachedArtists

    private suspend fun loadDiskCacheIfNeeded() {
        if (diskCacheLoaded) return
        diskCacheLoaded = true
        try {
            val json = cacheRead() ?: return
            val wrapper = diskJson.decodeFromString<RecommendationsDiskCache>(json)
            // An empty list means nothing was saved (see the empty-answer
            // branches below), not a cached empty answer, so the next load asks
            // again.
            cachedTracks = wrapper.tracks.takeIf { it.isNotEmpty() }
            cachedArtists = wrapper.artists.takeIf { it.isNotEmpty() }
            providerResults = wrapper.providers.mapNotNull { p -> p.resolverId?.let { it to p } }.toMap()
            Log.d(TAG, "Loaded ${wrapper.tracks.size} tracks, ${wrapper.artists.size} artists from disk cache")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load disk cache", e)
        }
    }

    private suspend fun saveDiskCache() {
        try {
            val wrapper = RecommendationsDiskCache(
                tracks = cachedTracks ?: emptyList(),
                artists = cachedArtists ?: emptyList(),
                providers = providerResults.values.toList(),
            )
            cacheWrite(diskJson.encodeToString(wrapper))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save disk cache", e)
        }
    }

    /** The recommended songs: the generic Songs bucket of every provider's answer. */
    fun getRecommendedTracks(): Flow<Resource<List<RecommendedTrack>>> = flow {
        loadDiskCacheIfNeeded()
        // Show stale cache immediately while refreshing
        if (cachedTracks != null) {
            emit(Resource.Success(cachedTracks!!))
        } else {
            emit(Resource.Loading)
        }
        try {
            val loaded = load()
            if (!loaded.configured) {
                emit(Resource.Error(NOT_CONFIGURED_MESSAGE))
                return@flow
            }
            if (loaded.tracks.isEmpty()) {
                if (loaded.anyFailed) {
                    // A provider couldn't answer: that isn't "nothing to
                    // recommend", so keep what's shown.
                    emit(Resource.Error(FAILED_MESSAGE))
                } else {
                    cachedTracks = null
                    saveDiskCache()
                    emit(Resource.Success(emptyList()))
                    emit(Resource.Error(emptyRecommendationsMessage(loaded.hasListenBrainz, loaded.hasLastFm)))
                }
                return@flow
            }
            Log.d(TAG, "Merged recommendations → ${loaded.tracks.size} unique tracks")
            cachedTracks = loaded.tracks
            saveDiskCache()
            emit(Resource.Success(loaded.tracks))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recommendations", e)
            emit(Resource.Error(FAILED_MESSAGE))
        }
    }

    /**
     * The recommended artists: the generic Artists bucket (the artists a
     * provider sent, then the artists of the recommended songs), with photos
     * filled in one by one, as desktop's page does.
     */
    fun getRecommendedArtists(): Flow<Resource<List<RecommendedArtist>>> = flow {
        loadDiskCacheIfNeeded()
        // Show stale cache immediately while refreshing
        if (cachedArtists != null) {
            emit(Resource.Success(cachedArtists!!))
        } else {
            emit(Resource.Loading)
        }
        try {
            val loaded = load()
            if (!loaded.configured) {
                emit(Resource.Error(NOT_CONFIGURED_MESSAGE))
                return@flow
            }
            if (loaded.artists.isEmpty()) {
                if (loaded.anyFailed) {
                    emit(Resource.Error(FAILED_MESSAGE))
                } else {
                    cachedArtists = null
                    saveDiskCache()
                    emit(Resource.Success(emptyList()))
                    emit(Resource.Error(emptyRecommendationsMessage(loaded.hasListenBrainz, loaded.hasLastFm)))
                }
                return@flow
            }

            // Carry over photos already found for the same artists.
            val cachedImagesByName = cachedArtists?.associateBy(
                { it.name.lowercase().trim() },
                { it.imageUrl },
            ) ?: emptyMap()
            val artists = loaded.artists.map { item ->
                RecommendedArtist(
                    name = item.name,
                    source = item.resolverId.orEmpty(),
                    imageUrl = item.imageUrl ?: cachedImagesByName[item.name.lowercase().trim()],
                    reason = item.reason,
                )
            }

            cachedArtists = artists
            saveDiskCache()
            emit(Resource.Success(artists))

            // Progressively resolve images only for artists that still need them.
            val mutableArtists = artists.toMutableList()
            val toEnrich = mutableArtists.withIndex()
                .filter { it.value.imageUrl == null }
                .take(15)

            Log.d(TAG, "Fetching images for ${toEnrich.size} recommended artists...")
            for ((index, artist) in toEnrich) {
                try {
                    val info = metadataService.getArtistInfo(artist.name)
                    val imageUrl = info?.imageUrl
                    if (imageUrl != null) {
                        mutableArtists[index] = artist.copy(imageUrl = imageUrl)
                        cachedArtists = mutableArtists.toList()
                        emit(Resource.Success(mutableArtists.toList()))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { /* skip */ }
            }
            // Save final enriched data to disk
            saveDiskCache()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recommended artists", e)
            emit(Resource.Error(FAILED_MESSAGE))
        }
    }

    /** One round of asking the providers, as both flows read it. */
    private class Loaded(
        val tracks: List<RecommendedTrack>,
        val artists: List<RecommendationItem>,
        /** A provider that should have answered didn't. */
        val anyFailed: Boolean,
        val configured: Boolean,
        val hasListenBrainz: Boolean,
        val hasLastFm: Boolean,
        val at: Long,
    )

    private suspend fun load(): Loaded = loadMutex.withLock {
        lastLoad?.takeIf { nowMs() - it.at in 0 until SHARE_WINDOW_MS }?.let { return@withLock it }
        askProviders().also { lastLoad = it }
    }

    private suspend fun askProviders(): Loaded {
        val hasLastFm = !settingsStore.getLastFmUsername().isNullOrBlank()
        val hasListenBrainz = !settingsStore.getListenBrainzUsername().isNullOrBlank()
        val providers = try {
            source.providers()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't list recommendation providers", e)
            emptyList()
        }
        // Last.fm and ListenBrainz need a username; any other provider decides
        // for itself whether it has anything.
        val connected = buildSet {
            if (hasLastFm) add(LASTFM)
            if (hasListenBrainz) add(LISTENBRAINZ)
        }
        val toAsk = providers.filter { it.id in connected || (it.id != LASTFM && it.id != LISTENBRAINZ) }
        if (connected.isEmpty() && toAsk.isEmpty()) {
            return Loaded(emptyList(), emptyList(), anyFailed = false, configured = false, hasListenBrainz = false, hasLastFm = false, at = nowMs())
        }

        val answers = coroutineScope {
            toAsk.map { provider ->
                async {
                    try {
                        val raw = diskJson.parseToJsonElement(source.fetch(provider.id))
                        normalizeRecommendationSections(raw, provider.id, provider.name).copy(fetchedAt = nowMs())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Recommendations from ${provider.name} failed: ${e.message}")
                        null
                    }
                }
            }.awaitAll()
        }
        // A connected service whose plugin isn't loaded (the plugin runtime
        // didn't start) couldn't answer either.
        var anyFailed = connected.any { id -> providers.none { it.id == id } }
        val results = LinkedHashMap<String, ProviderRecommendations>()
        toAsk.forEachIndexed { index, provider ->
            val answer = answers[index]
            if (answer != null) {
                results[provider.id] = answer
            } else {
                anyFailed = true
                // Keep its last answer, so a failure doesn't take rows off the page.
                providerResults[provider.id]?.let { results[provider.id] = it }
            }
        }
        providerResults = results

        val sections = buildRecommendationSections(results.values.toList())
        val songs = sections.firstOrNull { it.bucketType == RecommendationType.SONG }?.items.orEmpty()
        val artists = sections.firstOrNull { it.bucketType == RecommendationType.ARTIST }?.items.orEmpty()
        return Loaded(
            tracks = songs.mapNotNull { it.toRecommendedTrack() },
            artists = artists,
            anyFailed = anyFailed,
            configured = true,
            hasListenBrainz = hasListenBrainz,
            hasLastFm = hasLastFm,
            at = nowMs(),
        )
    }
}

/**
 * A recommended song as the pages show it. Plugins send seconds;
 * [RecommendedTrack.duration], like `Track.duration`, is milliseconds (#349).
 * The ids are kept only when well-formed. A song with no artist can't be
 * resolved, so it's left out.
 */
internal fun RecommendationItem.toRecommendedTrack(): RecommendedTrack? {
    val artistName = artist?.takeIf { it.isNotBlank() } ?: return null
    return RecommendedTrack(
        title = name,
        artist = artistName,
        duration = durationSeconds?.let { (it * 1000).toLong() }?.takeIf { it > 0 },
        source = resolverId.orEmpty(),
        recordingMbid = mbid?.lowercase()?.takeIf { isValidMbid(it) },
        isrc = validateIsrc(isrc),
    )
}

/**
 * What the Recommendations page says when no connected service has anything
 * to recommend. Port of desktop's `recommendations-empty.js` (parachord#1121).
 * ListenBrainz builds its recommendation playlist from a few weeks of listens,
 * so a new account has none.
 */
fun emptyRecommendationsMessage(hasListenBrainz: Boolean, hasLastFm: Boolean): String = when {
    hasListenBrainz && !hasLastFm ->
        "ListenBrainz hasn't made recommendations for you yet. It builds them from a few weeks of listens, so check back later, or connect Last.fm in Settings for recommendations now."
    hasListenBrainz && hasLastFm ->
        "Neither ListenBrainz nor Last.fm has recommendations for you yet. ListenBrainz builds them from a few weeks of listens, so check back later."
    else ->
        "Last.fm doesn't have recommendations for you yet. Check back once it has more of your listening history."
}

/**
 * Disk cache wrapper for JSON serialization. [tracks] and [artists] are what
 * the page shows first; [providers] is each provider's last answer, kept so a
 * provider that fails keeps its rows. A file from before #606 has no
 * [providers].
 */
@Serializable
private data class RecommendationsDiskCache(
    val tracks: List<RecommendedTrack> = emptyList(),
    val artists: List<RecommendedArtist> = emptyList(),
    val providers: List<ProviderRecommendations> = emptyList(),
)
