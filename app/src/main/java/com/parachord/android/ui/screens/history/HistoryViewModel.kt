package com.parachord.android.ui.screens.history

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.MetadataService
import com.parachord.shared.model.HistoryAlbum
import com.parachord.shared.model.HistoryArtist
import com.parachord.android.data.repository.HistoryRepository
import com.parachord.shared.model.HistoryTrack
import com.parachord.shared.model.RecentTrack
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.shared.model.Resource
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolvedSource
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import com.parachord.shared.deeplink.HistoryLinkTab
import com.parachord.shared.deeplink.parseHistoryLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
/**
 * ViewModel for the History screen.
 * Manages top tracks, albums, artists, and recent listening history.
 * Supports track resolution and playback via the resolver pipeline.
 */
class HistoryViewModel constructor(
    savedStateHandle: SavedStateHandle,
    private val historyRepository: HistoryRepository,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val playbackController: PlaybackController,
    private val trackResolverCache: TrackResolverCache,
    private val metadataService: MetadataService,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "HistoryViewModel"
    }

    /** A deep link's tab and period (#470); null fields leave the defaults. */
    private val link = parseHistoryLink(savedStateHandle["tab"], savedStateHandle["period"])

    /** Pager page to open on: Top Songs, Top Albums, Top Artists, Recently Played. */
    val initialPage: Int = when (link.tab) {
        HistoryLinkTab.TOP_ALBUMS -> 1
        HistoryLinkTab.TOP_ARTISTS -> 2
        HistoryLinkTab.RECENT -> 3
        HistoryLinkTab.TOP_TRACKS, null -> 0
    }

    // Period filter for top charts
    val selectedPeriod = MutableStateFlow(link.period ?: "1month")

    // Top charts data
    private val _topTracks = MutableStateFlow<Resource<List<HistoryTrack>>>(Resource.Loading)
    val topTracks: StateFlow<Resource<List<HistoryTrack>>> = _topTracks

    private val _topAlbums = MutableStateFlow<Resource<List<HistoryAlbum>>>(Resource.Loading)
    val topAlbums: StateFlow<Resource<List<HistoryAlbum>>> = _topAlbums

    private val _topArtists = MutableStateFlow<Resource<List<HistoryArtist>>>(Resource.Loading)
    val topArtists: StateFlow<Resource<List<HistoryArtist>>> = _topArtists

    // Recent tracks
    private val _recentTracks = MutableStateFlow<Resource<List<RecentTrack>>>(Resource.Loading)

    // Recent tracks sort and search
    val recentSort = MutableStateFlow("recent")
    val recentSearch = MutableStateFlow("")

    // Resolving state
    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving

    /** Cached resolved sources for tracks, keyed by "title|artist" */
    private val _trackSources = MutableStateFlow<Map<String, List<ResolvedSource>>>(emptyMap())

    /** Resolver badge names for UI display from shared cache */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> = trackResolverCache.trackResolverConfidences

    /** Filtered and sorted recent tracks. */
    val filteredRecentTracks: StateFlow<Resource<List<RecentTrack>>> = combine(
        _recentTracks,
        recentSort,
        recentSearch,
    ) { resource, sort, search ->
        when (resource) {
            is Resource.Success -> {
                var tracks = resource.data

                // Filter by search query
                if (search.isNotBlank()) {
                    val query = search.lowercase()
                    tracks = tracks.filter {
                        it.title.lowercase().contains(query) ||
                            it.artist.lowercase().contains(query) ||
                            (it.album?.lowercase()?.contains(query) == true)
                    }
                }

                // Sort
                tracks = when (sort) {
                    "artist" -> tracks.sortedBy { it.artist.lowercase() }
                    "title" -> tracks.sortedBy { it.title.lowercase() }
                    else -> tracks // "recent" — already sorted by timestamp
                }

                Resource.Success(tracks)
            }
            else -> resource
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Resource.Loading)

    init {
        loadTopCharts()
        loadRecentTracks()
    }

    fun setPeriod(period: String) {
        selectedPeriod.value = period
        loadTopCharts()
    }

    fun setRecentSort(sort: String) {
        recentSort.value = sort
    }

    fun setRecentSearch(query: String) {
        recentSearch.value = query
    }

    fun refreshRecentTracks() {
        loadRecentTracks()
    }

    /**
     * Resolve and play a top track at the given index.
     * Queues remaining tracks in the list after the clicked one.
     */
    fun playTopTrack(index: Int) {
        val resource = _topTracks.value
        if (resource !is Resource.Success) return
        val tracks = resource.data
        if (index !in tracks.indices) return

        viewModelScope.launch {
            _isResolving.value = true
            try {
                val track = tracks[index]
                val query = "${track.artist} - ${track.title}"
                Log.d(TAG, "Playing top track: '$query'")

                val entity = resolveTrack(track.title, track.artist, track.artworkUrl)
                if (entity == null) {
                    Log.w(TAG, "No playable source for '$query'")
                    return@launch
                }

                playbackController.playTrack(entity)
                _isResolving.value = false

                // Queue remaining tracks in background
                val remaining = tracks.subList(index + 1, tracks.size)
                if (remaining.isNotEmpty()) {
                    val entities = remaining.mapNotNull { t ->
                        resolveTrack(t.title, t.artist, t.artworkUrl)
                    }
                    if (entities.isNotEmpty()) {
                        val context = PlaybackContext(type = "history", name = "Top Songs")
                        playbackController.addToQueue(entities)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed", e)
            } finally {
                _isResolving.value = false
            }
        }
    }

    /**
     * Resolve and play a recent track at the given index.
     * Queues remaining tracks in the filtered list after the clicked one.
     */
    fun playRecentTrack(index: Int) {
        val resource = filteredRecentTracks.value
        if (resource !is Resource.Success) return
        val tracks = resource.data
        if (index !in tracks.indices) return

        viewModelScope.launch {
            _isResolving.value = true
            try {
                val track = tracks[index]
                val query = "${track.artist} - ${track.title}"
                Log.d(TAG, "Playing recent track: '$query'")

                val entity = resolveTrack(track.title, track.artist, track.artworkUrl, track.album)
                if (entity == null) {
                    Log.w(TAG, "No playable source for '$query'")
                    return@launch
                }

                playbackController.playTrack(entity)
                _isResolving.value = false

                // Queue remaining tracks in background
                val remaining = tracks.subList(index + 1, tracks.size)
                if (remaining.isNotEmpty()) {
                    val entities = remaining.mapNotNull { t ->
                        resolveTrack(t.title, t.artist, t.artworkUrl, t.album)
                    }
                    if (entities.isNotEmpty()) {
                        val context = PlaybackContext(type = "history", name = "Recently Played")
                        playbackController.addToQueue(entities)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed", e)
            } finally {
                _isResolving.value = false
            }
        }
    }

    /**
     * Resolve a track through the resolver pipeline and return a playable TrackEntity.
     */
    private suspend fun resolveTrack(
        title: String,
        artist: String,
        artworkUrl: String?,
        album: String? = null,
    ): TrackEntity? {
        val key = trackKey(title, artist)
        val sources = _trackSources.value[key]
            ?: resolverManager.resolveWithHints(query = "$artist - $title", targetTitle = title, targetArtist = artist)
        val best = resolverScoring.selectBest(sources) ?: return null

        return TrackEntity(
            id = "history-${title.hashCode()}-${artist.hashCode()}",
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            sourceType = best.sourceType,
            sourceUrl = best.url,
            resolver = best.resolver,
            spotifyUri = best.spotifyUri,
            soundcloudId = best.soundcloudId,
            appleMusicId = best.appleMusicId,
        )
    }

    private fun resolveTracksInBackground(tracks: List<HistoryTrack>) {
        viewModelScope.launch {
            for (track in tracks) {
                val key = trackKey(track.title, track.artist)
                if (_trackSources.value.containsKey(key)) continue
                // Check shared cache first (cross-context dedup)
                val cached = trackResolverCache.getSources(track.title, track.artist)
                if (cached != null) {
                    _trackSources.value = _trackSources.value + (key to cached)
                    trackResolverCache.putSources(track.title, track.artist, cached)
                    continue
                }
                try {
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.title} ${track.artist}",
                        targetTitle = track.title,
                        targetArtist = track.artist,
                    )
                    if (sources.isNotEmpty()) {
                        _trackSources.value = _trackSources.value + (key to sources)
                        trackResolverCache.putSources(track.title, track.artist, sources)
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }
    }

    private fun loadTopCharts() {
        val period = selectedPeriod.value

        viewModelScope.launch {
            historyRepository.getTopTracks(period).collect {
                _topTracks.value = it
                if (it is Resource.Success) resolveTracksInBackground(it.data)
            }
        }
        viewModelScope.launch {
            historyRepository.getTopAlbums(period).collect { _topAlbums.value = it }
        }
        viewModelScope.launch {
            historyRepository.getTopArtists(period).collect { _topArtists.value = it }
        }
    }

    private fun resolveRecentTracksInBackground(tracks: List<RecentTrack>) {
        viewModelScope.launch {
            for (track in tracks) {
                val key = trackKey(track.title, track.artist)
                if (_trackSources.value.containsKey(key)) continue
                // Check shared cache first (cross-context dedup)
                val cached = trackResolverCache.getSources(track.title, track.artist)
                if (cached != null) {
                    _trackSources.value = _trackSources.value + (key to cached)
                    trackResolverCache.putSources(track.title, track.artist, cached)
                    continue
                }
                try {
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.title} ${track.artist}",
                        targetTitle = track.title,
                        targetArtist = track.artist,
                    )
                    if (sources.isNotEmpty()) {
                        _trackSources.value = _trackSources.value + (key to sources)
                        trackResolverCache.putSources(track.title, track.artist, sources)
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }
    }

    private fun loadRecentTracks() {
        viewModelScope.launch {
            historyRepository.getRecentTracks().collect {
                _recentTracks.value = it
                if (it is Resource.Success) resolveRecentTracksInBackground(it.data)
            }
        }
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
                    com.parachord.android.data.db.entity.ArtistEntity(
                        id = "manual-${java.util.UUID.randomUUID()}",
                        name = artistName,
                        imageUrl = imageUrl,
                    )
                )
            }
        }
    }

    // ── Album actions ────────────────────────────────────────────────

    fun addAlbumToCollection(title: String, artist: String, artworkUrl: String?) {
        viewModelScope.launch {
            libraryRepository.addAlbum(AlbumEntity(
                id = "album-${title.hashCode()}-${artist.hashCode()}",
                title = title,
                artist = artist,
                artworkUrl = artworkUrl,
            ))
        }
    }

    fun queueAlbumByName(albumTitle: String, albumArtist: String) {
        viewModelScope.launch {
            try {
                val detail = metadataService.getAlbumTracks(albumTitle, albumArtist)
                if (detail == null || detail.tracks.isEmpty()) return@launch
                val entities = detail.tracks.mapNotNull { track ->
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.artist} - ${track.title}",
                        spotifyId = track.spotifyId,
                        targetTitle = track.title,
                        targetArtist = track.artist,
                    )
                    val best = resolverScoring.selectBest(sources) ?: return@mapNotNull null
                    TrackEntity(
                        id = "resolved-${track.title.hashCode()}-${track.artist.hashCode()}-${albumTitle.hashCode()}",
                        title = track.title, artist = track.artist, album = albumTitle,
                        duration = track.duration,
                        artworkUrl = track.artworkUrl ?: detail.artworkUrl,
                        sourceType = best.sourceType, sourceUrl = best.url, resolver = best.resolver,
                        spotifyUri = best.spotifyUri, soundcloudId = best.soundcloudId, appleMusicId = best.appleMusicId,
                    )
                }
                if (entities.isNotEmpty()) playbackController.addToQueue(entities)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue album '$albumTitle'", e)
            }
        }
    }

}
