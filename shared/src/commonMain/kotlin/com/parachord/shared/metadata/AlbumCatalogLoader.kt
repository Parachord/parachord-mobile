package com.parachord.shared.metadata

import com.parachord.shared.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads an album's track list from the plugins' catalogs (`albumSearch`) when
 * the metadata providers can't (#424). Port of desktop `loadAlbumFromCatalogs`
 * (app.js, parachord#1129): resolvers are asked in [AlbumCatalog.orderCatalogResolvers]
 * order, each step capped at [stepTimeoutMs], and the first resolver whose
 * search turns up a matching album that it can load wins.
 *
 * The plugin calls are injected (the platform's `PluginManager` runs them in the
 * JS runtime), so this class is unit-testable.
 */
class AlbumCatalogLoader(
    /** Album-search resolvers as (id, display name). */
    private val resolvers: suspend () -> List<Pair<String, String>>,
    /** `searchAlbums` on one resolver: raw JSON array, or null. */
    private val searchAlbums: suspend (resolverId: String, artist: String?, title: String?, text: String?) -> String?,
    /** `lookupAlbumFrom` on one resolver: raw JSON, or null. */
    private val lookupAlbumFrom: suspend (resolverId: String, url: String) -> String?,
    private val activeResolverIds: suspend () -> List<String> = { emptyList() },
    private val resolverOrder: suspend () -> List<String> = { emptyList() },
    private val stepTimeoutMs: Long = 8_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    data class Found(val album: AlbumCatalog.Album, val resolverId: String, val resolverName: String)

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<Found?>>()

    /** Albums a catalog search returned, by artist+title, so opening one loads it
     *  straight from that resolver instead of searching again (desktop `_catalog`). */
    private val searchHits = mutableMapOf<String, Pair<String, String>>()

    private fun key(artist: String?, title: String?) =
        AlbumCatalog.normalizeArtistName(artist) + "\u0000" + AlbumCatalog.normalizeAlbumTitle(title)

    /**
     * The track list for [title] by [artist] from the first catalog that has it,
     * or null. Concurrent calls for the same album share one lookup.
     */
    suspend fun load(artist: String, title: String, preferResolverId: String? = null): Found? {
        val k = key(artist, title) + "\u0000" + preferResolverId.orEmpty()
        val deferred = mutex.withLock {
            inFlight[k] ?: scope.async {
                try {
                    loadNow(artist, title, preferResolverId)
                } finally {
                    mutex.withLock { inFlight.remove(k) }
                }
            }.also { inFlight[k] = it }
        }
        return deferred.await()
    }

    private suspend fun loadNow(artist: String, title: String, preferResolverId: String?): Found? {
        val all = try { resolvers() } catch (e: Exception) { emptyList() }
        if (all.isEmpty()) return null
        val names = all.toMap()

        // An album a catalog search already found: load it from there directly.
        mutex.withLock { searchHits[key(artist, title)] }?.let { (resolverId, url) ->
            lookup(resolverId, url, names)?.let { return it }
        }

        val ordered = AlbumCatalog.orderCatalogResolvers(
            all.map { it.first },
            activeIds = runCatching { activeResolverIds() }.getOrDefault(emptyList()),
            order = runCatching { resolverOrder() }.getOrDefault(emptyList()),
            preferId = preferResolverId,
        )
        for (resolverId in ordered) {
            try {
                val raw = withTimeoutOrNull(stepTimeoutMs) { searchAlbums(resolverId, artist, title, null) }
                val match = AlbumCatalog.pickCatalogAlbum(AlbumCatalog.parseAlbums(raw), artist, title) ?: continue
                lookup(resolverId, match.url, names)?.let {
                    Log.d(TAG, "\"$title\" by $artist from $resolverId (${it.album.tracks.size} tracks)")
                    return it
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$resolverId failed for \"$title\": ${e.message}")
            }
        }
        return null
    }

    private suspend fun lookup(resolverId: String, url: String, names: Map<String, String>): Found? {
        val raw = withTimeoutOrNull(stepTimeoutMs) { lookupAlbumFrom(resolverId, url) }
        val album = AlbumCatalog.parseLookup(raw)?.takeIf { it.tracks.isNotEmpty() } ?: return null
        return Found(album, resolverId, names[resolverId] ?: resolverId)
    }

    /**
     * Free-text album search across every album-search resolver, in parallel,
     * each capped at [stepTimeoutMs]. Used when MusicBrainz returns few or no
     * albums (desktop: fewer than three). Each hit is remembered so opening it
     * loads from its resolver without a second search.
     */
    suspend fun search(query: String, limitPerResolver: Int = 10): List<AlbumSearchResult> = coroutineScope {
        val all = try { resolvers() } catch (e: Exception) { emptyList() }
        val perResolver = all.map { (resolverId, _) ->
            async {
                val raw = try {
                    withTimeoutOrNull(stepTimeoutMs) { searchAlbums(resolverId, null, null, query) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                AlbumCatalog.parseAlbums(raw).take(limitPerResolver).map { resolverId to it }
            }
        }.awaitAll().flatten()
        mutex.withLock {
            for ((resolverId, a) in perResolver) searchHits[key(a.artist, a.name)] = resolverId to a.url
        }
        perResolver.map { (resolverId, a) ->
            AlbumSearchResult(
                title = a.name,
                artist = a.artist.orEmpty(),
                artworkUrl = a.albumArt,
                year = a.releaseDate?.take(4)?.toIntOrNull(),
                trackCount = a.trackCount,
                provider = resolverId,
            )
        }
    }

    private companion object {
        const val TAG = "AlbumCatalog"
    }
}
