package com.parachord.shared.db.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.parachord.shared.db.Albums
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.model.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [AlbumQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class AlbumDao(private val db: ParachordDb) {

    private val queries get() = db.albumQueries

    /* ---- Mapping ---- */

    private fun Albums.toAlbum() = Album(
        id = id,
        title = title,
        artist = artist,
        artworkUrl = artworkUrl,
        year = year?.toInt(),
        trackCount = trackCount?.toInt(),
        addedAt = addedAt,
        spotifyId = spotifyId,
    )

    /* ---- Queries returning Flow ---- */

    fun getAll(): Flow<List<Album>> =
        queries.getAll().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toAlbum() } }

    fun search(query: String): Flow<List<Album>> =
        queries.search(query, query).asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toAlbum() } }

    fun existsByTitleAndArtist(title: String, artist: String): Flow<Boolean> =
        queries.existsByTitleAndArtist(title, artist).asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it ?: false }

    /* ---- Suspend one-shot reads ---- */

    suspend fun getById(id: String): Album? = withContext(Dispatchers.Default) {
        queries.getById(id).executeAsOneOrNull()?.toAlbum()
    }

    /** Total album count. Used by SyncEngine to detect entity-table wipes
     *  where `sync_sources` rows survived but the `albums` table is empty —
     *  forces a full refetch instead of trusting the provider's
     *  unchanged-shortcut. */
    suspend fun countAll(): Int = withContext(Dispatchers.Default) {
        queries.countAll().executeAsOne().toInt()
    }

    /** Count of albums whose primary key starts with [idPrefix] (e.g.
     *  `"spotify-"`). Per-provider variant of [countAll] — Apple Music
     *  rows shouldn't unmask a Spotify wipe. */
    suspend fun countByIdPrefix(idPrefix: String): Int = withContext(Dispatchers.Default) {
        queries.countByIdPrefix(idPrefix).executeAsOne().toInt()
    }

    suspend fun getByTitleAndArtist(title: String, artist: String): Album? = withContext(Dispatchers.Default) {
        queries.getByTitleAndArtist(title, artist).executeAsOneOrNull()?.toAlbum()
    }

    /* ---- Writes ---- */

    suspend fun insert(album: Album): Unit = withContext(Dispatchers.Default) {
        queries.insert(
            id = album.id,
            title = album.title,
            artist = album.artist,
            artworkUrl = album.artworkUrl,
            year = album.year?.toLong(),
            trackCount = album.trackCount?.toLong(),
            addedAt = album.addedAt,
            spotifyId = album.spotifyId,
        )
    }

    suspend fun insertAll(albums: List<Album>): Unit = withContext(Dispatchers.Default) {
        queries.transaction {
            for (album in albums) {
                queries.insert(
                    id = album.id,
                    title = album.title,
                    artist = album.artist,
                    artworkUrl = album.artworkUrl,
                    year = album.year?.toLong(),
                    trackCount = album.trackCount?.toLong(),
                    addedAt = album.addedAt,
                    spotifyId = album.spotifyId,
                )
            }
        }
    }

    suspend fun delete(album: Album): Unit = withContext(Dispatchers.Default) {
        queries.deleteById(album.id)
    }

    suspend fun updateArtworkByTitleAndArtist(title: String, artist: String, artworkUrl: String): Unit =
        withContext(Dispatchers.Default) {
            queries.updateArtworkByTitleAndArtist(
                artworkUrl = artworkUrl,
                title = title,
                artist = artist,
            )
        }

    /** Replace a dead cover URL on every album row that holds it. */
    suspend fun replaceArtworkUrl(dead: String, fresh: String): Unit = withContext(Dispatchers.Default) {
        queries.replaceArtworkUrl(fresh = fresh, dead = dead)
    }
}
