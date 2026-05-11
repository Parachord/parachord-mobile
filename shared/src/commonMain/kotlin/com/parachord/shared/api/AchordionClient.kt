package com.parachord.shared.api

import com.parachord.shared.platform.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client for the Achordion entity-link + submit-track-links APIs.
 *
 * Two endpoints, both Bearer-token authenticated:
 *
 *  - `GET /api/entity-link?type={track|release-group|artist}&mbid={mbid}` —
 *    returns the canonical Achordion entity URL (possibly a slug-based
 *    redirect target) plus optional names. Used to mint share URLs.
 *  - `POST /api/track-links/submit` — pre-warms Achordion's match cache
 *    with the sharer's resolved per-service URLs (Spotify/Apple/SoundCloud)
 *    so the recipient lands on a fully-populated entity page on first click.
 *
 * Per-session dedup: an MBID submitted once won't re-submit until the
 * process restarts. Matches desktop's `submittedThisSession` in
 * `parachord-desktop/plugins/achordion.axe`.
 *
 * `authFailed` kill-switch: once any call returns 401, subsequent calls
 * short-circuit without hitting the network until process restart.
 *
 * KMP-clean (commonMain). Token is sourced from
 * [com.parachord.shared.config.AppConfig.achordionBearerToken] which
 * carries an empty string when not configured — empty-token calls
 * short-circuit (null entity links / [SubmitResult.AuthFailed]) without
 * making a request.
 */
class AchordionClient(
    private val httpClient: HttpClient,
    private val bearerToken: String,
) {
    companion object {
        private const val TAG = "AchordionClient"
        private const val ENTITY_LINK_ENDPOINT = "https://achordion.xyz/api/entity-link"
        private const val SUBMIT_ENDPOINT = "https://achordion.xyz/api/track-links/submit"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }

    private val dedupMutex = Mutex()
    private val submittedThisSession: MutableSet<String> = mutableSetOf()
    @Volatile private var authFailed: Boolean = false

    suspend fun fetchEntityLink(
        type: EntityType,
        mbid: String,
        includeNames: Boolean = false,
    ): EntityLink? {
        if (bearerToken.isBlank()) return null
        if (authFailed) return null
        return try {
            val response = httpClient.get(ENTITY_LINK_ENDPOINT) {
                parameter("type", type.wireValue)
                parameter("mbid", mbid)
                if (includeNames) parameter("include", "names")
                header(HttpHeaders.Authorization, "Bearer $bearerToken")
                accept(ContentType.Application.Json)
            }
            when (response.status) {
                HttpStatusCode.Unauthorized -> {
                    authFailed = true
                    Log.w(TAG, "entity-link returned 401 — suppressing further calls this session")
                    null
                }
                HttpStatusCode.OK -> {
                    val body = response.bodyAsText()
                    json.decodeFromString<EntityLink>(body)
                }
                else -> {
                    Log.w(TAG, "entity-link returned HTTP ${response.status.value} for $type mbid=$mbid")
                    null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "entity-link failed for $type mbid=$mbid: ${e.message}")
            null
        }
    }

    suspend fun submitTrackLinks(payload: SubmitTrackLinksRequest): SubmitResult {
        if (bearerToken.isBlank()) return SubmitResult.AuthFailed
        if (authFailed) return SubmitResult.AuthFailed
        if (payload.mbid.isBlank()) return SubmitResult.NoMbid
        if (payload.links.isEmpty()) return SubmitResult.NoLinks

        val mbidKey = payload.mbid.lowercase()
        val alreadySubmitted = dedupMutex.withLock {
            if (mbidKey in submittedThisSession) true
            else {
                submittedThisSession.add(mbidKey)
                false
            }
        }
        if (alreadySubmitted) return SubmitResult.AlreadySubmitted

        return try {
            val response = httpClient.post(SUBMIT_ENDPOINT) {
                header(HttpHeaders.Authorization, "Bearer $bearerToken")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            when (response.status) {
                HttpStatusCode.OK, HttpStatusCode.Created, HttpStatusCode.Accepted -> SubmitResult.Ok
                HttpStatusCode.Unauthorized -> {
                    authFailed = true
                    Log.w(TAG, "submit returned 401 — suppressing further calls this session")
                    SubmitResult.AuthFailed
                }
                else -> {
                    dedupMutex.withLock { submittedThisSession.remove(mbidKey) }
                    Log.w(TAG, "submit returned HTTP ${response.status.value} for mbid=${payload.mbid}")
                    SubmitResult.HttpError(response.status.value)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            dedupMutex.withLock { submittedThisSession.remove(mbidKey) }
            Log.w(TAG, "submit failed for mbid=${payload.mbid}: ${e.message}")
            SubmitResult.NetworkError(e.message ?: "unknown")
        }
    }
}

enum class EntityType(val wireValue: String) {
    Track("track"),
    ReleaseGroup("release-group"),
    Artist("artist"),
}

@Serializable
data class EntityLink(
    val url: String,
    @SerialName("embed_url") val embedUrl: String? = null,
    val name: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("album_name") val albumName: String? = null,
)

@Serializable
data class SubmitTrackLinksRequest(
    val mbid: String,
    val links: List<TrackLink>,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
)

@Serializable
data class TrackLink(
    val url: String,
    val host: String,
    val label: String? = null,
)

sealed class SubmitResult {
    object Ok : SubmitResult()
    object NoLinks : SubmitResult()
    object AlreadySubmitted : SubmitResult()
    object NoMbid : SubmitResult()
    data class HttpError(val status: Int) : SubmitResult()
    object AuthFailed : SubmitResult()
    data class NetworkError(val message: String) : SubmitResult()
}
