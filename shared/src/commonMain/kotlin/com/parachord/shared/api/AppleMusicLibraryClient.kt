package com.parachord.shared.api

import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Apple Music Library + Storefront API client. Cross-platform (commonMain).
 *
 * Migrated from app-side Retrofit + AppleMusicAuthInterceptor in Phase 9E.1.7.
 * Auth headers are applied per-request — Bearer dev-token + Music-User-Token —
 * sourced from AuthTokenProvider's BearerWithMUT credential for AuthRealm.AppleMusicLibrary.
 *
 * Behavior contract preserved from the Retrofit version:
 * - Read methods (GET) throw [com.parachord.android.sync.AppleMusicReauthRequiredException]
 *   on 401 (caller imports the Android-side exception class for now; this file uses
 *   a sentinel throw that the consumer maps).
 * - Write methods (POST/PUT/PATCH/DELETE) return [HttpResponse] so the caller can
 *   inspect status (.status.value) without an exception being thrown for 4xx responses.
 *   Apple's documented PATCH/PUT/DELETE-on-/me/library/playlists 401s are NOT
 *   token-related and must NOT be auto-retried by the OAuthRefreshPlugin (this client
 *   is NOT registered with OAuthRefreshPlugin, so 401s pass through unchanged).
 *
 * iOS sync extraction will consume this client directly via shared SyncEngine.
 */
class AppleMusicLibraryClient(
    private val httpClient: HttpClient,
    private val tokens: AuthTokenProvider,
) {
    companion object {
        private const val BASE_URL = "https://api.music.apple.com"
        // Cross-provider ISRC lookup bounds (runs inside syncAll — must not hang).
        private const val ISRC_REQUEST_DELAY_MS = 200L
        private const val MAX_ISRC_BATCHES = 40   // 40 × 300 = 12k songs
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }

    /** Apply Bearer dev-token + Music-User-Token to a request. Throws if MUT is missing. */
    private suspend fun applyAuth(builder: io.ktor.client.request.HttpRequestBuilder) {
        val cred = tokens.tokenFor(AuthRealm.AppleMusicLibrary) as? AuthCredential.BearerWithMUT
            ?: throw com.parachord.shared.sync.AppleMusicReauthRequiredException()
        builder.header("Authorization", "Bearer ${cred.devToken}")
        builder.header("Music-User-Token", cred.mut)
    }

    /** Sentinel class — wraps to AppleMusicReauthRequiredException at the consumer boundary. */
    class AmReauthRequired : Exception("Apple Music re-authentication required")

    /**
     * Thrown by read methods on non-2xx, non-401 responses. Carries the HTTP status code
     * so consumers can switch on common cases (e.g. 404 → break-out-of-pagination) without
     * needing a Retrofit HttpException dependency. Replaces the prior `HttpException(resp)`
     * pattern from the Retrofit-based client.
     */
    class AmHttpException(val status: Int, message: String? = null) :
        Exception(message ?: "Apple Music API returned HTTP $status")

    private suspend fun ensureSuccessOrThrowTyped(response: HttpResponse) {
        if (response.status == HttpStatusCode.Unauthorized) {
            throw com.parachord.shared.sync.AppleMusicReauthRequiredException()
        }
        if (!response.status.isSuccess()) {
            throw AmHttpException(response.status.value)
        }
    }

    private fun ensureSuccessOrThrow401(response: HttpResponse) {
        if (response.status == HttpStatusCode.Unauthorized) throw com.parachord.shared.sync.AppleMusicReauthRequiredException()
    }

    // ── Library playlists ────────────────────────────────────────────

    suspend fun listPlaylists(limit: Int = 100, offset: Int = 0): AmPlaylistListResponse {
        val response = httpClient.get("$BASE_URL/v1/me/library/playlists") {
            applyAuth(this)
            parameter("limit", limit)
            parameter("offset", offset)
        }
        ensureSuccessOrThrowTyped(response)
        return json.decodeFromString(response.bodyAsText())
    }

    suspend fun listPlaylistTracks(
        playlistId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): AmPlaylistTrackListResponse {
        val response = httpClient.get("$BASE_URL/v1/me/library/playlists/$playlistId/tracks") {
            applyAuth(this)
            parameter("limit", limit)
            parameter("offset", offset)
        }
        ensureSuccessOrThrowTyped(response)
        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * Workaround for Apple's `/me/library/playlists/{id}/tracks` returning empty
     * for many library playlists. The `?include=tracks` form returns the actual
     * tracks via `relationships.tracks.data`.
     */
    suspend fun getLibraryPlaylistWithTracks(
        playlistId: String,
        include: String = "tracks",
    ): AmPlaylistDetailResponse {
        val response = httpClient.get("$BASE_URL/v1/me/library/playlists/$playlistId") {
            applyAuth(this)
            parameter("include", include)
        }
        ensureSuccessOrThrowTyped(response)
        return json.decodeFromString(response.bodyAsText())
    }

    suspend fun createPlaylist(body: AmCreatePlaylistRequest): AmPlaylistListResponse {
        val response = httpClient.post("$BASE_URL/v1/me/library/playlists") {
            applyAuth(this)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AmCreatePlaylistRequest.serializer(), body))
        }
        ensureSuccessOrThrowTyped(response)
        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * Full-replace via PUT. Returns HttpResponse so caller can inspect status.
     * Apple often returns 401 / 403 / 405 here on most user tokens; the caller
     * flips a session kill-switch and degrades to [appendPlaylistTracks].
     * Does NOT throw on 4xx — caller decides.
     */
    suspend fun replacePlaylistTracks(
        playlistId: String,
        body: AmTracksRequest,
    ): HttpResponse =
        httpClient.put("$BASE_URL/v1/me/library/playlists/$playlistId/tracks") {
            applyAuth(this)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AmTracksRequest.serializer(), body))
        }

    /** Append tracks. Returns HttpResponse so caller can inspect status. */
    suspend fun appendPlaylistTracks(
        playlistId: String,
        body: AmTracksRequest,
    ): HttpResponse =
        httpClient.post("$BASE_URL/v1/me/library/playlists/$playlistId/tracks") {
            applyAuth(this)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AmTracksRequest.serializer(), body))
        }

    /**
     * Rename via PATCH. Returns HttpResponse so caller can inspect status without
     * throwing (PATCH 401s are not token-related and must not throw).
     */
    suspend fun updatePlaylistDetails(
        playlistId: String,
        body: AmUpdatePlaylistRequest,
    ): HttpResponse =
        httpClient.patch("$BASE_URL/v1/me/library/playlists/$playlistId") {
            applyAuth(this)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AmUpdatePlaylistRequest.serializer(), body))
        }

    suspend fun deletePlaylist(playlistId: String): HttpResponse =
        httpClient.delete("$BASE_URL/v1/me/library/playlists/$playlistId") {
            applyAuth(this)
        }

    // ── Library tracks (collection sync) ─────────────────────────────

    suspend fun listLibrarySongs(limit: Int = 100, offset: Int = 0): AmLibrarySongListResponse {
        val response = httpClient.get("$BASE_URL/v1/me/library/songs") {
            applyAuth(this)
            parameter("limit", limit)
            parameter("offset", offset)
        }
        ensureSuccessOrThrowTyped(response)
        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * Apple's documented add-to-library endpoint takes `ids[songs]` / `ids[albums]`
     * as query string params (NOT a JSON body), comma-separated. Returns HttpResponse.
     */
    suspend fun addToLibrary(
        songIds: String? = null,
        albumIds: String? = null,
    ): HttpResponse =
        httpClient.post("$BASE_URL/v1/me/library") {
            applyAuth(this)
            songIds?.let { parameter("ids[songs]", it) }
            albumIds?.let { parameter("ids[albums]", it) }
        }

    suspend fun deleteLibrarySong(songId: String): HttpResponse =
        httpClient.delete("$BASE_URL/v1/me/library/songs/$songId") {
            applyAuth(this)
        }

    // ── Library albums (collection sync) ─────────────────────────────

    suspend fun listLibraryAlbums(limit: Int = 100, offset: Int = 0): AmLibraryAlbumListResponse {
        val response = httpClient.get("$BASE_URL/v1/me/library/albums") {
            applyAuth(this)
            parameter("limit", limit)
            parameter("offset", offset)
        }
        ensureSuccessOrThrowTyped(response)
        return json.decodeFromString(response.bodyAsText())
    }

    suspend fun deleteLibraryAlbum(albumId: String): HttpResponse =
        httpClient.delete("$BASE_URL/v1/me/library/albums/$albumId") {
            applyAuth(this)
        }

    // ── Library artists ──────────────────────────────────────────────

    suspend fun listLibraryArtists(
        limit: Int = 100,
        offset: Int = 0,
    ): AmLibraryArtistListResponse {
        val response = httpClient.get("$BASE_URL/v1/me/library/artists") {
            applyAuth(this)
            parameter("limit", limit)
            parameter("offset", offset)
        }
        ensureSuccessOrThrowTyped(response)
        return json.decodeFromString(response.bodyAsText())
    }

    // ── Storefront detection ─────────────────────────────────────────

    suspend fun getStorefront(): AmStorefrontResponse {
        val response = httpClient.get("$BASE_URL/v1/me/storefront") {
            applyAuth(this)
        }
        ensureSuccessOrThrowTyped(response)
        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * Batch-fetch ISRCs for catalog song ids via the CATALOG API. The library
     * API (`/me/library/songs`) doesn't return ISRC, so cross-provider dedup
     * needs this extra lookup (#cross-provider-track-dedup). Returns
     * `catalogId -> ISRC` only for ids that have one. Chunked at 300 (Apple's
     * `ids=` cap). Best-effort: a failed batch just yields no ISRCs for it, so
     * those tracks fall back to their `applemusic-…` id (no merge) rather than
     * failing the sync.
     */
    suspend fun getCatalogSongIsrcs(storefront: String, catalogIds: List<String>): Map<String, String> {
        if (storefront.isBlank() || catalogIds.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, String>()
        // BOUNDED + STOP-ON-FAILURE: this runs inside syncAll holding the sync
        // mutex, so it must never hang or hammer Apple. A 200ms inter-request gap
        // respects the catalog rate limit; we stop on the FIRST failure (rate
        // limit / error / timeout) instead of grinding every batch; and a hard
        // cap bounds huge libraries. Songs past the cap (or after a stop) keep
        // their `applemusic-` id and just don't cross-provider-merge.
        val batches = catalogIds.distinct().chunked(300)
        for ((i, batch) in batches.withIndex()) {
            if (i >= MAX_ISRC_BATCHES) break
            if (i > 0) delay(ISRC_REQUEST_DELAY_MS)
            try {
                val response = httpClient.get("$BASE_URL/v1/catalog/$storefront/songs") {
                    applyAuth(this)
                    parameter("ids", batch.joinToString(","))
                }
                if (!response.status.isSuccess()) break
                val parsed = json.decodeFromString<AmCatalogSongsResponse>(response.bodyAsText())
                parsed.data.forEach { song -> song.attributes?.isrc?.let { out[song.id] = it } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                break
            }
        }
        return out
    }

    /**
     * Resolve a catalog song id from an ISRC via the CATALOG API
     * (`GET /v1/catalog/{storefront}/songs?filter[isrc]=…`). EXACT — an ISRC
     * identifies the recording — so no fuzzy text-search wrong-variant risk
     * (unlike the iTunes Search fallback). The returned catalog id is what the
     * library add-to-playlist endpoint accepts. Returns null on miss / no
     * storefront / error (caller falls back to iTunes search).
     */
    suspend fun getCatalogSongIdByIsrc(storefront: String, isrc: String): String? {
        if (storefront.isBlank() || isrc.isBlank()) return null
        return try {
            val response = httpClient.get("$BASE_URL/v1/catalog/$storefront/songs") {
                applyAuth(this)
                parameter("filter[isrc]", isrc)
                parameter("limit", 1)
            }
            if (!response.status.isSuccess()) return null
            val parsed = json.decodeFromString<AmCatalogSongsResponse>(response.bodyAsText())
            parsed.data.firstOrNull()?.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

@Serializable
data class AmCatalogSongsResponse(val data: List<AmCatalogSong> = emptyList())

@Serializable
data class AmCatalogSong(val id: String, val attributes: AmCatalogSongAttributes? = null)

@Serializable
data class AmCatalogSongAttributes(val isrc: String? = null)

// ── Library JSON models ──────────────────────────────────────────────

@Serializable
data class AmLibrarySongListResponse(
    val data: List<AmLibrarySong> = emptyList(),
    val next: String? = null,
    val meta: AmListMeta? = null,
)

/** Apple Music wraps total counts in a `meta` envelope on paginated list endpoints. */
@Serializable
data class AmListMeta(
    val total: Int? = null,
)

@Serializable
data class AmLibrarySong(
    val id: String,
    val type: String? = null,
    val attributes: AmLibrarySongAttributes? = null,
)

@Serializable
data class AmLibrarySongAttributes(
    // Nullable + defaulted so ONE library song with incomplete metadata (no
    // name/artist — e.g. a cloud-uploaded or oddly-tagged track) can't throw
    // during decode and take the whole songs page down with it. The mapper skips
    // songs missing essentials instead.
    val name: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val durationInMillis: Long? = null,
    val artwork: AmArtwork? = null,
    val playParams: AmPlayParams? = null,
    val dateAdded: String? = null,
)

@Serializable
data class AmLibraryAlbumListResponse(
    val data: List<AmLibraryAlbum> = emptyList(),
    val next: String? = null,
    val meta: AmListMeta? = null,
)

@Serializable
data class AmLibraryAlbum(
    val id: String,
    val type: String,
    val attributes: AmLibraryAlbumAttributes,
)

@Serializable
data class AmLibraryAlbumAttributes(
    val name: String,
    val artistName: String,
    val artwork: AmArtwork? = null,
    val playParams: AmPlayParams? = null,
    val dateAdded: String? = null,
    val trackCount: Int = 0,
)

@Serializable
data class AmLibraryArtistListResponse(
    val data: List<AmLibraryArtist> = emptyList(),
    val next: String? = null,
    val meta: AmListMeta? = null,
)

@Serializable
data class AmLibraryArtist(
    val id: String,
    val type: String,
    val attributes: AmLibraryArtistAttributes,
)

@Serializable
data class AmLibraryArtistAttributes(
    val name: String,
)

// ── Playlist JSON models ─────────────────────────────────────────────

@Serializable
data class AmPlaylistListResponse(
    val data: List<AmPlaylist> = emptyList(),
    val next: String? = null,
)

@Serializable
data class AmPlaylist(
    val id: String,
    val type: String,
    val attributes: AmPlaylistAttributes,
)

@Serializable
data class AmPlaylistAttributes(
    val name: String,
    val description: AmDescription? = null,
    val canEdit: Boolean = false,
    val dateAdded: String? = null,
    val lastModifiedDate: String? = null,
    val playParams: AmPlayParams? = null,
    val artwork: AmArtwork? = null,
)

@Serializable
data class AmDescription(
    val standard: String? = null,
    val short: String? = null,
)

@Serializable
data class AmPlayParams(
    val id: String,
    val kind: String,
)

@Serializable
data class AmArtwork(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class AmPlaylistTrackListResponse(
    val data: List<AmTrack> = emptyList(),
    val next: String? = null,
)

@Serializable
data class AmPlaylistDetailResponse(
    val data: List<AmPlaylistDetail> = emptyList(),
)

@Serializable
data class AmPlaylistDetail(
    val id: String,
    val type: String,
    val attributes: AmPlaylistAttributes,
    val relationships: AmPlaylistRelationships? = null,
)

@Serializable
data class AmPlaylistRelationships(
    val tracks: AmPlaylistTracksRelationship? = null,
)

@Serializable
data class AmPlaylistTracksRelationship(
    val data: List<AmTrack> = emptyList(),
    val next: String? = null,
)

@Serializable
data class AmTrack(
    val id: String,
    val type: String,
    val attributes: AmTrackAttributes,
)

@Serializable
data class AmTrackAttributes(
    val name: String,
    val artistName: String,
    val albumName: String? = null,
    val durationInMillis: Long? = null,
    val artwork: AmArtwork? = null,
    val playParams: AmPlayParams? = null,
)

@Serializable
data class AmCreatePlaylistRequest(
    val attributes: AmCreatePlaylistAttributes,
    val relationships: AmCreatePlaylistRelationships? = null,
)

@Serializable
data class AmCreatePlaylistAttributes(
    val name: String,
    val description: String? = null,
)

@Serializable
data class AmCreatePlaylistRelationships(
    val tracks: AmTracksRelationship,
)

@Serializable
data class AmTracksRelationship(
    val data: List<AmTrackReference>,
)

@Serializable
data class AmTrackReference(
    val id: String,
    val type: String = "songs",
)

@Serializable
data class AmTracksRequest(
    val data: List<AmTrackReference>,
)

@Serializable
data class AmUpdatePlaylistRequest(
    val attributes: AmUpdatePlaylistAttributes,
)

@Serializable
data class AmUpdatePlaylistAttributes(
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class AmStorefrontResponse(
    val data: List<AmStorefront> = emptyList(),
)

@Serializable
data class AmStorefront(
    val id: String,
)
