package com.parachord.android.ui.screens.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.metadata.ImageEnrichmentService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
class PlaylistsViewModel constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val imageEnrichmentService: ImageEnrichmentService,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    init {
        viewModelScope.launch {
            libraryRepository.backfillPlaylistLastModified()
            settingsStore.getSortPlaylists()?.let { name ->
                runCatching { PlaylistSort.valueOf(name) }.getOrNull()?.let { _sort.value = it }
            }
        }
    }

    val playlists: StateFlow<List<PlaylistEntity>> = libraryRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** localPlaylistId -> providers it EFFECTIVELY syncs with (override-aware),
     *  for the source chips. Reads the per-playlist channel override (authoritative,
     *  set by the Sync menu / picker) so a chip disappears the instant a service is
     *  toggled off — NOT the raw sync_playlist_link rows, which can lag a stale or
     *  manually-deleted mirror. Reloads whenever the playlist list changes. */
    val mirrors: StateFlow<Map<String, List<String>>> = playlists
        .map { runCatching { libraryRepository.getAllEffectivePlaylistChannels() }.getOrDefault(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** All remotes a playlist mirrors to (source + links) — drives the per-mirror
     *  delete dialog. */
    suspend fun getDeletableMirrors(playlistId: String): List<String> =
        libraryRepository.getPlaylistMirrors(playlistId).keys.toList()

    private val _sort = MutableStateFlow(PlaylistSort.RECENT)
    val sort: StateFlow<PlaylistSort> = _sort

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    fun setSort(sort: PlaylistSort) {
        _sort.value = sort
        viewModelScope.launch { settingsStore.setSortPlaylists(sort.name) }
    }
    fun setSearchQuery(query: String) { _searchQuery.value = query }

    val sortedPlaylists: StateFlow<List<PlaylistEntity>> = combine(
        playlists, _sort, _searchQuery,
    ) { list, sort, query ->
        val filtered = if (query.isBlank()) list else {
            list.filter { it.name.contains(query, ignoreCase = true) }
        }
        when (sort) {
            PlaylistSort.RECENT -> filtered.sortedByDescending { it.createdAt }
            PlaylistSort.CREATED -> filtered.sortedBy { it.createdAt }
            PlaylistSort.MODIFIED -> filtered.sortedByDescending {
                if (it.lastModified > 0L) it.lastModified else it.updatedAt
            }
            PlaylistSort.ALPHA_ASC -> filtered.sortedBy { it.name.lowercase() }
            PlaylistSort.ALPHA_DESC -> filtered.sortedByDescending { it.name.lowercase() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Session-scoped set to avoid re-triggering enrichment for the same playlist. */
    private val enrichedPlaylists = mutableSetOf<String>()

    /**
     * Enrich a playlist's artwork if it has none.
     * Generates a 2x2 mosaic from the playlist's track artwork.
     */
    fun enrichPlaylistArtIfNeeded(playlistId: String) {
        if (!enrichedPlaylists.add(playlistId)) return
        viewModelScope.launch {
            imageEnrichmentService.enrichPlaylistArt(playlistId)
        }
    }

    /**
     * Phase 6.5 — one-shot toast events emitted after sync-aware
     * deletes when a provider returned `Unsupported` (Apple Music's
     * 401 on DELETE, per Decision D8). The Playlists screen collects
     * this and shows a Toast.
     */
    private val _toastEvents = kotlinx.coroutines.channels.Channel<String>(
        kotlinx.coroutines.channels.Channel.BUFFERED,
    )
    val toastEvents: kotlinx.coroutines.flow.Flow<String> = _toastEvents.receiveAsFlow()

    fun deletePlaylist(playlist: PlaylistEntity, deleteFromProviders: Set<String>? = null) {
        viewModelScope.launch {
            val attempts = libraryRepository.deletePlaylistWithSync(playlist, deleteFromProviders)
            val unsupported = attempts.filter {
                it.result is com.parachord.shared.sync.DeleteResult.Unsupported
            }
            if (unsupported.isNotEmpty()) {
                val names = unsupported.joinToString(", ") { it.providerDisplayName }
                _toastEvents.trySend(
                    "Removed from Parachord. $names doesn't allow deletion via the API — " +
                        "remove manually in the $names app."
                )
            }
        }
    }

    /** Play all tracks in a playlist. */
    fun playPlaylist(playlistId: String, playlistName: String) {
        viewModelScope.launch {
            val playlistTracks = libraryRepository.getPlaylistTracks(playlistId).first()
            if (playlistTracks.isEmpty()) return@launch
            val entities = playlistTracks.map { libraryRepository.playlistTrackToTrackEntity(it) }
            playbackController.playQueue(
                entities,
                startIndex = 0,
                context = PlaybackContext(type = "playlist", name = playlistName),
            )
        }
    }
}
