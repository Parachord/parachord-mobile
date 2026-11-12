package com.parachord.android.ui.screens.discover

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.MetadataService
import com.parachord.shared.model.CHARTS_COUNTRIES
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.ChartSong
import com.parachord.android.data.repository.ChartsRepository
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolvedSource
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
class PopOfTheTopsViewModel constructor(
    private val chartsRepository: ChartsRepository,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val playbackController: PlaybackController,
    private val trackResolverCache: TrackResolverCache,
    private val metadataService: MetadataService,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "PopOfTheTopsVM"
    }

    private val _albums = MutableStateFlow<List<ChartAlbum>>(emptyList())
    val albums: StateFlow<List<ChartAlbum>> = _albums

    private val _songs = MutableStateFlow<List<ChartSong>>(emptyList())
    val songs: StateFlow<List<ChartSong>> = _songs

    private val _albumsLoading = MutableStateFlow(false)
    val albumsLoading: StateFlow<Boolean> = _albumsLoading

    private val _songsLoading = MutableStateFlow(false)
    val songsLoading: StateFlow<Boolean> = _songsLoading

    private val _selectedCountry = MutableStateFlow("us")
    val selectedCountry: StateFlow<String> = _selectedCountry

    private val _songsSource = MutableStateFlow("apple")
    val songsSource: StateFlow<String> = _songsSource

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    /** Pre-resolved sources keyed by "title|artist" */
    private val _trackSources = MutableStateFlow<Map<String, List<ResolvedSource>>>(emptyMap())

    /** Resolver badge names for UI display from shared cache */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> = trackResolverCache.trackResolverConfidences

    private var resolveJob: Job? = null

    val countries = CHARTS_COUNTRIES

    init {
        loadAlbums()
        loadSongs()
    }

    /** Called on screen resume — reloads charts data. */
    fun refreshIfStale() {
        loadAlbums()
        loadSongs()
    }

    fun setCountry(code: String) {
        _selectedCountry.value = code
        loadAlbums()
        loadSongs()
    }

    fun setSongsSource(source: String) {
        _songsSource.value = source
        loadSongs()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun filterAlbums(albums: List<ChartAlbum>): List<ChartAlbum> =
        com.parachord.shared.model.filterChartAlbums(albums, _searchQuery.value)

    fun filterSongs(songs: List<ChartSong>): List<ChartSong> =
        com.parachord.shared.model.filterChartSongs(songs, _searchQuery.value)

    fun playSong(song: ChartSong, allSongs: List<ChartSong>) {
        val index = allSongs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        viewModelScope.launch {
            try {
                // 1. Resolve and play the tapped track immediately (use cache if available)
                val tappedEntity = resolveChartSong(song) ?: return@launch
                playbackController.playTrack(tappedEntity, PlaybackContext(type = "charts", name = "Top Songs"))

                // 2. Resolve remaining tracks and add to queue
                val remaining = allSongs.subList(index + 1, allSongs.size)
                if (remaining.isNotEmpty()) {
                    val entities = remaining.mapNotNull { s -> resolveChartSong(s) }
                    if (entities.isNotEmpty()) {
                        playbackController.addToQueue(entities)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play '${song.title}'", e)
            }
        }
    }

    private suspend fun resolveChartSong(song: ChartSong): TrackEntity? {
        val key = trackKey(song.title, song.artist)
        val sources = _trackSources.value[key]
            ?: resolverManager.resolveWithHints(
                query = "${song.artist} - ${song.title}",
                spotifyId = song.spotifyId,
                targetTitle = song.title,
                targetArtist = song.artist,
            )
        val best = resolverScoring.selectBest(sources) ?: return null
        return TrackEntity(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            artworkUrl = song.artworkUrl,
            sourceType = best.sourceType,
            sourceUrl = best.url,
            resolver = best.resolver,
            spotifyUri = best.spotifyUri,
            soundcloudId = best.soundcloudId,
            appleMusicId = best.appleMusicId,
        )
    }

    private fun resolveTracksInBackground(songs: List<ChartSong>) {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            for (song in songs) {
                val key = trackKey(song.title, song.artist)
                if (_trackSources.value.containsKey(key)) continue
                // The shared cache: its sources, a retry of resolvers that
                // couldn't answer, or a fresh resolve (#566).
                try {
                    val sources = trackResolverCache.resolveTrack(song.title, song.artist, spotifyId = song.spotifyId)
                    if (sources.isNotEmpty()) {
                        _trackSources.value = _trackSources.value + (key to sources)
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }
    }

    private fun loadAlbums() {
        viewModelScope.launch {
            _albumsLoading.value = true
            _albums.value = chartsRepository.getAppleMusicAlbums(_selectedCountry.value)
            _albumsLoading.value = false
        }
    }

    private fun loadSongs() {
        viewModelScope.launch {
            _songsLoading.value = true
            _trackSources.value = emptyMap() // clear stale sources for new song list
            _songs.value = when (_songsSource.value) {
                "lastfm" -> chartsRepository.getLastfmCharts(_selectedCountry.value)
                else -> chartsRepository.getAppleMusicSongs(_selectedCountry.value)
            }
            _songsLoading.value = false
            // Pre-resolve all songs in background
            resolveTracksInBackground(_songs.value)
        }
    }

    // ── Album actions ────────────────────────────────────────────────

    fun addAlbumToCollection(title: String, artist: String, artworkUrl: String?) {
        viewModelScope.launch {
            libraryRepository.addAlbumToCollection(AlbumEntity(
                id = "album-${title.hashCode()}-${artist.hashCode()}",
                title = title,
                artist = artist,
                artworkUrl = artworkUrl,
            ))
        }
    }

    /** Album and top-songs queues, shared with iOS (#588). */
    private val catalogQueues = com.parachord.shared.playback.CatalogQueues(metadataService)

    fun queueAlbumByName(albumTitle: String, albumArtist: String) {
        viewModelScope.launch {
            try {
                val queue = catalogQueues.album(albumTitle, albumArtist) ?: return@launch
                playbackController.addToQueue(queue.tracks)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("PopOfTheTopsVM", "Failed to queue album '$albumTitle'", e)
            }
        }
    }
}
