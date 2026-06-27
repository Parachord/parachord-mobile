package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
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

    /**
     * Fetch a PUBLIC Apple Music **catalog** playlist (a `pl.…` id from a
     * `music.apple.com/{storefront}/playlist/…` URL) + its tracks, for playlist
     * URL import (#18). Uses the developer (ES256 JWT) token only — no Music User
     * Token — since catalog playlists are public. Returns null if [developerToken]
     * is blank or the playlist can't be loaded. Paginates the tracks relationship
     * (bounded) so playlists over one page import fully.
     */
    suspend fun getCatalogPlaylist(
        storefront: String,
        playlistId: String,
        developerToken: String,
    ): AmCatalogPlaylistResult? {
        if (developerToken.isBlank()) return null
        val catalogBase = "https://api.music.apple.com"
        val first = try {
            val resp = httpClient.get("$catalogBase/v1/catalog/$storefront/playlists/$playlistId") {
                header("Authorization", "Bearer $developerToken")
                parameter("include", "tracks")
                parameter("limit[tracks]", "100")
            }
            if (!resp.status.isSuccess()) return null
            json.decodeFromString<AmCatalogPlaylistResponse>(resp.bodyAsText())
        } catch (e: Exception) {
            return null
        }
        val pl = first.data.firstOrNull() ?: return null
        val name = pl.attributes?.name?.takeIf { it.isNotBlank() } ?: "Apple Music Playlist"
        val tracks = mutableListOf<AmCatalogTrack>()
        var rel = pl.relationships?.tracks
        var pages = 0
        while (rel != null) {
            rel.data.forEach { song ->
                val a = song.attributes ?: return@forEach
                tracks.add(
                    AmCatalogTrack(
                        id = song.id,
                        title = a.name,
                        artist = a.artistName,
                        album = a.albumName,
                        durationMs = a.durationInMillis,
                        artworkUrl = a.artwork?.url?.let { amArtworkUrl(it) },
                    ),
                )
            }
            val next = rel.next
            if (next == null || pages++ >= 20) break
            rel = try {
                val resp = httpClient.get("$catalogBase$next") {
                    header("Authorization", "Bearer $developerToken")
                }
                if (!resp.status.isSuccess()) break
                json.decodeFromString<AmCatalogTracksResponse>(resp.bodyAsText())
                    .let { AmCatalogTracks(data = it.data, next = it.next) }
            } catch (e: Exception) {
                break
            }
        }
        val artworkUrl = pl.attributes?.artwork?.url?.let { amArtworkUrl(it) } ?: tracks.firstOrNull()?.artworkUrl
        return AmCatalogPlaylistResult(name = name, artworkUrl = artworkUrl, tracks = tracks)
    }

    /** Substitute Apple's `{w}x{h}` artwork-URL template with a 300x300 size. */
    private fun amArtworkUrl(template: String): String =
        template.replace("{w}", "300").replace("{h}", "300")
}

// ── Apple Music catalog-playlist DTOs (#18 URL import) ──────────────────────

/** Flattened catalog-playlist result for the importer. */
data class AmCatalogPlaylistResult(
    val name: String,
    val artworkUrl: String?,
    val tracks: List<AmCatalogTrack>,
)

data class AmCatalogTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val artworkUrl: String?,
)

@Serializable
data class AmCatalogPlaylistResponse(val data: List<AmCatalogPlaylistData> = emptyList())

@Serializable
data class AmCatalogPlaylistData(
    val id: String = "",
    val attributes: AmCatalogPlaylistAttributes? = null,
    val relationships: AmCatalogPlaylistRelationships? = null,
)

@Serializable
data class AmCatalogPlaylistAttributes(
    val name: String = "",
    val artwork: AmCatalogArtwork? = null,
)

@Serializable
data class AmCatalogPlaylistRelationships(val tracks: AmCatalogTracks? = null)

@Serializable
data class AmCatalogTracks(
    val data: List<AmCatPlSong> = emptyList(),
    val next: String? = null,
)

/** The tracks-relationship pagination response (`{ data:[…], next:"…" }`). */
@Serializable
data class AmCatalogTracksResponse(
    val data: List<AmCatPlSong> = emptyList(),
    val next: String? = null,
)

@Serializable
data class AmCatPlSong(
    val id: String = "",
    val attributes: AmCatPlSongAttributes? = null,
)

@Serializable
data class AmCatPlSongAttributes(
    val name: String = "",
    val artistName: String = "",
    val albumName: String? = null,
    val durationInMillis: Long? = null,
    val artwork: AmCatalogArtwork? = null,
)

@Serializable
data class AmCatalogArtwork(val url: String = "")

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
