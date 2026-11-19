package com.parachord.android.ui.screens.discover

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.FriendsRepository
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.RecommendationsRepository
import com.parachord.android.data.repository.WeeklyPlaylistsRepository
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.shared.deeplink.ProviderPlaylistResolver
import com.parachord.shared.model.Resource
import com.parachord.shared.model.Track
import com.parachord.shared.playback.CatalogQueue
import com.parachord.shared.playback.CatalogQueues
import com.parachord.shared.recommendations.ListenerItem
import com.parachord.shared.recommendations.ListenerPlay
import com.parachord.shared.recommendations.RecommendationCopy
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationRow
import com.parachord.shared.recommendations.listenerPlay
import com.parachord.shared.recommendations.servicePlaylistQueue
import com.parachord.shared.recommendations.songQueue
import com.parachord.shared.recommendations.weeklyContext
import com.parachord.shared.repository.RecommendationPageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for the Recommendations page. The page itself (rows, source pills,
 * loading and error states) is the shared repository's [page]; this holds the
 * session state (selected source, opened rows) and turns taps into playback
 * through the shared queue builders.
 */
class RecommendationsViewModel(
    private val repository: RecommendationsRepository,
    private val playbackController: PlaybackController,
    private val metadataService: MetadataService,
    private val libraryRepository: LibraryRepository,
    private val trackResolverCache: TrackResolverCache,
    private val weeklyPlaylistsRepository: WeeklyPlaylistsRepository,
    private val providerPlaylistResolver: ProviderPlaylistResolver,
    private val friendsRepository: FriendsRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "RecommendationsVM"
        private const val ALBUM_BATCH_MS = 300L
        private const val WEEKLY_COVERS = 4
    }

    val page: StateFlow<RecommendationPageState> = repository.page

    private val _sourceFilter = MutableStateFlow<String?>(null)
    /** The raw selection; the screen applies `activeSourceFilter`. Session state only. */
    val sourceFilter: StateFlow<String?> = _sourceFilter.asStateFlow()

    private val _expandedRows = MutableStateFlow<Set<String>>(emptySet())
    val expandedRows: StateFlow<Set<String>> = _expandedRows.asStateFlow()

    private val ownMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = merge(repository.messages, ownMessages).let { merged ->
        // Cold merge exposed as a shared flow so collectors see both sources.
        val shared = MutableSharedFlow<String>(extraBufferCapacity = 16)
        viewModelScope.launch { merged.collect { shared.emit(it) } }
        shared.asSharedFlow()
    }

    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> =
        trackResolverCache.trackResolverConfidences
    /** Covers from each song's resolved sources, keyed by title|artist (the plugins send none). */
    val trackArtwork: StateFlow<Map<String, String>> = trackResolverCache.trackArtwork

    private val catalogQueues = CatalogQueues(metadataService)

    init {
        viewModelScope.launch { repository.load(false) }
    }

    fun setSourceFilter(id: String?) {
        _sourceFilter.value = id
    }

    fun toggleRow(rowKey: String) {
        val open = rowKey in _expandedRows.value
        _expandedRows.value = if (open) _expandedRows.value - rowKey else _expandedRows.value + rowKey
        if (!open) viewModelScope.launch { repository.openRow(rowKey) }
    }

    fun refreshIfStale() {
        viewModelScope.launch { repository.load(false) }
    }

    fun retry() {
        viewModelScope.launch { repository.load(true) }
    }

    // ── Release dates ───────────────────────────────────────────────

    private val albumBatch = mutableListOf<RecommendationItem>()
    private var albumBatchJob: Job? = null

    /** Collects albums that come on screen for 300 ms, then asks for their release dates once. */
    fun albumShown(item: RecommendationItem) {
        synchronized(albumBatch) { albumBatch.add(item) }
        if (albumBatchJob?.isActive == true) return
        albumBatchJob = viewModelScope.launch {
            delay(ALBUM_BATCH_MS)
            val batch = synchronized(albumBatch) { albumBatch.toList().also { albumBatch.clear() } }
            if (batch.isNotEmpty()) repository.requestReleaseDates(batch)
        }
    }

    /** Resolve one song while its row is visible (#177); cancelled when the row leaves. */
    fun resolveVisible(track: Track): Job =
        trackResolverCache.resolveInBackground(listOf(track), backfillDb = false)

    // ── Playback ────────────────────────────────────────────────────

    private fun say(text: String) {
        ownMessages.tryEmit(text)
    }

    fun playSong(rowItems: List<RecommendationItem>, index: Int, row: RecommendationRow) {
        val queue = songQueue(rowItems, index, row) ?: return
        playbackController.playQueue(queue.tracks, startIndex = 0, context = queue.context)
    }

    fun playAlbum(item: RecommendationItem) = withAlbumQueue(item) { q ->
        playbackController.playQueue(q.tracks, startIndex = 0, context = q.context)
    }

    fun queueAlbum(item: RecommendationItem) = withAlbumQueue(item) { q ->
        playbackController.addToQueue(q.tracks)
    }

    private fun withAlbumQueue(item: RecommendationItem, use: (CatalogQueue) -> Unit) {
        viewModelScope.launch {
            say(RecommendationCopy.loading(item.name))
            try {
                val queue = catalogQueues.album(item.name, item.artist.orEmpty())
                if (queue == null) say(RecommendationCopy.couldntLoad(item.name)) else use(queue)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load album '${item.name}'", e)
                say(RecommendationCopy.couldntLoad(item.name))
            }
        }
    }

    fun playWeekly(item: RecommendationItem) {
        val weekly = item.weekly ?: return
        viewModelScope.launch {
            try {
                val tracks = weeklyPlaylistsRepository.loadPlaylistTracks(weekly.id).map {
                    Track(
                        id = "weekly-${weekly.id}-${it.id}",
                        title = it.title,
                        artist = it.artist,
                        album = it.album,
                        duration = it.durationMs,
                        artworkUrl = it.albumArt,
                        recordingMbid = it.mbid,
                    )
                }
                if (tracks.isEmpty()) {
                    say(RecommendationCopy.couldntLoad(item.name))
                    return@launch
                }
                playbackController.playQueue(tracks, startIndex = 0, context = weeklyContext(weekly))
                say(RecommendationCopy.playing(item.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play weekly '${item.name}'", e)
                say(RecommendationCopy.couldntLoad(item.name))
            }
        }
    }

    private val weeklyCoverFlows = mutableMapOf<String, MutableStateFlow<List<String>>>()
    private val weeklyCoverLoaded = mutableSetOf<String>()

    /** The first four distinct album covers of a weekly playlist, loaded once per id. */
    fun weeklyCovers(id: String): StateFlow<List<String>> {
        val flow = synchronized(weeklyCoverFlows) {
            weeklyCoverFlows.getOrPut(id) { MutableStateFlow(emptyList()) }
        }
        val start = synchronized(weeklyCoverFlows) { weeklyCoverLoaded.add(id) }
        if (start) {
            viewModelScope.launch {
                try {
                    val covers = weeklyPlaylistsRepository.loadPlaylistTracks(id)
                        .mapNotNull { it.albumArt?.takeIf { a -> a.isNotBlank() } }
                        .distinct().take(WEEKLY_COVERS)
                    if (covers.isEmpty()) synchronized(weeklyCoverFlows) { weeklyCoverLoaded.remove(id) }
                    flow.value = covers
                } catch (e: CancellationException) {
                    synchronized(weeklyCoverFlows) { weeklyCoverLoaded.remove(id) }
                    throw e
                } catch (e: Exception) {
                    synchronized(weeklyCoverFlows) { weeklyCoverLoaded.remove(id) }
                }
            }
        }
        return flow
    }

    fun playServicePlaylist(item: RecommendationItem) = withServiceQueue(item) { q ->
        playbackController.playQueue(q.tracks, startIndex = 0, context = q.context)
    }

    fun queueServicePlaylist(item: RecommendationItem) = withServiceQueue(item) { q ->
        playbackController.addToQueue(q.tracks)
    }

    private fun withServiceQueue(item: RecommendationItem, use: (CatalogQueue) -> Unit) {
        viewModelScope.launch {
            val queue = try {
                servicePlaylistQueue(item) { url -> providerPlaylistResolver.resolve(url) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (queue == null) say(RecommendationCopy.couldntLoad(item.name)) else use(queue)
        }
    }

    fun playListener(item: ListenerItem) {
        viewModelScope.launch {
            when (val play = listenerPlay(item.service, item.name, item.name, friendsRepository::listenerTopTracks)) {
                is ListenerPlay.Play ->
                    playbackController.playQueue(play.queue.tracks, startIndex = 0, context = play.queue.context)
                is ListenerPlay.Message -> say(play.text)
            }
        }
    }

    fun followListener(item: ListenerItem) {
        viewModelScope.launch {
            try {
                when (val result = friendsRepository.follow(item.service, item.name)) {
                    is Resource.Error -> say(result.message)
                    else -> repository.load(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to follow ${item.name}", e)
                say(e.message ?: RecommendationCopy.couldntLoad(item.name))
            }
        }
    }

    // ── Artist actions ──────────────────────────────────────────────

    fun playArtistTopSongs(artistName: String) {
        viewModelScope.launch {
            try {
                val queue = catalogQueues.artistTopSongs(artistName) ?: return@launch
                playbackController.playQueue(queue.tracks, startIndex = 0, context = queue.context)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to play top songs for '$artistName'", e)
            }
        }
    }

    fun queueArtistTopSongs(artistName: String) {
        viewModelScope.launch {
            try {
                val queue = catalogQueues.artistTopSongs(artistName) ?: return@launch
                playbackController.addToQueue(queue.tracks)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to queue top songs for '$artistName'", e)
            }
        }
    }

    fun toggleArtistCollection(artistName: String, imageUrl: String?, isInCollection: Boolean) {
        viewModelScope.launch {
            if (isInCollection) {
                libraryRepository.deleteArtistByName(artistName)
            } else {
                libraryRepository.addArtistToCollection(
                    ArtistEntity(
                        id = "manual-${UUID.randomUUID()}",
                        name = artistName,
                        imageUrl = imageUrl,
                    )
                )
            }
        }
    }
}
