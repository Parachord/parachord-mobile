package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.validateIsrc
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Thrown by [SpotifyClient.search] when Spotify responds with HTTP 429.
 * Callers (notably [com.parachord.android.resolver.ResolverManager.resolveSpotify])
 * catch this to set a session-level cooldown so subsequent searches
 * short-circuit while Spotify's rate-limit window expires — better not
 * to hammer Spotify into extending the throttle.
 *
 * Mirrors the [ItunesRateLimitedException] pattern from commit `16884d1`
 * for Apple Music's iTunes Search after the same KMP-migration regression:
 * Retrofit/OkHttp's interceptor-chain 429 retry was lost when Spotify cut
 * over to Ktor (commit `92ed9eb`, Phase 9E.1.8). The
 * [com.parachord.shared.sync.SpotifySyncProvider.withRetry] KDoc explicitly
 * documents this regression — this typed exception is the followup.
 *
 * @property retryAfterSeconds value of the `Retry-After` header if Spotify
 *   sent one; null otherwise. Callers may use this to size their cooldown.
 */
class SpotifyRateLimitedException(val retryAfterSeconds: Long? = null) : Exception(
    "Spotify returned HTTP 429" +
        (retryAfterSeconds?.let { " (Retry-After: ${it}s)" } ?: "")
)

/**
 * Spotify Web API client. Cross-platform (commonMain).
 *
 * Auth: per-request `Authorization: Bearer <access_token>` resolved
 * via [AuthTokenProvider.tokenFor] for [AuthRealm.Spotify]. Consumers
 * no longer pass `auth: String` per call — the client owns auth
 * resolution.
 *
 * 401 handling: requests on `api.spotify.com` flow through the global
 * [com.parachord.shared.api.transport.OAuthRefreshPlugin] (registered
 * via [installSharedPlugins]). On 401 the plugin single-flights a token
 * refresh via [com.parachord.shared.api.auth.OAuthTokenRefresher],
 * retries once with the new bearer, and throws
 * [com.parachord.shared.api.transport.ReauthRequiredException] on
 * two-strikes failure. This client does NOT need its own 401 retry
 * logic — the plugin owns that surface.
 *
 * Phase 9E.1.8 cutover (Apr 2026): consumers migrated from app-side
 * Retrofit `SpotifyApi`. Spotify is the OAuth refresh canary — first
 * production cutover to exercise [OAuthRefreshPlugin] under real
 * concurrent load.
 */
class SpotifyClient(
    private val httpClient: HttpClient,
    private val tokens: AuthTokenProvider,
    /**
     * Read/write the persisted cooldown epoch-ms across process restarts.
     * Wired via Koin to a `KvStore`-backed pair (key
     * `spotify_rate_limit_cooldown_ms`). Pass null on tests / non-Android
     * surfaces where persistence isn't desired.
     *
     * Why this exists: Spotify's abuse window can run 1+ hours
     * (`Retry-After: 3600`). An in-memory-only gate erases that on every
     * process restart and probes Spotify cold the moment the next resolve
     * fan-out fires, which Spotify often answers with a *fresh* 3600s
     * window — restarting the punishment clock. Persisting the cooldown
     * lets a restarted process honor the original window without poking
     * an already-angry upstream.
     */
    loadCooldownEpochMs: (() -> Long)? = null,
    saveCooldownEpochMs: (suspend (Long) -> Unit)? = null,
) {

    companion object {
        private const val BASE = "https://api.spotify.com"
    }

    /**
     * Per-client rate-limit gate. See [RateLimitGate] for design notes.
     *
     * Covers ALL Spotify GET endpoints below — Spotify's 429s are
     * account-wide (one endpoint hits 429, every subsequent call to any
     * endpoint also 429s until the window expires), so a single shared
     * gate is the right shape. PR #115 originally protected only `search`,
     * `getCurrentUser`, `getPlaylistTracks`; we extend coverage here
     * because `getTrack` (used by [com.parachord.android.resolver.ResolverManager.verifyTrack]
     * to fan out per-track checks for stored `spotifyId`s) was the actual
     * volume offender on hosted-XSPF playlists — every cached `spotifyId`
     * triggered an unprotected `/v1/tracks/{id}` call that 429'd, each one
     * extending Spotify's punishment window indefinitely.
     *
     * Library + playlist write methods (saveTracks, removeTracks,
     * createPlaylist, replacePlaylistTracks, …) ALSO route through the
     * gate via [gatedSend] (issue #176) so a 429 on a write sets the same
     * persisted cooldown a read does, and a write can't fire mid-cooldown
     * to re-arm Spotify's abuse window on the shared `client_id`.
     *
     * Only the interactive playback-control PUT/DELETE methods
     * (startPlayback, pausePlayback, seekPlayback, setVolume,
     * transferPlayback) and [getDevices] intentionally **do not** route
     * through the gate — the user needs to be able to skip / pause even
     * during a throttle window. Those low-volume, user-initiated calls
     * honor the cooldown advisorily (the playback flow checks the gate's
     * remaining cooldown and bails before poking a penalized account).
     */
    private val gate = RateLimitGate(
        tag = "SpotifyClient",
        loadCooldownEpochMs = loadCooldownEpochMs,
        saveCooldownEpochMs = saveCooldownEpochMs,
        // Spotify's abuse-mode ban routinely outlasts a flat 1h cooldown
        // (observed: still 429ing 12+ hours later despite a quiet network).
        // With a flat cap, each lapse re-pokes the still-banned account and
        // Spotify re-extends its window — so it never clears. Escalate the
        // cooldown on consecutive 429s (1h→2h→4h→6h cap) so a persistent ban
        // pushes our local cooldown past Spotify's window and we stop poking,
        // letting the ban decay. Resets to base on the first clean response.
        escalateOnRepeat = true,
        maxCooldownMs = 6L * 60L * 60L * 1000L,
        // PROACTIVE cap: Spotify Developer-Mode is a rolling-30s window that
        // caps around ~180–200 req/min. Stay well under (75 / 30s ≈ 150/min) so
        // a cold-cache browse can't blow the ceiling — and leave headroom for
        // the rest of the fleet on the shared client_id. Backstop to the #211
        // hint-skip (which removes most searches in the first place).
        maxRequestsPerWindow = 75,
        requestWindowMs = 30_000L,
    )

    /**
     * Milliseconds remaining on the Spotify rate-limit cooldown (0 = clear).
     *
     * The Spotify Connect device/playback endpoints (`getDevices`,
     * `startPlayback`, `pausePlayback`, …) are intentionally NOT gated —
     * they must work during normal playback regardless of catalog-search
     * pacing. But during a 429 abuse window, polling them keeps the
     * account's rolling window hot and re-arms the cooldown forever. The
     * playback flow should call this FIRST and bail when it's > 0, so we
     * stop poking an already-penalized account. Mirrors the Android rule:
     * never make ungated Spotify calls during an abuse window.
     */
    fun rateLimitRemainingMs(): Long = gate.remainingCooldownMs()

    /**
     * Apply the Spotify bearer token from [AuthTokenProvider]. If no
     * token is currently stored, the call falls through with no
     * Authorization header set — the server will return 401, and
     * `OAuthRefreshPlugin` will attempt a refresh-and-retry.
     */
    private suspend fun applyAuth(builder: HttpRequestBuilder) {
        val token = (tokens.tokenFor(AuthRealm.Spotify) as? AuthCredential.BearerToken)?.accessToken
        if (token != null) builder.header(HttpHeaders.Authorization, "Bearer $token")
    }

    /**
     * Wraps every GET in the rate-limit gate. Reads response status BEFORE
     * `.body()` deserialization so a 429 surfaces as a typed
     * [SpotifyRateLimitedException] (instead of a `NoTransformationFoundException`
     * from Ktor's body parser trying to deserialize the empty 429 body).
     *
     * Other non-success responses fall through to `.body()` — Ktor will
     * throw, which preserves the existing per-call exception handling at
     * caller sites (e.g. `ResolverManager.verifyTrack`'s try/catch → null).
     */
    private suspend inline fun <reified T> gatedGet(
        url: String,
        crossinline configure: suspend HttpRequestBuilder.() -> Unit,
    ): T = gate.withPermit(exceptionFactory = { SpotifyRateLimitedException(it) }) {
        val response: HttpResponse = httpClient.get(url) { configure() }
        gate.handleResponse(response) { SpotifyRateLimitedException(it) }
        response.body()
    }

    /**
     * Routes a write/mutation request through the same rate-limit gate as
     * [gatedGet] (issue #176). Acquires a permit (short-circuiting with a
     * typed [SpotifyRateLimitedException] if the cooldown is active),
     * issues the request, then calls [RateLimitGate.handleResponse] so a
     * 429 on a write SETS + persists the cooldown — and throws — exactly
     * like a 429 on a gated read.
     *
     * Why this matters: Spotify's 429s are account-wide and its abuse
     * window hands back a long `Retry-After` (~1h). If writes stayed
     * ungated, a write 429 would never arm the cooldown, and a write fired
     * mid-cooldown (e.g. set by another client on the shared `client_id`)
     * would poke an already-penalized account and re-arm a fresh ~1h
     * window. The gate's persisted cooldown is the single source of truth;
     * every high-volume call — read OR write — must route through it.
     *
     * Returns the raw [HttpResponse] for callers that inspect status. The
     * interactive playback-control PUTs (`play`/`pause`/`seek`/`volume`/
     * `transferPlayback`) and [getDevices] deliberately stay ungated — the
     * user must be able to control playback during a throttle window. See
     * the [gate] KDoc.
     */
    private suspend fun gatedSend(request: suspend () -> HttpResponse): HttpResponse =
        gate.withPermit(exceptionFactory = { SpotifyRateLimitedException(it) }) {
            val response = request()
            gate.handleResponse(response) { SpotifyRateLimitedException(it) }
            response
        }

    // ── Search + Lookup ──────────────────────────────────────────────

    /**
     * Search the Spotify catalog. The gate handles 429 → typed exception;
     * other non-success responses are mapped to an empty [SpSearchResponse]
     * so the resolver doesn't crash on transient 5xx (mirrors
     * [AppleMusicClient.search]).
     */
    suspend fun search(query: String, type: String, limit: Int = 20, market: String = "from_token"): SpSearchResponse =
        gate.withPermit(exceptionFactory = { SpotifyRateLimitedException(it) }) {
            val response: HttpResponse = httpClient.get("$BASE/v1/search") {
                applyAuth(this)
                parameter("q", query); parameter("type", type); parameter("limit", limit); parameter("market", market)
            }
            gate.handleResponse(response) { SpotifyRateLimitedException(it) }
            if (!response.status.isSuccess()) SpSearchResponse() else response.body()
        }

    /**
     * Resolve a free-text query to a single playable Spotify track as a
     * [ResolvedSource]. Lifted verbatim from Android's
     * `ResolverManager.searchSpotifyTrack` so both platforms share one impl.
     *
     * Filters to tracks that are actually playable in the user's market
     * (`market=from_token` sets `is_playable`). Returns the first such track,
     * or null if none. `confidence` defaults to 0.9 — overridden by
     * `scoreConfidence()` in the resolver pipeline.
     */
    suspend fun searchTrack(query: String): ResolvedSource? {
        val response = search(query = query, type = "track", limit = 5)
        val track = response.tracks?.items?.firstOrNull { it.isPlayable != false } ?: return null
        val albumArt = track.album?.images?.firstOrNull()?.url
        return ResolvedSource(
            url = "spotify:track:${track.id}",
            sourceType = "spotify",
            resolver = "spotify",
            spotifyUri = "spotify:track:${track.id}",
            spotifyId = track.id,
            isrc = validateIsrc(track.externalIds?.isrc),
            confidence = 0.9,
            matchedTitle = track.name,
            matchedArtist = track.artistName,
            matchedDurationMs = track.durationMs,
            artworkUrl = albumArt,
        )
    }

    suspend fun getTrack(trackId: String, market: String = "from_token"): SpTrack =
        gatedGet("$BASE/v1/tracks/$trackId") { applyAuth(this); parameter("market", market) }

    suspend fun getArtist(artistId: String): SpArtist =
        gatedGet("$BASE/v1/artists/$artistId") { applyAuth(this) }

    suspend fun getArtistTopTracks(artistId: String, market: String = "US"): SpTopTracksResponse =
        gatedGet("$BASE/v1/artists/$artistId/top-tracks") { applyAuth(this); parameter("market", market) }

    suspend fun getArtistAlbums(artistId: String, includeGroups: String = "album,single,compilation", limit: Int = 50): SpPaginated<SpAlbum> =
        gatedGet("$BASE/v1/artists/$artistId/albums") {
            applyAuth(this); parameter("include_groups", includeGroups); parameter("limit", limit)
        }

    suspend fun getAlbumTracks(albumId: String, limit: Int = 50): SpPaginated<SpSimpleTrack> =
        gatedGet("$BASE/v1/albums/$albumId/tracks") { applyAuth(this); parameter("limit", limit) }

    // ── Playback Control (Spotify Connect) ───────────────────────────

    suspend fun getDevices(): SpDevicesResponse =
        httpClient.get("$BASE/v1/me/player/devices") { applyAuth(this) }.body()

    suspend fun transferPlayback(body: SpTransferRequest): HttpResponse =
        httpClient.put("$BASE/v1/me/player") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
        }

    suspend fun startPlayback(body: SpPlaybackRequest, deviceId: String? = null): HttpResponse =
        httpClient.put("$BASE/v1/me/player/play") {
            applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
            if (deviceId != null) parameter("device_id", deviceId)
        }

    suspend fun resumePlayback(): HttpResponse =
        httpClient.put("$BASE/v1/me/player/play") { applyAuth(this) }

    suspend fun pausePlayback(): HttpResponse =
        httpClient.put("$BASE/v1/me/player/pause") { applyAuth(this) }

    suspend fun seekPlayback(positionMs: Long): HttpResponse =
        httpClient.put("$BASE/v1/me/player/seek") {
            applyAuth(this); parameter("position_ms", positionMs)
        }

    suspend fun setVolume(volumePercent: Int, deviceId: String? = null): HttpResponse =
        httpClient.put("$BASE/v1/me/player/volume") {
            applyAuth(this)
            parameter("volume_percent", volumePercent.coerceIn(0, 100))
            if (deviceId != null) parameter("device_id", deviceId)
        }

    suspend fun getPlaybackState(): HttpResponse =
        httpClient.get("$BASE/v1/me/player") { applyAuth(this) }

    /**
     * Typed convenience over [getPlaybackState] for Swift consumption and the
     * cold-device verification step (Swift can't call the reified
     * `HttpResponse.body<SpPlaybackState>()`). Returns the decoded state, or
     * null on 204 (no active device), any non-2xx, or a decode/transport
     * failure. Never throws.
     */
    suspend fun getPlaybackStateOrNull(): SpPlaybackState? = try {
        val resp = getPlaybackState()
        if (resp.status.value == 204 || !resp.status.isSuccess()) null else resp.body()
    } catch (e: Exception) {
        null
    }

    // ── Library (Sync Read) ──────────────────────────────────────────

    suspend fun getLikedTracks(limit: Int = 50, offset: Int = 0, market: String = "from_token"): SpSavedTracksResponse =
        gatedGet("$BASE/v1/me/tracks") {
            applyAuth(this); parameter("limit", limit); parameter("offset", offset); parameter("market", market)
        }

    suspend fun getSavedAlbums(limit: Int = 50, offset: Int = 0, market: String = "from_token"): SpSavedAlbumsResponse =
        gatedGet("$BASE/v1/me/albums") {
            applyAuth(this); parameter("limit", limit); parameter("offset", offset); parameter("market", market)
        }

    suspend fun getFollowedArtists(limit: Int = 50, after: String? = null): SpFollowedArtistsResponse =
        gatedGet("$BASE/v1/me/following") {
            applyAuth(this); parameter("type", "artist"); parameter("limit", limit)
            if (after != null) parameter("after", after)
        }

    suspend fun getUserPlaylists(limit: Int = 50, offset: Int = 0): SpPaginatedPlaylists =
        gatedGet("$BASE/v1/me/playlists") {
            applyAuth(this); parameter("limit", limit); parameter("offset", offset)
        }

    suspend fun getPlaylistTracks(playlistId: String, limit: Int = 100, offset: Int = 0, market: String = "from_token"): SpPlaylistTracksResponse =
        gatedGet("$BASE/v1/playlists/$playlistId/tracks") {
            applyAuth(this); parameter("limit", limit); parameter("offset", offset); parameter("market", market)
        }

    suspend fun getPlaylist(playlistId: String, fields: String? = null): SpPlaylistFull =
        gatedGet("$BASE/v1/playlists/$playlistId") {
            applyAuth(this); if (fields != null) parameter("fields", fields)
        }

    suspend fun getCurrentUser(): SpUser =
        gatedGet("$BASE/v1/me") { applyAuth(this) }

    // ── Library Write ────────────────────────────────────────────────

    suspend fun saveTracks(body: SpIdsRequest): HttpResponse =
        gatedSend {
            httpClient.put("$BASE/v1/me/tracks") {
                applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
            }
        }

    suspend fun removeTracks(body: SpIdsRequest): HttpResponse =
        gatedSend {
            httpClient.delete("$BASE/v1/me/tracks") {
                applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
            }
        }

    suspend fun saveAlbums(body: SpIdsRequest): HttpResponse =
        gatedSend {
            httpClient.put("$BASE/v1/me/albums") {
                applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
            }
        }

    suspend fun removeAlbums(body: SpIdsRequest): HttpResponse =
        gatedSend {
            httpClient.delete("$BASE/v1/me/albums") {
                applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
            }
        }

    suspend fun followArtists(body: SpIdsRequest): HttpResponse =
        gatedSend {
            httpClient.put("$BASE/v1/me/following") {
                applyAuth(this); parameter("type", "artist")
                contentType(ContentType.Application.Json); setBody(body)
            }
        }

    suspend fun unfollowArtists(body: SpIdsRequest): HttpResponse =
        gatedSend {
            httpClient.delete("$BASE/v1/me/following") {
                applyAuth(this); parameter("type", "artist")
                contentType(ContentType.Application.Json); setBody(body)
            }
        }

    // ── Playlist Write ───────────────────────────────────────────────

    suspend fun createPlaylist(userId: String, body: SpCreatePlaylistRequest): SpPlaylistFull =
        gatedSend {
            httpClient.post("$BASE/v1/users/$userId/playlists") {
                applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
            }
        }.body()

    suspend fun replacePlaylistTracks(playlistId: String, body: SpUrisRequest): HttpResponse =
        gatedSend {
            httpClient.put("$BASE/v1/playlists/$playlistId/tracks") {
                applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
            }
        }

    suspend fun addPlaylistTracks(playlistId: String, body: SpUrisRequest): HttpResponse =
        gatedSend {
            httpClient.post("$BASE/v1/playlists/$playlistId/tracks") {
                applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
            }
        }

    suspend fun updatePlaylistDetails(playlistId: String, body: SpUpdatePlaylistRequest): HttpResponse =
        gatedSend {
            httpClient.put("$BASE/v1/playlists/$playlistId") {
                applyAuth(this); contentType(ContentType.Application.Json); setBody(body)
            }
        }

    suspend fun unfollowPlaylist(playlistId: String): HttpResponse =
        gatedSend {
            httpClient.delete("$BASE/v1/playlists/$playlistId/followers") { applyAuth(this) }
        }
}

// ── Response Models ──────────────────────────────────────────────────

@Serializable
data class SpTopTracksResponse(val tracks: List<SpTrack> = emptyList())

@Serializable
data class SpSearchResponse(
    val tracks: SpPaginated<SpTrack>? = null,
    val albums: SpPaginated<SpAlbum>? = null,
    val artists: SpPaginated<SpArtist>? = null,
)

@Serializable
data class SpPaginated<T>(val items: List<T> = emptyList(), val total: Int = 0)

@Serializable
data class SpTrack(
    val id: String? = null,
    val name: String? = null,
    val artists: List<SpArtistRef> = emptyList(),
    val album: SpAlbumRef? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    @SerialName("is_playable") val isPlayable: Boolean? = null,
    @SerialName("external_ids") val externalIds: SpExternalIds? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name.orEmpty() }
}

/** Spotify `external_ids` — carries the ISRC (present on full track objects from
 *  search + `/v1/tracks/{id}`), the supply side of the ISRC → MBID fallback. */
@Serializable
data class SpExternalIds(val isrc: String? = null)

@Serializable
data class SpArtistRef(val id: String? = null, val name: String? = null)

@Serializable
data class SpAlbumRef(
    val id: String? = null, val name: String? = null, val images: List<SpImage>? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int? = null,
)

@Serializable
data class SpAlbum(
    val id: String? = null, val name: String? = null, val artists: List<SpArtistRef> = emptyList(),
    val images: List<SpImage>? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("total_tracks") val totalTracks: Int? = null,
    @SerialName("album_type") val albumType: String? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name.orEmpty() }
    val year: Int? get() = releaseDate?.take(4)?.toIntOrNull()
}

@Serializable
data class SpSimpleTrack(
    val id: String? = null, val name: String? = null, val artists: List<SpArtistRef> = emptyList(),
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
) {
    val artistName: String get() = artists.joinToString(", ") { it.name.orEmpty() }
}

@Serializable
data class SpArtist(val id: String? = null, val name: String? = null, val genres: List<String> = emptyList(), val images: List<SpImage>? = null)

@Serializable
data class SpImage(val url: String? = null, val height: Int? = null, val width: Int? = null)

fun List<SpImage>?.bestImageUrl(): String? =
    this?.filter { it.url != null }?.sortedBy { it.width ?: 0 }?.firstOrNull { (it.width ?: 0) >= 300 }?.url
        ?: this?.firstOrNull { it.url != null }?.url

@Serializable data class SpDevicesResponse(val devices: List<SpDevice> = emptyList())
@Serializable data class SpDevice(val id: String, val name: String, @SerialName("is_active") val isActive: Boolean = false, @SerialName("is_restricted") val isRestricted: Boolean = false, val type: String = "", @SerialName("volume_percent") val volumePercent: Int? = null)
@Serializable data class SpTransferRequest(@SerialName("device_ids") val deviceIds: List<String>, val play: Boolean = false)
@Serializable data class SpPlaybackRequest(val uris: List<String>? = null, @SerialName("context_uri") val contextUri: String? = null)
@Serializable data class SpPlaybackState(@SerialName("is_playing") val isPlaying: Boolean = false, @SerialName("progress_ms") val progressMs: Long? = null, val item: SpTrack? = null, val device: SpDevice? = null)
@Serializable data class SpSavedTrack(@SerialName("added_at") val addedAt: String? = null, val track: SpTrack? = null)
@Serializable data class SpSavedTracksResponse(val items: List<SpSavedTrack> = emptyList(), val total: Int = 0, val offset: Int = 0, val limit: Int = 50, val next: String? = null)
@Serializable data class SpSavedAlbum(@SerialName("added_at") val addedAt: String? = null, val album: SpAlbum? = null)
@Serializable data class SpSavedAlbumsResponse(val items: List<SpSavedAlbum> = emptyList(), val total: Int = 0, val offset: Int = 0, val limit: Int = 50, val next: String? = null)
@Serializable data class SpFollowedArtistsResponse(val artists: SpCursorPaginated)
@Serializable data class SpCursorPaginated(val items: List<SpArtist> = emptyList(), val total: Int = 0, val cursors: SpCursors? = null, val next: String? = null)
@Serializable data class SpCursors(val after: String? = null)
@Serializable data class SpPlaylistSimple(val id: String? = null, val name: String? = null, val description: String? = null, val images: List<SpImage>? = null, val owner: SpUser? = null, val collaborative: Boolean = false, @SerialName("snapshot_id") val snapshotId: String? = null, val tracks: SpPlaylistTracksRef? = null)
@Serializable data class SpPlaylistTracksRef(val total: Int = 0)
@Serializable data class SpPaginatedPlaylists(val items: List<SpPlaylistSimple> = emptyList(), val total: Int = 0, val offset: Int = 0, val limit: Int = 50, val next: String? = null)
@Serializable data class SpPlaylistTrackItem(@SerialName("added_at") val addedAt: String? = null, val track: SpTrack? = null)
@Serializable data class SpPlaylistTracksResponse(val items: List<SpPlaylistTrackItem> = emptyList(), val total: Int = 0, val offset: Int = 0, val limit: Int = 100, val next: String? = null)
@Serializable data class SpPlaylistFull(val id: String? = null, val name: String? = null, val description: String? = null, val images: List<SpImage>? = null, val owner: SpUser? = null, @SerialName("snapshot_id") val snapshotId: String? = null, val tracks: SpPlaylistTracksResponse? = null)
@Serializable data class SpUser(val id: String, @SerialName("display_name") val displayName: String? = null, val country: String? = null)
@Serializable data class SpIdsRequest(val ids: List<String>)
@Serializable data class SpUrisRequest(val uris: List<String>)
@Serializable data class SpCreatePlaylistRequest(val name: String, val description: String? = null, val public: Boolean = false)
@Serializable data class SpUpdatePlaylistRequest(val name: String? = null, val description: String? = null)
@Serializable data class SpSnapshotResponse(@SerialName("snapshot_id") val snapshotId: String)
