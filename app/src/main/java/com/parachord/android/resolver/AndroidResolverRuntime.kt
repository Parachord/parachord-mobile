package com.parachord.android.resolver

import com.parachord.shared.platform.Log
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.plugin.PluginManager
import com.parachord.shared.api.auth.retryAfterSoundCloudRejection
import com.parachord.shared.resolver.AppleMusicCatalogLookup
import com.parachord.shared.resolver.AppleMusicCatalogResolver
import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.ResolverRuntime
import com.parachord.shared.resolver.selectBestMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Android [ResolverRuntime] (#210, D2). Owns the platform-specific native
 * non-Spotify resolvers (Apple Music via the shared catalog search, then the
 * plugin / iTunes; local files via the DAO) and `.axe` execution via
 * [PluginManager.resolve]. SoundCloud is a plugin (`soundcloud.axe`), as on iOS
 * and desktop (#598).
 *
 * The shared [com.parachord.shared.resolver.ResolverCoordinator] owns the
 * fan-out, the Spotify branch (injected lambda), gating, re-score and ranking.
 * Spotify is NOT a dependency here — it lives entirely in the coordinator.
 *
 * These bodies were moved from `ResolverManager` (Task 2). Each returns null
 * for a no-match or a service that isn't connected, and throws when the
 * service couldn't answer (#566, see [ResolverRuntime]).
 */
class AndroidResolverRuntime constructor(
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val appleMusicResolver: AppleMusicCatalogResolver,
    private val trackDao: TrackDao,
    private val pluginManager: PluginManager,
) : ResolverRuntime {

    companion object {
        private const val TAG = "ResolverManager"
    }

    // SoundCloud resolves through soundcloud.axe, as on iOS and desktop (#598).
    override val nativeResolverIds = setOf("applemusic", "localfiles")

    override suspend fun resolveNative(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        album: String?,
    ): ResolvedSource? = when (resolverId) {
        // Android's native AM and local-file resolvers don't take
        // album — parity with the pre-#210 fan-out.
        "applemusic" -> resolveAppleMusic(query, targetTitle, targetArtist)
        "localfiles" -> resolveLocalFile(targetTitle, targetArtist)
        else -> null
    }

    /**
     * Identity-aware dispatch (#402): Apple Music's catalog tier picks among
     * several catalog hits, so it gets the target ISRC to prefer an exact
     * recording before name matching (desktop `findBestMatch` tier 2; catalog
     * songs carry an ISRC, not an MBID). Everything else ignores the identity.
     */
    override suspend fun resolveNative(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        album: String?,
        targetIsrc: String?,
        targetMbid: String?,
    ): ResolvedSource? =
        if (resolverId == "applemusic") resolveAppleMusic(query, targetTitle, targetArtist, targetIsrc)
        else resolveNative(resolverId, query, targetTitle, targetArtist, album)

    override suspend fun resolveAxe(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        album: String?,
    // Android passed null for the .axe album arg pre-#210 (resolveViaPlugin) — keep it.
    // A SoundCloud 401 refreshes the sign-in and asks once more (#598).
    ): ResolvedSource? = retryAfterSoundCloudRejection(
        resolverId,
        currentToken = { settingsStore.getSoundCloudToken() },
        refreshAfterRejection = { oAuthManager.refreshSoundCloudToken(rejectedToken = it) },
    ) { resolveViaPlugin(resolverId, query, targetTitle, targetArtist) }

    // ── Apple Music Resolver ────────────────────────────────────────

    private suspend fun resolveAppleMusic(
        query: String,
        targetTitle: String? = null,
        targetArtist: String? = null,
        targetIsrc: String? = null,
    ): ResolvedSource? {
        // Tier 1: the Apple Music catalog, through the shared search iOS runs
        // too. It also holds the connection gate: no Music User Token, no
        // Apple Music source on any tier (#327) — the tiers below would
        // otherwise resolve it for every user, Spotify-only ones included,
        // and a tap would route to the Apple Music sign-in. A catalog answer
        // is final, as in the plugin host's `appleMusicSearchWithMusicKit`;
        // only an unreachable catalog (no developer token, the limiter
        // cooling down, a failed request) falls through.
        when (val result = appleMusicResolver.lookup(query, targetTitle, targetArtist, targetIsrc)) {
            is AppleMusicCatalogLookup.Found -> return result.source
            AppleMusicCatalogLookup.NotConnected, AppleMusicCatalogLookup.NoMatch -> return null
            AppleMusicCatalogLookup.Unavailable ->
                Log.d(TAG, "Apple Music catalog unavailable, trying the plugin for '$query'")
        }

        // Tier 2: the Apple Music plugin's own resolve, through the resolver
        // registry — the same .axe desktop runs. Its search asks the host's
        // `appleMusicSearchWithMusicKit` (bootstrap.html), which tries the
        // same catalog and falls back to iTunes itself when it can't be
        // reached. The native iTunes search stays only for when the plugin
        // isn't loaded.
        // Wait for the plugins to load first: at launch they finish a few
        // seconds after Home starts resolving, and checking early skipped
        // the plugin for exactly the tracks this tier is for.
        pluginManager.ensureInitialized()
        if (pluginManager.plugins.value.any { it.id == "applemusic" }) {
            return resolveViaPlugin("applemusic", query, targetTitle, targetArtist)
        }
        return resolveViaiTunes(query, targetTitle, targetArtist)
    }

    private suspend fun resolveViaiTunes(
        query: String,
        targetTitle: String? = null,
        targetArtist: String? = null,
    ): ResolvedSource? =
        withContext(Dispatchers.IO) {
            try {
                val url = okhttp3.HttpUrl.Builder()
                    .scheme("https")
                    .host("itunes.apple.com")
                    .addPathSegment("search")
                    .addQueryParameter("term", query)
                    .addQueryParameter("media", "music")
                    .addQueryParameter("entity", "song")
                    .addQueryParameter("limit", "5")
                    .build()

                val request = Request.Builder().url(url).get().build()
                val response = okHttpClient.newCall(request).execute()
                // Couldn't answer (#566): throw, so the track isn't cached as
                // having no Apple Music match.
                if (!response.isSuccessful) throw IllegalStateException("iTunes search returned ${response.code}")

                val body = response.body?.string() ?: throw IllegalStateException("iTunes search returned no body")
                val result = json.decodeFromString<ITunesSearchResponse>(body)
                // Best title+artist match (the shared matcher) rather than the
                // first iTunes hit.
                val best = (
                    if (targetTitle != null && targetArtist != null) {
                        selectBestMatch(result.results, targetTitle, targetArtist, { it.trackName }, { it.artistName })
                            ?: result.results.firstOrNull()
                    } else {
                        result.results.firstOrNull()
                    }
                    ) ?: return@withContext null

                Log.d(TAG, "Apple Music (iTunes) matched '${best.trackName}' by ${best.artistName}")

                // iTunes returns artworkUrl100 (100x100). Substitute to 600x600
                // for a higher-quality URL, matching what `bestImageUrl` does
                // for AM library responses elsewhere in the app.
                val artwork600 = best.artworkUrl100?.replace("100x100", "600x600")
                ResolvedSource(
                    url = best.trackViewUrl ?: "applemusic:song:${best.trackId}",
                    sourceType = "applemusic",
                    resolver = "applemusic",
                    appleMusicId = best.trackId.toString(),
                    confidence = 0.85, // Default — overridden by scoreConfidence() in resolve()
                    matchedTitle = best.trackName,
                    matchedArtist = best.artistName,
                    matchedDurationMs = best.trackTimeMillis,
                    artworkUrl = artwork600,
                )
            } catch (e: Exception) {
                Log.w(TAG, "iTunes search failed for '$query': ${e.message}")
                throw e
            }
        }

    // ── Local File Resolver ──────────────────────────────────────────────

    /**
     * Search the local library for a matching track.
     * Requires both title and artist to be known for a reliable match.
     */
    private suspend fun resolveLocalFile(
        targetTitle: String?,
        targetArtist: String?,
    ): ResolvedSource? {
        if (targetTitle.isNullOrBlank() || targetArtist.isNullOrBlank()) return null
        return try {
            val track = trackDao.findLocalFile(targetTitle, targetArtist) ?: return null
            val sourceUrl = track.sourceUrl ?: return null
            Log.d(TAG, "Local file matched '${track.title}' by ${track.artist}")
            ResolvedSource(
                url = sourceUrl,
                sourceType = "local",
                resolver = "localfiles",
                // Direct-match path: findLocalFile is a deterministic
                // case-insensitive equality SQL lookup, not a fuzzy search.
                // Matches desktop's localfiles resolver, which the
                // eager-enrichment gate at app.js:24143 treats as a 1.0
                // signal.
                confidence = 1.0,
                matchedTitle = track.title,
                matchedArtist = track.artist,
                matchedDurationMs = track.duration,
                // ISRC captured from the file's ID3 TSRC tag at scan time and
                // persisted on the track row (#238) — surface it so pickTrackIsrc
                // walks it and the ISRC→MBID fallback works for local files too.
                isrc = com.parachord.shared.resolver.validateIsrc(track.isrc),
                // The row's recording MBID (mapper / ISRC enrichment) feeds the
                // identity tier of scoreConfidence (#402).
                recordingMbid = track.recordingMbid,
                // Local files store their artworkUrl on the track row (either
                // a content:// URI from MediaScanner's albumart resolution or
                // a file:// URI from ID3 embedded art). Surface it so the
                // resolver carries an authoritative-for-this-track URL.
                artworkUrl = track.artworkUrl,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Local file resolve failed: ${e.message}")
            throw e
        }
    }

    // ── .axe Plugin Resolver ──────────────────────────────────────────

    /**
     * Resolve a track via an .axe plugin (bandcamp, youtube, etc.).
     * The plugin's resolve() function returns a JSON result that we
     * map to a [ResolvedSource].
     */
    private suspend fun resolveViaPlugin(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
    ): ResolvedSource? = try {
        val resultJson = pluginManager.resolve(
            resolverId,
            targetArtist ?: "",
            targetTitle ?: query,
            null,
        )
        Log.d(TAG, "Plugin resolve($resolverId) raw result: ${resultJson?.take(200)}")
        if (resultJson == null) {
            null
        } else {
            val result = json.decodeFromString<AxeResolveResult>(resultJson)
            // Plugins don't all return a top-level `url`: ID-based resolvers
            // (Apple Music, Spotify) carry a catalog id they route by, and
            // Bandcamp carries only `bandcampUrl` (its `<resolverId>Url`).
            // Accept any of them, as iOS does.
            val resolvedUrl = result.url
                ?: com.parachord.shared.resolver.pluginServiceUrl(resultJson, resolverId)
                ?: result.appleMusicUrl
                ?: result.soundcloudUrl
                ?: result.spotifyUrl
                ?: result.appleMusicId?.let { "applemusic:song:$it" }
                ?: result.spotifyUri
                ?: return@resolveViaPlugin null
            ResolvedSource(
                resolver = resolverId,
                sourceType = result.sourceType ?: resolverId,
                url = resolvedUrl,
                spotifyUri = result.spotifyUri,
                spotifyId = result.spotifyId,
                soundcloudId = result.soundcloudId,
                appleMusicId = result.appleMusicId,
                bandcampTrackId = com.parachord.shared.resolver.pluginBandcampTrackId(resultJson),
                // Optional `.axe` result field — a plugin that resolves a
                // streaming source MAY return its ISRC for the MBID fallback,
                // validated/normalized so a malformed value never stores (#217).
                isrc = com.parachord.shared.resolver.validateIsrc(result.isrc),
                // Optional `.axe` recording MBID (`mbid` first, then
                // `recordingMbid`, desktop's precedence) for the identity tier (#402).
                recordingMbid = result.mbid?.takeIf { it.isNotEmpty() } ?: result.recordingMbid?.takeIf { it.isNotEmpty() },
                confidence = 0.9,
                matchedTitle = result.title,
                matchedArtist = result.artist,
                matchedDurationMs = result.duration?.takeIf { it > 0 }?.times(1000),
                artworkUrl = result.albumArt,
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Plugin resolve($resolverId) failed: ${e.message}")
        throw e
    }
}

@kotlinx.serialization.Serializable
private data class AxeResolveResult(
    val url: String? = null,
    val title: String? = null,
    val artist: String? = null,
    /** Seconds, per the plugin track shape. */
    val duration: Long? = null,
    val sourceType: String? = null,
    val spotifyUri: String? = null,
    val spotifyId: String? = null,
    val spotifyUrl: String? = null,
    val soundcloudId: String? = null,
    val soundcloudUrl: String? = null,
    val appleMusicId: String? = null,
    val appleMusicUrl: String? = null,
    val isrc: String? = null,
    val mbid: String? = null,
    val recordingMbid: String? = null,
    val albumArt: String? = null,
)

// ── iTunes API Response Models ──────────────────────────────────────

@Serializable
private data class ITunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ITunesTrack> = emptyList(),
)

@Serializable
private data class ITunesTrack(
    val trackId: Long,
    val trackName: String,
    val artistName: String,
    val collectionName: String? = null,
    val trackViewUrl: String? = null,
    val artworkUrl100: String? = null,
    val trackTimeMillis: Long? = null,
    val previewUrl: String? = null,
)
