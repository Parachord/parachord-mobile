package com.parachord.android.car.cal

import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import androidx.car.app.CarContext
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.shared.car.CarBrowse
import com.parachord.shared.car.CarIds
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.home.HomeQueue
import com.parachord.shared.home.HomeQueues
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.WeeklyPlaylistsRepository
import com.parachord.shared.deeplink.ProviderPlaylistResolver
import com.parachord.shared.metadata.toTrackEntity
import com.parachord.shared.repository.HistoryMixesRepository
import com.parachord.shared.search.SearchSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.core.context.GlobalContext

/**
 * The bridge between the CAL template screens and the shared playback stack:
 * every template tap plays through the same queues the phone Home, the
 * recommendations page and the Android Auto tree play, and every call is
 * posted to the main thread (`PlaybackController` requires it). The session
 * token registration links the host's `MediaPlaybackTemplate` to our Media3
 * session.
 */
class CalPlaybackBridge(
    private val playbackController: PlaybackController,
    private val carBrowse: CarBrowse,
    private val libraryRepository: LibraryRepository,
    metadataService: MetadataService,
    private val weeklyPlaylistsRepository: WeeklyPlaylistsRepository,
    private val providerPlaylistResolver: ProviderPlaylistResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val queues = HomeQueues(
        lovedTracks = { emptyList() },
        playlistTracks = { id ->
            libraryRepository.getPlaylistTracks(id).first().map { pt -> libraryRepository.playlistTrackToTrackEntity(pt) }
        },
        mixTracks = { emptyList() },
        catalogQueues = CatalogQueues(metadataService),
        collectionRadio = { carBrowse.playRequest(CarIds.COLLECTION_RADIO) },
    )

    private val catalogQueues = CatalogQueues(metadataService)

    fun playCollectionRadio() = play { queues.collectionRadio() }

    fun playPlaylist(id: String, name: String) = play { queues.playlist(id, name) }

    fun playQueue(tracks: List<Track>, startIndex: Int, name: String) = onMain {
        playbackController.playQueue(tracks, startIndex, PlaybackContext(type = "playlist", name = name))
    }

    fun playFromQueue(index: Int) = onMain {
        playbackController.playFromQueue(index)
    }

    fun playSearchTrack(track: com.parachord.shared.model.Track, query: String) = onMain {
        playbackController.playTrack(track, PlaybackContext(type = "search", name = query))
    }

    fun playSearchRemote(result: com.parachord.shared.metadata.TrackSearchResult, query: String) = onMain {
        playbackController.playTrack(result.toTrackEntity(), PlaybackContext(type = "search", name = query))
    }

    fun playSearchLibraryAlbum(album: com.parachord.shared.model.Album) = scope.launch {
        try {
            val tracks = libraryRepository.getAlbumTracks(album.id).first()
            if (tracks.isEmpty()) {
                say("Couldn't load ${album.title}")
            } else {
                onMain { playbackController.playQueue(tracks, 0, CatalogQueues.albumContext(album.title, album.artist)) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            say("Couldn't load ${album.title}")
        }
    }

    fun playSearchRemoteAlbum(title: String, artist: String) = scope.launch {
        val queue = try {
            catalogQueues.album(title, artist)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (queue == null) {
            say("Couldn't load $title")
        } else {
            onMain { playbackController.playQueue(queue.tracks, 0, queue.context) }
        }
    }

    fun playSearchArtist(name: String) = scope.launch {
        val queue = try {
            catalogQueues.artistTopSongs(name)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (queue == null) {
            say("Couldn't load $name")
        } else {
            onMain { playbackController.playQueue(queue.tracks, 0, queue.context) }
        }
    }

    /** A recommendation entry's tap — the same plays the recommendations page's taps make. */
    fun playRecommendation(row: RecommendationRow, item: RecommendationItem, rowItems: List<RecommendationItem>) = scope.launch {
        when (item.type) {
            RecommendationType.SONG ->
                songQueue(rowItems, rowItems.indexOf(item).takeIf { it >= 0 } ?: 0, row)?.let { queue ->
                    onMain { playbackController.playQueue(queue.tracks, 0, queue.context) }
                }
            RecommendationType.ALBUM ->
                catalogQueues.album(item.name, item.artist.orEmpty())?.let { queue ->
                    onMain { playbackController.playQueue(queue.tracks, 0, queue.context) }
                } ?: say("Couldn't load ${item.name}")
            RecommendationType.ARTIST ->
                catalogQueues.artistTopSongs(item.name)?.let { queue ->
                    onMain { playbackController.playQueue(queue.tracks, 0, queue.context) }
                }
            RecommendationType.PLAYLIST -> {
                val weekly = item.weekly
                if (weekly != null) {
                    val tracks = weeklyTracks(weekly, weeklyPlaylistsRepository.loadPlaylistTracks(weekly.id))
                    if (tracks.isEmpty()) {
                        say("Couldn't load ${item.name}")
                    } else {
                        onMain { playbackController.playQueue(tracks, 0, weeklyContext(weekly)) }
                    }
                } else {
                    val queue = try {
                        servicePlaylistQueue(item) { url -> providerPlaylistResolver.resolve(url) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (queue == null) {
                        say("Couldn't load ${item.name}")
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

    /** Registers the Media3 session token with the host, so its `MediaPlaybackTemplate` and the car's media card track our playback. */
    fun register(carContext: CarContext) {
        scope.launch {
            try {
                playbackController.awaitConnected()
            } catch (_: Exception) {
                return@launch
            }
            val token = com.parachord.android.playback.PlaybackService.sessionCompatToken ?: return@launch
            val manager = carContext.getCarService(MediaPlaybackManager::class.java)
            manager.registerMediaPlaybackToken(token)
        }
    }

    private fun play(load: suspend () -> HomeQueue) {
        scope.launch {
            when (val queue = load()) {
                is HomeQueue.Play -> onMain {
                    val shuffle = queue.shuffle
                    if (shuffle == null) {
                        playbackController.playQueue(queue.tracks, 0, queue.context)
                    } else {
                        playbackController.playQueue(queue.tracks, 0, queue.context, shuffle)
                    }
                }
                is HomeQueue.Failed -> say(queue.message)
                HomeQueue.Empty -> Unit
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        mainHandler.post(block)
    }
}
