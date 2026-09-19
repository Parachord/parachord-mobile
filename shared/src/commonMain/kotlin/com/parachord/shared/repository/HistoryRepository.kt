package com.parachord.shared.repository

import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.bestImageUrl
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.HistoryAlbum
import com.parachord.shared.model.HistoryArtist
import com.parachord.shared.model.HistoryTrack
import com.parachord.shared.model.RecentTrack
import com.parachord.shared.model.Resource
import com.parachord.shared.platform.Log
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Repository for listening history data, fetching from Last.fm and ListenBrainz.
 * Mirrors the desktop app's history page data sources.
 *
 * The Last.fm API key is passed in via constructor (Android sources it
 * from `BuildConfig.LASTFM_API_KEY`; iOS will source it from
 * `AppConfig` once the iOS DI module lights up). The shared class
 * stays platform-agnostic.
 */
class HistoryRepository(
    private val lastFmClient: LastFmClient,
    private val listenBrainzClient: ListenBrainzClient,
    private val settingsStore: SettingsStore,
    private val metadataService: MetadataService,
    private val lastFmApiKey: String,
) {
    companion object {
        private const val TAG = "HistoryRepository"
        private const val DEDUPE_WINDOW_SECONDS = 300 // 5 minutes
    }

    /**
     * Fetch user's top tracks from Last.fm for the given period.
     * Enriches tracks missing artwork by looking up album art via Cover Art Archive / Last.fm / Spotify.
     */
    fun getTopTracks(period: String, limit: Int = 50): Flow<Resource<List<HistoryTrack>>> = flow {
        emit(Resource.Loading)
        try {
            val username = settingsStore.getLastFmUsername()
            if (username == null) {
                emit(Resource.Error("Connect Last.fm to view your top tracks"))
                return@flow
            }

            val response = lastFmClient.getUserTopTracks(
                user = username,
                period = period,
                limit = limit,
                apiKey = lastFmApiKey,
            )

            val tracks = response.toptracks?.track?.map { track ->
                HistoryTrack(
                    title = track.name,
                    artist = track.artist?.name ?: "",
                    artworkUrl = track.image.bestImageUrl(),
                    playCount = track.playcount?.toIntOrNull() ?: 0,
                    rank = track.attr?.rank?.toIntOrNull() ?: 0,
                )
            } ?: emptyList()

            // Emit initial results immediately (may be missing artwork)
            emit(Resource.Success(tracks))

            // Enrich tracks missing artwork via MetadataService (Cover Art Archive → Last.fm → Spotify)
            val tracksNeedingArt = tracks.filter { it.artworkUrl == null }
            if (tracksNeedingArt.isNotEmpty()) {
                val enriched = enrichTrackArtwork(tracks)
                emit(Resource.Success(enriched))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top tracks", e)
            emit(Resource.Error("Failed to load top tracks"))
        }
    }

    /**
     * Enrich tracks missing artwork by searching via MetadataService.
     * Groups by artist+title and looks up artwork concurrently.
     */
    private suspend fun enrichTrackArtwork(tracks: List<HistoryTrack>): List<HistoryTrack> = coroutineScope {
        // Build a cache of artwork URLs from search results — guarded by Mutex
        // (KMP-safe replacement for the JVM-only `synchronized` block this used
        // to use; lock scope is tiny so contention is negligible).
        val artworkCache = mutableMapOf<String, String>()
        val cacheMutex = Mutex()

        // Batch-lookup missing artwork concurrently (limit to 15 concurrent)
        val tracksNeedingArt = tracks.filter { it.artworkUrl == null }.take(15)
        tracksNeedingArt.map { track ->
            async {
                try {
                    val results = metadataService.searchTracks("${track.artist} ${track.title}", limit = 1)
                    val artwork = results.firstOrNull()?.artworkUrl
                    if (artwork != null) {
                        val key = "${track.artist.lowercase()}|${track.title.lowercase()}"
                        cacheMutex.withLock { artworkCache[key] = artwork }
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }.awaitAll()

        // Apply cached artwork to tracks
        tracks.map { track ->
            if (track.artworkUrl != null) return@map track
            val key = "${track.artist.lowercase()}|${track.title.lowercase()}"
            val enrichedUrl = artworkCache[key]
            if (enrichedUrl != null) track.copy(artworkUrl = enrichedUrl) else track
        }
    }

    /**
     * Fetch user's top albums from Last.fm for the given period.
     */
    fun getTopAlbums(period: String, limit: Int = 50): Flow<Resource<List<HistoryAlbum>>> = flow {
        emit(Resource.Loading)
        try {
            val username = settingsStore.getLastFmUsername()
            if (username == null) {
                emit(Resource.Error("Connect Last.fm to view your top albums"))
                return@flow
            }

            val response = lastFmClient.getUserTopAlbums(
                user = username,
                period = period,
                limit = limit,
                apiKey = lastFmApiKey,
            )

            val albums = response.topalbums?.album?.map { album ->
                HistoryAlbum(
                    name = album.name,
                    artist = album.artist?.name ?: "",
                    artworkUrl = album.image.bestImageUrl(),
                    playCount = album.playcount?.toIntOrNull() ?: 0,
                    rank = album.attr?.rank?.toIntOrNull() ?: 0,
                )
            } ?: emptyList()

            emit(Resource.Success(albums))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top albums", e)
            emit(Resource.Error("Failed to load top albums"))
        }
    }

    /**
     * Fetch user's top artists from Last.fm for the given period.
     * Last.fm deprecated artist images in 2020, so we resolve images from Spotify
     * via MetadataService, matching the desktop's resolveTopArtistImages().
     */
    fun getTopArtists(period: String, limit: Int = 50): Flow<Resource<List<HistoryArtist>>> = flow {
        emit(Resource.Loading)
        try {
            val username = settingsStore.getLastFmUsername()
            if (username == null) {
                emit(Resource.Error("Connect Last.fm to view your top artists"))
                return@flow
            }

            val response = lastFmClient.getUserTopArtists(
                user = username,
                period = period,
                limit = limit,
                apiKey = lastFmApiKey,
            )

            val artists = response.topartists?.artist?.map { artist ->
                HistoryArtist(
                    name = artist.name,
                    imageUrl = artist.image.bestImageUrl(), // Almost always null (deprecated)
                    playCount = artist.playcount?.toIntOrNull() ?: 0,
                    rank = artist.attr?.rank?.toIntOrNull() ?: 0,
                )
            } ?: emptyList()

            // Emit initial results immediately (likely without images)
            emit(Resource.Success(artists))

            // Resolve artist images from Spotify via MetadataService
            // Matches desktop's resolveTopArtistImages() pattern
            val artistsNeedingImages = artists.filter { it.imageUrl == null }
            if (artistsNeedingImages.isNotEmpty()) {
                val enriched = enrichArtistImages(artists)
                emit(Resource.Success(enriched))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch top artists", e)
            emit(Resource.Error("Failed to load top artists"))
        }
    }

    /**
     * Resolve artist images from Spotify via MetadataService.
     * Mirrors desktop's resolveTopArtistImages() which calls getArtistImage() for each artist.
     */
    private suspend fun enrichArtistImages(artists: List<HistoryArtist>): List<HistoryArtist> = coroutineScope {
        val imageCache = mutableMapOf<String, String>()
        val cacheMutex = Mutex()

        // Batch-lookup missing images concurrently (limit to 15).
        // getArtistImage, NOT getArtistInfo: the image path is serial per
        // artist, short-circuits at the first provider that has one, and hits
        // the persistent artist-image cache. getArtistInfo fans out to EVERY
        // provider in parallel, so this 15-wide batch was firing ~90 requests
        // at once and exhausting the device DNS resolver (Sept 2026) — for a
        // full cascade whose only field we read is imageUrl.
        artists.filter { it.imageUrl == null }.take(15).map { artist ->
            async {
                try {
                    val imageUrl = metadataService.getArtistImage(artist.name)
                    if (imageUrl != null) {
                        cacheMutex.withLock { imageCache[artist.name.lowercase()] = imageUrl }
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }.awaitAll()

        artists.map { artist ->
            if (artist.imageUrl != null) return@map artist
            val enrichedUrl = imageCache[artist.name.lowercase()]
            if (enrichedUrl != null) artist.copy(imageUrl = enrichedUrl) else artist
        }
    }

    /**
     * Fetch recently played tracks from both Last.fm and ListenBrainz.
     * Deduplicates by artist+title within 5-minute windows, matching the desktop.
     */
    fun getRecentTracks(): Flow<Resource<List<RecentTrack>>> = flow {
        emit(Resource.Loading)
        try {
            val allTracks = mutableListOf<RecentTrack>()

            coroutineScope {
                // Last.fm recent tracks
                val lastFmDeferred = async { fetchLastFmRecentTracks() }
                // ListenBrainz recent listens
                val lbDeferred = async { fetchListenBrainzRecentTracks() }

                allTracks.addAll(lastFmDeferred.await())
                allTracks.addAll(lbDeferred.await())
            }

            // Deduplicate: group by normalized artist+title, within 5-min windows
            val deduped = deduplicateRecentTracks(allTracks)

            // Sort by timestamp descending (most recent first)
            val sorted = deduped.sortedByDescending { it.timestamp }

            emit(Resource.Success(sorted))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch recent tracks", e)
            emit(Resource.Error("Failed to load recent tracks"))
        }
    }

    private suspend fun fetchLastFmRecentTracks(): List<RecentTrack> {
        val username = settingsStore.getLastFmUsername() ?: return emptyList()
        return try {
            val response = lastFmClient.getUserRecentTracks(
                user = username,
                limit = 50,
                apiKey = lastFmApiKey,
            )
            response.recenttracks?.track?.map { track ->
                RecentTrack(
                    title = track.name,
                    artist = track.artist?.name ?: "",
                    album = track.album?.name,
                    artworkUrl = track.image.bestImageUrl(),
                    timestamp = track.date?.uts?.toLongOrNull() ?: 0,
                    source = "Last.fm",
                    nowPlaying = track.attr?.nowplaying == "true",
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Last.fm recent tracks failed", e)
            emptyList()
        }
    }

    private suspend fun fetchListenBrainzRecentTracks(): List<RecentTrack> {
        val token = settingsStore.getListenBrainzToken() ?: return emptyList()
        val username = settingsStore.getListenBrainzUsername() ?: return emptyList()
        return try {
            val listens = listenBrainzClient.getRecentListens(username, token)
            listens.map { listen ->
                RecentTrack(
                    title = listen.trackName,
                    artist = listen.artistName,
                    album = listen.releaseName,
                    timestamp = listen.listenedAt,
                    source = "ListenBrainz",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "ListenBrainz recent listens failed", e)
            emptyList()
        }
    }

    /**
     * Deduplicate tracks by normalized artist+title within 5-minute windows.
     * Prefers Last.fm entries (they have artwork).
     */
    private fun deduplicateRecentTracks(tracks: List<RecentTrack>): List<RecentTrack> {
        val result = mutableListOf<RecentTrack>()

        for (track in tracks.sortedByDescending { it.timestamp }) {
            val key = "${track.artist.lowercase().trim()}|${track.title.lowercase().trim()}"
            val isDupe = result.any { existing ->
                val existingKey = "${existing.artist.lowercase().trim()}|${existing.title.lowercase().trim()}"
                existingKey == key && abs(existing.timestamp - track.timestamp) < DEDUPE_WINDOW_SECONDS
            }
            if (!isDupe) {
                result.add(track)
            }
        }

        return result
    }
}
