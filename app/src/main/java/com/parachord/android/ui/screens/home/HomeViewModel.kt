package com.parachord.android.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.shared.car.CarBrowse
import com.parachord.shared.car.CarIds
import com.parachord.shared.deeplink.ChatPrompt
import com.parachord.shared.deeplink.ProviderPlaylistResolver
import com.parachord.shared.home.HOME_RECENT_LOVES
import com.parachord.shared.home.HistoryMix
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.home.HomeQueue
import com.parachord.shared.home.HomeQueues
import com.parachord.shared.home.HomeSession
import com.parachord.shared.home.HomeSources
import com.parachord.shared.home.HomeState
import com.parachord.shared.home.JumpTile
import com.parachord.shared.home.JumpTileArt
import com.parachord.shared.home.JumpTileArtLookup
import com.parachord.shared.home.HomeHalfCard
import com.parachord.shared.home.ShownAlbumArtLookup
import com.parachord.shared.home.halfCardArtKey
import com.parachord.shared.home.jumpTilesArt
import com.parachord.shared.home.jumpTileKey
import com.parachord.shared.home.jumpTileContext
import com.parachord.shared.home.PLAYLIST_CONTEXT
import com.parachord.shared.metadata.ImageEnrichmentService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import com.parachord.shared.playback.CatalogQueues
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.servicePlaylistTracks
import com.parachord.shared.repository.AnnouncementsRepository
import com.parachord.shared.repository.RecommendationsRepository
import com.parachord.shared.repository.recentLoves
import com.parachord.shared.ui.UiMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    metadataService: MetadataService,
    private val carBrowse: CarBrowse,
    private val providerPlaylistResolver: ProviderPlaylistResolver,
    private val announcementsRepository: AnnouncementsRepository,
    private val imageEnrichmentService: ImageEnrichmentService,
    private val recommendationsRepository: RecommendationsRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "HomeViewModel"
    }

    private val session = HomeSession(sources, settingsStore, viewModelScope)

    /** Everything Home draws. */
    val state: StateFlow<HomeState> = session.state

    // ── Jump back in art ─────────────────────────────────────────────

    /** Covers looked up for album tiles that came without one (shared rule, both apps). */
    private val artLookup = JumpTileArtLookup(viewModelScope) { album, artist ->
        imageEnrichmentService.lookupAlbumArt(album, artist)
    }

    private val loves = sources.collectionTracks
        .map { recentLoves(it, HOME_RECENT_LOVES) }
        .distinctUntilChanged()

    /** Each tile's art by [jumpTileKey] (shared [jumpTilesArt]). */
    val tileArt: StateFlow<Map<String, JumpTileArt>> = combine(
        state.map { it.tiles }.distinctUntilChanged(),
        loves,
        artLookup.found,
    ) { tiles, loves, found -> jumpTilesArt(tiles, loves, found) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** A tile came on screen: an album tile with no cover looks one up ([JumpTileArtLookup]). */
    fun tileShown(tile: JumpTile) {
        artLookup.shown(tile, tileArt.value[jumpTileKey(tile)] ?: JumpTileArt())
    }

    // ── Half cards' art ──────────────────────────────────────────────

    /** Covers looked up for half cards (Fresh Drops, Critical Darlings, Pop of the Tops) shown without one. */
    private val halfArtLookup = ShownAlbumArtLookup(viewModelScope) { album, artist ->
        imageEnrichmentService.lookupAlbumArt(album, artist)
    }

    /** Found covers by [halfCardArtKey]; the halves apply them with `withHalfCardArt`. */
    val halfArt: StateFlow<Map<String, String>> = halfArtLookup.found

    /** A half card came on screen with no cover. */
    fun halfCardShown(card: HomeHalfCard) {
        halfArtLookup.shown(halfCardArtKey(card), card.title, card.artist)
    }

    // ── Album cards ──────────────────────────────────────────────────

    /**
     * An album card came on screen: the Recommendations repository looks up its
     * release date and, when it has none, its cover, batched with the other
     * cards shown at the same time (as the Recommendations page's cards do).
     */
    fun albumShown(item: RecommendationItem) = recommendationsRepository.cardShown(item)

    fun isAlbumInCollection(title: String, artist: String): Flow<Boolean> =
        repository.isAlbumInCollection(title, artist)

    /** Album and top-songs queues, shared with iOS (#588). */
    private val catalogQueues = CatalogQueues(metadataService)

    // ── Session actions ──────────────────────────────────────────────

    /** Home came on screen: the sources' refresh, then the loads, under their throttles. */
    fun onVisible() {
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

    /** What each tap plays (shared with iOS); this only plays the answer. */
    private val queues = HomeQueues(
        lovedTracks = { currentLoves() },
        playlistTracks = { id ->
            repository.getPlaylistTracks(id).first().map { repository.playlistTrackToTrackEntity(it) }
        },
        mixTracks = { url -> providerPlaylistResolver.resolve(url)?.let { servicePlaylistTracks(it) }.orEmpty() },
        catalogQueues = catalogQueues,
        collectionRadio = { carBrowse.playRequest(CarIds.COLLECTION_RADIO) },
    )

    private fun play(what: String, queue: suspend () -> HomeQueue) {
        viewModelScope.launch {
            guard(what) {
                when (val q = queue()) {
                    is HomeQueue.Play ->
                        if (q.shuffle == null) playbackController.playQueue(q.tracks, 0, q.context)
                        else playbackController.playQueue(q.tracks, 0, q.context, q.shuffle!!)
                    is HomeQueue.Failed -> _toastEvents.trySend(q.message)
                    HomeQueue.Empty -> Unit
                }
            }
        }
    }

    /** "Play Collection Radio": the car's Collection Radio (every track row, shuffled). */
    fun playCollectionRadio() = play("Collection Radio") { queues.collectionRadio() }

    /** A Jump back in tile, under its shared context ([jumpTileContext]), so its active check sees it. */
    fun playTile(tile: JumpTile) = play("tile") { queues.tile(tile) }

    /** A history mix, as a `mix` queue under its link; a failed lookup says so (desktop's toast). */
    fun playMix(mix: HistoryMix) = play("mix '${mix.title}'") { queues.mix(mix) }

    fun playRecentLoves() = play("Recent Loves") { queues.loves() }

    private suspend fun currentLoves(): List<TrackEntity> =
        recentLoves(sources.collectionTracks.first(), HOME_RECENT_LOVES)

    /** A saved playlist, from the top. */
    fun playPlaylist(playlistId: String, playlistName: String) =
        play("playlist") { queues.playlist(playlistId, playlistName) }

    /** A recommended album card's Play. */
    fun playAlbum(item: RecommendationItem) = playAlbumByName(item.name, item.artist.orEmpty())

    /** An album that isn't in the library (#588): its tracks from the catalog, as an `album` queue. */
    fun playAlbumByName(albumTitle: String, artistName: String) =
        play("album '$albumTitle'") { queues.album(albumTitle, artistName) }

    fun queueAlbumByName(albumTitle: String, artistName: String) {
        viewModelScope.launch {
            guard("album '$albumTitle'") {
                val queue = catalogQueues.album(albumTitle, artistName) ?: return@guard
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

    // ── Menus ────────────────────────────────────────────────────────

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
}
