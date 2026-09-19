package com.parachord.shared.api

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
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
 * **Preemptive throttling — 1 req/sec, serialized (#375).** This client used
 * to run unthrottled on the theory that a unique User-Agent bought tolerance
 * for ~50 RPS bursts. MetaBrainz's 2026-08 bot mitigation ended that: bursts
 * now draw 429/503, and a throttled search that silently returned empty read
 * to users as "no results". The gate is therefore `Semaphore(1)` +
 * [MB_MIN_REQUEST_GAP_MS], which also serializes the three `/ws/2/` queries a
 * global search fires — parallelism against a 1 req/sec server buys nothing
 * but throttling. A 429/503 is retried up to [MB_MAX_RATE_LIMIT_RETRIES]
 * times honoring `Retry-After`, and only surfaces to the caller if it
 * outlasts that.
 *
 * Unlike Spotify and Last.fm, MusicBrainz returns **503 Service Unavailable**
 * with a `Retry-After` header when throttled (not 429). The gate's
 * `isRateLimited` predicate accepts both codes.
 */
class MusicBrainzClient(
    private val httpClient: HttpClient,
    /** Clock source (epoch ms), injectable so the throttle's cooldown windows
     *  can be driven by `runTest`'s virtual time. Production uses the real
     *  clock. NOTE: the gate's cooldown and the retry's `delay()` must read
     *  the SAME clock — a real-clock cooldown never elapses under virtual
     *  time, which silently turns every retry test into a no-op. */
    nowMs: () -> Long = { currentTimeMillis() },
) {

    companion object {
        private const val TAG = "MusicBrainzClient"
        private const val BASE_URL = "https://musicbrainz.org/ws/2"

        /** Minimum backoff floor for a 503/429 (#273). MusicBrainz 503s with
         *  no (or a zero) `Retry-After` were yielding a 0ms cooldown, so the
         *  gate's "short-circuit during the window" protection was defeated and
         *  calls tight-looped against an already-throttled MB (observed
         *  on-device: repeated `Backing off 0s` ~120ms apart). 1s buys a real
         *  window; a larger server `Retry-After` still wins (this is a floor). */
        const val MB_MIN_BACKOFF_MS = 1_000L

        /** Inter-request gap enforcing MusicBrainz's 1 req/sec/IP ceiling,
         *  with 100ms of slack for clock/scheduling jitter so we land just
         *  under the limit rather than just over it. */
        const val MB_MIN_REQUEST_GAP_MS = 1_100L

        /** Bounded retries for a 429/503 before giving up (#375).
         *
         *  A throttle is transient and the server tells us how long to wait,
         *  so the useful behavior is to WAIT and retry — not to return an
         *  empty result the UI renders as "no matches", which is how a
         *  rate-limited search silently looked like a broken one. Bounded so
         *  a sustained outage can't wedge a request forever. */
        const val MB_MAX_RATE_LIMIT_RETRIES = 2

        /** Cap on how long a single retry will wait, however large a
         *  `Retry-After` MusicBrainz sends. A 10-minute `Retry-After` must not
         *  park a user-facing search for 10 minutes — past this we surface the
         *  rate-limited state instead and let the caller decide. */
        const val MB_MAX_RETRY_WAIT_MS = 5_000L

        /** Cooldown applied to a 429/503 that arrives with NO `Retry-After`.
         *
         *  The gate's generic default is 30s, which is wrong for MusicBrainz
         *  twice over: its limit is 1 req/sec, so a bare-throttle window is
         *  ~1s, and a 30s cooldown exceeds [MB_MAX_RETRY_WAIT_MS] — so the
         *  retry would decline to wait and give up on a throttle it could
         *  have ridden out in a second. A server-sent `Retry-After` still
         *  wins outright. */
        const val MB_DEFAULT_COOLDOWN_SEC = 2L
    }

    /** Serialized gate honoring MusicBrainz's published **1 request/sec/IP**
     *  ceiling (#375).
     *
     *  This was previously the permissive default — `Semaphore(2)` with a
     *  150ms gap — on a "let calls fly, react only to actual throttle
     *  responses" policy. That permits ~6–13 req/sec against a 1 req/sec
     *  server, so we were *guaranteeing* the throttle rather than reacting to
     *  it: a single global search fires three `/ws/2/` queries (artist +
     *  release + recording) and re-fires as the user types, and under
     *  MetaBrainz's 2026-08 bot mitigation those bursts draw 429/503.
     *
     *  Concurrency 1 also SERIALIZES the per-search category queries for free,
     *  wherever they are issued — parallelism buys nothing against a 1 req/sec
     *  server, it just converts into throttling. Fixing it here rather than at
     *  each call site means every MB path is covered, not just search.
     *
     *  Floors the 503/429 backoff at [MB_MIN_BACKOFF_MS] so a no-`Retry-After`
     *  response can't produce a 0s window (#273). */
    private val gate = RateLimitGate(
        tag = "MusicBrainzClient",
        maxConcurrent = 1,
        interRequestDelayMs = MB_MIN_REQUEST_GAP_MS,
        minCooldownMs = MB_MIN_BACKOFF_MS,
        defaultCooldownSec = MB_DEFAULT_COOLDOWN_SEC,
        nowMs = nowMs,
    )

    private val _rateLimited = MutableStateFlow(false)

    /**
     * True while MusicBrainz is throttling us *and the retries didn't clear
     * it* — i.e. the point at which callers would otherwise receive an empty
     * result and render "no matches".
     *
     * Exposed here rather than threaded through MetadataService because the
     * client is the only layer that can tell "MB had nothing" apart from "MB
     * refused to answer", and every MB path gets it, not just search. UIs
     * should say "rate-limited, retrying" instead of showing an empty state.
     * Cleared by the next successful call.
     */
    val rateLimited: StateFlow<Boolean> = _rateLimited.asStateFlow()

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
    private suspend inline fun <reified T> guardedGet(url: String, crossinline block: io.ktor.client.request.HttpRequestBuilder.() -> Unit): T {
        var attempt = 0
        while (true) {
            try {
                return gate.withPermit(
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
                    response.body<T>().also { _rateLimited.value = false }
                }
            } catch (e: MusicBrainzRateLimitedException) {
                // A throttle is transient and MB tells us how long to wait, so
                // WAIT and retry rather than handing the caller nothing — an
                // empty return here is indistinguishable from "no matches" and
                // is how a rate-limited search silently looked like a broken
                // one (#375).
                if (attempt >= MB_MAX_RATE_LIMIT_RETRIES) { _rateLimited.value = true; throw e }
                // Prefer the server's Retry-After; fall back to whatever the
                // gate's cooldown still has left, floored so we never spin.
                val waitMs = (e.retryAfterSeconds?.times(1000L) ?: gate.remainingCooldownMs())
                    .coerceAtLeast(MB_MIN_BACKOFF_MS)
                // Don't park a user-facing call behind a long Retry-After;
                // surface the rate-limited state instead and let the caller decide.
                if (waitMs > MB_MAX_RETRY_WAIT_MS) { _rateLimited.value = true; throw e }
                Log.w(TAG, "MusicBrainz throttled — waiting ${waitMs}ms then retrying (attempt ${attempt + 1}/$MB_MAX_RATE_LIMIT_RETRIES)")
                delay(waitMs)
                attempt++
            }
        }
    }

    suspend fun searchRecordings(query: String, limit: Int = 20): MbRecordingSearchResponse =
        guardedGet("$BASE_URL/recording/") { parameter("query", query); parameter("limit", limit) }

    suspend fun searchReleases(query: String, limit: Int = 10): MbReleaseSearchResponse =
        guardedGet("$BASE_URL/release/") { parameter("query", query); parameter("limit", limit) }

    suspend fun searchArtists(query: String, limit: Int = 10): MbArtistSearchResponse =
        guardedGet("$BASE_URL/artist/") { parameter("query", query); parameter("limit", limit) }

    /**
     * Resolve a recording MBID from an ISRC via `GET /ws/2/isrc/{isrc}`. The
     * ISRC identifies the exact recording the streaming service is playing, so
     * this is an exact lookup (no fuzzy title/artist matching). Independent of
     * the ListenBrainz mapper — the service-agnostic core of the ISRC → MBID
     * fallback. Returns the first recording's MBID, or null when the ISRC has no
     * MusicBrainz recording. Throws [MusicBrainzRateLimitedException] on 429/503;
     * callers wrap other failures (e.g. 404) and fall back to null.
     */
    suspend fun lookupRecordingMbidByIsrc(isrc: String): String? {
        // Validate/normalize before hitting MB (#217): a malformed ISRC just 404s,
        // and /ws/2/isrc/ expects the canonical (uppercased) form.
        val normalized = com.parachord.shared.resolver.validateIsrc(isrc) ?: return null
        return guardedGet<MbIsrcLookupResponse>("$BASE_URL/isrc/$normalized") {}.recordings.firstOrNull()?.id
    }

    suspend fun getRelease(
        releaseId: String,
        inc: String = "recordings+artist-credits",
    ): MbReleaseDetail =
        guardedGet("$BASE_URL/release/$releaseId") { parameter("inc", inc) }

    /**
     * Browse releases that belong to a given release-group, including
     * media + recordings + artist-credits so the caller can map tracks
     * directly without a follow-up release lookup.
     *
     * Sorted by release date (oldest first per MB default). Caller picks
     * the first release as a canonical pick — usually adequate since most
     * release-groups have one or a few editions of the same tracklist.
     *
     * Endpoint: GET /ws/2/release?release-group={rgMbid}&inc=...&limit=...
     *
     * Used by the protocol deeplink resolver to handle the common case
     * where an external tool (e.g. Achordion) emits a release-group MBID
     * as the canonical "album" identifier — `/ws/2/release/{rgMbid}` 404s
     * for those, so we fall through to this browse endpoint.
     */
    suspend fun browseReleasesByReleaseGroup(
        releaseGroupMbid: String,
        inc: String = "recordings+artist-credits",
        limit: Int = 1,
    ): MbReleaseBrowseResponse =
        guardedGet("$BASE_URL/release") {
            parameter("release-group", releaseGroupMbid)
            parameter("inc", inc)
            parameter("limit", limit)
        }

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

/** `GET /ws/2/isrc/{isrc}` — recordings carrying that ISRC. */
@Serializable
data class MbIsrcLookupResponse(
    val recordings: List<MbIsrcRecording> = emptyList(),
)

@Serializable
data class MbIsrcRecording(
    val id: String? = null,
    val title: String? = null,
)

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

/**
 * Response for `GET /ws/2/release?release-group={rgMbid}&inc=...`.
 *
 * The browsed release entries carry the same shape as [MbReleaseDetail]
 * (id + title + artist-credit + media[]+tracks[]) when the request
 * includes `inc=recordings+artist-credits`, so we reuse that type for
 * the items rather than introducing a parallel response model.
 */
@Serializable
data class MbReleaseBrowseResponse(
    @SerialName("release-offset") val releaseOffset: Int = 0,
    @SerialName("release-count") val releaseCount: Int = 0,
    val releases: List<MbReleaseDetail> = emptyList(),
)

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
