package com.parachord.android.ui.screens.discover

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.RecommendationsRepository
import com.parachord.shared.model.RecommendedArtist
import com.parachord.shared.model.RecommendedTrack
import com.parachord.shared.model.Resource
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.resolver.TrackResolverCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.parachord.android.playback.PlaybackContext
import java.util.UUID
/**
 * ViewModel for the Recommendations screen.
 * Fetches recommended artists and tracks based on listening history.
 * Supports source filtering: All | Last.fm | ListenBrainz (matching desktop).
 * Resolves tracks through the resolver pipeline for content resolver badges.
 */
class RecommendationsViewModel constructor(
    private val recommendationsRepository: RecommendationsRepository,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val playbackController: PlaybackController,
    private val metadataService: MetadataService,
    private val libraryRepository: LibraryRepository,
    /**
     * The app-wide resolver cache (#177). Badges come from here, keyed by
     * title|artist, so they survive leaving and reopening the screen, and rows
     * resolve only once they scroll into view. Recommendations used to resolve
     * every track itself into screen-local state on every open (and again on
     * every resume), firing ~80 Spotify + Apple Music searches each time.
     */
    private val trackResolverCache: TrackResolverCache,
) : ViewModel() {

    companion object {
        private const val TAG = "RecommendationsVM"
        /** [refreshIfStale] reloads on resume only past this age (30 min). */
        private const val STALE_AFTER_MS = 30 * 60 * 1000L
    }

    // Initialize from singleton cache immediately — avoids flash of Loading state
    private val _allArtists = MutableStateFlow<Resource<List<RecommendedArtist>>>(
        recommendationsRepository.cachedArtistsList?.let { Resource.Success(it) } ?: Resource.Loading
    )
    private val _allTracks = MutableStateFlow<Resource<List<RecommendedTrack>>>(
        recommendationsRepository.cachedTracksList?.let { Resource.Success(it) } ?: Resource.Loading
    )

    /** Current source filter: "all", "lastfm", or "listenbrainz" */
    private val _sourceFilter = MutableStateFlow("all")
    val sourceFilter: StateFlow<String> = _sourceFilter

    /** Filtered artists based on current source filter */
    val recommendedArtists: StateFlow<Resource<List<RecommendedArtist>>> = MutableStateFlow<Resource<List<RecommendedArtist>>>(Resource.Loading)

    /** Filtered tracks based on current source filter */
    val recommendedTracks: StateFlow<Resource<List<RecommendedTrack>>> = MutableStateFlow<Resource<List<RecommendedTrack>>>(Resource.Loading)

    /** Track counts per source for filter chip labels */
    private val _sourceCounts = MutableStateFlow(SourceCounts())
    val sourceCounts: StateFlow<SourceCounts> = _sourceCounts

    /** Resolver badge names for display, keyed by title|artist (shared cache). */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers

    /** Resolver confidences for dimming low-confidence badges (shared cache). */
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> =
        trackResolverCache.trackResolverConfidences

    /** The current load's collectors — cancelled before a reload so they don't stack. */
    private var loadJob: Job? = null

    /** When the last load started, for [refreshIfStale]. 0 = never. */
    private var lastLoadAtMs = 0L

    init {
        // Set up filtered flows
        viewModelScope.launch {
            combine(_allArtists, _sourceFilter) { artists, filter ->
                when (artists) {
                    is Resource.Loading -> Resource.Loading
                    is Resource.Error -> artists
                    is Resource.Success -> {
                        val filtered = if (filter == "all") {
                            artists.data
                        } else {
                            artists.data.filter { it.source == filter }
                        }
                        Resource.Success(filtered)
                    }
                }
            }.collect { (recommendedArtists as MutableStateFlow).value = it }
        }
        viewModelScope.launch {
            combine(_allTracks, _sourceFilter) { tracks, filter ->
                when (tracks) {
                    is Resource.Loading -> Resource.Loading
                    is Resource.Error -> tracks
                    is Resource.Success -> {
                        val filtered = if (filter == "all") {
                            tracks.data
                        } else {
                            tracks.data.filter { it.source == filter }
                        }
                        Resource.Success(filtered)
                    }
                }
            }.collect { (recommendedTracks as MutableStateFlow).value = it }
        }

        loadRecommendations()
    }

    fun setSourceFilter(filter: String) {
        _sourceFilter.value = filter
    }

    fun refresh() {
        loadRecommendations()
    }

    /**
     * Called on screen resume. Reloads only when the last load failed (e.g.
     * coming back after connecting Last.fm / ListenBrainz) or is over
     * [STALE_AFTER_MS] old. It used to reload on EVERY resume — including the
     * first, right after init's load — so each visit loaded twice. A load still
     * in flight is left alone. The refresh button forces a reload via [refresh].
     */
    fun refreshIfStale() {
        val failed = _allTracks.value is Resource.Error
        val stale = System.currentTimeMillis() - lastLoadAtMs > STALE_AFTER_MS
        if (failed || stale) loadRecommendations()
    }

    /**
     * Resolve one recommended track once its row is visible (#177). The shared
     * cache dedups by title|artist and bounds concurrency, so scrolling back or
     * reopening the screen is free. backfillDb = false: these rows aren't in
     * the library.
     */
    fun resolveVisibleTrack(track: RecommendedTrack) {
        trackResolverCache.resolveInBackground(listOf(track.toEntity()), backfillDb = false)
    }

    private fun RecommendedTrack.toEntity() = TrackEntity(
        id = "rec-${title.hashCode()}-${artist.hashCode()}",
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        artworkUrl = artworkUrl,
    )

    /**
     * Play a recommended track and populate the queue with the remaining
     * tracks in the filtered list — same pattern as library, playlists, etc.
     *
     * Tracks are added as metadata-only [TrackEntity] objects (no resolver data).
     * [PlaybackController.playTrackInternal] resolves each track on-the-fly
     * via the resolver pipeline when it's time to play, and
     * [TrackResolverCache.resolveInBackground] pre-resolves upcoming tracks
     * so resolver badges appear and playback starts instantly.
     */
    fun playTrack(track: RecommendedTrack) {
        val allTracks = (recommendedTracks.value as? Resource.Success)?.data ?: return
        val index = allTracks.indexOf(track).coerceAtLeast(0)

        val entities = allTracks.map { it.toEntity() }

        playbackController.playQueue(
            entities,
            startIndex = index,
            context = PlaybackContext(type = "recommendations", name = "Recommended Songs"),
        )
    }

    // ── Artist actions ─────────────────────────────────────────────

    fun playArtistTopSongs(artistName: String) {
        viewModelScope.launch {
            try {
                val topTracks = metadataService.getArtistTopTracks(artistName, limit = 10)
                if (topTracks.isEmpty()) return@launch
                val entities = topTracks.mapNotNull { track ->
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.artist} - ${track.title}",
                        spotifyId = track.spotifyId,
                        targetTitle = track.title,
                        targetArtist = track.artist,
                    )
                    val best = resolverScoring.selectBest(sources) ?: return@mapNotNull null
                    TrackEntity(
                        id = "top-${track.title.hashCode()}-${track.artist.hashCode()}",
                        title = track.title, artist = track.artist, album = track.album,
                        duration = track.duration, artworkUrl = track.artworkUrl,
                        sourceType = best.sourceType, sourceUrl = best.url, resolver = best.resolver,
                        spotifyUri = best.spotifyUri, soundcloudId = best.soundcloudId, appleMusicId = best.appleMusicId,
                    )
                }
                if (entities.isNotEmpty()) {
                    playbackController.playQueue(
                        entities, startIndex = 0,
                        context = PlaybackContext(type = "artist", name = artistName),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play top songs for '$artistName'", e)
            }
        }
    }

    fun queueArtistTopSongs(artistName: String) {
        viewModelScope.launch {
            try {
                val topTracks = metadataService.getArtistTopTracks(artistName, limit = 10)
                if (topTracks.isEmpty()) return@launch
                val entities = topTracks.mapNotNull { track ->
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.artist} - ${track.title}",
                        spotifyId = track.spotifyId,
                        targetTitle = track.title,
                        targetArtist = track.artist,
                    )
                    val best = resolverScoring.selectBest(sources) ?: return@mapNotNull null
                    TrackEntity(
                        id = "top-${track.title.hashCode()}-${track.artist.hashCode()}",
                        title = track.title, artist = track.artist, album = track.album,
                        duration = track.duration, artworkUrl = track.artworkUrl,
                        sourceType = best.sourceType, sourceUrl = best.url, resolver = best.resolver,
                        spotifyUri = best.spotifyUri, soundcloudId = best.soundcloudId, appleMusicId = best.appleMusicId,
                    )
                }
                if (entities.isNotEmpty()) playbackController.addToQueue(entities)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue top songs for '$artistName'", e)
            }
        }
    }

    fun toggleArtistCollection(artistName: String, imageUrl: String?, isInCollection: Boolean) {
        viewModelScope.launch {
            if (isInCollection) {
                libraryRepository.deleteArtistByName(artistName)
            } else {
                libraryRepository.addArtist(
                    ArtistEntity(
                        id = "manual-${UUID.randomUUID()}",
                        name = artistName,
                        imageUrl = imageUrl,
                    )
                )
            }
        }
    }

    private fun loadRecommendations() {
        lastLoadAtMs = System.currentTimeMillis()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            launch {
                recommendationsRepository.getRecommendedArtists().collect {
                    _allArtists.value = it
                }
            }
            launch {
                recommendationsRepository.getRecommendedTracks().collect { resource ->
                    _allTracks.value = resource
                    // Update source counts when tracks change. Badges are not
                    // resolved here: each row asks the shared cache once it's
                    // visible (resolveVisibleTrack).
                    if (resource is Resource.Success) {
                        val tracks = resource.data
                        _sourceCounts.value = SourceCounts(
                            total = tracks.size,
                            lastfm = tracks.count { it.source == "lastfm" },
                            listenbrainz = tracks.count { it.source == "listenbrainz" },
                        )
                    }
                }
            }
        }
    }
}

data class SourceCounts(
    val total: Int = 0,
    val lastfm: Int = 0,
    val listenbrainz: Int = 0,
)
