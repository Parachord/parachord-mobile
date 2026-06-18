package com.parachord.android.ui.screens.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.WeeklyPlaylistsRepository
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.android.resolver.TrackResolverCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
class WeeklyPlaylistViewModel constructor(
    savedStateHandle: SavedStateHandle,
    private val weeklyPlaylistsRepository: WeeklyPlaylistsRepository,
    private val libraryRepository: LibraryRepository,
    private val playlistDao: PlaylistDao,
    private val playbackController: PlaybackController,
    private val playbackStateHolder: PlaybackStateHolder,
    private val trackResolverCache: TrackResolverCache,
) : ViewModel() {

    private val playlistId: String = savedStateHandle["playlistId"] ?: ""
    val contextType: String = savedStateHandle["contextType"] ?: "weekly-jam"

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    private val _weekLabel = MutableStateFlow("")
    val weekLabel: StateFlow<String> = _weekLabel

    /** Clean playlist type ("Weekly Jams" / "Weekly Exploration"), for the detail
     *  header — iOS parity (#205), not the verbose LB title. */
    private val _kind = MutableStateFlow("")
    val kind: StateFlow<String> = _kind

    /** Playlist creation date formatted "Jun 15, 2026" — shown on the detail
     *  header instead of the relative week label (#205, matches desktop/iOS). */
    private val _dateLabel = MutableStateFlow("")
    val dateLabel: StateFlow<String> = _dateLabel

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description

    private val _tracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    val tracks: StateFlow<List<TrackEntity>> = _tracks

    private val _coverUrls = MutableStateFlow<List<String>>(emptyList())
    val coverUrls: StateFlow<List<String>> = _coverUrls

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    val nowPlayingTitle: StateFlow<String?> = playbackStateHolder.state
        .map { it.currentTrack?.title }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Resolver badge names for UI display. */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers

    /** All playlists for the playlist picker. */
    val allPlaylists: StateFlow<List<PlaylistEntity>> = libraryRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        loadPlaylist()
    }

    private val savedPlaylistId get() = "listenbrainz-$playlistId"

    private fun loadPlaylist() {
        viewModelScope.launch {
            _isLoading.value = true

            // Check if already saved to library
            _saved.value = playlistDao.getById(savedPlaylistId) != null

            // Find the entry from cached data to get title/weekLabel
            val result = weeklyPlaylistsRepository.loadWeeklyPlaylists()
            val allEntries = (result?.jams.orEmpty()) + (result?.exploration.orEmpty())
            val entry = allEntries.find { it.id == playlistId }

            // "Weekly Jam" (singular) to parallel "Weekly Exploration" in the header.
            val kindLabel = if (contextType == "weekly-jam") "Weekly Jam" else "Weekly Exploration"
            _title.value = entry?.title ?: kindLabel
            _weekLabel.value = entry?.weekLabel ?: ""
            _kind.value = kindLabel
            _dateLabel.value = entry?.dateLabel ?: ""
            _description.value = entry?.description ?: ""

            // Load tracks
            val lbTracks = weeklyPlaylistsRepository.loadPlaylistTracks(playlistId)
            val trackEntities = lbTracks.map { lbTrack ->
                TrackEntity(
                    id = lbTrack.id,
                    title = lbTrack.title,
                    artist = lbTrack.artist,
                    album = lbTrack.album,
                    duration = lbTrack.durationMs?.let { it / 1000 },
                    artworkUrl = lbTrack.albumArt,
                )
            }
            _tracks.value = trackEntities
            _coverUrls.value = lbTracks.mapNotNull { it.albumArt }.distinct().take(4)
            _isLoading.value = false

            // NOTE: resolution is visibility-scoped (#177) — the screen calls
            // resolveVisibleTrack() per row as it scrolls into view, instead of
            // bulk-submitting the whole list on open (which fanned out one
            // Spotify + one iTunes catalog search per track in a single burst,
            // tripping Spotify's account-wide abuse window on a fresh client).
            // Mirrors the desktop's ResolutionScheduler.updateVisibility.
        }
    }

    /**
     * Resolve a single track the moment its row becomes visible (driven by
     * the LazyColumn item's `LaunchedEffect`). Mirrors the desktop's
     * `ResolutionScheduler.updateVisibility` — resolve only the viewport +
     * overscan, not the whole list on open. Submitting per-row is safe
     * because [TrackResolverCache.resolveInBackground] dedups by track key
     * and skips in-flight requests, so scrolling back over an already
     * resolved row is free. `backfillDb = false` because weekly playlists
     * are ephemeral, metadata-only LB tracks with no DB row to backfill.
     */
    fun resolveVisibleTrack(track: TrackEntity) {
        trackResolverCache.resolveInBackground(listOf(track), backfillDb = false)
    }

    fun playAll(startIndex: Int = 0) {
        val trackList = tracks.value
        if (trackList.isEmpty()) return
        val label = if (contextType == "weekly-jam") "Weekly Jams" else "Weekly Exploration"
        val context = PlaybackContext(
            type = contextType,
            name = "${_weekLabel.value}'s $label",
        )
        playbackController.playQueue(trackList, startIndex, context = context)
    }

    fun playTrack(index: Int) {
        playAll(startIndex = index)
    }

    fun playNext(track: TrackEntity) {
        playbackController.insertNext(listOf(track))
    }

    fun addToQueue(track: TrackEntity) {
        playbackController.addToQueue(listOf(track))
    }

    fun queueAll() {
        val trackList = tracks.value
        if (trackList.isEmpty()) return
        playbackController.addToQueue(trackList)
    }

    fun addToPlaylist(targetPlaylist: PlaylistEntity, track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addTracksToPlaylist(targetPlaylist.id, listOf(track))
        }
    }

    fun addToCollection(track: TrackEntity) {
        viewModelScope.launch {
            libraryRepository.addToCollection(track)
        }
    }

    fun toggleCollection(track: TrackEntity, isInCollection: Boolean) {
        viewModelScope.launch {
            if (isInCollection) {
                libraryRepository.deleteTrack(track)
            } else {
                libraryRepository.addToCollection(track)
            }
        }
    }

    /** Save this ephemeral weekly playlist to the user's library. */
    fun saveToLibrary() {
        val trackList = tracks.value
        if (trackList.isEmpty()) return
        viewModelScope.launch {
            val playlist = PlaylistEntity(
                id = savedPlaylistId,
                name = _title.value,
                description = _description.value,
                trackCount = trackList.size,
                artworkUrl = _coverUrls.value.firstOrNull(),
                ownerName = "ListenBrainz",
            )
            libraryRepository.createPlaylistWithTracks(playlist, trackList)
            _saved.value = true
        }
    }
}
