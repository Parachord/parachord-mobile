package com.parachord.android.playlist

import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.shared.platform.Log
import com.parachord.shared.playlist.PlaylistUrlImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "PlaylistImportManager"

data class ImportResult(
    val playlistId: String,
    val playlistName: String,
    val trackCount: Int,
)

/**
 * Android's playlist import. A link goes to the shared [PlaylistUrlImporter],
 * which iOS uses too: service playlists through the resolvers (native Spotify
 * and Apple Music, then whichever plugin claims the URL), anything else as a
 * hosted XSPF. An uploaded `.xspf` file imports here.
 */
class PlaylistImportManager(
    private val urlImporter: PlaylistUrlImporter,
    private val libraryRepository: LibraryRepository,
) {
    suspend fun importFromUrl(url: String): ImportResult = withContext(Dispatchers.IO) {
        val r = urlImporter.importFromUrl(url)
        ImportResult(r.playlistId, r.playlistName, r.trackCount)
    }

    suspend fun importFromXspfContent(content: String, filename: String? = null): ImportResult =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Importing playlist from XSPF content (filename=$filename)")
            val parsed = XspfParser.parse(content)
            val playlistId = "imported-xspf-${UUID.randomUUID()}"
            libraryRepository.createPlaylistWithTracks(
                PlaylistEntity(
                    id = playlistId,
                    name = parsed.title,
                    artworkUrl = parsed.tracks.firstOrNull()?.artworkUrl,
                    trackCount = parsed.tracks.size,
                ),
                parsed.tracks,
            )
            ImportResult(playlistId, parsed.title, parsed.tracks.size)
        }
}
