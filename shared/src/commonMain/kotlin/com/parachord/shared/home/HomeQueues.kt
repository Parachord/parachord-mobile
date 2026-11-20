package com.parachord.shared.home

import com.parachord.shared.car.CarPlayRequest
import com.parachord.shared.model.Track
import com.parachord.shared.playback.CatalogQueues
import com.parachord.shared.playback.PlaybackContext
import kotlinx.coroutines.CancellationException

/** What a Home tap plays, or why it plays nothing. */
sealed class HomeQueue {
    /** Play [tracks] from the top under [context]; [shuffle] null keeps the current setting. */
    data class Play(val tracks: List<Track>, val context: PlaybackContext, val shuffle: Boolean? = null) : HomeQueue()

    /** The tap couldn't be played; [message] is for a toast. */
    data class Failed(val message: String) : HomeQueue()

    /** Nothing to play, and nothing to say. */
    data object Empty : HomeQueue()
}

/**
 * What each Home tap plays, for both apps: the tracks and the context they play
 * under ([jumpTileContext], so Jump back in's active check sees it). Each
 * platform supplies only where tracks come from and plays the answer.
 */
class HomeQueues(
    private val lovedTracks: suspend () -> List<Track>,
    private val playlistTracks: suspend (playlistId: String) -> List<Track>,
    private val mixTracks: suspend (url: String) -> List<Track>,
    private val catalogQueues: CatalogQueues,
    private val collectionRadio: suspend () -> CarPlayRequest?,
) {
    suspend fun tile(tile: JumpTile): HomeQueue = when (tile) {
        is JumpTile.Playlist -> playlist(tile.playlist.id, tile.playlist.name)
        is JumpTile.Loves -> loves()
        is JumpTile.Album -> album(tile.album.album, tile.album.artist)
        is JumpTile.Mix -> mix(tile.mix)
    }

    /**
     * A history mix: its tracks through the registry's link lookup. A lookup that
     * throws or finds no tracks fails with desktop's "Could not load" copy.
     */
    suspend fun mix(mix: HistoryMix): HomeQueue {
        val tracks = try {
            mixTracks(mix.url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (tracks.isEmpty()) return HomeQueue.Failed(HomeCopy.couldNotLoad(mix.title))
        return HomeQueue.Play(tracks, jumpTileContext(JumpTile.Mix(mix)))
    }

    suspend fun loves(): HomeQueue {
        val loves = lovedTracks()
        if (loves.isEmpty()) return HomeQueue.Empty
        return HomeQueue.Play(loves, jumpTileContext(JumpTile.Loves(loves)))
    }

    suspend fun playlist(id: String, name: String): HomeQueue {
        val tracks = playlistTracks(id)
        if (tracks.isEmpty()) return HomeQueue.Empty
        return HomeQueue.Play(tracks, PlaybackContext(PLAYLIST_CONTEXT, name, id))
    }

    suspend fun album(title: String, artist: String): HomeQueue {
        val queue = catalogQueues.album(title, artist) ?: return HomeQueue.Empty
        return HomeQueue.Play(queue.tracks, queue.context)
    }

    /** The car's Collection Radio, under the context the car plays it with, shuffled unless told otherwise. */
    suspend fun collectionRadio(): HomeQueue {
        val request = collectionRadio.invoke() ?: return HomeQueue.Empty
        return HomeQueue.Play(request.tracks, PlaybackContext("collection", "Collection Radio"), request.shuffle ?: true)
    }
}
