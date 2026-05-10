package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Thrown by [MusicBrainzClient] methods when MusicBrainz responds with
 * either HTTP 429 OR HTTP 503 (with `Retry-After`). MusicBrainz signals
 * rate-limiting primarily via 503 — our gate predicate accepts both.
 *
 * MusicBrainz publishes a 1 RPS limit for unauthenticated requests
 * (anything stricter and the user-agent gets banned). We honor this with
 * a `Semaphore(1)` + ~1.1s inter-request delay in the gate config.
 *
 * @property retryAfterSeconds the value of the `Retry-After` header if
 *   MusicBrainz sent one; null otherwise. The fallback is the gate's default.
 */
class MusicBrainzRateLimitedException(val retryAfterSeconds: Long? = null) : Exception(
    "MusicBrainz rate-limited" + (retryAfterSeconds?.let { " (Retry-After: ${it}s)" } ?: "")
)

/**
 * MusicBrainz API v2 client.
 * Free, no auth required.
 * https://musicbrainz.org/doc/MusicBrainz_API
 *
 * **429/503 handling.** All GET methods route through [gate], which
 * surfaces 429 OR 503 (MB's preferred throttle response) as a typed
 * [MusicBrainzRateLimitedException] and engages a cooldown sized by
 * `Retry-After`. The KMP cutover (Phase 9E.1.1, commit `f41d5bc`) lost
 * the Retrofit/OkHttp interceptor 429 retry that previously protected
 * this.
 *
 * **No preemptive throttling.** MusicBrainz publishes a 1 RPS limit for
 * default-UA clients, but with a unique User-Agent (the global OkHttpClient
 * interceptor sets `Parachord/<version> (Android; ...)`), MB tolerates
 * ~50 RPS bursts in practice. Desktop calls MB directly via `fetch()` with
 * no preemptive throttle and runs fine — we match that policy here. The
 * gate kicks in only if MB actually returns 429/503; until then, calls
 * flow through unimpeded.
 *
 * Unlike Spotify and Last.fm, MusicBrainz returns **503 Service Unavailable**
 * with a `Retry-After` header when throttled (not 429). The gate's
 * `isRateLimited` predicate accepts both codes.
 */
class MusicBrainzClient(private val httpClient: HttpClient) {

    companion object {
        private const val BASE_URL = "https://musicbrainz.org/ws/2"
    }

    /** Permissive gate — defaults: Semaphore(2), 150ms inter-request delay.
     *  Matches desktop's policy of "let calls fly, react only to actual
     *  throttle responses". The 503-aware predicate is configured per-call. */
    private val gate = RateLimitGate(tag = "MusicBrainzClient")

    /** Predicate matching MusicBrainz's 503-with-Retry-After throttling
     *  (in addition to plain 429 in case they ever switch). */
    private val isMbRateLimited: (HttpResponse) -> Boolean = {
        it.status.value == 429 || it.status.value == 503
    }

    /**
     * Run a `GET $url` through the rate-limit gate, applying [block] to the
     * Ktor request builder before sending.
     *
     * **Do NOT rename [block] back to `build`** — `HttpRequestBuilder` has a
     * member function `build(): HttpRequestData` that would silently shadow
     * the lambda parameter, causing every `parameter("…", …)` call passed
     * by callers to be discarded. The bug shipped from `f41d5bc` (the
     * Retrofit→Ktor cutover) until issue #133, surviving because `fmt=json`
     * (set inline below) was the only param MB actually requires for
     * happy-path queries to return *something* — search endpoints returned
     * empty result sets and `inc=` calls returned defaults, but UI paths
     * fell back to other providers cleanly enough that nobody noticed.
     */
    private suspend inline fun <reified T> guardedGet(url: String, crossinline block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): T =
        gate.withPermit(
            isRateLimited = isMbRateLimited,
            exceptionFactory = { MusicBrainzRateLimitedException(it) },
        ) {
            val response: HttpResponse = httpClient.get(url) {
                block()
                parameter("fmt", "json")
            }
            gate.handleResponse(
                response,
                isRateLimited = isMbRateLimited,
                exceptionFactory = { MusicBrainzRateLimitedException(it) },
            )
            response.body()
        }

    suspend fun searchRecordings(query: String, limit: Int = 20): MbRecordingSearchResponse =
        guardedGet("$BASE_URL/recording/") { parameter("query", query); parameter("limit", limit) }

    suspend fun searchReleases(query: String, limit: Int = 10): MbReleaseSearchResponse =
        guardedGet("$BASE_URL/release/") { parameter("query", query); parameter("limit", limit) }

    suspend fun searchArtists(query: String, limit: Int = 10): MbArtistSearchResponse =
        guardedGet("$BASE_URL/artist/") { parameter("query", query); parameter("limit", limit) }

    suspend fun getRelease(
        releaseId: String,
        inc: String = "recordings+artist-credits",
    ): MbReleaseDetail =
        guardedGet("$BASE_URL/release/$releaseId") { parameter("inc", inc) }

    suspend fun browseReleaseGroups(
        artistId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): MbReleaseGroupBrowseResponse =
        guardedGet("$BASE_URL/release-group") {
            parameter("artist", artistId); parameter("limit", limit); parameter("offset", offset)
        }

    suspend fun getArtist(
        artistId: String,
        inc: String = "url-rels",
    ): MbArtistDetail =
        guardedGet("$BASE_URL/artist/$artistId") { parameter("inc", inc) }
}

// ── Response Models ──────────────────────────────────────────────────

@Serializable
data class MbRecordingSearchResponse(
    val recordings: List<MbRecording> = emptyList(),
)

@Serializable
data class MbRecording(
    val id: String,
    val title: String,
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val releases: List<MbReleaseRef> = emptyList(),
) {
    val artistName: String get() = artistCredit.joinToString(", ") { it.name }
    val albumTitle: String? get() = releases.firstOrNull()?.title
}

@Serializable
data class MbArtistCredit(
    val name: String,
    val artist: MbArtistRef? = null,
)

@Serializable
data class MbArtistRef(
    val id: String,
    val name: String,
)

@Serializable
data class MbReleaseRef(
    val id: String,
    val title: String,
)

@Serializable
data class MbReleaseSearchResponse(
    val releases: List<MbRelease> = emptyList(),
)

@Serializable
data class MbRelease(
    val id: String,
    val title: String,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val date: String? = null,
    @SerialName("track-count") val trackCount: Int? = null,
    @SerialName("release-group") val releaseGroup: MbReleaseGroup? = null,
) {
    val artistName: String get() = artistCredit.joinToString(", ") { it.name }
    val year: Int? get() = date?.take(4)?.toIntOrNull()
}

@Serializable
data class MbReleaseGroup(
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
)

@Serializable
data class MbArtistSearchResponse(
    val artists: List<MbArtist> = emptyList(),
)

@Serializable
data class MbArtist(
    val id: String,
    val name: String,
    val disambiguation: String? = null,
    val tags: List<MbTag> = emptyList(),
)

@Serializable
data class MbTag(
    val name: String,
    val count: Int = 0,
)

@Serializable
data class MbReleaseDetail(
    val id: String,
    val title: String,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val date: String? = null,
    val media: List<MbMedia> = emptyList(),
) {
    val artistName: String get() = artistCredit.joinToString(", ") { it.name }
    val year: Int? get() = date?.take(4)?.toIntOrNull()
}

@Serializable
data class MbMedia(
    val position: Int? = null,
    val format: String? = null,
    val tracks: List<MbTrack> = emptyList(),
)

@Serializable
data class MbTrack(
    val id: String,
    val number: String? = null,
    val title: String,
    val length: Long? = null,
    val position: Int? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val recording: MbTrackRecording? = null,
) {
    val artistName: String
        get() = artistCredit.ifEmpty { recording?.artistCredit ?: emptyList() }
            .joinToString(", ") { it.name }
}

@Serializable
data class MbTrackRecording(
    val id: String,
    val title: String,
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
)

@Serializable
data class MbReleaseGroupBrowseResponse(
    @SerialName("release-groups") val releaseGroups: List<MbReleaseGroupEntry> = emptyList(),
    @SerialName("release-group-count") val releaseGroupCount: Int = 0,
    @SerialName("release-group-offset") val releaseGroupOffset: Int = 0,
)

@Serializable
data class MbReleaseGroupEntry(
    val id: String,
    val title: String,
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
) {
    val artistName: String get() = artistCredit.joinToString(", ") { it.name }
    val year: Int? get() = firstReleaseDate?.take(4)?.toIntOrNull()
}

@Serializable
data class MbArtistDetail(
    val id: String,
    val name: String,
    val relations: List<MbRelation> = emptyList(),
)

@Serializable
data class MbRelation(
    val type: String = "",
    val url: MbRelationUrl? = null,
)

@Serializable
data class MbRelationUrl(
    val resource: String = "",
)
