package com.parachord.shared.home

import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Each Jump back in tile's art by [jumpTileKey]: the shared [jumpTileArt],
 * else a cover [found] by [JumpTileArtLookup] for an album tile that had none.
 */
fun jumpTilesArt(tiles: List<JumpTile>, loves: List<Track>, found: Map<String, String>): Map<String, JumpTileArt> =
    tiles.associate { tile ->
        val key = jumpTileKey(tile)
        val art = jumpTileArt(tile, loves)
        key to if (art.isEmpty) found[key]?.let { JumpTileArt(imageUrl = it) } ?: art else art
    }

/**
 * Looks up the cover of an album tile that came on screen without one, as the
 * Recommendations album cards do. Each tile is asked once per session; a
 * lookup that threw is asked again the next time the tile shows. [lookup] is
 * the platform's album-art search (`ImageEnrichmentService.lookupAlbumArt`).
 */
class JumpTileArtLookup(
    private val scope: CoroutineScope,
    private val lookup: suspend (album: String, artist: String) -> String?,
) {
    private val _found = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Covers found so far, by [jumpTileKey]. */
    val found: StateFlow<Map<String, String>> = _found.asStateFlow()

    private val mutex = Mutex()
    private val asked = mutableSetOf<String>()

    /** A tile came on screen showing [art]. Only an album tile with no art asks. */
    fun shown(tile: JumpTile, art: JumpTileArt) {
        if (tile !is JumpTile.Album || !art.isEmpty) return
        val key = jumpTileKey(tile)
        if (key in _found.value) return
        scope.launch {
            if (!mutex.withLock { asked.add(key) }) return@launch
            try {
                val url = lookup(tile.album.album, tile.album.artist)?.trim()
                if (!url.isNullOrEmpty()) _found.update { it + (key to url) }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { mutex.withLock { asked.remove(key) } }
                throw e
            } catch (e: Exception) {
                mutex.withLock { asked.remove(key) }
                Log.w(TAG, "Album art for '${tile.album.album}' failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "JumpTileArtLookup"
    }
}
