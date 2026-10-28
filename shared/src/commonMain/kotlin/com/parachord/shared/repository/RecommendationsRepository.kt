package com.parachord.shared.repository

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.RecommendedArtist
import com.parachord.shared.model.RecommendedTrack
import com.parachord.shared.model.Resource
import com.parachord.shared.platform.Log
import com.parachord.shared.settings.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository for music recommendations, mirroring the desktop app's approach.
 *
 * Strategy (matching desktop `loadRecommendations()`):
 * 1. Fetch pre-computed recommendations from Last.fm web endpoint
 * 2. Fetch ListenBrainz curated recommendation playlists (fallback: top recordings)
 * 3. Merge and deduplicate (ListenBrainz takes priority)
 * 4. Extract unique artists from the combined track list
 *
 * Both services are fetched in parallel; either can fail without blocking the other.
 *
 * KMP migration notes:
 *  - OkHttp + `org.json.JSONObject` replaced with shared Ktor `HttpClient` and
 *    kotlinx.serialization wire models for the Last.fm web endpoint.
 *  - File I/O for the disk cache flows through `cacheRead`/`cacheWrite` suspend
 *    lambdas (same pattern as `CriticalDarlingsRepository` / `FreshDropsRepository`).
 */
class RecommendationsRepository(
    private val httpClient: HttpClient,
    private val listenBrainzClient: ListenBrainzClient,
    private val settingsStore: SettingsStore,
    private val metadataService: MetadataService,
    /** Read JSON from `recommendations_cache.json`; null if missing/fails. */
    private val cacheRead: suspend () -> String?,
    /** Write JSON to `recommendations_cache.json`. Failures are swallowed. */
    private val cacheWrite: suspend (String) -> Unit,
) {
    companion object {
        private const val TAG = "RecommendationsRepo"
    }

    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val wireJson = Json { ignoreUnknownKeys = true }

    /** In-memory cache for stale-while-revalidate. */
    private var cachedTracks: List<RecommendedTrack>? = null
    private var cachedArtists: List<RecommendedArtist>? = null
    private var diskCacheLoaded = false

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
            // An empty list means nothing was saved (see clearForEmptyAnswer),
            // not a cached empty answer, so the next load asks again.
            cachedTracks = wrapper.tracks.takeIf { it.isNotEmpty() }
            cachedArtists = wrapper.artists.takeIf { it.isNotEmpty() }
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
            )
            cacheWrite(diskJson.encodeToString(wrapper))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save disk cache", e)
        }
    }

    /**
     * Get recommended tracks from Last.fm and ListenBrainz.
     * Mirrors desktop's parallel fetch + merge approach.
     */
    fun getRecommendedTracks(): Flow<Resource<List<RecommendedTrack>>> = flow {
        loadDiskCacheIfNeeded()
        // Show stale cache immediately while refreshing
        if (cachedTracks != null) {
            emit(Resource.Success(cachedTracks!!))
        } else {
            emit(Resource.Loading)
        }
        try {
            val lastFmUsername = settingsStore.getLastFmUsername()
            val lbUsername = settingsStore.getListenBrainzUsername()

            if (lastFmUsername == null && lbUsername == null) {
                emit(Resource.Error("Connect Last.fm or ListenBrainz in Settings to get recommendations"))
                return@flow
            }

            val fetched = fetchAll(lastFmUsername, lbUsername)
            val allTracks = fetched.tracks
            if (allTracks.isEmpty()) {
                if (fetched.anyFailed) {
                    // A request failed: that isn't "nothing to recommend", so keep
                    // what's shown.
                    emit(Resource.Error("Failed to load recommendations"))
                } else {
                    cachedTracks = null
                    saveDiskCache()
                    emit(Resource.Success(emptyList()))
                    emit(Resource.Error(emptyRecommendationsMessage(lbUsername != null, lastFmUsername != null)))
                }
                return@flow
            }

            // Deduplicate by normalized artist+title
            val seen = mutableSetOf<String>()
            val deduped = allTracks.filter { track ->
                val key = "${track.artist.lowercase().trim()}|${track.title.lowercase().trim()}"
                seen.add(key) // returns false if already present
            }

            Log.d(TAG, "Merged recommendations → ${deduped.size} unique tracks")
            cachedTracks = deduped
            saveDiskCache()
            emit(Resource.Success(deduped))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recommendations", e)
            emit(Resource.Error("Failed to load recommendations"))
        }
    }

    /**
     * Get recommended artists extracted from the recommended tracks.
     * Mirrors desktop: unique artists from merged tracks, with progressive image loading.
     * Desktop's resolveRecommendationArtistImages() fetches images one-by-one and
     * updates state after each, so artists appear with images progressively.
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
            val lastFmUsername = settingsStore.getLastFmUsername()
            val lbUsername = settingsStore.getListenBrainzUsername()

            if (lastFmUsername == null && lbUsername == null) {
                emit(Resource.Error("Connect Last.fm or ListenBrainz in Settings to get recommendations"))
                return@flow
            }

            val fetched = fetchAll(lastFmUsername, lbUsername)
            val allTracks = fetched.tracks
            if (allTracks.isEmpty()) {
                if (fetched.anyFailed) {
                    emit(Resource.Error("Failed to load recommendations"))
                } else {
                    cachedArtists = null
                    saveDiskCache()
                    emit(Resource.Success(emptyList()))
                    emit(Resource.Error(emptyRecommendationsMessage(lbUsername != null, lastFmUsername != null)))
                }
                return@flow
            }

            // Extract unique artists, carrying over images from cached data
            val cachedImagesByName = cachedArtists?.associateBy(
                { it.name.lowercase().trim() },
                { it.imageUrl },
            ) ?: emptyMap()

            val seenArtists = mutableSetOf<String>()
            val artists = allTracks.mapNotNull { track ->
                val key = track.artist.lowercase().trim()
                if (seenArtists.add(key)) {
                    RecommendedArtist(
                        name = track.artist,
                        source = track.source,
                        imageUrl = cachedImagesByName[key],
                    )
                } else null
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
                } catch (_: Exception) { /* skip */ }
            }
            // Save final enriched data to disk
            saveDiskCache()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recommended artists", e)
            emit(Resource.Error("Failed to load recommendations"))
        }
    }

    private class Fetched(val tracks: List<RecommendedTrack>, val anyFailed: Boolean)

    /**
     * Both services in parallel, ListenBrainz first (higher priority in the
     * merge, matching desktop). [Fetched.anyFailed] tells a failed request
     * apart from a service with nothing to recommend.
     */
    private suspend fun fetchAll(lastFmUsername: String?, lbUsername: String?): Fetched = coroutineScope {
        val lastFm = async { lastFmUsername?.let { fetchLastFmRecommendations(it) } }
        val lb = async { lbUsername?.let { fetchListenBrainzRecommendations(it) } }
        val lbTracks = lb.await()
        val lastFmTracks = lastFm.await()
        Fetched(
            tracks = lbTracks.orEmpty() + lastFmTracks.orEmpty(),
            anyFailed = (lbUsername != null && lbTracks == null) || (lastFmUsername != null && lastFmTracks == null),
        )
    }

    /**
     * Fetch recommendations from Last.fm's web endpoint.
     * Endpoint: https://www.last.fm/player/station/user/{username}/recommended
     * This is a web API, not the official Last.fm API v2.
     */
    private suspend fun fetchLastFmRecommendations(username: String): List<RecommendedTrack>? {
        return try {
            val url = "https://www.last.fm/player/station/user/$username/recommended"
            val response = httpClient.get(url) {
                header("Accept", "application/json")
            }

            if (!response.status.isSuccess()) {
                Log.w(TAG, "Last.fm recommendations failed: ${response.status.value}")
                return null
            }

            val body = response.bodyAsText()
            val parsed = wireJson.decodeFromString<LastFmRecsResponse>(body)
            val playlist = parsed.playlist ?: return emptyList()

            val tracks = playlist.mapNotNull { item ->
                try {
                    val title = item.name ?: return@mapNotNull null
                    val artistName = item.artists?.firstOrNull()?.name ?: return@mapNotNull null
                    if (title.isBlank() || artistName.isBlank()) return@mapNotNull null
                    // Last.fm's station feed reports seconds (e.g. 262 for a 4:22
                    // song); RecommendedTrack.duration, like Track.duration, is ms (#349).
                    val duration = (item.duration ?: 0L) * 1000
                    RecommendedTrack(
                        title = title,
                        artist = artistName,
                        duration = if (duration > 0) duration else null,
                        source = "lastfm",
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse Last.fm recommendation item", e)
                    null
                }
            }

            Log.d(TAG, "Fetched ${tracks.size} Last.fm recommendations for $username")
            tracks
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Last.fm recommendations failed", e)
            null
        }
    }

    /**
     * Fetch recommendations from ListenBrainz's recommendation playlists.
     *
     * With no playlist yet (a new account, or before ListenBrainz has generated
     * one) this returns nothing. It used to fall back to the user's own top
     * recordings of the month, shown as recommendations: the opposite of what
     * the page promises (desktop parachord#1121). The page says why instead.
     * Null when the request failed.
     */
    private suspend fun fetchListenBrainzRecommendations(username: String): List<RecommendedTrack>? {
        return try {
            val playlistTracks = listenBrainzClient.getRecommendationPlaylistTracks(username)
            Log.d(TAG, "Fetched ${playlistTracks.size} ListenBrainz playlist recommendations for $username")
            playlistTracks.map { track ->
                RecommendedTrack(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    duration = track.durationMs, // ms, like Track.duration (#349)
                    source = "listenbrainz",
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "ListenBrainz recommendations failed for $username", e)
            null
        }
    }
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

/** Disk cache wrapper for JSON serialization. */
@Serializable
private data class RecommendationsDiskCache(
    val tracks: List<RecommendedTrack>,
    val artists: List<RecommendedArtist>,
)

/** Wire model for Last.fm web `/player/station/user/{u}/recommended` response. */
@Serializable
private data class LastFmRecsResponse(
    val playlist: List<LastFmRecItem>? = null,
)

@Serializable
private data class LastFmRecItem(
    val name: String? = null,
    val duration: Long? = null,
    val artists: List<LastFmRecArtist>? = null,
)

@Serializable
private data class LastFmRecArtist(
    val name: String? = null,
)
