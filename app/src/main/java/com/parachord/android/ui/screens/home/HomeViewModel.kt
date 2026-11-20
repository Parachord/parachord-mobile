package com.parachord.android.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.ai.AiRecommendations
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.WeeklyPlaylistEntry
import com.parachord.android.data.scanner.ScanProgress
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.shared.car.CarBrowse
import com.parachord.shared.car.CarIds
import com.parachord.shared.deeplink.ChatPrompt
import com.parachord.shared.deeplink.ProviderPlaylistResolver
import com.parachord.shared.home.HOME_RECENT_LOVES
import com.parachord.shared.home.HistoryMix
import com.parachord.shared.home.HomeSession
import com.parachord.shared.home.HomeSources
import com.parachord.shared.home.HomeState
import com.parachord.shared.home.JumpTile
import com.parachord.shared.playback.CatalogQueues
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.servicePlaylistTracks
import com.parachord.shared.repository.AnnouncementsRepository
import com.parachord.shared.repository.recentLoves
import com.parachord.shared.ui.UiMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The old Home's collection stats card. Task 4's screen drops it. */
data class CollectionStats(
    val tracks: Int = 0,
    val albums: Int = 0,
    val artists: Int = 0,
    val playlists: Int = 0,
    val friends: Int = 0,
)

/** The old Home's Discover card preview. Task 4's screen drops it. */
data class DiscoverPreview(
    val title: String,
    val subtitle: String,
    val artworkUrl: String? = null,
    val label: String = "",
)

/**
 * Home: the shared [HomeSession] ([state]) plus what Home's taps play. Every
 * rule (what shows, the loads and their throttles, getting started) lives in
 * the session; this only wires Android's playback and library to it.
 */
class HomeViewModel(
    private val sources: HomeSources,
    settingsStore: SettingsStore,
    private val repository: LibraryRepository,
    private val playbackController: PlaybackController,
    playbackStateHolder: PlaybackStateHolder,
    private val trackResolverCache: TrackResolverCache,
    metadataService: MetadataService,
    private val carBrowse: CarBrowse,
    private val providerPlaylistResolver: ProviderPlaylistResolver,
    private val announcementsRepository: AnnouncementsRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "HomeViewModel"
        const val LOVES_CONTEXT = "loves"
        const val LOVES_NAME = "Recent Loves"
        const val MIX_CONTEXT = "mix"
    }

    private val session = HomeSession(sources, settingsStore, viewModelScope)

    /** Everything Home draws. */
    val state: StateFlow<HomeState> = session.state

    /** Album and top-songs queues, shared with iOS (#588). */
    private val catalogQueues = CatalogQueues(metadataService)

    // ── Session actions ──────────────────────────────────────────────

    /** Home came on screen: the loads, under their throttles. */
    fun onVisible() {
        (sources as? RefreshableHomeSources)?.refresh()
        session.onVisible()
    }

    /** A new visit: a new phrasing for the Ask example. */
    fun reseedAskExample() = session.reseedAskExample()

    /** Pop of the Tops' Try again. */
    fun retryCharts() = session.retryCharts()

    fun setMixSource(id: String) = session.setMixSource(id)

    fun setAlbumSource(id: String) = session.setAlbumSource(id)

    fun skipSetup() = session.skipSetup()

    fun resumeSetup() = session.resumeSetup()

    fun dismissSetupItem(id: String) = session.dismissSetupItem(id)

    /** The banner Home painted; its view is reported once per id. */
    fun bannerShown(id: String) = session.bannerShown(id)

    fun dismissBanner(id: String) {
        viewModelScope.launch { announcementsRepository.dismiss(id) }
    }

    fun bannerCtaClicked(id: String) = announcementsRepository.trackCtaClick(id)

    /**
     * Ask Shuffleupagus. [send] is `DeepLinkViewModel.handleInAppChatPrompt`
     * (activity-scoped, so the screen passes it): no confirmation for a prompt
     * typed in the app. Blank sends nothing; a long one is capped.
     */
    fun ask(prompt: String, send: (String) -> Unit) {
        val text = ChatPrompt.cap(prompt)
        if (text.isBlank()) return
        send(text)
    }

    // ── Play ─────────────────────────────────────────────────────────

    /** "Play Collection Radio": the car's Collection Radio (every track row, shuffled). */
    fun playCollectionRadio() {
        viewModelScope.launch {
            guard("Collection Radio") {
                val request = carBrowse.playRequest(CarIds.COLLECTION_RADIO) ?: return@guard
                playbackController.playQueue(request.tracks, request.startIndex, shuffle = request.shuffle ?: true)
            }
        }
    }

    fun playTile(tile: JumpTile) {
        when (tile) {
            is JumpTile.Playlist -> playPlaylist(tile.playlist.id, tile.playlist.name)
            is JumpTile.Loves -> playRecentLoves()
            is JumpTile.Album -> playAlbumByName(tile.album.album, tile.album.artist)
            is JumpTile.Mix -> playMix(tile.mix)
        }
    }

    /** A history mix: its tracks through the registry's link lookup, as a `mix` queue (Jump back in's active check reads it). */
    fun playMix(mix: HistoryMix) {
        viewModelScope.launch {
            guard("mix '${mix.title}'") {
                val play = providerPlaylistResolver.resolve(mix.url) ?: return@guard
                val tracks = servicePlaylistTracks(play)
                if (tracks.isEmpty()) return@guard
                playbackController.playQueue(tracks, 0, context = PlaybackContext(MIX_CONTEXT, mix.title, mix.id))
            }
        }
    }

    fun playRecentLoves() {
        viewModelScope.launch {
            val loves = currentLoves()
            if (loves.isEmpty()) return@launch
            playbackController.playQueue(loves, 0, context = PlaybackContext(LOVES_CONTEXT, LOVES_NAME))
        }
    }

    fun queueRecentLoves() {
        viewModelScope.launch {
            val loves = currentLoves()
            if (loves.isNotEmpty()) playbackController.addToQueue(loves)
        }
    }

    private suspend fun currentLoves(): List<TrackEntity> =
        recentLoves(sources.collectionTracks.first(), HOME_RECENT_LOVES)

    /** A saved playlist, from the top. */
    fun playPlaylist(playlistId: String, playlistName: String) {
        viewModelScope.launch {
            val tracks = repository.getPlaylistTracks(playlistId).first()
            if (tracks.isEmpty()) return@launch
            val entities = tracks.map { repository.playlistTrackToTrackEntity(it) }
            playbackController.playQueue(
                entities,
                startIndex = 0,
                context = PlaybackContext(type = "playlist", name = playlistName, id = playlistId),
            )
        }
    }

    /** A recommended album card's Play. */
    fun playAlbum(item: RecommendationItem) = playAlbumByName(item.name, item.artist.orEmpty())

    /** An album that isn't in the library (#588): its tracks from the catalog, as an `album` queue. */
    fun playAlbumByName(albumTitle: String, artistName: String) {
        viewModelScope.launch {
            guard("album '$albumTitle'") {
                val queue = catalogQueues.album(albumTitle, artistName) ?: return@guard
                playbackController.playQueue(queue.tracks, startIndex = 0, context = queue.context)
            }
        }
    }

    fun queueAlbumByName(albumTitle: String, artistName: String) {
        viewModelScope.launch {
            guard("album '$albumTitle'") {
                val queue = catalogQueues.album(albumTitle, artistName) ?: return@guard
                playbackController.addToQueue(queue.tracks)
            }
        }
    }

    fun playArtistTopSongs(artistName: String) {
        viewModelScope.launch {
            guard("top songs for '$artistName'") {
                val queue = catalogQueues.artistTopSongs(artistName) ?: return@guard
                playbackController.playQueue(queue.tracks, startIndex = 0, context = queue.context)
            }
        }
    }

    fun queueArtistTopSongs(artistName: String) {
        viewModelScope.launch {
            guard("top songs for '$artistName'") {
                val queue = catalogQueues.artistTopSongs(artistName) ?: return@guard
                playbackController.addToQueue(queue.tracks)
            }
        }
    }

    private suspend fun guard(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play $what", e)
        }
    }

    // ── Rows and menus ───────────────────────────────────────────────

    /** Resolves one track row while it's on screen (#181); see ResolveWhileVisible. */
    fun resolveVisible(track: TrackEntity): Job =
        trackResolverCache.resolveInBackground(listOf(track))

    fun playNext(track: TrackEntity) {
        playbackController.insertNext(listOf(track))
    }

    fun addToQueue(track: TrackEntity) {
        playbackController.addToQueue(listOf(track))
    }

    fun addToPlaylist(playlist: PlaylistEntity, track: TrackEntity) {
        viewModelScope.launch {
            repository.addTracksToPlaylist(playlist.id, listOf(track))
        }
    }

    /** Removes a love (multi-provider sync removal included). */
    fun removeTrackFromCollection(track: TrackEntity) {
        viewModelScope.launch {
            repository.removeFromCollection(track)
        }
    }

    val playlists: StateFlow<List<PlaylistEntity>> =
        repository.getAllPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * One-shot toasts for Home: [deletePlaylist] when an Apple Music mirror
     * answers `DeleteResult.Unsupported` (Decision D8).
     */
    private val _toastEvents = Channel<String>(Channel.BUFFERED)
    val toastEvents: Flow<String> = _toastEvents.receiveAsFlow()

    /** The services this playlist is on, for the delete dialog (#684). */
    suspend fun getDeletableMirrors(playlistId: String): List<String> =
        repository.getPlaylistMirrors(playlistId).keys.toList()

    /** Sync-aware delete from a playlist card's menu; an unsupported remote delete is reported (D8). */
    fun deletePlaylist(playlist: PlaylistEntity, deleteFromProviders: Set<String>) {
        viewModelScope.launch {
            val attempts = repository.deletePlaylistWithSync(playlist, deleteFromProviders)
            val unsupported = attempts.filter {
                it.result is com.parachord.shared.sync.DeleteResult.Unsupported
            }
            if (unsupported.isNotEmpty()) {
                _toastEvents.trySend(
                    UiMessages.playlistDeletionUnsupported(unsupported.map { it.providerDisplayName }),
                )
            }
        }
    }

    fun toggleArtistCollection(artistName: String, imageUrl: String?, isInCollection: Boolean) {
        viewModelScope.launch {
            if (isInCollection) {
                repository.deleteArtistByName(artistName)
            } else {
                repository.addArtistToCollection(
                    ArtistEntity(
                        id = "manual-${java.util.UUID.randomUUID()}",
                        name = artistName,
                        imageUrl = imageUrl,
                    )
                )
            }
        }
    }

    fun addAlbumToCollection(title: String, artist: String, artworkUrl: String?) {
        viewModelScope.launch {
            val album = AlbumEntity(
                id = "album-${title.hashCode()}-${artist.hashCode()}",
                title = title,
                artist = artist,
                artworkUrl = artworkUrl,
            )
            repository.addAlbumToCollection(album)
        }
    }

    fun removeAlbumFromCollection(title: String, artist: String) {
        viewModelScope.launch {
            val existing = repository.getAlbumByTitleAndArtist(title, artist)
            if (existing != null) {
                repository.deleteAlbumWithSync(existing)
            }
        }
    }

    // ── The old screen, until Task 4 rewrites it ─────────────────────
    // HomeScreen.kt still reads these. The sections they fed are gone from
    // Home, so each is a fixed value (the old screen hides those sections);
    // the rest still work. Delete this block with the old screen.

    val resolverOrder: StateFlow<List<String>> = settingsStore.getResolverOrderFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> = trackResolverCache.trackResolverConfidences
    val trackArtwork: StateFlow<Map<String, String>> = trackResolverCache.trackArtwork
    val playbackState = playbackStateHolder.state

    val recentTracks: StateFlow<List<TrackEntity>> = repository.getAllTracks()
        .map { recentLoves(it, 20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recentPlaylists: StateFlow<List<PlaylistEntity>> = repository.getAllPlaylists()
        .map { list -> list.sortedByDescending { if (it.lastModified > 0L) it.lastModified else it.updatedAt }.take(6) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Always true: the old welcome/scan card is gone from Home. */
    val hasLibrary: StateFlow<Boolean?> = MutableStateFlow(true)
    val scanProgress: StateFlow<ScanProgress> = MutableStateFlow(ScanProgress())
    val recentAlbums: StateFlow<List<AlbumEntity>> = MutableStateFlow(emptyList())
    val friendActivity: StateFlow<List<FriendEntity>> = MutableStateFlow(emptyList())
    val collectionStats: StateFlow<CollectionStats> = MutableStateFlow(CollectionStats())
    val forYouPreview: StateFlow<DiscoverPreview?> = MutableStateFlow(null)
    val criticalDarlingsPreview: StateFlow<DiscoverPreview?> = MutableStateFlow(null)
    val freshDropsPreview: StateFlow<DiscoverPreview?> = MutableStateFlow(null)
    val popOfTheTopsPreview: StateFlow<DiscoverPreview?> = MutableStateFlow(null)
    val hasAiPlugins: StateFlow<Boolean?> = state.map { it.chatAvailable }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val aiRecommendations: StateFlow<AiRecommendations?> = MutableStateFlow(null)
    val aiLoading: StateFlow<Boolean> = MutableStateFlow(false)
    val aiError: StateFlow<String?> = MutableStateFlow(null)
    val weeklyJams: StateFlow<List<WeeklyPlaylistEntry>?> = MutableStateFlow(null)
    val weeklyExploration: StateFlow<List<WeeklyPlaylistEntry>?> = MutableStateFlow(null)
    val weeklyCovers: StateFlow<Map<String, List<String>>> = MutableStateFlow(emptyMap())
    val weeklyTrackCounts: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())

    fun refreshAiRecommendations() {}
    fun scanLocalMusic() {}
    fun togglePin(friend: FriendEntity) {}
    fun playWeeklyPlaylist(entry: WeeklyPlaylistEntry, contextType: String) {}
    fun saveWeeklyPlaylist(entry: WeeklyPlaylistEntry, contextType: String) {}
    fun togglePlayPause() = playbackController.togglePlayPause()

    fun playTrack(track: TrackEntity) {
        val tracks = recentTracks.value
        playbackController.playQueue(tracks, startIndex = tracks.indexOf(track).coerceAtLeast(0))
    }
}
