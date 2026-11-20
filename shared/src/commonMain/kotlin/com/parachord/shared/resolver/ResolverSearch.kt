package com.parachord.shared.resolver

import com.parachord.shared.api.SpSearchResponse
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.api.auth.ReauthRequiredException
import com.parachord.shared.model.Track
import com.parachord.shared.search.LibraryTrackSearch
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.platform.Log
import com.parachord.shared.plugin.PluginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A track search across the person's streaming services, the way desktop's
 * `searchResolvers(query)` does it (`src/renderer/app.js`): every active
 * resolver that can search is asked, its own results are kept in the order it
 * gave them, and the services' lists are joined in the person's resolver order
 * (Spotify first by default). Nothing is re-ranked or merged, and each result
 * carries the resolver it came from in [TrackSearchResult.provider].
 *
 * This is what Shuffleupagus's `search` tool answers with, and what `play`,
 * `queue_add` and `create_playlist` look tracks up in ([findRequestedTrack]),
 * as on desktop. The metadata search (MusicBrainz first) it replaced answers a
 * bare artist name with recordings titled like the name, by anyone.
 *
 * **Who is asked.** The ids in [nativeSearchers] (Spotify, Apple Music, local
 * files: each platform's own search, so Spotify's goes through its rate-limit
 * gate) pass the same gate the resolver coordinator uses for its natives
 * (`active` empty, as on Android, or the id in it); a native searcher that
 * answers null isn't connected and counts as not asked. Every other loaded
 * plugin that declares `capabilities.search` is asked through [searchPlugin],
 * gated as the coordinator gates its `.axe` resolvers ([ResolverGating]): in
 * the active set, not switched off. A resolver that throws (offline, a
 * rate-limit cooldown, a plugin that timed out) is skipped; the others still
 * answer. Desktop asks one service after the other; this asks them all at
 * once and joins the answers in order, so a slow service doesn't hold up the
 * ones ranked above it.
 *
 * @param getOrder the person's resolver order (`SettingsStore.getResolverOrder`).
 *   Resolvers it doesn't name go after the ones it does, in the order found.
 */
class ResolverSearch(
    private val getOrder: suspend () -> List<String>,
    private val getActive: suspend () -> List<String>,
    private val getDisabled: suspend () -> Set<String>,
    private val getPlugins: suspend () -> List<PluginManager.PluginInfo>,
    private val nativeSearchers: Map<String, suspend (query: String) -> List<TrackSearchResult>?>,
    private val searchPlugin: suspend (resolverId: String, query: String) -> List<TrackSearchResult>,
) {
    /** The resolvers to ask, in the order their results are joined. */
    suspend fun resolverIds(): List<String> {
        val active = getActive()
        val disabled = getDisabled()
        val natives = nativeSearchers.keys.filter { active.isEmpty() || it in active }
        val plugins = ResolverGating.axeResolverIds(
            plugins = getPlugins(),
            active = active,
            disabled = disabled,
            nativeResolverIds = nativeSearchers.keys,
            capability = "search",
        )
        val wanted = (natives + plugins).distinct()
        val order = getOrder()
        return order.filter { it in wanted } + wanted.filter { it !in order }
    }

    /**
     * The first [limit] results, each service's in its own order, the services
     * in the person's order. Blank queries find nothing.
     */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<TrackSearchResult> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val ids = resolverIds()
        val answers = coroutineScope {
            ids.map { id -> async { id to ask(id, query) } }.awaitAll()
        }
        return answers.flatMap { (id, results) ->
            results.filter { it.title.isNotBlank() }.map { it.copy(provider = id) }
        }.take(limit)
    }

    private suspend fun ask(id: String, query: String): List<TrackSearchResult> = try {
        val native = nativeSearchers[id]
        if (native != null) native(query).orEmpty() else searchPlugin(id, query)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$id couldn't search for '$query': ${e.message}")
        emptyList()
    }

    companion object {
        private const val TAG = "ResolverSearch"

        /** Desktop's `search` tool keeps 10 by default. */
        const val DEFAULT_LIMIT = 10

        /** How many tracks one native service search asks for (the plugins' 20). */
        const val SERVICE_SEARCH_LIMIT = 20

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * A plugin's `search` answer (a JSON array in the plugin track shape:
         * `title`, `artist`, `album`, `duration` in seconds, `albumArt`, the
         * service's ids) as search results. Anything that isn't a track with a
         * title is dropped; an answer that isn't an array is no results.
         */
        fun parsePluginResults(raw: String?): List<TrackSearchResult> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray
                ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.ifBlank { null }
                val title = str("title") ?: return@mapNotNull null
                val seconds = (obj["duration"] as? JsonPrimitive)?.doubleOrNull
                TrackSearchResult(
                    title = title,
                    artist = str("artist").orEmpty(),
                    album = str("album"),
                    duration = seconds?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
                    artworkUrl = str("albumArt") ?: str("artworkUrl"),
                    spotifyId = str("spotifyId") ?: str("spotifyUri")?.removePrefix("spotify:track:"),
                    mbid = str("mbid") ?: str("recordingMbid"),
                    appleMusicId = str("appleMusicId")?.takeIf { isAppleMusicCatalogId(it) },
                    isrc = validateIsrc(str("isrc")),
                )
            }
        }

        /** Spotify's playable search results, in Spotify's order. */
        fun spotifyResults(response: SpSearchResponse): List<TrackSearchResult> =
            response.tracks?.items.orEmpty()
                .filter { it.isPlayable != false && !it.id.isNullOrBlank() && !it.name.isNullOrBlank() }
                .map { track ->
                    TrackSearchResult(
                        title = track.name.orEmpty(),
                        artist = track.artistName,
                        album = track.album?.name,
                        duration = track.durationMs,
                        artworkUrl = track.album?.images?.firstOrNull()?.url,
                        previewUrl = track.previewUrl,
                        spotifyId = track.id,
                        isrc = validateIsrc(track.externalIds?.isrc),
                        provider = "spotify",
                    )
                }

        private fun isAppleMusicCatalogId(id: String) = id.all { it.isDigit() }
    }
}

/**
 * A search answer as the `search` tool hands it to the AI: desktop's shape
 * (`artist`, `title`, `album`, `source`), plus `total`, and desktop's message
 * when nothing was found.
 */
fun searchToolResult(query: String, results: List<TrackSearchResult>, limit: Int): Map<String, Any?> {
    if (results.isEmpty()) {
        return mapOf("success" to true, "results" to emptyList<Any>(), "message" to "No results found for \"$query\"")
    }
    return mapOf(
        "success" to true,
        "results" to results.take(limit).map {
            mapOf("artist" to it.artist, "title" to it.title, "album" to it.album, "source" to it.provider)
        },
        "total" to results.size,
    )
}

/** Local files matching [query] ([LibraryTrackSearch]'s match and order), as search results. */
fun localFileSearchResults(localFiles: List<Track>, query: String): List<TrackSearchResult> =
    LibraryTrackSearch.search(localFiles, query).map { track ->
        TrackSearchResult(
            title = track.title,
            artist = track.artist,
            album = track.album,
            duration = track.duration,
            artworkUrl = track.artworkUrl,
            spotifyId = track.spotifyId,
            mbid = track.recordingMbid,
            isrc = track.isrc,
            provider = "localfiles",
        )
    }

/**
 * The [ResolverSearch] both apps use: Spotify's own search through
 * [SpotifyClient] (so its rate-limit gate applies; only with a Spotify sign-in),
 * the Apple Music catalog (only once Apple Music is connected), the local files,
 * and every other searching plugin through [PluginManager.search].
 */
fun streamingResolverSearch(
    settingsStore: SettingsStore,
    pluginManager: PluginManager,
    spotifyClient: SpotifyClient,
    appleMusic: AppleMusicCatalogResolver,
    localFiles: suspend () -> List<Track>,
): ResolverSearch = ResolverSearch(
    getOrder = { settingsStore.getResolverOrder() },
    getActive = { settingsStore.getActiveResolvers() },
    getDisabled = { settingsStore.getDisabledPlugins() },
    getPlugins = { pluginManager.ensureInitialized(); pluginManager.plugins.value },
    nativeSearchers = mapOf(
        "spotify" to { query ->
            if (settingsStore.getSpotifyAccessToken().isNullOrBlank()) {
                null
            } else {
                try {
                    ResolverSearch.spotifyResults(
                        spotifyClient.search(query, "track", limit = ResolverSearch.SERVICE_SEARCH_LIMIT, throwOnServerError = true),
                    )
                } catch (e: ReauthRequiredException) {
                    null
                }
            }
        },
        "applemusic" to { query -> appleMusic.search(query) },
        "localfiles" to { query -> localFileSearchResults(localFiles(), query) },
    ),
    searchPlugin = { id, query -> ResolverSearch.parsePluginResults(pluginManager.search(id, query)) },
)
