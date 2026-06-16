package com.parachord.shared.api

import com.parachord.shared.platform.Log
import com.parachord.shared.sync.ListenBrainzUnauthorizedException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * ListenBrainz API client. Cross-platform (commonMain).
 *
 * Migrated from app-side OkHttp + manual JSONObject parsing in Phase 9E.1.4.
 * Auth uses "Authorization: Token <token>" header (NOT Bearer); validate-token,
 * follow/unfollow, and authenticated listens require it.
 *
 * API docs: https://listenbrainz.readthedocs.io/en/latest/users/api/core.html
 *
 * Public model classes (LbListen, LbArtistStat, ...) live in ListenBrainzModels.kt
 * to preserve the existing consumer-facing API surface; this client file owns
 * the wire-format @Serializable types as private data classes that map to those.
 */
class ListenBrainzClient(private val httpClient: HttpClient) {

    companion object {
        private const val TAG = "ListenBrainzClient"
        private const val BASE_URL = "https://api.listenbrainz.org"
        private const val MAPPER_URL = "https://mapper.listenbrainz.org"
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        /** Canonical ISRC shape (after uppercasing): 2-char country + 3-char registrant + 7 digits. */
        private val ISRC_REGEX = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")
    }

    /** Validate that a ListenBrainz user exists (no auth required). */
    suspend fun validateUser(username: String): Boolean {
        return try {
            val response = httpClient.get("$BASE_URL/1/user/$username/listen-count")
            response.status.isSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to validate user $username", e)
            false
        }
    }

    /** Validate a ListenBrainz user token; returns username if valid, null otherwise. */
    suspend fun validateToken(token: String): String? {
        return try {
            val response = httpClient.get("$BASE_URL/1/validate-token") {
                header("Authorization", "Token $token")
            }
            if (!response.status.isSuccess()) {
                Log.w(TAG, "Token validation failed: ${response.status.value}")
                return null
            }
            val body = response.bodyAsText()
            val parsed = json.decodeFromString<ValidateTokenWire>(body)
            if (parsed.valid == true) parsed.userName?.ifBlank { null } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to validate token", e)
            null
        }
    }

    /** Fetch the list of users this user follows (no auth required). */
    suspend fun getUserFollowing(username: String): List<String> {
        return try {
            val response = httpClient.get("$BASE_URL/1/user/$username/following")
            if (!response.status.isSuccess()) return emptyList()
            json.decodeFromString<FollowingWire>(response.bodyAsText()).following
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to fetch following for $username", e)
            emptyList()
        }
    }

    /** Follow a user (requires auth). */
    suspend fun followUser(username: String, token: String): Boolean {
        return try {
            val response = httpClient.post("$BASE_URL/1/user/$username/follow") {
                header("Authorization", "Token $token")
            }
            response.status.isSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to follow $username", e)
            false
        }
    }

    /** Unfollow a user (requires auth). */
    suspend fun unfollowUser(username: String, token: String): Boolean {
        return try {
            val response = httpClient.post("$BASE_URL/1/user/$username/unfollow") {
                header("Authorization", "Token $token")
            }
            response.status.isSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unfollow $username", e)
            false
        }
    }

    /**
     * Submit a recording-level feedback ("love" / "hate" / "neutral") to LB.
     *
     * Used by the loved-tracks push (issue #125): when the user adds a track
     * to their Parachord collection AND the LB push toggle is on, we POST
     * `{ recording_mbid, score: 1 }` to set the love. `score` per LB API:
     *   1 = love, -1 = hate, 0 = clear/neutral.
     *
     * Caller is responsible for ensuring `recordingMbid` is a valid 36-char
     * UUID (the desktop's `loveTrack` validates `track.mbid` against
     * `^[a-f0-9-]{36}$/i` before calling this — mirror that check here in
     * the calling scrobbler since LB returns a 400 for malformed UUIDs and
     * we don't want to retry those).
     *
     * Throws on hard errors (auth invalid, rate-limited 5xx). Callers in
     * the love-push path catch and skip the per-track push, leaving the
     * idempotency cache untouched so the next sync gets another shot.
     */
    /**
     * POST /1/submit-listens — scrobble a play to ListenBrainz (shared so iOS gets
     * scrobbling too; #193). `listenedAt == null` ⇒ a `playing_now` now-playing
     * update (no timestamp); otherwise a `single` listen. MBIDs (recording / artist /
     * release) are included per the LB spec (top-level + additional_info), AND the
     * streaming-source link/service (`origin_url`, `music_service`,
     * `music_service_name`, `spotify_id`) so the listen carries where it played from
     * — matching the Android ListenBrainzScrobbler payload.
     */
    suspend fun submitListens(
        token: String,
        artist: String,
        title: String,
        release: String? = null,
        recordingMbid: String? = null,
        artistMbids: List<String> = emptyList(),
        releaseMbid: String? = null,
        durationMs: Long? = null,
        listenedAt: Long? = null,
        originUrl: String? = null,
        musicService: String? = null,
        musicServiceName: String? = null,
        spotifyId: String? = null,
        isrc: String? = null,
    ): Boolean {
        // Normalize up-front: uppercase, keep ONLY a canonical ISRC. A bad
        // value is omitted entirely (never null/""/malformed) so Achordion can
        // key a mapper-less listen on the exact recording. (#216 / achordion#77)
        val normalizedIsrc = isrc?.trim()?.uppercase()?.takeIf { ISRC_REGEX.matches(it) }
        return try {
            val payload = buildJsonObject {
                put("listen_type", if (listenedAt == null) "playing_now" else "single")
                putJsonArray("payload") {
                    addJsonObject {
                        if (listenedAt != null) put("listened_at", listenedAt)
                        putJsonObject("track_metadata") {
                            put("artist_name", artist)
                            put("track_name", title)
                            if (!release.isNullOrBlank()) put("release_name", release)
                            if (!recordingMbid.isNullOrBlank()) put("recording_mbid", recordingMbid)
                            putJsonObject("additional_info") {
                                put("media_player", "Parachord")
                                put("submission_client", "Parachord")
                                put("submission_client_version", "1.1.0")
                                if (durationMs != null) put("duration_ms", durationMs)
                                if (!recordingMbid.isNullOrBlank()) put("recording_mbid", recordingMbid)
                                if (!releaseMbid.isNullOrBlank()) put("release_mbid", releaseMbid)
                                if (artistMbids.isNotEmpty()) {
                                    putJsonArray("artist_mbids") { artistMbids.forEach { add(it) } }
                                }
                                // Streaming source link/service (Android parity).
                                if (!originUrl.isNullOrBlank()) put("origin_url", originUrl)
                                if (!musicService.isNullOrBlank()) put("music_service", musicService)
                                if (!musicServiceName.isNullOrBlank()) put("music_service_name", musicServiceName)
                                if (!spotifyId.isNullOrBlank()) put("spotify_id", spotifyId)
                                // Exact recording key for Achordion when the mapper has no MBID.
                                if (normalizedIsrc != null) put("isrc", normalizedIsrc)
                            }
                        }
                    }
                }
            }
            val response = httpClient.post("$BASE_URL/1/submit-listens") {
                header("Authorization", "Token $token")
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(payload)
            }
            if (!response.status.isSuccess()) {
                Log.w(TAG, "submitListens($artist - $title) → HTTP ${response.status.value}")
            }
            response.status.isSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "submitListens failed: ${e.message}")
            false
        }
    }

    suspend fun submitRecordingFeedback(
        recordingMbid: String,
        score: Int,
        token: String,
    ): Boolean {
        return try {
            val response = httpClient.post("$BASE_URL/1/feedback/recording-feedback") {
                header("Authorization", "Token $token")
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(
                    kotlinx.serialization.json.buildJsonObject {
                        put("recording_mbid", recordingMbid)
                        put("score", score)
                    },
                )
            }
            if (!response.status.isSuccess()) {
                Log.w(TAG, "submitRecordingFeedback($recordingMbid, score=$score) → HTTP ${response.status.value}")
            }
            response.status.isSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "submitRecordingFeedback failed for $recordingMbid: ${e.message}")
            throw e
        }
    }

    /**
     * POST /1/playlist/create — create a new JSPF playlist owned by the
     * token's user. Returns the assigned playlist MBID.
     *
     * Body shape per LB JSPF playlist spec
     * (https://listenbrainz.readthedocs.io/en/latest/users/api/playlist.html):
     *
     *   {"playlist": {
     *      "title": "...",
     *      "annotation": "...?",
     *      "extension": {
     *        "https://musicbrainz.org/doc/jspf#playlist": { "public": true }
     *      }
     *   }}
     *
     * Response: `{"playlist_mbid": "<uuid>", "status": "ok"}`.
     *
     * Wrapped in [executeWithRetry] for 429/503 backoff, mirroring
     * [submitRecordingFeedback]. Throws [ListenBrainzUnauthorizedException]
     * on HTTP 401 so [ListenBrainzSyncProvider] can trip its session-scoped
     * auth-failed kill-switch (matches AM reauth contract).
     */
    suspend fun createPlaylist(
        name: String,
        description: String? = null,
        isPublic: Boolean = true,
        token: String,
    ): String {
        val body = buildJsonObject {
            put(
                "playlist",
                buildJsonObject {
                    put("title", name)
                    if (description != null) put("annotation", description)
                    put(
                        "extension",
                        buildJsonObject {
                            put(
                                "https://musicbrainz.org/doc/jspf#playlist",
                                buildJsonObject { put("public", isPublic) },
                            )
                        },
                    )
                },
            )
        }
        val response = executeWithRetry {
            httpClient.post("$BASE_URL/1/playlist/create") {
                header("Authorization", "Token $token")
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(body)
            }
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw ListenBrainzUnauthorizedException(
                "ListenBrainz returned 401 on createPlaylist — token rejected",
            )
        }
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText().take(200)
            throw Exception("createPlaylist failed: HTTP ${response.status.value} $text")
        }
        val parsed = json.decodeFromString<CreatePlaylistWire>(response.bodyAsText())
        return parsed.playlistMbid
            ?: throw Exception("createPlaylist response missing playlist_mbid")
    }

    /**
     * POST /1/playlist/edit/{playlistMbid} — rename + description.
     *
     * Best-effort. LB rejects edits to non-owned playlists with 403/404
     * (caller should treat those as no-op rather than crashing the sync
     * cycle). Body shape is the same as [createPlaylist] but only includes
     * fields that are non-null — LB ignores absent fields and leaves them
     * unchanged.
     *
     * Throws [ListenBrainzUnauthorizedException] on HTTP 401.
     */
    suspend fun editPlaylist(
        playlistMbid: String,
        name: String?,
        description: String?,
        token: String,
    ) {
        val body = buildJsonObject {
            put(
                "playlist",
                buildJsonObject {
                    if (name != null) put("title", name)
                    if (description != null) put("annotation", description)
                },
            )
        }
        val response = executeWithRetry {
            httpClient.post("$BASE_URL/1/playlist/edit/$playlistMbid") {
                header("Authorization", "Token $token")
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(body)
            }
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw ListenBrainzUnauthorizedException(
                "ListenBrainz returned 401 on editPlaylist — token rejected",
            )
        }
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText().take(200)
            throw Exception("editPlaylist($playlistMbid) failed: HTTP ${response.status.value} $text")
        }
    }

    /**
     * POST /1/playlist/{playlistMbid}/delete — soft-deletes from the
     * user's profile. LB's REST surface uses POST for delete, not the
     * HTTP DELETE verb. No body required.
     *
     * Throws [ListenBrainzUnauthorizedException] on HTTP 401.
     */
    suspend fun deletePlaylist(playlistMbid: String, token: String) {
        val response = executeWithRetry {
            httpClient.post("$BASE_URL/1/playlist/$playlistMbid/delete") {
                header("Authorization", "Token $token")
            }
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw ListenBrainzUnauthorizedException(
                "ListenBrainz returned 401 on deletePlaylist — token rejected",
            )
        }
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText().take(200)
            throw Exception("deletePlaylist($playlistMbid) failed: HTTP ${response.status.value} $text")
        }
    }

    /**
     * POST /1/playlist/{playlistMbid}/item/add — append recording MBIDs.
     *
     * Per LB JSPF spec, recordings are identified by full MusicBrainz URI
     * (`https://musicbrainz.org/recording/<mbid>`). Body shape:
     *
     *   {"playlist": {"track": [{"identifier": "https://..."}, ...]}}
     *
     * No-op (no HTTP request) when [recordingMbids] is empty.
     *
     * Wrapped in [executeWithRetry]. Throws [ListenBrainzUnauthorizedException]
     * on HTTP 401.
     */
    suspend fun addPlaylistItems(
        playlistMbid: String,
        recordingMbids: List<String>,
        token: String,
    ) {
        if (recordingMbids.isEmpty()) return
        val body = buildJsonObject {
            put(
                "playlist",
                buildJsonObject {
                    put(
                        "track",
                        buildJsonArray {
                            for (mbid in recordingMbids) {
                                add(
                                    buildJsonObject {
                                        put("identifier", "https://musicbrainz.org/recording/$mbid")
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
        val response = executeWithRetry {
            httpClient.post("$BASE_URL/1/playlist/$playlistMbid/item/add") {
                header("Authorization", "Token $token")
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(body)
            }
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw ListenBrainzUnauthorizedException(
                "ListenBrainz returned 401 on addPlaylistItems — token rejected",
            )
        }
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText().take(200)
            throw Exception("addPlaylistItems($playlistMbid) failed: HTTP ${response.status.value} $text")
        }
    }

    /**
     * POST /1/playlist/{playlistMbid}/item/delete — remove items by position range.
     *
     * `index` is the 0-based starting position; `count` is the number of items
     * to remove. To full-replace, call this with (0, currentTrackCount) then
     * [addPlaylistItems] with the new list.
     *
     * No-op (no HTTP request) when [count] is `<= 0`.
     *
     * Wrapped in [executeWithRetry]. Throws [ListenBrainzUnauthorizedException]
     * on HTTP 401.
     */
    suspend fun deletePlaylistItems(
        playlistMbid: String,
        index: Int,
        count: Int,
        token: String,
    ) {
        if (count <= 0) return
        val body = buildJsonObject {
            put("index", index)
            put("count", count)
        }
        val response = executeWithRetry {
            httpClient.post("$BASE_URL/1/playlist/$playlistMbid/item/delete") {
                header("Authorization", "Token $token")
                contentType(io.ktor.http.ContentType.Application.Json)
                setBody(body)
            }
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw ListenBrainzUnauthorizedException(
                "ListenBrainz returned 401 on deletePlaylistItems — token rejected",
            )
        }
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText().take(200)
            throw Exception("deletePlaylistItems($playlistMbid) failed: HTTP ${response.status.value} $text")
        }
    }

    /**
     * GET /1/playlist/{playlistMbid} — extract only the `last_modified_at`
     * timestamp from the JSPF extension.
     *
     * Used by `ListenBrainzSyncProvider.getPlaylistSnapshotId` to detect
     * remote changes since the local snapshot. Returns `null` when:
     *  - The playlist returns 404 (deleted / never existed)
     *  - The `last_modified_at` field is absent
     *
     * Reads `playlist.extension["https://musicbrainz.org/doc/jspf#playlist"]
     * .last_modified_at` (JSPF extension structure LB uses).
     *
     * No auth required — public LB playlists are publicly readable, and the
     * caller may be checking other users' shared playlists. Non-2xx errors
     * other than 404 propagate as [Exception].
     */
    suspend fun getPlaylistLastModified(playlistMbid: String): String? {
        val response = httpClient.get("$BASE_URL/1/playlist/$playlistMbid")
        if (response.status == HttpStatusCode.NotFound) return null
        if (!response.status.isSuccess()) {
            val text = response.bodyAsText().take(200)
            throw Exception("getPlaylistLastModified($playlistMbid) failed: HTTP ${response.status.value} $text")
        }
        return try {
            val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val playlist = root["playlist"]?.jsonObject ?: return null
            val ext = playlist["extension"]?.jsonObject ?: return null
            val jspf = ext["https://musicbrainz.org/doc/jspf#playlist"]?.jsonObject ?: return null
            jspf["last_modified_at"]?.jsonPrimitive?.content?.ifBlank { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse last_modified_at for $playlistMbid", e)
            null
        }
    }

    /**
     * GET /1/user/{userName}/playlists — owned playlists, not the system
     * `createdfor` playlists already exposed via [getCreatedForPlaylists].
     *
     * Returns the user's playlists as a list of [LbPlaylist] entries. Each
     * entry carries playlist MBID (extracted from the `identifier` URL's
     * trailing segment) + title + annotation (description) +
     * last_modified_at (from the JSPF extension).
     *
     * Unauth — public playlists are world-readable. The `Token` header is
     * NOT sent. 404 → empty list (treat as "user has no playlists or does
     * not exist"); other non-2xx → throws [Exception]. Parse failures
     * propagate as well — the caller ([ListenBrainzSyncProvider.fetchPlaylists])
     * needs to distinguish "user has no playlists" (empty list) from "LB is
     * broken right now" (exception) so a malformed response doesn't wipe the
     * local mirror.
     *
     * Wrapped in [executeWithRetry] for 429/503 backoff, matching the other
     * read paths in this client.
     */
    /**
     * Fetch ALL of a user's own playlists, paginating through every page.
     *
     * CRITICAL — two invariants, both load-bearing for the sync dedup:
     *
     * 1. **Complete.** The endpoint defaults to `count=25`; without paging, the
     *    sync engine's three-layer dedup only sees the 25 newest playlists and
     *    recreates everything else every cycle — the runaway bug that produced
     *    ~6,400 duplicate public playlists on a real account.
     *
     * 2. **All-or-nothing.** If the full list can't be retrieved (HTTP error,
     *    429, malformed body, a 404 mid-walk, or an empty page before reaching
     *    `playlist_count`) this THROWS rather than returning a partial list. Per
     *    the LB sync interop contract (CLAUDE.md "ListenBrainz Playlist Sync —
     *    Interop Contract", rule 4): a failed/partial fetch is NOT a
     *    confirmed-empty result. Returning a truncated list would make the dedup
     *    treat the un-fetched playlists as deleted-remotely and RECREATE them.
     *    Throwing aborts the sync cycle for this provider, preserving the
     *    local→MBID mappings; it retries next cycle.
     *
     * A 404 on the FIRST page (offset 0) is the one legitimate empty result:
     * the user has no playlists (or the user doesn't exist). Returns empty.
     */
    suspend fun getUserOwnedPlaylists(userName: String): List<LbPlaylist> {
        val pageSize = 100 // LB's max per request
        val all = mutableListOf<LbPlaylist>()
        var offset = 0
        var expectedTotal = -1 // captured from the first successful page
        // Hard cap on iterations as a runaway guard (1000 pages = 100k playlists).
        repeat(1000) {
            val response = executeWithRetry {
                httpClient.get("$BASE_URL/1/user/$userName/playlists") {
                    parameter("count", pageSize)
                    parameter("offset", offset)
                }
            }
            if (response.status == HttpStatusCode.NotFound) {
                // First-page 404 = confirmed "no playlists". A mid-walk 404 is
                // anomalous and must NOT be read as "the rest were deleted".
                if (offset == 0) return emptyList()
                throw Exception("getUserOwnedPlaylists($userName): 404 mid-pagination at offset $offset (incomplete fetch)")
            }
            if (!response.status.isSuccess()) {
                val text = response.bodyAsText().take(200)
                throw Exception("getUserOwnedPlaylists($userName) failed: HTTP ${response.status.value} $text")
            }
            val parsed = json.decodeFromString<CreatedForListWire>(response.bodyAsText())
            if (expectedTotal < 0) expectedTotal = parsed.playlistCount
            all.addAll(
                parsed.playlists.mapNotNull { wrapper ->
                    val pl = wrapper.playlist ?: return@mapNotNull null
                    val mbid = pl.identifier?.substringAfterLast("/")?.ifBlank { null }
                        ?: return@mapNotNull null
                    LbPlaylist(
                        mbid = mbid,
                        title = pl.title.orEmpty(),
                        annotation = pl.annotation.orEmpty(),
                        lastModifiedAt = pl.lastModifiedAt?.ifBlank { null },
                    )
                },
            )
            offset += parsed.playlists.size
            // Covered the full reported total (raw offset, not parsed count — a
            // playlist that fails MBID parsing still advances the walk). Also
            // covers the empty-library case (total 0 ⇒ 0 >= 0 on the first page).
            if (offset >= expectedTotal) return all
            // Empty page BEFORE reaching the total ⇒ LB truncated / transient.
            // Abort rather than return a partial list (see invariant 2).
            if (parsed.playlists.isEmpty()) {
                throw Exception("getUserOwnedPlaylists($userName): empty page at offset $offset before reaching playlist_count=$expectedTotal (incomplete fetch)")
            }
        }
        // Hit the 1000-page guard without covering the total — incomplete.
        throw Exception("getUserOwnedPlaylists($userName): exceeded pagination cap before reaching playlist_count=$expectedTotal")
    }

    /** Fetch recent listens for a user. */
    suspend fun getRecentListens(
        username: String,
        token: String? = null,
        count: Int = 50,
    ): List<LbListen> {
        return try {
            val response = httpClient.get("$BASE_URL/1/user/$username/listens") {
                parameter("count", count)
                token?.let { header("Authorization", "Token $it") }
            }
            if (!response.status.isSuccess()) {
                Log.e(TAG, "API error ${response.status.value}: ${response.bodyAsText().take(200)}")
                return emptyList()
            }
            val parsed = json.decodeFromString<ListensWire>(response.bodyAsText())
            parsed.payload?.listens.orEmpty().mapNotNull { listen ->
                val md = listen.trackMetadata ?: return@mapNotNull null
                LbListen(
                    artistName = md.artistName.orEmpty(),
                    trackName = md.trackName.orEmpty(),
                    releaseName = md.releaseName?.ifBlank { null },
                    listenedAt = listen.listenedAt ?: 0L,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to fetch listens for $username", e)
            emptyList()
        }
    }

    /**
     * Fetch a user's currently-playing track (if any).
     *
     * Endpoint: `GET /1/user/{name}/playing-now`. The response shape is
     * identical to `/listens` but capped to at most one entry — reuse
     * [ListensWire] and pluck `listens.firstOrNull()`. Returns `null`
     * when the user isn't currently scrobbling, when track metadata is
     * blank, or on any HTTP error.
     *
     * Auth required as of mid-2026; the [ListenBrainzAuthPlugin] (Phase
     * 3 Task 2) auto-attaches the configured token, so this method
     * doesn't need a token parameter. If no token is configured the
     * request will 401 and we return null.
     *
     * Used by the `parachord://listen-along` transient-friend fallback
     * when the target user isn't in the local friends list.
     */
    suspend fun getPlayingNow(username: String): LbListen? {
        return try {
            val response = httpClient.get("$BASE_URL/1/user/$username/playing-now")
            if (!response.status.isSuccess()) {
                Log.w(TAG, "playing-now returned ${response.status.value} for $username")
                return null
            }
            val parsed = json.decodeFromString<ListensWire>(response.bodyAsText())
            val listen = parsed.payload?.listens?.firstOrNull() ?: return null
            val md = listen.trackMetadata ?: return null
            val artist = md.artistName.orEmpty()
            val title = md.trackName.orEmpty()
            if (artist.isBlank() || title.isBlank()) return null
            LbListen(
                artistName = artist,
                trackName = title,
                releaseName = md.releaseName?.ifBlank { null },
                listenedAt = listen.listenedAt ?: 0L,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "playing-now failed for $username", e)
            null
        }
    }

    /** Fetch user's top artists. range: week, month, quarter, half_yearly, year, all_time. */
    suspend fun getUserTopArtists(
        username: String,
        range: String = "month",
        count: Int = 50,
    ): List<LbArtistStat> {
        return try {
            val response = httpClient.get("$BASE_URL/1/stats/user/$username/artists") {
                parameter("range", range)
                parameter("count", count)
            }
            if (!response.status.isSuccess()) return emptyList()
            val parsed = json.decodeFromString<TopArtistsWire>(response.bodyAsText())
            parsed.payload?.artists.orEmpty().map { artist ->
                LbArtistStat(
                    name = artist.artistName.orEmpty(),
                    listenCount = artist.listenCount ?: 0,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to fetch top artists for $username", e)
            emptyList()
        }
    }

    /** Fetch user's top recordings (tracks). */
    suspend fun getUserTopRecordings(
        username: String,
        range: String = "month",
        count: Int = 50,
    ): List<LbRecordingStat> {
        return try {
            val response = httpClient.get("$BASE_URL/1/stats/user/$username/recordings") {
                parameter("range", range)
                parameter("count", count)
            }
            if (!response.status.isSuccess()) return emptyList()
            val parsed = json.decodeFromString<TopRecordingsWire>(response.bodyAsText())
            parsed.payload?.recordings.orEmpty().map { rec ->
                LbRecordingStat(
                    trackName = rec.trackName.orEmpty(),
                    artistName = rec.artistName.orEmpty(),
                    releaseName = rec.releaseName?.ifBlank { null },
                    listenCount = rec.listenCount ?: 0,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to fetch top recordings for $username", e)
            emptyList()
        }
    }

    /** Fetch user's top releases (albums). */
    suspend fun getUserTopReleases(
        username: String,
        range: String = "month",
        count: Int = 50,
    ): List<LbReleaseStat> {
        return try {
            val response = httpClient.get("$BASE_URL/1/stats/user/$username/releases") {
                parameter("range", range)
                parameter("count", count)
            }
            if (!response.status.isSuccess()) return emptyList()
            val parsed = json.decodeFromString<TopReleasesWire>(response.bodyAsText())
            parsed.payload?.releases.orEmpty().map { rel ->
                LbReleaseStat(
                    releaseName = rel.releaseName.orEmpty(),
                    artistName = rel.artistName.orEmpty(),
                    listenCount = rel.listenCount ?: 0,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to fetch top releases for $username", e)
            emptyList()
        }
    }

    /** Find a "Recommended" or weekly playlist for a user; return its tracks. */
    suspend fun getRecommendationPlaylistTracks(username: String): List<LbRecommendedTrack> {
        return try {
            val response = httpClient.get("$BASE_URL/1/user/$username/playlists/createdfor")
            if (!response.status.isSuccess()) {
                Log.w(TAG, "Failed to fetch recommendation playlists: ${response.status.value}")
                return emptyList()
            }
            val parsed = json.decodeFromString<CreatedForListWire>(response.bodyAsText())
            val recPlaylistMbid = parsed.playlists.firstNotNullOfOrNull { wrapper ->
                val pl = wrapper.playlist ?: return@firstNotNullOfOrNull null
                val title = pl.title.orEmpty()
                if (title.contains("Recommended", ignoreCase = true) ||
                    title.contains("Weekly Exploration", ignoreCase = true) ||
                    title.contains("Weekly Jams", ignoreCase = true)
                ) {
                    pl.identifier?.substringAfterLast("/")?.ifBlank { null }
                } else null
            } ?: run {
                Log.d(TAG, "No recommendation playlists found for $username")
                return emptyList()
            }

            // Fetch the actual playlist tracks (lighter shape — just title/creator/release).
            val plResponse = httpClient.get("$BASE_URL/1/playlist/$recPlaylistMbid")
            if (!plResponse.status.isSuccess()) return emptyList()
            val plParsed = json.decodeFromString<PlaylistDetailWire>(plResponse.bodyAsText())
            plParsed.playlist?.track.orEmpty().map { track ->
                val ext = track.extension?.jspfTrack
                LbRecommendedTrack(
                    title = track.title.orEmpty(),
                    artist = track.creator.orEmpty(),
                    album = ext?.releaseName?.ifBlank { null },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to fetch recommendation playlists for $username", e)
            emptyList()
        }
    }

    /** Fetch playlist metadata "createdfor" a user (Weekly Jams, Weekly Exploration). */
    suspend fun getCreatedForPlaylists(
        username: String,
        count: Int = 100,
    ): List<LbCreatedForPlaylist> {
        return try {
            val response = executeWithRetry {
                httpClient.get("$BASE_URL/1/user/$username/playlists/createdfor") {
                    parameter("count", count)
                }
            }
            if (!response.status.isSuccess()) return emptyList()
            val parsed = json.decodeFromString<CreatedForListWire>(response.bodyAsText())
            parsed.playlists.mapNotNull { wrapper ->
                val pl = wrapper.playlist ?: return@mapNotNull null
                val mbid = pl.identifier?.substringAfterLast("/")?.ifBlank { null }
                    ?: return@mapNotNull null
                LbCreatedForPlaylist(
                    id = mbid,
                    title = pl.title.orEmpty(),
                    date = pl.date.orEmpty(),
                    annotation = pl.annotation.orEmpty(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to fetch created-for playlists for $username", e)
            emptyList()
        }
    }

    /** Fetch tracks from a specific playlist with rich info (album art, durations). */
    suspend fun getPlaylistTracksRich(playlistMbid: String): List<LbPlaylistTrack> {
        return try {
            val response = executeWithRetry { httpClient.get("$BASE_URL/1/playlist/$playlistMbid") }
            if (!response.status.isSuccess()) return emptyList()
            val parsed = json.decodeFromString<PlaylistDetailWire>(response.bodyAsText())
            val tracks = parsed.playlist?.track.orEmpty()
            tracks.mapIndexedNotNull { i, track ->
                try {
                    val ext = track.extension?.jspfTrack
                    val addl = ext?.additionalMetadata
                    val caaMbid = addl?.caaReleaseMbid?.ifBlank { null }
                    val albumArt = caaMbid?.let { "https://coverartarchive.org/release/$it/front-250" }
                    val recordingMbid = track.identifier?.firstOrNull()?.substringAfterLast("/")?.ifBlank { null }
                    LbPlaylistTrack(
                        id = recordingMbid ?: "lb-track-$playlistMbid-$i",
                        title = track.title?.ifBlank { null } ?: "Unknown Track",
                        artist = track.creator?.ifBlank { null } ?: "Unknown Artist",
                        album = ext?.releaseName?.ifBlank { null }
                            ?: addl?.releaseName?.ifBlank { null },
                        albumArt = albumArt,
                        durationMs = track.duration?.takeIf { it > 0 },
                        mbid = recordingMbid,
                    )
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to parse playlist track at index $i", e)
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to fetch playlist $playlistMbid", e)
            emptyList()
        }
    }

    /** ListenBrainz MBID Mapper lookup. ~4ms, no auth, no documented strict rate limits. */
    suspend fun mbidMapperLookup(
        artistName: String,
        recordingName: String,
    ): MbidMapperResult? {
        return try {
            val response = httpClient.get("$MAPPER_URL/mapping/lookup") {
                parameter("artist_credit_name", artistName)
                parameter("recording_name", recordingName)
            }
            if (!response.status.isSuccess()) return null
            val body = response.bodyAsText()
            val parsed = json.decodeFromString<MbidMapperWire>(body)
            // The mapper returns {} when there's no match — artistCreditMbids will be null.
            if (parsed.artistCreditMbids == null) return null
            MbidMapperResult(
                artistMbid = parsed.artistCreditMbids.firstOrNull()?.ifBlank { null },
                artistCreditName = parsed.artistCreditName?.ifBlank { null },
                recordingName = parsed.recordingName?.ifBlank { null },
                recordingMbid = parsed.recordingMbid?.ifBlank { null },
                releaseName = parsed.releaseName?.ifBlank { null },
                releaseMbid = parsed.releaseMbid?.ifBlank { null },
                confidence = parsed.confidence ?: 0.0,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "MBID mapper lookup failed for '$artistName' - '$recordingName'", e)
            null
        }
    }

    /**
     * Retry on 429/503 with exponential backoff (matches desktop's fetchListenBrainz).
     * Up to 3 attempts: 1.5s, 3s, 6s delays before retries.
     */
    private suspend fun executeWithRetry(
        maxRetries: Int = 3,
        block: suspend () -> HttpResponse,
    ): HttpResponse {
        var lastResponse: HttpResponse? = null
        for (attempt in 0 until maxRetries) {
            val response = block()
            if (response.status.isSuccess()) return response
            if (response.status == HttpStatusCode.TooManyRequests ||
                response.status == HttpStatusCode.ServiceUnavailable
            ) {
                lastResponse = response
                val delayMs = 1500L * (1L shl attempt) // 1500, 3000, 6000
                delay(delayMs)
                continue
            }
            return response
        }
        return lastResponse ?: block()
    }
}

// ── Wire-format models (private, @Serializable) ──────────────────────────────

@Serializable
private data class CreatePlaylistWire(
    @SerialName("playlist_mbid") val playlistMbid: String? = null,
    val status: String? = null,
)

@Serializable
private data class ValidateTokenWire(
    val valid: Boolean? = null,
    @SerialName("user_name") val userName: String? = null,
)

@Serializable
private data class FollowingWire(
    val following: List<String> = emptyList(),
)

@Serializable
private data class ListensWire(val payload: ListensPayloadWire? = null)

@Serializable
private data class ListensPayloadWire(val listens: List<ListenWire> = emptyList())

@Serializable
private data class ListenWire(
    @SerialName("listened_at") val listenedAt: Long? = null,
    @SerialName("track_metadata") val trackMetadata: TrackMetadataWire? = null,
)

@Serializable
private data class TrackMetadataWire(
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("track_name") val trackName: String? = null,
    @SerialName("release_name") val releaseName: String? = null,
)

@Serializable
private data class TopArtistsWire(val payload: TopArtistsPayloadWire? = null)

@Serializable
private data class TopArtistsPayloadWire(val artists: List<ArtistStatWire> = emptyList())

@Serializable
private data class ArtistStatWire(
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("listen_count") val listenCount: Int? = null,
)

@Serializable
private data class TopRecordingsWire(val payload: TopRecordingsPayloadWire? = null)

@Serializable
private data class TopRecordingsPayloadWire(val recordings: List<RecordingStatWire> = emptyList())

@Serializable
private data class RecordingStatWire(
    @SerialName("track_name") val trackName: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("release_name") val releaseName: String? = null,
    @SerialName("listen_count") val listenCount: Int? = null,
)

@Serializable
private data class TopReleasesWire(val payload: TopReleasesPayloadWire? = null)

@Serializable
private data class TopReleasesPayloadWire(val releases: List<ReleaseStatWire> = emptyList())

@Serializable
private data class ReleaseStatWire(
    @SerialName("release_name") val releaseName: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("listen_count") val listenCount: Int? = null,
)

@Serializable
private data class CreatedForListWire(
    val playlists: List<CreatedForWrapperWire> = emptyList(),
    @SerialName("playlist_count") val playlistCount: Int = 0,
)

@Serializable
private data class CreatedForWrapperWire(val playlist: PlaylistMetaWire? = null)

@Serializable
private data class PlaylistMetaWire(
    val identifier: String? = null,
    val title: String? = null,
    val date: String? = null,
    val annotation: String? = null,
    val extension: PlaylistMetaExtensionWire? = null,
) {
    val lastModifiedAt: String?
        get() = extension?.jspfPlaylist?.lastModifiedAt
}

@Serializable
private data class PlaylistMetaExtensionWire(
    @SerialName("https://musicbrainz.org/doc/jspf#playlist")
    val jspfPlaylist: JspfPlaylistMetaWire? = null,
)

@Serializable
private data class JspfPlaylistMetaWire(
    @SerialName("last_modified_at") val lastModifiedAt: String? = null,
)

@Serializable
private data class PlaylistDetailWire(val playlist: PlaylistDetailBodyWire? = null)

@Serializable
private data class PlaylistDetailBodyWire(
    val track: List<PlaylistTrackWire> = emptyList(),
)

@Serializable
private data class PlaylistTrackWire(
    val title: String? = null,
    val creator: String? = null,
    val duration: Long? = null,
    val identifier: List<String>? = null,
    val extension: PlaylistTrackExtensionWire? = null,
)

@Serializable
private data class PlaylistTrackExtensionWire(
    @SerialName("https://musicbrainz.org/doc/jspf#track")
    val jspfTrack: JspfTrackExtensionWire? = null,
)

@Serializable
private data class JspfTrackExtensionWire(
    @SerialName("release_name") val releaseName: String? = null,
    @SerialName("additional_metadata") val additionalMetadata: AdditionalMetadataWire? = null,
)

@Serializable
private data class AdditionalMetadataWire(
    @SerialName("caa_release_mbid") val caaReleaseMbid: String? = null,
    @SerialName("release_name") val releaseName: String? = null,
)

@Serializable
private data class MbidMapperWire(
    @SerialName("artist_credit_mbids") val artistCreditMbids: List<String>? = null,
    @SerialName("artist_credit_name") val artistCreditName: String? = null,
    @SerialName("recording_name") val recordingName: String? = null,
    @SerialName("recording_mbid") val recordingMbid: String? = null,
    @SerialName("release_name") val releaseName: String? = null,
    @SerialName("release_mbid") val releaseMbid: String? = null,
    val confidence: Double? = null,
)
