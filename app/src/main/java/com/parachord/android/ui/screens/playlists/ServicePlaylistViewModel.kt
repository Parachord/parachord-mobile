package com.parachord.android.ui.screens.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.android.playlist.PlaylistImportManager
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.shared.deeplink.ProviderPlaylistResolver
import com.parachord.shared.model.Resource
import com.parachord.shared.recommendations.RecommendationCopy
import com.parachord.shared.recommendations.playlistCovers
import com.parachord.shared.recommendations.recommendedPlaylistKey
import com.parachord.shared.recommendations.servicePlaylistContext
import com.parachord.shared.recommendations.servicePlaylistTracks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the view asks its screen to do once Save finishes. */
sealed class ServicePlaylistEvent {
    data class Saved(val playlistId: String, val name: String) : ServicePlaylistEvent()
    data class Message(val text: String) : ServicePlaylistEvent()
}

/**
 * A recommended service playlist opened in a playlist view (desktop's `openServicePlaylist`):
 * the tracks come from the registry's link lookup ([ProviderPlaylistResolver]), nothing is
 * saved until the user taps Save, which imports the link like any other playlist link.
 */
class ServicePlaylistViewModel(
    savedStateHandle: SavedStateHandle,
    private val providerPlaylistResolver: ProviderPlaylistResolver,
    private val playlistImportManager: PlaylistImportManager,
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val playbackStateHolder: PlaybackStateHolder,
    private val trackResolverCache: TrackResolverCache,
) : ViewModel() {

    private val url: String = savedStateHandle["url"] ?: ""
    val title: String = savedStateHandle["name"] ?: ""
    private val sourceName: String = savedStateHandle["source"] ?: ""
    private val image: String? = savedStateHandle.get<String>("image")?.takeIf { it.isNotBlank() }

    val description: String = RecommendationCopy.recommendedBy(sourceName)

    private val _coverUrl = MutableStateFlow(image)
    /** The cover the card showed, else the lookup's cover. */
    val coverUrl: StateFlow<String?> = _coverUrl

    private val _tracks = MutableStateFlow<Resource<List<TrackEntity>>>(Resource.Loading)
    val tracks: StateFlow<Resource<List<TrackEntity>>> = _tracks

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private val _events = MutableSharedFlow<ServicePlaylistEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ServicePlaylistEvent> = _events.asSharedFlow()

    val nowPlayingTitle: StateFlow<String?> = playbackStateHolder.state
        .map { it.currentTrack?.title }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers

    val allPlaylists: StateFlow<List<PlaylistEntity>> = libraryRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        _tracks.value = Resource.Loading
        loadJob = viewModelScope.launch {
            val play = try {
                providerPlaylistResolver.resolve(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val list = play?.let { servicePlaylistTracks(it) }.orEmpty()
            if (play == null || list.isEmpty()) {
                _tracks.value = Resource.Error(RecommendationCopy.couldntLoad(title))
                return@launch
            }
            if (_coverUrl.value == null) {
                _coverUrl.value = play.albumArt?.takeIf { it.isNotBlank() }
            }
            _tracks.value = Resource.Success(list)
        }
    }

    /** The first four distinct track covers, for the header's mosaic when the card had no cover. */
    fun trackCovers(list: List<TrackEntity>): List<String> = playlistCovers(list.map { it.artworkUrl })

    /** Row resolution is visibility-scoped: the row's `ResolveWhileVisible` cancels this job. */
    fun resolveVisible(track: TrackEntity): Job =
        trackResolverCache.resolveInBackground(listOf(track), backfillDb = false)

    fun playFrom(index: Int) {
        val list = (tracks.value as? Resource.Success)?.data ?: return
        if (list.isEmpty()) return
        playbackController.playQueue(list, index.coerceIn(0, list.lastIndex), context = servicePlaylistContext(title, url))
    }

    fun playNext(track: TrackEntity) = playbackController.insertNext(listOf(track))

    fun addToQueue(track: TrackEntity) = playbackController.addToQueue(listOf(track))

    fun addToPlaylist(target: PlaylistEntity, track: TrackEntity) {
        viewModelScope.launch { libraryRepository.addTracksToPlaylist(target.id, listOf(track)) }
    }

    fun toggleCollection(track: TrackEntity, isInCollection: Boolean) {
        viewModelScope.launch {
            if (isInCollection) libraryRepository.removeFromCollection(track) else libraryRepository.addToCollection(track)
        }
    }

    private var savedPlaylistId: String? = null

    /**
     * Saves the playlist through the link importer under its stable key ([recommendedPlaylistKey]),
     * then asks the screen to open the saved one. A playlist already saved from this link (an
     * earlier visit) is opened instead of imported again. A save that went through stays "saving"
     * and remembers the playlist, so another tap before the screen navigates opens it at once.
     */
    fun save() {
        savedPlaylistId?.let { _events.tryEmit(ServicePlaylistEvent.Saved(it, title)); return }
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val result = playlistImportManager.importFromUrl(url, savedKey = recommendedPlaylistKey(url))
                savedPlaylistId = result.playlistId
                _events.tryEmit(ServicePlaylistEvent.Saved(result.playlistId, title))
            } catch (e: CancellationException) {
                _saving.value = false
                throw e
            } catch (e: Exception) {
                _saving.value = false
                _events.tryEmit(ServicePlaylistEvent.Message(RecommendationCopy.couldntSave(title)))
            }
        }
    }
}
