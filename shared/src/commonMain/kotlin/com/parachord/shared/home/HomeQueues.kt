package com.parachord.shared.home

import com.parachord.shared.car.CarPlayRequest
import com.parachord.shared.model.Track
import com.parachord.shared.playback.CatalogQueues
import com.parachord.shared.platform.Log
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
 *
 * A load that throws (a database read, the catalog, the mix lookup) never
 * reaches the platform: it is logged and answered with [HomeQueue.Failed]
 * carrying desktop's "Could not load <name>" copy, which both apps show as a
 * toast.
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
    suspend fun mix(mix: HistoryMix): HomeQueue = loading(mix.title) {
        val tracks = mixTracks(mix.url)
        if (tracks.isEmpty()) HomeQueue.Failed(HomeCopy.couldNotLoad(mix.title))
        else HomeQueue.Play(tracks, jumpTileContext(JumpTile.Mix(mix)))
    }

    suspend fun loves(): HomeQueue = loading(HomeCopy.RECENT_LOVES) {
        val loves = lovedTracks()
        if (loves.isEmpty()) HomeQueue.Empty
        else HomeQueue.Play(loves, jumpTileContext(JumpTile.Loves(loves)))
    }

    suspend fun playlist(id: String, name: String): HomeQueue = loading(name) {
        val tracks = playlistTracks(id)
        if (tracks.isEmpty()) HomeQueue.Empty
        else HomeQueue.Play(tracks, PlaybackContext(PLAYLIST_CONTEXT, name, id))
    }

    suspend fun album(title: String, artist: String): HomeQueue = loading(title) {
        val queue = catalogQueues.album(title, artist)
        if (queue == null) HomeQueue.Empty else HomeQueue.Play(queue.tracks, queue.context)
    }

    /** The car's Collection Radio, under the context the car plays it with, shuffled unless told otherwise. */
    suspend fun collectionRadio(): HomeQueue = loading(HomeCopy.COLLECTION_RADIO) {
        val request = collectionRadio.invoke()
        if (request == null) HomeQueue.Empty
        else HomeQueue.Play(request.tracks, PlaybackContext(COLLECTION_CONTEXT, HomeCopy.COLLECTION_RADIO), request.shuffle ?: true)
    }

    /** Runs a load; one that throws is logged and fails with "Could not load [name]". */
    private suspend fun loading(name: String, block: suspend () -> HomeQueue): HomeQueue = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't load '$name' to play", e)
        HomeQueue.Failed(HomeCopy.couldNotLoad(name))
    }

    private companion object {
        const val TAG = "HomeQueues"
    }
}
