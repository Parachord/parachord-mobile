package com.parachord.shared.car

import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.Track
import com.parachord.shared.repository.LibraryRepository
import kotlinx.coroutines.flow.first

/** Max loved songs shown in a car's "Loved Songs" folder. */
const val CAR_LOVED_CAP = 100

/** Max playlists, or tracks of one playlist, listed in a car. */
const val CAR_PLAYLIST_CAP = 500

/** Ids of the car browse tree. Shared by Android Auto and CarPlay. */
object CarIds {
    const val ROOT = "parachord_root"
    const val PLAYLISTS = "browse:playlists"
    const val LOVED = "browse:loved"
    const val COLLECTION_RADIO = "action:collection-radio"

    private const val PLAYLIST_PREFIX = "playlist:"
    private const val PLAYLIST_SHUFFLE_PREFIX = "playlist-shuffle:"
    private const val PLAYLIST_TRACK_PREFIX = "playlist-track:"
    private const val LOVED_TRACK_PREFIX = "loved-track:"

    fun playlist(id: String) = "$PLAYLIST_PREFIX$id"
    fun playlistShuffle(id: String) = "$PLAYLIST_SHUFFLE_PREFIX$id"

    /** Position first, so a playlist id containing ':' still parses. */
    fun playlistTrack(playlistId: String, position: Int) = "$PLAYLIST_TRACK_PREFIX$position:$playlistId"
    fun lovedTrack(id: String) = "$LOVED_TRACK_PREFIX$id"

    /** (playlistId, position) from [playlistTrack], or null for any other id. */
    fun parsePlaylistTrack(id: String): Pair<String, Int>? {
        if (!id.startsWith(PLAYLIST_TRACK_PREFIX)) return null
        val rest = id.removePrefix(PLAYLIST_TRACK_PREFIX)
        val position = rest.substringBefore(':').toIntOrNull() ?: return null
        val playlistId = rest.substringAfter(':', missingDelimiterValue = "").ifEmpty { return null }
        return playlistId to position
    }

    internal fun playlistIdOrNull(id: String) = id.takeIf { it.startsWith(PLAYLIST_PREFIX) }?.removePrefix(PLAYLIST_PREFIX)
    internal fun playlistShuffleIdOrNull(id: String) =
        id.takeIf { it.startsWith(PLAYLIST_SHUFFLE_PREFIX) }?.removePrefix(PLAYLIST_SHUFFLE_PREFIX)
    internal fun lovedTrackIdOrNull(id: String) = id.takeIf { it.startsWith(LOVED_TRACK_PREFIX) }?.removePrefix(LOVED_TRACK_PREFIX)
}

enum class CarItemKind { FOLDER, PLAYLIST, TRACK, ACTION }

/** One tile in the car browse tree. */
data class CarItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val album: String?,
    val isBrowsable: Boolean,
    val isPlayable: Boolean,
    val kind: CarItemKind,
)

/** What tapping an item plays. [shuffle] null keeps the current shuffle setting. */
data class CarPlayRequest(val tracks: List<Track>, val startIndex: Int, val shuffle: Boolean?)

/** The library reads the car browse tree needs. */
interface CarLibrarySource {
    suspend fun playlists(): List<Playlist>
    suspend fun playlist(id: String): Playlist?
    suspend fun playlistTracks(playlistId: String): List<PlaylistTrack>

    /** The collection, most recently added first. */
    suspend fun collectionTracks(): List<Track>
    fun toTrack(row: PlaylistTrack): Track
}

fun daoCarLibrarySource(
    trackDao: TrackDao,
    playlistDao: PlaylistDao,
    playlistTrackDao: PlaylistTrackDao,
    libraryRepository: LibraryRepository,
): CarLibrarySource = object : CarLibrarySource {
    override suspend fun playlists() = playlistDao.getAllSync()
    override suspend fun playlist(id: String) = playlistDao.getById(id)
    override suspend fun playlistTracks(playlistId: String) = playlistTrackDao.getByPlaylistIdSync(playlistId)
    override suspend fun collectionTracks() = trackDao.getAll().first()
    override fun toTrack(row: PlaylistTrack) = libraryRepository.playlistTrackToTrackEntity(row)
}

/** The browse tree and tap rules for car screens, over a [CarLibrarySource]. */
class CarBrowse(private val source: CarLibrarySource) {

    suspend fun children(parentId: String): List<CarItem> {
        CarIds.playlistIdOrNull(parentId)?.let { return playlistChildren(it) }
        return when (parentId) {
            CarIds.ROOT -> listOf(playlistsFolder(), lovedFolder(), collectionRadio())
            CarIds.PLAYLISTS -> source.playlists()
                .sortedByDescending { it.updatedAt }
                .take(CAR_PLAYLIST_CAP)
                .map { playlistItem(it) }
            CarIds.LOVED -> lovedSongs(source.collectionTracks()).map { lovedItem(it) }
            else -> emptyList()
        }
    }

    suspend fun item(id: String): CarItem? {
        when (id) {
            CarIds.ROOT -> return CarItem(CarIds.ROOT, "Parachord", null, null, null, true, false, CarItemKind.FOLDER)
            CarIds.PLAYLISTS -> return playlistsFolder()
            CarIds.LOVED -> return lovedFolder()
            CarIds.COLLECTION_RADIO -> return collectionRadio()
        }
        CarIds.lovedTrackIdOrNull(id)?.let { trackId ->
            return source.collectionTracks().firstOrNull { it.id == trackId }?.let { lovedItem(it) }
        }
        CarIds.playlistIdOrNull(id)?.let { playlistId ->
            return source.playlist(playlistId)?.let { playlistItem(it) }
        }
        return null
    }

    suspend fun playRequest(id: String): CarPlayRequest? {
        if (id == CarIds.COLLECTION_RADIO) {
            val all = source.collectionTracks()
            return if (all.isEmpty()) null else CarPlayRequest(all, 0, true)
        }
        CarIds.lovedTrackIdOrNull(id)?.let { trackId ->
            // The same capped, ordered list the folder shows.
            val loved = lovedSongs(source.collectionTracks())
            val idx = loved.indexOfFirst { it.id == trackId }
            return if (idx < 0) null else CarPlayRequest(loved, idx, null)
        }
        CarIds.playlistIdOrNull(id)?.let { return playlistRequest(it, null, false) }
        CarIds.playlistShuffleIdOrNull(id)?.let { return playlistRequest(it, null, true) }
        CarIds.parsePlaylistTrack(id)?.let { (playlistId, position) ->
            return playlistRequest(playlistId, position, false)
        }
        return null
    }

    fun isBrowseTreeId(id: String): Boolean =
        id == CarIds.COLLECTION_RADIO ||
            CarIds.playlistIdOrNull(id) != null ||
            CarIds.playlistShuffleIdOrNull(id) != null ||
            CarIds.parsePlaylistTrack(id) != null ||
            CarIds.lovedTrackIdOrNull(id) != null

    private suspend fun playlistChildren(playlistId: String): List<CarItem> {
        val playlist = source.playlist(playlistId)
        val rows = source.playlistTracks(playlistId).take(CAR_PLAYLIST_CAP)
        return buildList {
            if (rows.isNotEmpty()) {
                add(
                    CarItem(
                        id = CarIds.playlistShuffle(playlistId),
                        title = "Shuffle",
                        subtitle = playlist?.name ?: "${rows.size} tracks",
                        artworkUrl = null, album = null,
                        isBrowsable = false, isPlayable = true, kind = CarItemKind.ACTION,
                    ),
                )
            }
            rows.forEach { add(playlistTrackItem(it)) }
        }
    }

    /** Every row plays, not just the capped list shown. A start position no longer there plays from 0. */
    private suspend fun playlistRequest(playlistId: String, startPosition: Int?, shuffle: Boolean): CarPlayRequest? {
        val rows = source.playlistTracks(playlistId)
        if (rows.isEmpty()) return null
        val startIndex = startPosition?.let { p -> rows.indexOfFirst { it.position == p } }?.takeIf { it >= 0 } ?: 0
        return CarPlayRequest(rows.map { source.toTrack(it) }, startIndex, shuffle)
    }

    private fun lovedSongs(tracks: List<Track>): List<Track> = tracks.take(CAR_LOVED_CAP)

    private fun playlistsFolder() =
        CarItem(CarIds.PLAYLISTS, "Playlists", null, null, null, true, false, CarItemKind.FOLDER)

    private fun lovedFolder() =
        CarItem(CarIds.LOVED, "Loved Songs", null, null, null, true, false, CarItemKind.FOLDER)

    private fun collectionRadio() =
        CarItem(
            CarIds.COLLECTION_RADIO, "Collection Radio", "Shuffle all loved tracks", null, null,
            false, true, CarItemKind.ACTION,
        )

    private fun playlistItem(p: Playlist) =
        CarItem(
            id = CarIds.playlist(p.id), title = p.name, subtitle = p.ownerName ?: "Parachord",
            artworkUrl = p.artworkUrl, album = null,
            isBrowsable = true, isPlayable = false, kind = CarItemKind.PLAYLIST,
        )

    private fun playlistTrackItem(t: PlaylistTrack) =
        CarItem(
            id = CarIds.playlistTrack(t.playlistId, t.position), title = t.trackTitle, subtitle = t.trackArtist,
            artworkUrl = t.trackArtworkUrl, album = t.trackAlbum,
            isBrowsable = false, isPlayable = true, kind = CarItemKind.TRACK,
        )

    private fun lovedItem(t: Track) =
        CarItem(
            id = CarIds.lovedTrack(t.id), title = t.title, subtitle = t.artist,
            artworkUrl = t.artworkUrl, album = t.album,
            isBrowsable = false, isPlayable = true, kind = CarItemKind.TRACK,
        )
}
