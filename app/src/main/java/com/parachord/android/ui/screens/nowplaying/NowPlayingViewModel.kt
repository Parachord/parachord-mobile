package com.parachord.android.ui.screens.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.ConcertsRepository
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackState
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.android.resolver.TrackResolverCache
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
class NowPlayingViewModel constructor(
    private val playbackStateHolder: PlaybackStateHolder,
    private val playbackController: PlaybackController,
    private val libraryRepository: LibraryRepository,
    private val settingsStore: SettingsStore,
    private val trackResolverCache: TrackResolverCache,
    private val concertsRepository: ConcertsRepository,
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playbackStateHolder.state

    /** User-configured resolver priority order, used to sort resolver icons on track rows. */
    val resolverOrder: StateFlow<List<String>> = settingsStore.getResolverOrderFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> =
        libraryRepository.getAllPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Resolver badge names for UI display — shared across all screens via TrackResolverCache. */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> = trackResolverCache.trackResolverConfidences

    /** All resolved sources for the current track, for the source switcher dropdown. */
    val trackSources: StateFlow<Map<String, List<com.parachord.android.resolver.ResolvedSource>>> =
        trackResolverCache.trackSources

    /** Whether the currently playing artist is on tour. */
    private val _isOnTour = MutableStateFlow(false)
    val isOnTour: StateFlow<Boolean> = _isOnTour.asStateFlow()
    private var lastCheckedArtist: String? = null

    init {
        // Resolve queue tracks in background as they change (concurrent, deduplicated)
        viewModelScope.launch {
            playbackState.collect { state ->
                val allTracks = buildList {
                    state.currentTrack?.let { add(it) }
                    addAll(state.upNext)
                }
                if (allTracks.isNotEmpty()) {
                    trackResolverCache.resolveInBackground(allTracks, backfillDb = false)
                }
            }
        }

        // Check if current artist is on tour near the user's selected area
        viewModelScope.launch {
            playbackState
                .map { it.currentTrack?.artist }
                .distinctUntilChanged()
                .collect { artist ->
                    if (artist.isNullOrBlank() || artist == lastCheckedArtist) return@collect
                    lastCheckedArtist = artist
                    _isOnTour.value = false // Reset while checking
                    try {
                        val loc = settingsStore.getConcertLocation()
                        _isOnTour.value = concertsRepository.checkOnTour(
                            artistName = artist,
                            lat = loc.latitude,
                            lon = loc.longitude,
                            radiusMiles = loc.radiusMiles,
                        )
                    } catch (e: Exception) {
                        Log.w("NowPlayingVM", "On-tour check failed for '$artist'", e)
                    }
                }
        }
    }

    fun togglePlayPause() {
        playbackController.togglePlayPause()
    }

    fun skipNext() {
        playbackController.skipNext()
    }

    fun skipPrevious() {
        playbackController.skipPrevious()
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    fun toggleShuffle() {
        playbackController.toggleShuffle()
    }

    // Queue management
    fun playFromQueue(index: Int) {
        playbackController.playFromQueue(index)
    }

    fun moveInQueue(from: Int, to: Int) {
        playbackController.moveInQueue(from, to)
    }

    fun removeFromQueue(index: Int) {
        playbackController.removeFromQueue(index)
    }

    fun clearQueue() {
        playbackController.clearQueue()
    }

    // Context menu actions
    fun playNext(track: TrackEntity) {
        playbackController.insertNext(listOf(track))
    }

    fun addToQueue(track: TrackEntity) {
        playbackController.addToQueue(listOf(track))
    }

    fun addToPlaylist(playlist: PlaylistEntity, track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addTracksToPlaylist(playlist.id, listOf(track))
        }
    }

    fun addToCollection(track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addToCollection(track)
        }
    }

    // Spinoff
    fun toggleSpinoff() {
        playbackController.toggleSpinoff()
    }

    /** Switch the current track to a different resolver source (restarts from 0:00). */
    fun switchSource(resolver: String) {
        playbackController.switchSource(resolver)
    }
}
