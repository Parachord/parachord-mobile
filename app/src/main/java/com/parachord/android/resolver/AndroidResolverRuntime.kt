package com.parachord.android.resolver

import com.parachord.shared.platform.Log
import com.parachord.android.auth.OAuthManager
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.handlers.MusicKitWebBridge
import com.parachord.android.plugin.PluginManager
import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.ResolverRuntime
import com.parachord.shared.resolver.selectBestMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Android [ResolverRuntime] (#210, D2). Owns the platform-specific native
 * non-Spotify resolvers (Apple Music via MusicKit + iTunes, SoundCloud via the
 * API, local files via the DAO) and `.axe` execution via [PluginManager.resolve].
 *
 * The shared [com.parachord.shared.resolver.ResolverCoordinator] owns the
 * fan-out, the Spotify branch (injected lambda), gating, re-score and ranking.
 * Spotify is NOT a dependency here — it lives entirely in the coordinator.
 *
 * These bodies were moved VERBATIM from `ResolverManager` (Task 2). The logic is
 * unchanged.
 */
class AndroidResolverRuntime constructor(
    private val settingsStore: SettingsStore,
    private val oAuthManager: OAuthManager,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val musicKitBridge: MusicKitWebBridge,
    private val trackDao: TrackDao,
    private val pluginManager: PluginManager,
) : ResolverRuntime {

    companion object {
        private const val TAG = "ResolverManager"
        private const val SC_API_BASE = "https://api.soundcloud.com"
    }

    override val nativeResolverIds = setOf("applemusic", "soundcloud", "localfiles")

    override suspend fun resolveNative(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        album: String?,
    ): ResolvedSource? = when (resolverId) {
        // Android's native AM (MusicKit+iTunes), SoundCloud, and local-file
        // resolvers don't take album — parity with the pre-#210 fan-out.
        "applemusic" -> resolveAppleMusic(query, targetTitle, targetArtist)
        "soundcloud" -> resolveSoundCloud(query)
        "localfiles" -> resolveLocalFile(targetTitle, targetArtist)
        else -> null
    }

    override suspend fun resolveAxe(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        album: String?,
    // Android passed null for the .axe album arg pre-#210 (resolveViaPlugin) — keep it.
    ): ResolvedSource? = resolveViaPlugin(resolverId, query, targetTitle, targetArtist)

    // ── Apple Music Resolver ────────────────────────────────────────

    private suspend fun resolveAppleMusic(
        query: String,
        targetTitle: String? = null,
        targetArtist: String? = null,
    ): ResolvedSource? {
        // Tier 1: MusicKit JS (requires developer token + auth)
        if (musicKitBridge.configured.value) {
            try {
                val results = musicKitBridge.search(query, limit = 5)
                // Pick the best title+artist match (desktop's matchFromResults,
                // via the shared bidirectional matcher that scoreConfidence uses)
                // instead of blindly taking the first catalog hit — a wrong first
                // result would score 0.50 and get floored out, leaving NO Apple
                // Music source even when a correct match sat lower in the list.
                val best = if (targetTitle != null && targetArtist != null) {
                    selectBestMatch(results, targetTitle, targetArtist, { it.title }, { it.artist })
                        ?: results.firstOrNull()
                } else {
                    results.firstOrNull()
                }
                if (best != null) {
                    Log.d(TAG, "Apple Music (MusicKit) matched '${best.title}' by ${best.artist}")
                    return ResolvedSource(
                        url = best.appleMusicUrl ?: "applemusic:song:${best.id}",
                        sourceType = "applemusic",
                        resolver = "applemusic",
                        appleMusicId = best.id,
                        // MusicKit catalog attributes carry the ISRC (iTunes-Search
                        // Tier 2 below does not) — supply it for the MBID fallback,
                        // validated/normalized so a malformed value never stores (#217).
                        isrc = com.parachord.shared.resolver.validateIsrc(best.isrc),
                        confidence = 0.9, // Default — overridden by scoreConfidence() in resolve()
                        matchedTitle = best.title,
                        matchedArtist = best.artist,
                        // Carry MusicKit's artwork URL so ImageEnrichmentService
                        // persists it onto the track row instead of re-asking
                        // the metadata cascade for art it could've had for free.
                        artworkUrl = best.artworkUrl,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "MusicKit search failed, falling back to iTunes: ${e.message}")
            }
        }

        // Tier 2: iTunes Search API (no auth, metadata-only)
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
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val result = json.decodeFromString<ITunesSearchResponse>(body)
                // Best title+artist match (see resolveAppleMusic Tier 1) rather
                // than the first iTunes hit.
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
                null
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
                // signal. See SoundCloud branch (~L190) for the contract.
                confidence = 1.0,
                matchedTitle = track.title,
                matchedArtist = track.artist,
                matchedDurationMs = track.duration,
                // Local files store their artworkUrl on the track row (either
                // a content:// URI from MediaScanner's albumart resolution or
                // a file:// URI from ID3 embedded art). Surface it so the
                // resolver carries an authoritative-for-this-track URL.
                artworkUrl = track.artworkUrl,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Local file resolve failed: ${e.message}")
            null
        }
    }

    // ── SoundCloud Resolver ─────────────────────────────────────────────

    /**
     * Search SoundCloud for a matching track.
     * Mirrors the desktop's soundcloud.axe resolver: searches via the
     * /tracks endpoint, filters to streamable non-blocked tracks, and
     * returns the best match with default 0.9 confidence.
     * Handles 401 by refreshing the OAuth token and retrying.
     */
    private suspend fun resolveSoundCloud(query: String): ResolvedSource? {
        val token = settingsStore.getSoundCloudToken()
        if (token.isNullOrBlank()) return null

        return try {
            val result = searchSoundCloudTrack(query, token)
            // Check for 401 (token expired) — returned as null with a log warning
            result
        } catch (e: SoundCloudAuthException) {
            // Token expired — try to refresh
            if (oAuthManager.refreshSoundCloudToken()) {
                val newToken = settingsStore.getSoundCloudToken() ?: return null
                try {
                    searchSoundCloudTrack(query, newToken)
                } catch (e2: Exception) {
                    Log.w(TAG, "SoundCloud resolve failed after refresh for '$query': ${e2.message}")
                    null
                }
            } else {
                Log.w(TAG, "SoundCloud token refresh failed for '$query'")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloud resolve failed for '$query': ${e.message}")
            null
        }
    }

    /** Thrown when SoundCloud API returns 401 (token expired). */
    private class SoundCloudAuthException : Exception("SoundCloud token expired")

    private suspend fun searchSoundCloudTrack(query: String, token: String): ResolvedSource? =
        withContext(Dispatchers.IO) {
            val url = okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("api.soundcloud.com")
                .addPathSegment("tracks")
                .addQueryParameter("q", query)
                .addQueryParameter("limit", "20")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "OAuth $token")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.code == 401) {
                throw SoundCloudAuthException()
            }
            if (!response.isSuccessful) {
                Log.w(TAG, "SoundCloud search returned ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val tracks = try {
                json.decodeFromString<List<ScTrack>>(body)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse SoundCloud search results: ${e.message}")
                return@withContext null
            }

            // Filter to streamable, non-blocked tracks (matches desktop logic)
            val streamable = tracks.filter { it.streamable == true && it.access != "blocked" }
            val best = streamable.firstOrNull() ?: return@withContext null

            Log.d(TAG, "SoundCloud matched '${best.title}' by ${best.user?.username}")

            ResolvedSource(
                url = best.permalinkUrl ?: "$SC_API_BASE/tracks/${best.id}",
                sourceType = "soundcloud",
                resolver = "soundcloud",
                soundcloudId = best.id.toString(),
                soundcloudUrl = best.permalinkUrl,
                confidence = 0.9, // Default — overridden by scoreConfidence() in resolve()
                matchedTitle = best.title,
                matchedArtist = best.user?.username,
                matchedDurationMs = best.duration,
            )
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
            val resolvedUrl = result.url ?: return@resolveViaPlugin null
            ResolvedSource(
                resolver = resolverId,
                sourceType = result.sourceType ?: resolverId,
                url = resolvedUrl,
                spotifyUri = result.spotifyUri,
                spotifyId = result.spotifyId,
                soundcloudId = result.soundcloudId,
                appleMusicId = result.appleMusicId,
                // Optional `.axe` result field — a plugin that resolves a
                // streaming source MAY return its ISRC for the MBID fallback,
                // validated/normalized so a malformed value never stores (#217).
                isrc = com.parachord.shared.resolver.validateIsrc(result.isrc),
                confidence = 0.9,
                matchedTitle = result.title,
                matchedArtist = result.artist,
                matchedDurationMs = result.duration,
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Plugin resolve($resolverId) failed: ${e.message}")
        null
    }
}

@kotlinx.serialization.Serializable
private data class AxeResolveResult(
    val url: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val duration: Long? = null,
    val sourceType: String? = null,
    val spotifyUri: String? = null,
    val spotifyId: String? = null,
    val soundcloudId: String? = null,
    val appleMusicId: String? = null,
    val isrc: String? = null,
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

// ── SoundCloud API Response Models ──────────────────────────────────

@Serializable
private data class ScTrack(
    val id: Long,
    val title: String? = null,
    val user: ScUser? = null,
    val duration: Long? = null,
    @SerialName("permalink_url") val permalinkUrl: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("waveform_url") val waveformUrl: String? = null,
    val streamable: Boolean? = null,
    val access: String? = null,
    @SerialName("label_name") val labelName: String? = null,
)

@Serializable
private data class ScUser(
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)
