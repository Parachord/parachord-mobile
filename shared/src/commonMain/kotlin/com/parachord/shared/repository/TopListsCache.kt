package com.parachord.shared.repository

import com.parachord.shared.model.HistoryAlbum
import com.parachord.shared.model.HistoryArtist
import com.parachord.shared.model.HistoryTrack
import com.parachord.shared.model.Resource
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A 24-hour disk cache of top tracks, albums and artists, for the user's own
 * History and for a friend's profile, one entry per period (#595). Both apps
 * use it; it used to live in iOS's container only.
 *
 * - A list cached less than [TTL_MS] ago is shown without a request.
 * - Otherwise the repository's flow runs. A new non-empty list is saved and
 *   shown. An empty answer or a failure shows the last saved list when there
 *   is one (a failure only then: with nothing saved, its error shows).
 * - Recently played isn't cached: it changes all the time.
 *
 * Files keep iOS's names and shape, so the cache iOS already had carries over.
 */
class TopListsCache(
    private val read: suspend (file: String) -> String?,
    private val write: suspend (file: String, json: String) -> Unit,
    private val nowMs: () -> Long = { currentTimeMillis() },
) {
    companion object {
        const val TTL_MS = 24L * 60 * 60 * 1000
        const val TRACKS_FILE = "history_top_tracks.json"
        const val ALBUMS_FILE = "history_top_albums.json"
        const val ARTISTS_FILE = "history_top_artists.json"

        /** The user's own list for [period]. */
        fun ownKey(period: String): String = period

        /** A friend's list for [period]. */
        fun friendKey(username: String, service: String, period: String): String = "friend:$username:$service:$period"
    }

    @Serializable
    private data class Timed<T>(val fetchedAt: Long, val data: List<T>)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    fun tracks(key: String, load: () -> Flow<Resource<List<HistoryTrack>>>): Flow<Resource<List<HistoryTrack>>> =
        cached(TRACKS_FILE, HistoryTrack.serializer(), key, load)

    fun albums(key: String, load: () -> Flow<Resource<List<HistoryAlbum>>>): Flow<Resource<List<HistoryAlbum>>> =
        cached(ALBUMS_FILE, HistoryAlbum.serializer(), key, load)

    fun artists(key: String, load: () -> Flow<Resource<List<HistoryArtist>>>): Flow<Resource<List<HistoryArtist>>> =
        cached(ARTISTS_FILE, HistoryArtist.serializer(), key, load)

    private fun <T> cached(
        file: String,
        item: KSerializer<T>,
        key: String,
        load: () -> Flow<Resource<List<T>>>,
    ): Flow<Resource<List<T>>> = flow {
        val ser = MapSerializer(String.serializer(), Timed.serializer(item))
        val saved = readMap(file, ser)[key]
        if (saved != null && saved.data.isNotEmpty() && nowMs() - saved.fetchedAt < TTL_MS) {
            emit(Resource.Success(saved.data))
            return@flow
        }
        val stale = saved?.data?.takeIf { it.isNotEmpty() }
        load().collect { r ->
            when (r) {
                is Resource.Loading -> emit(r)
                is Resource.Error -> emit(if (stale != null) Resource.Success(stale) else r)
                is Resource.Success -> {
                    if (r.data.isEmpty()) {
                        emit(Resource.Success(stale ?: r.data))
                    } else {
                        save(file, ser, key, Timed(nowMs(), r.data))
                        emit(r)
                    }
                }
            }
        }
    }

    private suspend fun <T> readMap(file: String, ser: KSerializer<Map<String, Timed<T>>>): Map<String, Timed<T>> =
        try {
            read(file)?.takeIf { it.isNotBlank() }?.let { json.decodeFromString(ser, it) } ?: emptyMap()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyMap()
        }

    private suspend fun <T> save(file: String, ser: KSerializer<Map<String, Timed<T>>>, key: String, entry: Timed<T>) {
        mutex.withLock {
            val map = readMap(file, ser).toMutableMap()
            map[key] = entry
            try {
                write(file, json.encodeToString(ser, map))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }
}
