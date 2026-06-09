package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
        json.decodeFromString(deserializer, response.bodyAsText())
    }

    suspend fun searchTracks(track: String, apiKey: String, limit: Int = 20): LfmTrackSearchResponse =
        guardedGet(LfmTrackSearchResponse.serializer()) { parameter("method", "track.search"); parameter("track", track); parameter("api_key", apiKey); parameter("limit", limit) }

    suspend fun searchAlbums(album: String, apiKey: String, limit: Int = 10): LfmAlbumSearchResponse =
        guardedGet(LfmAlbumSearchResponse.serializer()) { parameter("method", "album.search"); parameter("album", album); parameter("api_key", apiKey); parameter("limit", limit) }

    suspend fun searchArtists(artist: String, apiKey: String, limit: Int = 10): LfmArtistSearchResponse =
        guardedGet(LfmArtistSearchResponse.serializer()) { parameter("method", "artist.search"); parameter("artist", artist); parameter("api_key", apiKey); parameter("limit", limit) }

    suspend fun getArtistInfo(artist: String, apiKey: String): LfmArtistInfoResponse =
        guardedGet(LfmArtistInfoResponse.serializer()) { parameter("method", "artist.getinfo"); parameter("artist", artist); parameter("api_key", apiKey) }

    suspend fun getSimilarArtists(artist: String, apiKey: String, limit: Int = 20): LfmSimilarArtistsResponse =
        guardedGet(LfmSimilarArtistsResponse.serializer()) { parameter("method", "artist.getsimilar"); parameter("artist", artist); parameter("api_key", apiKey); parameter("limit", limit) }

    suspend fun getArtistTopTracks(artist: String, apiKey: String, limit: Int = 10): LfmTopTracksResponse =
        guardedGet(LfmTopTracksResponse.serializer()) { parameter("method", "artist.gettoptracks"); parameter("artist", artist); parameter("api_key", apiKey); parameter("limit", limit) }

    suspend fun getArtistTopAlbums(artist: String, apiKey: String, limit: Int = 50): LfmTopAlbumsResponse =
        guardedGet(LfmTopAlbumsResponse.serializer()) { parameter("method", "artist.gettopalbums"); parameter("artist", artist); parameter("api_key", apiKey); parameter("limit", limit) }

    suspend fun getTrackInfo(track: String, artist: String, apiKey: String): LfmTrackInfoResponse =
        guardedGet(LfmTrackInfoResponse.serializer()) { parameter("method", "track.getInfo"); parameter("track", track); parameter("artist", artist); parameter("api_key", apiKey) }

    suspend fun getSimilarTracks(track: String, artist: String, apiKey: String, limit: Int = 20): LfmSimilarTracksResponse =
        guardedGet(LfmSimilarTracksResponse.serializer()) { parameter("method", "track.getsimilar"); parameter("track", track); parameter("artist", artist); parameter("api_key", apiKey); parameter("limit", limit) }

    suspend fun getAlbumInfo(album: String, artist: String, apiKey: String): LfmAlbumInfoResponse =
        guardedGet(LfmAlbumInfoResponse.serializer()) { parameter("method", "album.getinfo"); parameter("album", album); parameter("artist", artist); parameter("api_key", apiKey) }

    suspend fun getUserInfo(user: String, apiKey: String): LfmUserInfoResponse =
        guardedGet(LfmUserInfoResponse.serializer()) { parameter("method", "user.getinfo"); parameter("user", user); parameter("api_key", apiKey) }

    suspend fun getUserTopTracks(user: String, apiKey: String, period: String = "overall", limit: Int = 50): LfmUserTopTracksResponse =
        guardedGet(LfmUserTopTracksResponse.serializer()) { parameter("method", "user.gettoptracks"); parameter("user", user); parameter("period", period); parameter("limit", limit); parameter("api_key", apiKey) }

    suspend fun getUserTopAlbums(user: String, apiKey: String, period: String = "overall", limit: Int = 50): LfmUserTopAlbumsResponse =
        guardedGet(LfmUserTopAlbumsResponse.serializer()) { parameter("method", "user.gettopalbums"); parameter("user", user); parameter("period", period); parameter("limit", limit); parameter("api_key", apiKey) }

    suspend fun getUserTopArtists(user: String, apiKey: String, period: String = "overall", limit: Int = 50): LfmUserTopArtistsResponse =
        guardedGet(LfmUserTopArtistsResponse.serializer()) { parameter("method", "user.gettopartists"); parameter("user", user); parameter("period", period); parameter("limit", limit); parameter("api_key", apiKey) }

    suspend fun getUserRecentTracks(user: String, apiKey: String, limit: Int = 50): LfmUserRecentTracksResponse =
        guardedGet(LfmUserRecentTracksResponse.serializer()) { parameter("method", "user.getrecenttracks"); parameter("user", user); parameter("limit", limit); parameter("api_key", apiKey) }

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
