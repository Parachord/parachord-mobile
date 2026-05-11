package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wrapper for the public Smart Links Cloudflare Workers backend that powers
 * `https://go.parachord.com/<id>` rich share pages — feature.fm /
 * linkfire-style landing pages with title, artwork, and per-service play
 * buttons. Recipients get a rich preview (Open Graph / oEmbed) on Slack,
 * Discord, iMessage, etc.
 *
 * **Playlist-only after the Achordion migration (v0.6.x+).** Track / album /
 * artist shares now route through `AchordionClient` (`achordion.xyz`
 * entity pages with server-side per-service link resolution). This client
 * is retained for the playlist path because Achordion has no playlist
 * entity page yet — `ShareManager.sharePlaylist` still mints
 * `go.parachord.com/<id>` URLs here. Future smart-link-shaped shares
 * (e.g. albums-with-full-tracklist landing pages) could re-use this
 * infrastructure if needed.
 *
 * The `/api/create` endpoint is open (CORS `*`, no auth) — see
 * `smart-links/functions/api/create.js` in the Parachord/parachord repo.
 *
 * **Base URL trap**: production short URLs land on `go.parachord.com`,
 * NOT `links.parachord.app` (which doesn't even resolve) or the Pages
 * default `parachord-links.pages.dev`. Always hit `go.parachord.com` so
 * Android shares stay brand-consistent with desktop.
 */
class SmartLinksClient(private val httpClient: HttpClient) {

    companion object {
        private const val BASE_URL = "https://go.parachord.com/"
    }

    /**
     * POST a new smart link payload. Server enriches missing service URLs
     * (Apple Music, SoundCloud, etc.) in the background. Returns the
     * minted short URL.
     *
     * Throws on transport / 4xx / 5xx — `ShareManager` wraps the call in
     * `withTimeout` + try/catch and falls back to the deeplink wrapper
     * silently, so a slow or dead API never holds up the share sheet.
     */
    suspend fun create(request: SmartLinkCreateRequest): SmartLinkCreateResponse {
        val response = httpClient.post("${BASE_URL}api/create") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return response.body()
    }
}

@Serializable
data class SmartLinkCreateRequest(
    val title: String,
    val artist: String? = null,
    val creator: String? = null,
    @SerialName("albumArt") val albumArt: String? = null,
    /** "track" | "album" | "playlist". Server enriches missing service URLs in background. */
    val type: String = "track",
    /** For tracks: per-service URL map. Required when type=track. */
    val urls: Map<String, String>? = null,
    /** For albums/playlists: tracklist. Required when type=album|playlist. */
    val tracks: List<SmartLinkTrack>? = null,
)

@Serializable
data class SmartLinkTrack(
    val title: String,
    val artist: String? = null,
    val duration: Long? = null,
    val trackNumber: Int? = null,
    val urls: Map<String, String> = emptyMap(),
    @SerialName("albumArt") val albumArt: String? = null,
)

@Serializable
data class SmartLinkCreateResponse(
    val id: String,
    val url: String,
)
