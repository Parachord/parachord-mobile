package com.parachord.android.ui.screens.playlists

import com.parachord.shared.ui.UiMessages
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.repository.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
class EditPlaylistViewModel constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistDao: PlaylistDao,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val playlistId: String = savedStateHandle["playlistId"] ?: ""

    val playlist: StateFlow<PlaylistEntity?> = playlistDao.getByIdFlow(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Mutable working copy of tracks for drag-reorder and deletion. */
    private val _editableTracks = MutableStateFlow<List<PlaylistTrackEntity>>(emptyList())
    val editableTracks: StateFlow<List<PlaylistTrackEntity>> = _editableTracks.asStateFlow()

    /** Current playlist name being edited. */
    private val _editName = MutableStateFlow("")
    val editName: StateFlow<String> = _editName.asStateFlow()

    /** Whether any changes have been made. */
    private val _hasChanges = MutableStateFlow(false)
    val hasChanges: StateFlow<Boolean> = _hasChanges.asStateFlow()

    private var originalTracks: List<PlaylistTrackEntity> = emptyList()
    private var originalName: String = ""

    init {
        viewModelScope.launch {
            // Load initial data
            val tracks = libraryRepository.getPlaylistTracks(playlistId).first()
            originalTracks = tracks
            _editableTracks.value = tracks

            val pl = playlistDao.getById(playlistId)
            originalName = pl?.name ?: ""
            _editName.value = originalName
        }
    }

    fun updateName(name: String) {
        _editName.value = name
        checkForChanges()
    }

    fun removeTrack(index: Int) {
        val current = _editableTracks.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _editableTracks.value = current
            checkForChanges()
        }
    }

    fun moveTrack(fromIndex: Int, toIndex: Int) {
        val current = _editableTracks.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices && fromIndex != toIndex) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            _editableTracks.value = current
            checkForChanges()
        }
    }

    private fun checkForChanges() {
        val nameChanged = _editName.value != originalName
        val tracksChanged = _editableTracks.value.map { it.trackTitle to it.trackArtist } !=
            originalTracks.map { it.trackTitle to it.trackArtist }
        _hasChanges.value = nameChanged || tracksChanged
    }

    /** Persist all changes and return. */
    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            val nameChanged = _editName.value.trim() != originalName
            val currentTracks = _editableTracks.value

            // Check if tracks changed (order or removals)
            val tracksChanged = currentTracks.map { it.trackTitle to it.trackArtist } !=
                originalTracks.map { it.trackTitle to it.trackArtist }

            if (nameChanged && _editName.value.isNotBlank()) {
                libraryRepository.renamePlaylist(playlistId, _editName.value.trim())
            }

            if (tracksChanged) {
                libraryRepository.reorderPlaylistTracks(playlistId, currentTracks)
                // Update track count if tracks were removed
                val pl = playlistDao.getById(playlistId)
                if (pl != null && pl.trackCount != currentTracks.size) {
                    val now = System.currentTimeMillis()
                    playlistDao.update(pl.copy(
                        trackCount = currentTracks.size,
                        updatedAt = now,
                        lastModified = now,
                        locallyModified = true,
                    ))
                }
            }

            onDone()
        }
    }

    /**
     * Phase 6.5 — one-shot toast events for sync-aware delete
     * Unsupported responses (e.g. Apple Music's 401 on DELETE).
     */
    private val _toastEvents = kotlinx.coroutines.channels.Channel<String>(
        kotlinx.coroutines.channels.Channel.BUFFERED,
    )
    val toastEvents: kotlinx.coroutines.flow.Flow<String> = _toastEvents.receiveAsFlow()

    /**
     * Delete this playlist entirely. Phase 6.5 sync-aware: also
     * attempts remote deletion and surfaces a toast if any provider
     * returned Unsupported (per Decision D8).
     */
    fun deletePlaylist(onDone: () -> Unit) {
        viewModelScope.launch {
            val pl = playlist.value ?: run { onDone(); return@launch }
            val attempts = libraryRepository.deletePlaylistWithSync(pl)
            val unsupported = attempts.filter {
                it.result is com.parachord.shared.sync.DeleteResult.Unsupported
            }
            if (unsupported.isNotEmpty()) {
                _toastEvents.trySend(
                    UiMessages.playlistDeletionUnsupported(unsupported.map { it.providerDisplayName }),
                )
            }
            onDone()
        }
    }
}
