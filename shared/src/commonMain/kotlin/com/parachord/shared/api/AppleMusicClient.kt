package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Thrown by [AppleMusicClient.search] when iTunes Search responds with
 * HTTP 429. Callers (notably [com.parachord.shared.sync.AppleMusicSyncProvider])
 * use this to flip a session kill-switch so subsequent searches no-op
 * for the remainder of the sync — better N-1 hydrated tracks now than
 * hammering Apple until they black-list us.
 */
class ItunesRateLimitedException : Exception("iTunes Search returned HTTP 429")

/**
 * iTunes Search/Lookup API client — no authentication required.
 * Used for looking up Apple Music content by ID (from shared URLs)
 * and searching the iTunes catalog.
 *
 * Base URL: https://itunes.apple.com/
 */
class AppleMusicClient(private val httpClient: HttpClient) {

    companion object {
        private const val BASE_URL = "https://itunes.apple.com"
    }

    // iTunes Search/Lookup respond with `Content-Type: text/javascript`, NOT
    // application/json — so Ktor's ContentNegotiation refuses to deserialize and
    // `.body()` throws NoTransformationFoundException (every search silently
    // returned null). Read the text and decode manually instead.
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(id: String, entity: String? = null): AppleMusicLookupResponse {
        val response = httpClient.get("$BASE_URL/lookup") {
            parameter("id", id)
            if (entity != null) parameter("entity", entity)
        }
        if (!response.status.isSuccess()) return AppleMusicLookupResponse()
        return json.decodeFromString(response.bodyAsText())
    }

    suspend fun search(
        term: String,
        media: String = "music",
        entity: String = "song",
        limit: Int = 25,
    ): AppleMusicSearchResponse {
        val response: HttpResponse = httpClient.get("$BASE_URL/search") {
            parameter("term", term)
            parameter("media", media)
            parameter("entity", entity)
            parameter("limit", limit)
        }
        // iTunes Search returns 429 with an HTML body when rate-limited.
        // Surface a typed exception so [AppleMusicSyncProvider] can flip
        // its session kill-switch instead of letting the body parser
        // throw on every retry.
        if (response.status.value == 429) {
            throw ItunesRateLimitedException()
        }
        // Non-429 non-success: return empty so the caller treats it as
        // "no match" rather than throwing during body parsing.
        if (!response.status.isSuccess()) {
            return AppleMusicSearchResponse()
        }
        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * Apple Music "most-played" charts via the public marketing-tools RSS endpoint
     * (separate host from itunes.apple.com — no auth, but returns a different
     * `feed.results` shape).
     *
     * Powers the Pop of the Tops / Charts screen.
     */
    suspend fun mostPlayedAlbums(country: String = "us", limit: Int = 50): AppleChartsFeedResponse =
        httpClient.get("https://rss.marketingtools.apple.com/api/v2/$country/music/most-played/$limit/albums.json").body()

    suspend fun mostPlayedSongs(country: String = "us", limit: Int = 50): AppleChartsFeedResponse =
        httpClient.get("https://rss.marketingtools.apple.com/api/v2/$country/music/most-played/$limit/songs.json").body()
}

// ── Response Models ──────────────────────────────────────────────────

@Serializable
data class AppleMusicLookupResponse(
    val resultCount: Int = 0,
    val results: List<AppleMusicItem> = emptyList(),
)

@Serializable
data class AppleMusicSearchResponse(
    val resultCount: Int = 0,
    val results: List<AppleMusicItem> = emptyList(),
)

@Serializable
data class AppleMusicItem(
    val wrapperType: String? = null,
    val kind: String? = null,
    val trackId: Long? = null,
    val collectionId: Long? = null,
    val artistId: Long? = null,
    val trackName: String? = null,
    val collectionName: String? = null,
    val artistName: String? = null,
    @SerialName("artworkUrl100") val artworkUrl100: String? = null,
    val trackTimeMillis: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val collectionType: String? = null,
)

/** Extension: get high-res artwork URL from 100x100 thumbnail. */
fun AppleMusicItem.highResArtworkUrl(): String? =
    artworkUrl100?.replace("100x100", "600x600")

/** Extension: best image URL from a list of lookup results. */
fun List<AppleMusicItem>.bestImageUrl(): String? =
    firstOrNull()?.artworkUrl100?.replace("100x100", "600x600")

// ── Marketing-Tools RSS feed models (most-played charts) ─────────────
//
// Different shape from the iTunes Search/Lookup responses above —
// `feed.results` is the relevant array. Genres are objects with `name`,
// `genreId`, `url`, but only `name` matters for our chart entries.

@Serializable
data class AppleChartsFeedResponse(
    val feed: AppleChartsFeed? = null,
)

@Serializable
data class AppleChartsFeed(
    val results: List<AppleChartsItem> = emptyList(),
)

@Serializable
data class AppleChartsItem(
    val name: String = "",
    val artistName: String = "",
    @SerialName("artworkUrl100") val artworkUrl100: String? = null,
    val url: String? = null,
    val genres: List<AppleChartsGenre> = emptyList(),
)

@Serializable
data class AppleChartsGenre(val name: String = "")
