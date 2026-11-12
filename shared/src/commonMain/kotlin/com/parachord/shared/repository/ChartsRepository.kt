package com.parachord.shared.repository

import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.bestImageUrl
import com.parachord.shared.model.CHARTS_COUNTRIES
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.ChartSong
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Repository for the Pop of the Tops / Charts screen.
 *
 * Apple Music albums + songs come from the public marketing-tools RSS
 * feed (`rss.marketingtools.apple.com`) via the shared
 * [AppleMusicClient]. Last.fm "global" and per-country charts come from
 * the shared [LastFmClient]. Both fold into the same [ChartSong] model
 * so the UI can render them with a single layout.
 *
 * Last.fm chart entries usually arrive without artwork (their image
 * field has been deprecated since 2020), so [enrichArtwork] fans out a
 * batch of `track.getInfo` calls in parallel to backfill the artwork
 * URLs. The cache is keyed `<source>-<country>`; both the main fetch
 * and the enriched result share the same TTL.
 *
 * Migrated from OkHttp + JSONObject to shared Ktor in the OkHttp
 * cleanup. Last meaningful Android-only repository to use raw HTTP is
 * gone; remaining repos are blocked on file-system caching only.
 */
class ChartsRepository(
    private val appleMusicClient: AppleMusicClient,
    private val lastFmClient: LastFmClient,
    private val lastFmApiKey: String,
    /**
     * The charts saved on disk (one JSON blob), so a launch within a day shows
     * them without a request and a failed request shows the last ones (#570:
     * Apple's chart feed timed out at launch and Home's Pop of the Tops card
     * stayed empty for the session). Null keeps them in memory only.
     */
    private val cacheRead: (suspend () -> String?)? = null,
    private val cacheWrite: (suspend (String) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "ChartsRepo"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }

    @Serializable
    private data class SavedAlbums(val fetchedAt: Long, val data: List<ChartAlbum>)

    @Serializable
    private data class SavedSongs(val fetchedAt: Long, val data: List<ChartSong>)

    @Serializable
    private data class SavedCharts(
        val albums: Map<String, SavedAlbums> = emptyMap(),
        val songs: Map<String, SavedSongs> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val albumsCache = mutableMapOf<String, Pair<List<ChartAlbum>, Long>>()
    private val songsCache = mutableMapOf<String, Pair<List<ChartSong>, Long>>()
    private val cacheMutex = Mutex()
    private var diskLoaded = false

    /** Read the saved charts into memory once. Call with [cacheMutex] held. */
    private suspend fun loadDiskOnce() {
        if (diskLoaded) return
        diskLoaded = true
        val raw = try { cacheRead?.invoke() } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return
        val saved = try { json.decodeFromString(SavedCharts.serializer(), raw) } catch (e: Exception) {
            Log.w(TAG, "Saved charts unreadable; starting empty")
            return
        }
        saved.albums.forEach { (k, v) -> albumsCache.getOrPut(k) { v.data to v.fetchedAt } }
        saved.songs.forEach { (k, v) -> songsCache.getOrPut(k) { v.data to v.fetchedAt } }
    }

    /** Write what's in memory. Call with [cacheMutex] held. */
    private suspend fun saveDisk() {
        val write = cacheWrite ?: return
        val blob = SavedCharts(
            albums = albumsCache.mapValues { (_, v) -> SavedAlbums(v.second, v.first) },
            songs = songsCache.mapValues { (_, v) -> SavedSongs(v.second, v.first) },
        )
        try {
            write(json.encodeToString(SavedCharts.serializer(), blob))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Couldn't save the charts: ${e.message}")
        }
    }

    suspend fun getAppleMusicAlbums(country: String = "us"): List<ChartAlbum> {
        // Apple's charts are per storefront; "Global" (blank, Last.fm only) shows the US one.
        val countryCode = country.ifBlank { "us" }
        val cacheKey = "apple-albums-$countryCode"
        cacheMutex.withLock {
            loadDiskOnce()
            albumsCache[cacheKey]?.let { (data, ts) ->
                if (currentTimeMillis() - ts < CACHE_TTL_MS) return data
            }
        }
        return try {
            val response = appleMusicClient.mostPlayedAlbums(countryCode)
            val albums = response.feed?.results?.mapIndexed { i, item ->
                ChartAlbum(
                    id = "apple-album-$countryCode-$i",
                    title = item.name,
                    artist = item.artistName,
                    artworkUrl = item.artworkUrl100?.replace("100x100", "300x300"),
                    rank = i + 1,
                    genres = item.genres.map { it.name },
                    url = item.url,
                )
            } ?: emptyList()
            if (albums.isNotEmpty()) cacheMutex.withLock { albumsCache[cacheKey] = albums to currentTimeMillis(); saveDisk() }
            albums
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Apple Music albums ($countryCode)", e)
            cacheMutex.withLock { albumsCache[cacheKey]?.first } ?: emptyList()
        }
    }

    suspend fun getAppleMusicSongs(country: String = "us"): List<ChartSong> {
        // Apple's charts are per storefront; "Global" (blank, Last.fm only) shows the US one.
        val countryCode = country.ifBlank { "us" }
        val cacheKey = "apple-songs-$countryCode"
        cacheMutex.withLock {
            loadDiskOnce()
            songsCache[cacheKey]?.let { (data, ts) ->
                if (currentTimeMillis() - ts < CACHE_TTL_MS) return data
            }
        }
        return try {
            val response = appleMusicClient.mostPlayedSongs(countryCode)
            val songs = response.feed?.results?.mapIndexed { i, item ->
                ChartSong(
                    id = "apple-song-$countryCode-$i",
                    title = item.name,
                    artist = item.artistName,
                    artworkUrl = item.artworkUrl100?.replace("100x100", "600x600"),
                    rank = i + 1,
                    source = "apple",
                    url = item.url,
                )
            } ?: emptyList()
            if (songs.isNotEmpty()) cacheMutex.withLock { songsCache[cacheKey] = songs to currentTimeMillis(); saveDisk() }
            songs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Apple Music songs ($countryCode)", e)
            cacheMutex.withLock { songsCache[cacheKey]?.first } ?: emptyList()
        }
    }

    /** [country] null or blank = Last.fm's global chart (the "Global" picker entry). */
    suspend fun getLastfmCharts(country: String? = null): List<ChartSong> {
        val countryCode = country?.takeIf { it.isNotBlank() }
        val cacheKey = "lastfm-${countryCode ?: "global"}"
        cacheMutex.withLock {
            loadDiskOnce()
            songsCache[cacheKey]?.let { (data, ts) ->
                if (currentTimeMillis() - ts < CACHE_TTL_MS) return data
            }
        }
        return try {
            val tracks = if (countryCode == null) {
                val response = lastFmClient.getChartTopTracks(apiKey = lastFmApiKey)
                response.tracks?.track ?: emptyList()
            } else {
                val countryName = CHARTS_COUNTRIES.find { it.code == countryCode }?.lastfmName
                    ?: return emptyList()
                val response = lastFmClient.getGeoTopTracks(country = countryName, apiKey = lastFmApiKey)
                response.tracks?.track ?: emptyList()
            }
            val songs = tracks.mapIndexed { i, t ->
                ChartSong(
                    id = "lastfm-chart-${countryCode ?: "global"}-$i",
                    title = t.name,
                    artist = t.artist?.name ?: "",
                    artworkUrl = t.image.bestImageUrl(),
                    rank = i + 1,
                    listeners = t.listeners?.toLongOrNull(),
                    playcount = t.playcount?.toLongOrNull(),
                    url = t.url,
                    source = "lastfm",
                    mbid = t.mbid?.takeIf { it.isNotBlank() },
                )
            }
            // Enrich missing artwork via track.getInfo (concurrent, batched)
            val enriched = enrichArtwork(songs)
            if (enriched.isNotEmpty()) cacheMutex.withLock { songsCache[cacheKey] = enriched to currentTimeMillis(); saveDisk() }
            enriched
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Last.fm charts ($countryCode)", e)
            cacheMutex.withLock { songsCache[cacheKey]?.first } ?: emptyList()
        }
    }

    private suspend fun enrichArtwork(songs: List<ChartSong>): List<ChartSong> = coroutineScope {
        // Only enrich songs that are missing artwork
        val needsArt = songs.mapIndexedNotNull { i, s -> if (s.artworkUrl == null) i else null }
        if (needsArt.isEmpty()) return@coroutineScope songs

        val result = songs.toMutableList()
        // Fetch in batches of 10 to avoid hammering the API
        needsArt.chunked(10).forEach { batch ->
            val deferred = batch.map { idx ->
                async {
                    idx to fetchTrackArtwork(result[idx].artist, result[idx].title)
                }
            }
            deferred.awaitAll().forEach { (idx, artInfo) ->
                if (artInfo != null) {
                    result[idx] = result[idx].copy(
                        artworkUrl = artInfo.first ?: result[idx].artworkUrl,
                        album = artInfo.second ?: result[idx].album,
                    )
                }
            }
        }
        result
    }

    /** Returns (artworkUrl, albumTitle) or null on failure. */
    private suspend fun fetchTrackArtwork(artist: String, track: String): Pair<String?, String?>? {
        return try {
            val response = lastFmClient.getTrackInfo(track = track, artist = artist, apiKey = lastFmApiKey)
            val album = response.track?.album
            val artUrl = album?.image?.bestImageUrl()
            if (artUrl != null || album?.title != null) {
                artUrl to album?.title
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
