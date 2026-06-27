package com.parachord.shared.api

import com.parachord.shared.platform.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

    /**
     * Inbound resolution for a `go.parachord.com/<id>` short link (#138) — the
     * reverse of [create]. Returns the stored payload so an opened smart link can
     * be dispatched in-app: prefer [SmartLinkLookupResult.deeplinkOrNull] (the
     * wrapped `parachord://` action) and fall back to the per-service [urls].
     *
     * Tolerant by design: the `/api/lookup` endpoint is an open seam (the backend
     * may not expose it yet) — any non-2xx / transport error / unparseable body
     * yields null so the caller can fall through to opening the page in a browser.
     * Lenient JSON (`ignoreUnknownKeys`) so server-side field additions don't break
     * the client.
     */
    suspend fun lookup(id: String): SmartLinkLookupResult? = try {
        val response = httpClient.get("${BASE_URL}api/lookup") { parameter("id", id) }
        if (!response.status.isSuccess()) {
            Log.w(TAG, "smart-link lookup HTTP ${response.status.value} for id=$id"); null
        } else {
            lenientJson.decodeFromString<SmartLinkLookupResult>(response.bodyAsText())
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "smart-link lookup failed for id=$id: ${e.message}"); null
    }
}

private const val TAG = "SmartLinksClient"
private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

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

/**
 * The stored payload behind a `go.parachord.com/<id>` short link (#138). Fields
 * mirror what [create] persists; all optional since the lookup endpoint's exact
 * shape is server-owned. [deeplinkOrNull] is the wrapped `parachord://` action
 * (preferred for in-app dispatch).
 */
@Serializable
data class SmartLinkLookupResult(
    val id: String? = null,
    val type: String? = null,
    val title: String? = null,
    val artist: String? = null,
    /** The `parachord://` deeplink this short link wraps, if the server stored one. */
    val deeplink: String? = null,
    val uri: String? = null,
    val urls: Map<String, String>? = null,
    val tracks: List<SmartLinkTrack>? = null,
) {
    val deeplinkOrNull: String?
        get() = deeplink?.takeIf { it.startsWith("parachord://") }
            ?: uri?.takeIf { it.startsWith("parachord://") }
}
