package com.parachord.shared.repository

import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.db.dao.AlbumDao
import com.parachord.shared.db.dao.ArtistDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.model.Album
import com.parachord.shared.model.Artist
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * The artists whose concerts the Concerts page looks up (#589). One rule for
 * both apps, a port of desktop's `gatherConcertsArtists`:
 *
 * 1. **Collection:** the artists in the collection.
 * 2. **Library:** the artists of the collection's tracks, then its albums.
 * 3. **History:** the user's top artists over the last 6 months, Last.fm's
 *    ([HISTORY_LIMIT], `6month`) then ListenBrainz's ([HISTORY_LIMIT],
 *    `half_yearly`), for whichever of the two has a username.
 *
 * Each artist appears once, in the first group that has it (names compared
 * trimmed and lowercase). The groups are then dealt round-robin, the first of
 * each, then the second of each, until there are [MAX_ARTISTS].
 */
object ConcertArtistSeed {
    const val MAX_ARTISTS = 40
    const val HISTORY_LIMIT = 40
    const val LASTFM_PERIOD = "6month"
    const val LISTENBRAINZ_RANGE = "half_yearly"

    /**
     * The seed from what the collection holds and the history artists, in
     * order (Last.fm's, then ListenBrainz's).
     */
    fun seed(
        collection: List<Artist>,
        tracks: List<Track>,
        albums: List<Album>,
        history: List<String>,
    ): List<ConcertArtist> {
        val seen = mutableSetOf<String>()
        fun firstTime(name: String): Boolean = name.isNotBlank() && seen.add(name.trim().lowercase())

        val collectionArtists = collection.filter { firstTime(it.name) }
            .map { ConcertArtist(it.name, "collection", it.imageUrl) }
        val libraryArtists = (tracks.map { it.artist } + albums.map { it.artist })
            .filter(::firstTime)
            .map { ConcertArtist(it, "library") }
        val historyArtists = history.filter(::firstTime).map { ConcertArtist(it, "history") }

        val buckets = listOf(collectionArtists, libraryArtists, historyArtists).filter { it.isNotEmpty() }
        val out = mutableListOf<ConcertArtist>()
        var round = 0
        while (out.size < MAX_ARTISTS) {
            var added = false
            for (bucket in buckets) {
                if (round < bucket.size && out.size < MAX_ARTISTS) {
                    out.add(bucket[round])
                    added = true
                }
            }
            if (!added) break
            round++
        }
        return out
    }
}

/** Reads the collection and the history services and builds [ConcertArtistSeed.seed]. */
class ConcertArtistSeedSource(
    private val artistDao: ArtistDao,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val settingsStore: SettingsStore,
    private val lastFmClient: LastFmClient,
    private val listenBrainzClient: ListenBrainzClient,
    private val lastFmApiKey: String,
) {
    companion object {
        private const val TAG = "ConcertArtistSeed"
    }

    suspend fun gather(): List<ConcertArtist> = coroutineScope {
        val lastFm = async { lastFmHistory() }
        val listenBrainz = async { listenBrainzHistory() }
        val collection = orEmpty("collection artists") { artistDao.getAll().first() }
        val tracks = orEmpty("collection tracks") { trackDao.getAll().first() }
        val albums = orEmpty("collection albums") { albumDao.getAll().first() }
        ConcertArtistSeed.seed(collection, tracks, albums, lastFm.await() + listenBrainz.await())
    }

    private suspend fun lastFmHistory(): List<String> = orEmpty("Last.fm top artists") {
        val user = settingsStore.getLastFmUsername()
        if (user.isNullOrBlank() || lastFmApiKey.isBlank()) return@orEmpty emptyList()
        lastFmClient.getUserTopArtists(
            user = user,
            apiKey = lastFmApiKey,
            period = ConcertArtistSeed.LASTFM_PERIOD,
            limit = ConcertArtistSeed.HISTORY_LIMIT,
        ).topartists?.artist.orEmpty().map { it.name }
    }

    private suspend fun listenBrainzHistory(): List<String> = orEmpty("ListenBrainz top artists") {
        val user = settingsStore.getListenBrainzUsername()
        if (user.isNullOrBlank()) return@orEmpty emptyList()
        listenBrainzClient.getUserTopArtists(
            username = user,
            range = ConcertArtistSeed.LISTENBRAINZ_RANGE,
            count = ConcertArtistSeed.HISTORY_LIMIT,
        ).map { it.name }
    }

    private inline fun <T> orEmpty(what: String, block: () -> List<T>): List<T> =
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Couldn't read $what for the concert search: ${e.message}")
            emptyList()
        }
}
