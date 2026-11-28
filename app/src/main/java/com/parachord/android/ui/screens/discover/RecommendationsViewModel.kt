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
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackState
import com.parachord.android.playback.PlaybackStateHolder
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
import com.parachord.shared.recommendations.RecommendationPlay
import com.parachord.shared.recommendations.RecommendationRow
import com.parachord.shared.recommendations.listenerPlay
import com.parachord.shared.recommendations.recommendationItemPlay
import com.parachord.shared.recommendations.songQueue
import com.parachord.shared.recommendations.WeeklyCovers
import com.parachord.shared.ui.UiMessages
import com.parachord.shared.repository.RecommendationPageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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
    playbackStateHolder: PlaybackStateHolder,
    private val historyMixesRepository: com.parachord.shared.repository.HistoryMixesRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "RecommendationsVM"
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

    /** What's playing, for the song rows' now-playing highlight. */
    val playbackState: StateFlow<PlaybackState> = playbackStateHolder.state

    /** Every playlist, for the track menu's Add to Playlist. */
    val playlists: StateFlow<List<PlaylistEntity>> = libraryRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { loadPage(false) }
    }

    /** The page, plus Home's history mixes alongside it (their ids leave the Playlists row). */
    private suspend fun loadPage(force: Boolean) = com.parachord.shared.repository.loadRecommendationsPage(
        force = force,
        background = viewModelScope,
        mixes = { historyMixesRepository.load(it) },
        page = { repository.load(it) },
    )

    fun setSourceFilter(id: String?) {
        _sourceFilter.value = id
    }

    fun toggleRow(rowKey: String) {
        val open = rowKey in _expandedRows.value
        _expandedRows.value = if (open) _expandedRows.value - rowKey else _expandedRows.value + rowKey
        if (!open) viewModelScope.launch { repository.openRow(rowKey) }
    }

    fun refreshIfStale() {
        viewModelScope.launch { loadPage(false) }
    }

    fun retry() {
        viewModelScope.launch { loadPage(true) }
    }

    // ── Release dates and images ────────────────────────────────────

    /** An album or artist card came on screen: the shared repository batches the lookups. */
    fun cardShown(item: RecommendationItem) = repository.cardShown(item)

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

    fun playAlbum(item: RecommendationItem) = withItemQueue(item, announceLoading = true) { q ->
        playbackController.playQueue(q.tracks, startIndex = 0, context = q.context)
    }

    fun queueAlbum(item: RecommendationItem) = withItemQueue(item, announceLoading = true) { q ->
        playbackController.addToQueue(q.tracks)
    }

    fun playWeekly(item: RecommendationItem) {
        if (item.weekly == null) return
        withItemQueue(item, announceLoading = false) { q ->
            playbackController.playQueue(q.tracks, startIndex = 0, context = q.context)
            say(RecommendationCopy.playing(item.name))
        }
    }

    private val weeklyCoverLoader = WeeklyCovers(viewModelScope) { id ->
        weeklyPlaylistsRepository.loadPlaylistTracks(id).map { it.albumArt }
    }

    /** A weekly playlist card's mosaic (the shared [WeeklyCovers] rule). */
    fun weeklyCovers(id: String): StateFlow<List<String>> = weeklyCoverLoader.covers(id)

    fun playServicePlaylist(item: RecommendationItem) = withItemQueue(item, announceLoading = false) { q ->
        playbackController.playQueue(q.tracks, startIndex = 0, context = q.context)
    }

    fun queueServicePlaylist(item: RecommendationItem) = withItemQueue(item, announceLoading = false) { q ->
        playbackController.addToQueue(q.tracks)
    }

    private fun withItemQueue(
        item: RecommendationItem,
        announceLoading: Boolean,
        use: (CatalogQueue) -> Unit,
    ) {
        viewModelScope.launch {
            if (announceLoading) say(RecommendationCopy.loading(item.name))
            val placeholderRow = RecommendationRow(key = item.id, title = null, type = item.type, items = emptyList())
            when (
                val res = recommendationItemPlay(
                    row = placeholderRow,
                    item = item,
                    rowItems = emptyList(),
                    albumQueue = { title, artist -> catalogQueues.album(title, artist) },
                    artistTopSongs = { artist -> catalogQueues.artistTopSongs(artist) },
                    weeklyTracksLoader = { id -> weeklyPlaylistsRepository.loadPlaylistTracks(id) },
                    servicePlaylistResolver = { url -> providerPlaylistResolver.resolve(url) },
                )
            ) {
                is RecommendationPlay.Play -> use(res.queue)
                is RecommendationPlay.Message -> say(res.text)
            }
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
                    // Shared copy from the repository (UiMessages.couldntReach / USER_NOT_FOUND).
                    is Resource.Error -> say(result.message)
                    else -> loadPage(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to follow ${item.name}", e)
                say(UiMessages.couldntReach(item.service))
            }
        }
    }

    // ── Track menu ──────────────────────────────────────────────────

    fun playNext(track: TrackEntity) {
        playbackController.insertNext(listOf(track))
    }

    fun addToQueue(track: TrackEntity) {
        playbackController.addToQueue(listOf(track))
    }

    fun addToPlaylist(playlist: PlaylistEntity, track: TrackEntity) {
        viewModelScope.launch { libraryRepository.addTracksToPlaylist(playlist.id, listOf(track)) }
    }

    /** Whether a song is loved, read when its menu opens. */
    suspend fun isTrackInCollection(title: String, artist: String): Boolean =
        try { libraryRepository.isTrackInCollection(title, artist).first() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }

    fun toggleTrackCollection(track: TrackEntity, isInCollection: Boolean) {
        viewModelScope.launch {
            if (isInCollection) libraryRepository.removeFromCollection(track)
            else libraryRepository.addToCollection(track)
        }
    }

    // ── Album menu ──────────────────────────────────────────────────

    fun isAlbumInCollection(title: String, artist: String): Flow<Boolean> =
        libraryRepository.isAlbumInCollection(title, artist)

    fun toggleAlbumCollection(item: RecommendationItem, isInCollection: Boolean) {
        val artist = item.artist.orEmpty()
        val album = AlbumEntity(
            id = "album-${item.name.hashCode()}-${artist.hashCode()}",
            title = item.name,
            artist = artist,
            artworkUrl = item.imageUrl,
        )
        viewModelScope.launch {
            if (isInCollection) {
                libraryRepository.getAlbumByTitleAndArtist(item.name, artist)
                    ?.let { libraryRepository.deleteAlbumWithSync(it) }
            } else {
                libraryRepository.addAlbumToCollection(album)
            }
        }
    }

    fun isArtistInCollection(name: String): Flow<Boolean> = libraryRepository.isArtistInCollection(name)

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
