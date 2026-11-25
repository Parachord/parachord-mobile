package com.parachord.android.ui.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.playback.PlaybackController
import com.parachord.shared.car.CarBrowse
import com.parachord.shared.car.CarIds
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.home.HomeQueue
import com.parachord.shared.home.HomeQueues
import com.parachord.shared.playback.CatalogQueues

/**
 * The car home's Library section: the saved playlists as tiles, and what their
 * taps play. Every play goes through the shared [HomeQueues] (what every Home
 * tap plays on both apps), wired the way the phone Home wires it — only the
 * playlist and collection-radio paths are reachable from the car home, so the
 * loves and mix wires are inert.
 */
class CarHomeLibraryViewModel(
    private val repository: LibraryRepository,
    private val playbackController: PlaybackController,
    metadataService: MetadataService,
    carBrowse: CarBrowse,
) : ViewModel() {

    /** The library's saved playlists, newest change first. */
    val playlists: StateFlow<List<PlaylistEntity>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val ownMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = ownMessages.asSharedFlow()

    private val queues = HomeQueues(
        lovedTracks = { emptyList() },
        playlistTracks = { id ->
            repository.getPlaylistTracks(id).first().map { repository.playlistTrackToTrackEntity(it) }
        },
        mixTracks = { emptyList() },
        catalogQueues = CatalogQueues(metadataService),
        collectionRadio = { carBrowse.playRequest(CarIds.COLLECTION_RADIO) },
    )

    /** Collection Radio: everything in the collection, shuffled. */
    fun playCollectionRadio() = play { queues.collectionRadio() }

    /** A saved playlist, from the top. */
    fun playPlaylist(playlist: PlaylistEntity) = play { queues.playlist(playlist.id, playlist.name) }

    private fun play(load: suspend () -> HomeQueue) {
        viewModelScope.launch {
            when (val queue = load()) {
                is HomeQueue.Play -> {
                    val shuffle = queue.shuffle
                    if (shuffle == null) {
                        playbackController.playQueue(queue.tracks, 0, queue.context)
                    } else {
                        playbackController.playQueue(queue.tracks, 0, queue.context, shuffle)
                    }
                }
                is HomeQueue.Failed -> ownMessages.tryEmit(queue.message)
                HomeQueue.Empty -> Unit
            }
        }
    }
}
