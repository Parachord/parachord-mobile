package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thrown by [LastFmClient] methods when Last.fm responds with HTTP 429.
 * Callers (notably the metadata cascade in [com.parachord.shared.metadata.LastFmProvider])
 * already swallow generic exceptions and fall through to the next provider,
 * so a typed exception lets us short-circuit cleanly without log spam.
 *
 * Last.fm publishes a 5 RPS per-API-key limit. The rate-limit window is
 * usually short (seconds), but during sustained abuse the bucket stays
 * empty for longer. Mirrors [SpotifyRateLimitedException] / [ItunesRateLimitedException].
 *
 * @property retryAfterSeconds the value of the `Retry-After` header if
 *   Last.fm sent one; null otherwise. The fallback is the gate's default.
 */
class LastFmRateLimitedException(val retryAfterSeconds: Long? = null) : Exception(
    "Last.fm returned HTTP 429" + (retryAfterSeconds?.let { " (Retry-After: ${it}s)" } ?: "")
)

/**
 * The Last.fm profile hides its recent listening. With "Hide recent listening
 * information" on, `user.getRecentTracks` answers 403 with error 17 to everyone
 * but the account's owner; signing the request doesn't get past it, while the
 * profile's top tracks, artists and loved tracks still answer. A state, not a
 * failure (desktop parachord#1140).
 */
class LastFmHiddenListeningException(val user: String) : Exception(
    "Last.fm user $user hides their recent listening"
)

/** Last.fm error 17, "Login: User required to be logged in". */
internal const val LASTFM_LOGIN_REQUIRED = 17

/**
 * True when a `user.getRecentTracks` response (HTTP status + body) means the
 * profile hides its recent listening. Port of desktop's
 * `isLastfmHiddenListening` (`lib/lastfm-hidden-listening.js`).
 */
fun isLastFmHiddenListening(status: Int, body: String?): Boolean {
    if (status != 403 || body.isNullOrBlank()) return false
    val error = try {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content?.toIntOrNull()
    } catch (_: Exception) {
        null
    }
    return error == LASTFM_LOGIN_REQUIRED
}

/**
 * Last.fm API client.
 * Base URL: https://ws.audioscrobbler.com/2.0/
 * Auth: API key as query parameter.
 * All endpoints are GET requests to the same URL with different method params.
 *
 * **429 handling.** All GET methods route through [gate], which provides
 * cooldown + bounded concurrency + inter-request pacing — the same pattern
 * as [SpotifyClient]. The image-enrichment cascade in
 * `ImageEnrichmentService.enrichPlaylistArt` Pass 2 fans out per-track
 * Last.fm searches; without the gate, opening a 288-track XSPF playlist
 * trivially overruns Last.fm's 5 RPS limit. The KMP cutover (Phase 9E.1.6,
 * commit `79c5ed5`) lost the Retrofit/OkHttp interceptor 429 retry that
 * previously protected against this.
 */
/** Result of a successful Last.fm `auth.getSession`: the session key + username. */
data class LastFmSessionResult(val key: String, val name: String?)

class LastFmClient(private val httpClient: HttpClient) {

    companion object {
        private const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"
    }

    /** Per-client rate-limit gate. See [RateLimitGate]'s KDoc. */
    private val gate = RateLimitGate(tag = "LastFmClient")

    /**
     * Explicit Json for decoding the response body via the per-call
     * [KSerializer] passed to [guardedGet], rather than Ktor's
     * `response.body<reified T>()`. The compiler-generated serializer resolves
     * at the call site and still honors the per-type custom serializers below.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Wraps every GET against [BASE_URL] in the rate-limit gate. Methods
     * just supply their `method=` + endpoint params — `format=json` is
     * always set here so it can't be forgotten at a callsite.
     */
    private suspend inline fun <T> guardedGet(
        deserializer: KSerializer<T>,
        crossinline inspect: (status: Int, body: String) -> Unit = { _, _ -> },
        crossinline build: HttpRequestBuilder.() -> Unit,
    ): T = gate.withPermit(exceptionFactory = { LastFmRateLimitedException(it) }) {
        val response: HttpResponse = httpClient.get(BASE_URL) {
            apply(build)   // bind `build`'s receiver explicitly to this builder —
                           // a bare `build()` fails to bind on BOTH JVM and
                           // Native here and silently drops every param (Last.fm
                           // error 6). See LastFmClientParamsTest.
            parameter("format", "json")
        }
        gate.handleResponse(response) { LastFmRateLimitedException(it) }
        val body = response.bodyAsText()
        inspect(response.status.value, body)
        json.decodeFromString(deserializer, body)
    }

    // ── Scrobbling (#193, shared so iOS scrobbles too) ──────────────────────
    // Signed, authenticated POSTs (api_sig via LastFmSigning). Routed through the
    // SAME rate-limit gate as reads (CLAUDE.md: write methods must honor the gate).
    // `apiUrl` lets Libre.fm reuse this against its own endpoint.

    private suspend fun postSigned(
        params: Map<String, String>,
        apiKey: String,
        sessionKey: String,
        sharedSecret: String,
        apiUrl: String,
    ): Boolean = gate.withPermit(exceptionFactory = { LastFmRateLimitedException(it) }) {
        val all = params.toMutableMap()
        all["api_key"] = apiKey
        all["sk"] = sessionKey
        all["api_sig"] = LastFmSigning.apiSig(all, sharedSecret)   // sign BEFORE adding format
        all["format"] = "json"
        val response = httpClient.submitForm(
            url = apiUrl,
            formParameters = Parameters.build { all.forEach { (k, v) -> append(k, v) } },
        )
        gate.handleResponse(response) { LastFmRateLimitedException(it) }
        response.status.isSuccess() && !response.bodyAsText().contains("\"error\"")
    }

    suspend fun updateNowPlaying(
        artist: String, title: String,
        apiKey: String, sessionKey: String, sharedSecret: String,
        album: String? = null, recordingMbid: String? = null, durationSec: Long? = null,
        apiUrl: String = BASE_URL,
    ): Boolean = postSigned(
        buildMap {
            put("method", "track.updateNowPlaying"); put("artist", artist); put("track", title)
            if (!album.isNullOrBlank()) put("album", album)
            if (!recordingMbid.isNullOrBlank()) put("mbid", recordingMbid)
            if (durationSec != null) put("duration", durationSec.toString())
        }, apiKey, sessionKey, sharedSecret, apiUrl,
    )

    suspend fun scrobble(
        artist: String, title: String, timestamp: Long,
        apiKey: String, sessionKey: String, sharedSecret: String,
        album: String? = null, recordingMbid: String? = null, durationSec: Long? = null,
        apiUrl: String = BASE_URL,
    ): Boolean = postSigned(
        buildMap {
            put("method", "track.scrobble")
            put("artist[0]", artist); put("track[0]", title); put("timestamp[0]", timestamp.toString())
            if (!album.isNullOrBlank()) put("album[0]", album)
            if (durationSec != null) put("duration[0]", durationSec.toString())
            if (!recordingMbid.isNullOrBlank()) put("mbid[0]", recordingMbid)
        }, apiKey, sessionKey, sharedSecret, apiUrl,
    )

    suspend fun loveTrack(
        artist: String, title: String,
        apiKey: String, sessionKey: String, sharedSecret: String,
        recordingMbid: String? = null, apiUrl: String = BASE_URL,
    ): Boolean = postSigned(
        buildMap {
            put("method", "track.love"); put("artist", artist); put("track", title)
            if (!recordingMbid.isNullOrBlank()) put("mbid", recordingMbid)
        }, apiKey, sessionKey, sharedSecret, apiUrl,
    )

    /**
     * auth.getMobileSession — exchange username/password for a session key
     * (Libre.fm has no OAuth). Signed but NOT session-authenticated (no `sk`),
     * so it can't reuse [postSigned]. Returns the session key, or null on
     * auth/network/parse failure. Routed through the same gate as other writes.
     */
    suspend fun getMobileSession(
        username: String, password: String,
        apiKey: String, sharedSecret: String, apiUrl: String = BASE_URL,
    ): String? = gate.withPermit(exceptionFactory = { LastFmRateLimitedException(it) }) {
        val all = buildMap {
            put("method", "auth.getMobileSession")
            put("username", username)
            put("password", password)
            put("api_key", apiKey)
        }.toMutableMap()
        all["api_sig"] = LastFmSigning.apiSig(all, sharedSecret)   // sign BEFORE adding format
        all["format"] = "json"
        val response = httpClient.submitForm(
            url = apiUrl,
            formParameters = Parameters.build { all.forEach { (k, v) -> append(k, v) } },
        )
        gate.handleResponse(response) { LastFmRateLimitedException(it) }
        val body = response.bodyAsText()
        if (!response.status.isSuccess() || body.contains("\"error\"")) {
            null
        } else {
            try {
                json.parseToJsonElement(body).jsonObject["session"]
                    ?.jsonObject?.get("key")?.jsonPrimitive?.content
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * auth.getSession — exchange a web-auth `token` (from the
     * `last.fm/api/auth/?api_key&cb=` redirect) for a permanent session key +
     * username, used for scrobbling (#193 iOS Last.fm OAuth). Signed but NOT
     * session-authenticated. Mirrors Android's OAuthManager.handleLastFmCallback.
     * Returns null on auth/network/parse failure.
     */
    suspend fun getSession(token: String, apiKey: String, sharedSecret: String): LastFmSessionResult? =
        gate.withPermit(exceptionFactory = { LastFmRateLimitedException(it) }) {
            val params = mapOf("api_key" to apiKey, "method" to "auth.getSession", "token" to token)
            val response = httpClient.get(BASE_URL) {
                parameter("method", "auth.getSession")
                parameter("api_key", apiKey)
                parameter("token", token)
                parameter("api_sig", LastFmSigning.apiSig(params, sharedSecret))
                parameter("format", "json")
            }
            gate.handleResponse(response) { LastFmRateLimitedException(it) }
            val body = response.bodyAsText()
            if (!response.status.isSuccess() || body.contains("\"error\"")) {
                null
            } else {
                try {
                    val session = json.parseToJsonElement(body).jsonObject["session"]?.jsonObject
                    val key = session?.get("key")?.jsonPrimitive?.content
                    if (key == null) null
                    else LastFmSessionResult(key, session["name"]?.jsonPrimitive?.content)
                } catch (e: Exception) {
                    null
                }
            }
        }

    suspend fun searchTracks(track: String, apiKey: String, limit: Int = 20): LfmTrackSearchResponse =
        guardedGet(LfmTrackSearchResponse.serializer()) { parameter("method", "track.search"); parameter("track", lfmName(track)); parameter("api_key", apiKey); parameter("limit", limit) }

    suspend fun searchAlbums(album: String, apiKey: String, limit: Int = 10): LfmAlbumSearchResponse =
        guardedGet(LfmAlbumSearchResponse.serializer()) { parameter("method", "album.search"); parameter("album", lfmName(album)); parameter("api_key", apiKey); parameter("limit", limit) }

    suspend fun searchArtists(artist: String, apiKey: String, limit: Int = 10): LfmArtistSearchResponse =
        guardedGet(LfmArtistSearchResponse.serializer()) { parameter("method", "artist.search"); parameter("artist", lfmName(artist)); parameter("api_key", apiKey); parameter("limit", limit) }

    /**
     * Pre-encode a Last.fm name param so a literal "+" survives. Last.fm
     * DOUBLE-decodes query values and form-decodes "+"→space, so a normal "+"
     * (sent as %2B) arrives as a space — e.g. "Florence + the Machine" became
     * "Florence   the Machine" and returned the "incorrect tag" placeholder bio
     * + zero similar artists. Replacing "+" with the literal "%2B" makes Ktor
     * encode it to "%252B", which Last.fm double-decodes back to "+". Verified
     * against the live API.
     */
    private fun lfmName(s: String): String = s.replace("+", "%2B")

    // autocorrect=1 — without it, a name that isn't Last.fm's EXACT canonical
    // (e.g. "Florence + The Machine" vs "…the Machine") returns the "incorrect
    // tag" placeholder bio AND empty similar artists. Autocorrect redirects to
    // the canonical artist so bio / similar / top-tracks resolve correctly.
    suspend fun getArtistInfo(artist: String, apiKey: String): LfmArtistInfoResponse =
        guardedGet(LfmArtistInfoResponse.serializer()) { parameter("method", "artist.getinfo"); parameter("artist", lfmName(artist)); parameter("api_key", apiKey); parameter("autocorrect", 1) }

    suspend fun getSimilarArtists(artist: String, apiKey: String, limit: Int = 20): LfmSimilarArtistsResponse =
        guardedGet(LfmSimilarArtistsResponse.serializer()) { parameter("method", "artist.getsimilar"); parameter("artist", lfmName(artist)); parameter("api_key", apiKey); parameter("limit", limit); parameter("autocorrect", 1) }

    suspend fun getArtistTopTracks(artist: String, apiKey: String, limit: Int = 10): LfmTopTracksResponse =
        guardedGet(LfmTopTracksResponse.serializer()) { parameter("method", "artist.gettoptracks"); parameter("artist", lfmName(artist)); parameter("api_key", apiKey); parameter("limit", limit); parameter("autocorrect", 1) }

    suspend fun getArtistTopAlbums(artist: String, apiKey: String, limit: Int = 50): LfmTopAlbumsResponse =
        guardedGet(LfmTopAlbumsResponse.serializer()) { parameter("method", "artist.gettopalbums"); parameter("artist", lfmName(artist)); parameter("api_key", apiKey); parameter("limit", limit); parameter("autocorrect", 1) }

    suspend fun getTrackInfo(track: String, artist: String, apiKey: String): LfmTrackInfoResponse =
        guardedGet(LfmTrackInfoResponse.serializer()) { parameter("method", "track.getInfo"); parameter("track", lfmName(track)); parameter("artist", lfmName(artist)); parameter("api_key", apiKey) }

    suspend fun getSimilarTracks(track: String, artist: String, apiKey: String, limit: Int = 20): LfmSimilarTracksResponse =
        guardedGet(LfmSimilarTracksResponse.serializer()) { parameter("method", "track.getsimilar"); parameter("track", lfmName(track)); parameter("artist", lfmName(artist)); parameter("api_key", apiKey); parameter("limit", limit) }

    suspend fun getAlbumInfo(album: String, artist: String, apiKey: String): LfmAlbumInfoResponse =
        guardedGet(LfmAlbumInfoResponse.serializer()) { parameter("method", "album.getinfo"); parameter("album", lfmName(album)); parameter("artist", lfmName(artist)); parameter("api_key", apiKey) }

    suspend fun getUserInfo(user: String, apiKey: String): LfmUserInfoResponse =
        guardedGet(LfmUserInfoResponse.serializer()) { parameter("method", "user.getinfo"); parameter("user", user); parameter("api_key", apiKey) }

    suspend fun getUserTopTracks(user: String, apiKey: String, period: String = "overall", limit: Int = 50): LfmUserTopTracksResponse =
        guardedGet(LfmUserTopTracksResponse.serializer()) { parameter("method", "user.gettoptracks"); parameter("user", user); parameter("period", period); parameter("limit", limit); parameter("api_key", apiKey) }

    suspend fun getUserTopAlbums(user: String, apiKey: String, period: String = "overall", limit: Int = 50): LfmUserTopAlbumsResponse =
        guardedGet(LfmUserTopAlbumsResponse.serializer()) { parameter("method", "user.gettopalbums"); parameter("user", user); parameter("period", period); parameter("limit", limit); parameter("api_key", apiKey) }

    suspend fun getUserTopArtists(user: String, apiKey: String, period: String = "overall", limit: Int = 50): LfmUserTopArtistsResponse =
        guardedGet(LfmUserTopArtistsResponse.serializer()) { parameter("method", "user.gettopartists"); parameter("user", user); parameter("period", period); parameter("limit", limit); parameter("api_key", apiKey) }

    /** @throws LastFmHiddenListeningException when the profile hides its recent listening. */
    suspend fun getUserRecentTracks(user: String, apiKey: String, limit: Int = 50): LfmUserRecentTracksResponse =
        guardedGet(
            LfmUserRecentTracksResponse.serializer(),
            inspect = { status, body -> if (isLastFmHiddenListening(status, body)) throw LastFmHiddenListeningException(user) },
        ) { parameter("method", "user.getrecenttracks"); parameter("user", user); parameter("limit", limit); parameter("api_key", apiKey) }

    suspend fun getUserFriends(user: String, apiKey: String, limit: Int = 200): LfmUserFriendsResponse =
        guardedGet(LfmUserFriendsResponse.serializer()) { parameter("method", "user.getfriends"); parameter("user", user); parameter("limit", limit); parameter("api_key", apiKey) }

    suspend fun getChartTopTracks(apiKey: String, limit: Int = 50): LfmChartTopTracksResponse =
        guardedGet(LfmChartTopTracksResponse.serializer()) { parameter("method", "chart.gettoptracks"); parameter("limit", limit); parameter("api_key", apiKey) }

    suspend fun getGeoTopTracks(country: String, apiKey: String, limit: Int = 50): LfmGeoTopTracksResponse =
        guardedGet(LfmGeoTopTracksResponse.serializer()) { parameter("method", "geo.gettoptracks"); parameter("country", country); parameter("limit", limit); parameter("api_key", apiKey) }
}

// ── Response Models ──────────────────────────────────────────────────

@Serializable
data class LfmTrackSearchResponse(
    val results: LfmTrackResults? = null,
)

@Serializable
data class LfmTrackResults(
    val trackmatches: LfmTrackMatches? = null,
)

@Serializable
data class LfmTrackMatches(
    val track: List<LfmTrack> = emptyList(),
)

@Serializable
data class LfmTrack(
    val name: String,
    val artist: String,
    val url: String? = null,
    val listeners: String? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
)

@Serializable
data class LfmAlbumSearchResponse(
    val results: LfmAlbumResults? = null,
)

@Serializable
data class LfmAlbumResults(
    val albummatches: LfmAlbumMatches? = null,
)

@Serializable
data class LfmAlbumMatches(
    val album: List<LfmAlbum> = emptyList(),
)

@Serializable
data class LfmAlbum(
    val name: String,
    val artist: String,
    val url: String? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
)

@Serializable
data class LfmArtistSearchResponse(
    val results: LfmArtistResults? = null,
)

@Serializable
data class LfmArtistResults(
    val artistmatches: LfmArtistMatches? = null,
)

@Serializable
data class LfmArtistMatches(
    val artist: List<LfmArtistSummary> = emptyList(),
)

@Serializable
data class LfmArtistSummary(
    val name: String,
    val listeners: String? = null,
    val mbid: String? = null,
    val url: String? = null,
    val image: List<LfmImage> = emptyList(),
)

@Serializable
data class LfmArtistInfoResponse(
    val artist: LfmArtistDetail? = null,
)

@Serializable
data class LfmArtistDetail(
    val name: String,
    val mbid: String? = null,
    val url: String? = null,
    val image: List<LfmImage> = emptyList(),
    val bio: LfmBio? = null,
    val tags: LfmTags? = null,
    val similar: LfmSimilar? = null,
)

@Serializable
data class LfmBio(
    val summary: String? = null,
    val content: String? = null,
)

@Serializable
data class LfmTags(
    val tag: List<LfmTag> = emptyList(),
)

@Serializable
data class LfmTag(
    val name: String,
)

@Serializable
data class LfmSimilar(
    val artist: List<LfmSimilarArtist> = emptyList(),
)

@Serializable
data class LfmSimilarArtist(
    val name: String,
    val url: String? = null,
    val image: List<LfmImage> = emptyList(),
)

@Serializable
data class LfmSimilarArtistsResponse(
    val similarartists: LfmSimilar? = null,
)

@Serializable
data class LfmAlbumInfoResponse(
    val album: LfmAlbumDetail? = null,
)

@Serializable
data class LfmAlbumDetail(
    val name: String,
    val artist: String,
    val image: List<LfmImage> = emptyList(),
    val tracks: LfmAlbumTracks? = null,
    val mbid: String? = null,
    val wiki: LfmBio? = null,
)

@Serializable
data class LfmAlbumTracks(
    val track: LfmAlbumTrackList = LfmAlbumTrackList(),
)

@Serializable(with = LfmAlbumTrackListSerializer::class)
data class LfmAlbumTrackList(
    val items: List<LfmAlbumTrack> = emptyList(),
)

@Serializable
data class LfmAlbumTrack(
    val name: String,
    val duration: String? = null,
    val artist: LfmAlbumTrackArtist? = null,
    @SerialName("@attr") val attr: LfmTrackAttr? = null,
)

@Serializable
data class LfmAlbumTrackArtist(
    val name: String,
)

@Serializable
data class LfmTrackAttr(
    val rank: Int? = null,
)

@Serializable
data class LfmImage(
    @SerialName("#text") val url: String = "",
    val size: String = "",
) {
    val isUsable: Boolean get() = url.isNotBlank() && !url.contains("2a96cbd8b46e442fc41c2b86b821562f")
}

/** Get the best available image URL from a list of Last.fm images. */
fun List<LfmImage>.bestImageUrl(): String? =
    lastOrNull { it.isUsable }?.url

// --- Artist top tracks / top albums ---

@Serializable
data class LfmTopTracksResponse(
    val toptracks: LfmTopTracks? = null,
)

@Serializable
data class LfmTopTracks(
    val track: List<LfmTopTrack> = emptyList(),
)

@Serializable
data class LfmTopTrack(
    val name: String,
    val duration: String? = null,
    val listeners: String? = null,
    val playcount: String? = null,
    val artist: LfmTopTrackArtist? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
)

@Serializable
data class LfmTopTrackArtist(
    val name: String,
    val mbid: String? = null,
)

@Serializable
data class LfmTopAlbumsResponse(
    val topalbums: LfmTopAlbums? = null,
)

@Serializable
data class LfmTopAlbums(
    val album: List<LfmTopAlbum> = emptyList(),
)

@Serializable
data class LfmTopAlbum(
    val name: String,
    val playcount: String? = null,
    val artist: LfmTopTrackArtist? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
)

// --- User top tracks ---

@Serializable
data class LfmUserTopTracksResponse(
    val toptracks: LfmUserTopTracks? = null,
)

@Serializable
data class LfmUserTopTracks(
    val track: List<LfmUserTopTrack> = emptyList(),
)

@Serializable
data class LfmUserTopTrack(
    val name: String,
    val playcount: String? = null,
    val artist: LfmUserTopTrackArtist? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
    val url: String? = null,
    @SerialName("@attr") val attr: LfmUserRankAttr? = null,
)

@Serializable
data class LfmUserTopTrackArtist(
    val name: String,
    val mbid: String? = null,
)

// --- User top albums ---

@Serializable
data class LfmUserTopAlbumsResponse(
    val topalbums: LfmUserTopAlbums? = null,
)

@Serializable
data class LfmUserTopAlbums(
    val album: List<LfmUserTopAlbum> = emptyList(),
)

@Serializable
data class LfmUserTopAlbum(
    val name: String,
    val playcount: String? = null,
    val artist: LfmUserTopTrackArtist? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
    val url: String? = null,
    @SerialName("@attr") val attr: LfmUserRankAttr? = null,
)

// --- User top artists ---

@Serializable
data class LfmUserTopArtistsResponse(
    val topartists: LfmUserTopArtists? = null,
)

@Serializable
data class LfmUserTopArtists(
    val artist: List<LfmUserTopArtist> = emptyList(),
)

@Serializable
data class LfmUserTopArtist(
    val name: String,
    val playcount: String? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
    val url: String? = null,
    @SerialName("@attr") val attr: LfmUserRankAttr? = null,
)

// --- User recent tracks ---

@Serializable
data class LfmUserRecentTracksResponse(
    val recenttracks: LfmUserRecentTracks? = null,
)

@Serializable
data class LfmUserRecentTracks(
    @Serializable(with = LfmRecentTrackListSerializer::class)
    val track: List<LfmUserRecentTrack> = emptyList(),
)

@Serializable
data class LfmUserRecentTrack(
    val name: String,
    val artist: LfmUserRecentTrackArtist? = null,
    val album: LfmUserRecentTrackAlbum? = null,
    val image: List<LfmImage> = emptyList(),
    val url: String? = null,
    val mbid: String? = null,
    val date: LfmUserTrackDate? = null,
    @SerialName("@attr") val attr: LfmUserNowPlayingAttr? = null,
)

@Serializable
data class LfmUserRecentTrackArtist(
    @SerialName("#text") val name: String,
    val mbid: String? = null,
)

@Serializable
data class LfmUserRecentTrackAlbum(
    @SerialName("#text") val name: String,
    val mbid: String? = null,
)

@Serializable
data class LfmUserTrackDate(
    val uts: String? = null,
    @SerialName("#text") val text: String? = null,
)

@Serializable
data class LfmUserNowPlayingAttr(
    val nowplaying: String? = null,
)

// --- Shared user attr ---

@Serializable
data class LfmUserRankAttr(
    val rank: String? = null,
)

// --- User info ---

@Serializable
data class LfmUserInfoResponse(
    val user: LfmUserInfo? = null,
)

@Serializable
data class LfmUserInfo(
    val name: String,
    val realname: String? = null,
    val image: List<LfmImage> = emptyList(),
    val url: String? = null,
    val playcount: String? = null,
)

// --- User friends ---

@Serializable
data class LfmUserFriendsResponse(
    val friends: LfmUserFriends? = null,
)

@Serializable
data class LfmUserFriends(
    val user: List<LfmUserInfo> = emptyList(),
)

object LfmAlbumTrackListSerializer : KSerializer<LfmAlbumTrackList> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("LfmAlbumTrackList")

    override fun serialize(encoder: Encoder, value: LfmAlbumTrackList) {
        encoder.encodeSerializableValue(
            ListSerializer(LfmAlbumTrack.serializer()),
            value.items,
        )
    }

    override fun deserialize(decoder: Decoder): LfmAlbumTrackList {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonArray -> {
                val items = element.map { jsonDecoder.json.decodeFromJsonElement(LfmAlbumTrack.serializer(), it) }
                LfmAlbumTrackList(items)
            }
            is JsonObject -> {
                val item = jsonDecoder.json.decodeFromJsonElement(LfmAlbumTrack.serializer(), element)
                LfmAlbumTrackList(listOf(item))
            }
            else -> LfmAlbumTrackList()
        }
    }
}

/**
 * Last.fm returns `track` as either a single object (1 track) or an array (multiple tracks).
 * This serializer handles both cases, matching LfmAlbumTrackListSerializer.
 */
object LfmRecentTrackListSerializer : KSerializer<List<LfmUserRecentTrack>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("LfmRecentTrackList")

    override fun serialize(encoder: Encoder, value: List<LfmUserRecentTrack>) {
        encoder.encodeSerializableValue(
            ListSerializer(LfmUserRecentTrack.serializer()),
            value,
        )
    }

    override fun deserialize(decoder: Decoder): List<LfmUserRecentTrack> {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonArray -> element.map {
                jsonDecoder.json.decodeFromJsonElement(LfmUserRecentTrack.serializer(), it)
            }
            is JsonObject -> listOf(
                jsonDecoder.json.decodeFromJsonElement(LfmUserRecentTrack.serializer(), element),
            )
            else -> emptyList()
        }
    }
}

// --- Chart / Geo top tracks ---

@Serializable
data class LfmChartTopTracksResponse(
    val tracks: LfmChartTracks? = null,
)

@Serializable
data class LfmChartTracks(
    val track: List<LfmChartTrack> = emptyList(),
)

@Serializable
data class LfmGeoTopTracksResponse(
    val tracks: LfmGeoTracks? = null,
)

@Serializable
data class LfmGeoTracks(
    val track: List<LfmChartTrack> = emptyList(),
)

@Serializable
data class LfmChartTrack(
    val name: String,
    val artist: LfmChartTrackArtist? = null,
    val url: String? = null,
    val listeners: String? = null,
    val playcount: String? = null,
    val image: List<LfmImage> = emptyList(),
    val mbid: String? = null,
)

@Serializable
data class LfmChartTrackArtist(
    val name: String,
    val mbid: String? = null,
    val url: String? = null,
)

// --- Track info (track.getInfo) ---

@Serializable
data class LfmTrackInfoResponse(
    val track: LfmTrackInfoDetail? = null,
)

@Serializable
data class LfmTrackInfoDetail(
    val name: String? = null,
    val artist: LfmTrackInfoArtist? = null,
    val album: LfmTrackInfoAlbum? = null,
)

@Serializable
data class LfmTrackInfoArtist(
    val name: String? = null,
)

@Serializable
data class LfmTrackInfoAlbum(
    val title: String? = null,
    val image: List<LfmImage> = emptyList(),
)

// --- Similar tracks (track.getsimilar) ---

@Serializable
data class LfmSimilarTracksResponse(
    val similartracks: LfmSimilarTracksList? = null,
)

@Serializable
data class LfmSimilarTracksList(
    val track: List<LfmSimilarTrack> = emptyList(),
)

@Serializable
data class LfmSimilarTrack(
    val name: String,
    val artist: LfmSimilarTrackArtist? = null,
    val match: Double? = null,
    val image: List<LfmImage> = emptyList(),
)

@Serializable
data class LfmSimilarTrackArtist(
    val name: String,
)
