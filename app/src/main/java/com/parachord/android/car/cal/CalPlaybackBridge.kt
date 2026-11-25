package com.parachord.android.car.cal

import android.os.Handler
import android.os.Looper
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.WeeklyPlaylistsRepository
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.shared.car.CarBrowse
import com.parachord.shared.car.CarIds
import com.parachord.shared.deeplink.ProviderPlaylistResolver
import com.parachord.shared.home.HomeQueue
import com.parachord.shared.home.HomeQueues
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.metadata.toTrackEntity
import com.parachord.shared.model.Album
import com.parachord.shared.model.Track
import com.parachord.shared.playback.CatalogQueues
import com.parachord.shared.playback.PlaybackContext
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationRow
import com.parachord.shared.recommendations.RecommendationType
import com.parachord.shared.recommendations.servicePlaylistQueue
import com.parachord.shared.recommendations.songQueue
import com.parachord.shared.recommendations.weeklyContext
import com.parachord.shared.recommendations.weeklyTracks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The bridge between the CAL template screens and the shared playback stack:
 * every template tap plays through the same queues the phone Home, the
 * recommendations page and the Android Auto tree play, and every call is
 * posted to the main thread (`PlaybackController` requires it). Failures emit
 * onto [messages] so `CalMediaSession` surfaces them with a `CarToast`.
 */
class CalPlaybackBridge(
    private val playbackController: PlaybackController,
    private val stateHolder: PlaybackStateHolder,
    private val carBrowse: CarBrowse,
    private val libraryRepository: LibraryRepository,
    metadataService: MetadataService,
    private val weeklyPlaylistsRepository: WeeklyPlaylistsRepository,
    private val providerPlaylistResolver: ProviderPlaylistResolver,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val postToMain: (() -> Unit) -> Unit = defaultMainPoster(),
) {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val queues = HomeQueues(
        lovedTracks = {
            com.parachord.shared.repository.lovedTracks(libraryRepository.getAllTracks().first())
                .take(com.parachord.shared.car.CAR_LOVED_CAP)
        },
        playlistTracks = { id ->
            libraryRepository.getPlaylistTracks(id).first().map { pt -> libraryRepository.playlistTrackToTrackEntity(pt) }
        },
        mixTracks = { emptyList() },
        catalogQueues = CatalogQueues(metadataService),
        collectionRadio = { carBrowse.playRequest(CarIds.COLLECTION_RADIO) },
    )

    private val catalogQueues = CatalogQueues(metadataService)

    suspend fun ensureConnected() {
        playbackController.connect()
        playbackController.awaitConnected()
    }

    fun playCollectionRadio() = play { queues.collectionRadio() }

    fun playLovedSongs() = play { queues.loves() }

    fun playPlaylist(id: String, name: String) = play { queues.playlist(id, name) }

    fun playFromQueue(index: Int) = onMain {
        playbackController.playFromQueue(index)
    }

    /** Toggles Parachord's shuffle, as the phone's Now Playing button does. */
    fun toggleShuffle() = onMain {
        playbackController.toggleShuffle()
    }

    /**
     * Starts a spinoff from the playing track, or ends the running one — the
     * media button's rule: a tap while the pool is still being built is
     * ignored, so it can't restart it.
     */
    fun toggleSpinoff() {
        if (stateHolder.state.value.spinoffLoading) return
        onMain { playbackController.toggleSpinoff() }
    }

    fun playSearchTrack(track: Track, query: String) = scope.launch {
        connectIfNeeded()
        onMain { playbackController.playTrack(track, PlaybackContext(type = "search", name = query)) }
    }

    fun playSearchRemote(result: TrackSearchResult, query: String, artworkUrl: String? = null) = scope.launch {
        connectIfNeeded()
        val track = result.toTrackEntity(artworkUrl = artworkUrl ?: result.artworkUrl)
        onMain { playbackController.playTrack(track, PlaybackContext(type = "search", name = query)) }
    }

    fun playSearchLibraryAlbum(album: Album) = scope.launch {
        connectIfNeeded()
        try {
            val tracks = libraryRepository.getAlbumTracks(album.id).first()
            if (tracks.isEmpty()) {
                say("Couldn’t load ${album.title}")
            } else {
                onMain { playbackController.playQueue(tracks, 0, CatalogQueues.albumContext(album.title, album.artist)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            say("Couldn’t load ${album.title}")
        }
    }

    fun playSearchRemoteAlbum(title: String, artist: String) = scope.launch {
        connectIfNeeded()
        val queue = try {
            catalogQueues.album(title, artist)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (queue == null) {
            say("Couldn’t load $title")
        } else {
            onMain { playbackController.playQueue(queue.tracks, 0, queue.context) }
        }
    }

    fun playSearchArtist(name: String) = scope.launch {
        connectIfNeeded()
        val queue = try {
            catalogQueues.artistTopSongs(name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (queue == null) {
            say("Couldn’t load $name")
        } else {
            onMain { playbackController.playQueue(queue.tracks, 0, queue.context) }
        }
    }

    /** A recommendation entry's tap — the same plays the recommendations page's taps make. */
    fun playRecommendation(row: RecommendationRow, item: RecommendationItem, rowItems: List<RecommendationItem>) = scope.launch {
        connectIfNeeded()
        when (item.type) {
            RecommendationType.SONG ->
                songQueue(rowItems, rowItems.indexOf(item).takeIf { it >= 0 } ?: 0, row)?.let { queue ->
                    onMain { playbackController.playQueue(queue.tracks, 0, queue.context) }
                } ?: say("Couldn’t load ${item.name}")
            RecommendationType.ALBUM -> {
                val queue = try {
                    catalogQueues.album(item.name, item.artist.orEmpty())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (queue == null) {
                    say("Couldn’t load ${item.name}")
                } else {
                    onMain { playbackController.playQueue(queue.tracks, 0, queue.context) }
                }
            }
            RecommendationType.ARTIST -> {
                val queue = try {
                    catalogQueues.artistTopSongs(item.name)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (queue == null) {
                    say("Couldn’t load ${item.name}")
                } else {
                    onMain { playbackController.playQueue(queue.tracks, 0, queue.context) }
                }
            }
            RecommendationType.PLAYLIST -> {
                val weekly = item.weekly
                if (weekly != null) {
                    val tracks = try {
                        weeklyTracks(weekly, weeklyPlaylistsRepository.loadPlaylistTracks(weekly.id))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (tracks.isEmpty()) {
                        say("Couldn’t load ${item.name}")
                    } else {
                        onMain { playbackController.playQueue(tracks, 0, weeklyContext(weekly)) }
                    }
                } else {
                    val queue = try {
                        servicePlaylistQueue(item) { url -> providerPlaylistResolver.resolve(url) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (queue == null) {
                        say("Couldn’t load ${item.name}")
                    } else {
                        onMain { playbackController.playQueue(queue.tracks, 0, queue.context) }
                    }
                }
            }
        }
    }

    fun say(text: String) {
        _messages.tryEmit(text)
    }

    private suspend fun connectIfNeeded() {
        try {
            ensureConnected()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Proceed so PlaybackController can still handle or report the failure.
        }
    }

    private fun play(load: suspend () -> HomeQueue) {
        scope.launch {
            connectIfNeeded()
            val queue = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                HomeQueue.Failed("Couldn’t start playback")
            }
            when (queue) {
                is HomeQueue.Play -> onMain {
                    val shuffle = queue.shuffle ?: stateHolder.state.value.shuffleEnabled
                    playbackController.playQueue(queue.tracks, 0, queue.context, shuffle)
                }
                is HomeQueue.Failed -> say(queue.message)
                HomeQueue.Empty -> say("Nothing to play yet")
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        postToMain(block)
    }

    private companion object {
        fun defaultMainPoster(): (() -> Unit) -> Unit {
            val handler = Handler(Looper.getMainLooper())
            return { block -> handler.post(block) }
        }
    }
}
