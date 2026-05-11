package com.parachord.shared.api

import com.parachord.shared.platform.Log
import io.ktor.client.HttpClient
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
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
        TODO("Task 2")
    }

    suspend fun submitTrackLinks(payload: SubmitTrackLinksRequest): SubmitResult {
        TODO("Task 3")
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
