package com.parachord.shared.db.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.parachord.shared.db.Artists
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.model.Artist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [ArtistQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class ArtistDao(private val db: ParachordDb) {

    private val queries get() = db.artistQueries

    /* ---- Mapping ---- */

    private fun Artists.toArtist() = Artist(
        id = id,
        name = name,
        imageUrl = imageUrl,
        spotifyId = spotifyId,
        genres = genres,
        addedAt = addedAt,
    )

    /* ---- Queries returning Flow ---- */

    fun getAll(): Flow<List<Artist>> =
        queries.getAll().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toArtist() } }

    fun getByName(name: String): Flow<Artist?> =
        queries.getByName(name).asFlow().mapToOneOrNull(Dispatchers.Default).map { it?.toArtist() }

    fun existsByName(name: String): Flow<Boolean> =
        queries.existsByName(name).asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { it ?: false }

    fun search(query: String): Flow<List<Artist>> =
        queries.search(query).asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toArtist() } }

    /* ---- Suspend one-shot reads ---- */

    suspend fun getById(id: String): Artist? = withContext(Dispatchers.Default) {
        queries.getById(id).executeAsOneOrNull()?.toArtist()
    }

    suspend fun getBySpotifyId(spotifyId: String): Artist? = withContext(Dispatchers.Default) {
        queries.getBySpotifyId(spotifyId).executeAsOneOrNull()?.toArtist()
    }

    /** Total artist count. Used by SyncEngine to detect entity-table wipes
     *  where `sync_sources` rows survived but the `artists` table is empty. */
    suspend fun countAll(): Int = withContext(Dispatchers.Default) {
        queries.countAll().executeAsOne().toInt()
    }

    /** Count of artists whose primary key starts with [idPrefix]
     *  (e.g. `"spotify-"`). Per-provider variant of [countAll]. */
    suspend fun countByIdPrefix(idPrefix: String): Int = withContext(Dispatchers.Default) {
        queries.countByIdPrefix(idPrefix).executeAsOne().toInt()
    }

    /* ---- Writes ---- */

    suspend fun insert(artist: Artist): Unit = withContext(Dispatchers.Default) {
        queries.insert(
            id = artist.id,
            name = artist.name,
            imageUrl = artist.imageUrl,
            spotifyId = artist.spotifyId,
            genres = artist.genres,
            addedAt = artist.addedAt,
        )
    }

    suspend fun insertAll(artists: List<Artist>): Unit = withContext(Dispatchers.Default) {
        queries.transaction {
            for (artist in artists) {
                queries.insert(
                    id = artist.id,
                    name = artist.name,
                    imageUrl = artist.imageUrl,
                    spotifyId = artist.spotifyId,
                    genres = artist.genres,
                    addedAt = artist.addedAt,
                )
            }
        }
    }

    suspend fun delete(artist: Artist): Unit = withContext(Dispatchers.Default) {
        queries.deleteById(artist.id)
    }

    suspend fun deleteById(id: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteById(id)
    }

    suspend fun deleteByName(name: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteByName(name)
    }

    suspend fun updateImageByName(name: String, imageUrl: String): Unit = withContext(Dispatchers.Default) {
        queries.updateImageByName(imageUrl = imageUrl, name = name)
    }

    /** Replace a dead image URL on every artist row that holds it. */
    suspend fun replaceImageUrl(dead: String, fresh: String): Unit = withContext(Dispatchers.Default) {
        queries.replaceImageUrl(fresh = fresh, dead = dead)
    }
}
