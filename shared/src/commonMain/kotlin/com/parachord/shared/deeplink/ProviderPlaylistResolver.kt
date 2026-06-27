package com.parachord.shared.deeplink

import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.platform.Log
import com.parachord.shared.playlist.XspfParser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "ProviderPlaylistResolver"
private const val MAX_BODY = 100 * 1024

// Spotify embed-page fallback (parachord-mobile#286): editorial/algorithmic
// playlists (37i9…) return empty/404 from the Web API for third-party apps, but
// the public embed page server-renders the tracklist as a __NEXT_DATA__ JSON blob.
private const val SPOTIFY_EMBED_MAX_BODY = 4 * 1024 * 1024 // full HTML page, not a tracklist doc
private val NEXT_DATA_RE = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
private val embedJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Resolve a `parachord://play/playlist?url=<provider-url>` target into tracks
 * (parachord#930) — the mobile counterpart of desktop's `resolveProviderPlaylistUrl`
 * (app.js). Provider playlist *pages* (Spotify / Apple Music / Achordion) are web
 * pages, not tracklist documents, so they go through the provider path here; a
 * non-provider URL returns null so the caller falls back to a hosted XSPF/JSPF
 * document parse.
 *
 * Shared by both platforms via [DefaultProtocolInputResolver] (iOS) and
 * `AndroidProtocolInputResolver` (Android). Apple uses the shared catalog
 * (`getCatalogPlaylist`) on both platforms (devToken from `AppConfig`).
 *
 * **SoundCloud is not yet supported on mobile** — there's no native SoundCloud
 * playlist-fetch client (unlike desktop's `.axe` `lookupPlaylist`). Both the
 * canonical `/sets/` URL and the `on.soundcloud.com` short link throw a clear
 * "not supported yet" error rather than silently HTML-parsing the page. Adding a
 * SoundCloud playlist client (or `.axe` lookup plumbing) is a tracked follow-up.
 *
 * Semantics mirror desktop: a *recognized* provider playlist that fails to load
 * **throws** (surfaced as a toast); an *unrecognized* URL returns **null** (caller
 * parses it as a tracklist document).
 */
class ProviderPlaylistResolver(
    private val spotifyClient: SpotifyClient,
    private val appleMusicClient: AppleMusicClient,
    private val httpClient: HttpClient,
    private val appleMusicDeveloperToken: String,
    private val spotifyAccessToken: suspend () -> String?,
) {
    suspend fun resolve(rawUrl: String): ResolvedProtocolPlay? {
        when (val kind = classifyPlaylistUrl(rawUrl)) {
            is PlaylistUrlKind.Achordion -> return fetchAchordionXspf(kind.xspfUrl)
            // SoundCloud short link → would 302 to /sets/, but mobile can't fetch
            // a SoundCloud playlist anyway, so surface the gap directly.
            PlaylistUrlKind.SoundCloudShort ->
                throw IllegalStateException("SoundCloud playlists aren't supported on mobile yet.")
            PlaylistUrlKind.Standard -> Unit // sniff below
        }

        extractSpotifyPlaylistId(rawUrl)?.let { return fetchSpotifyPlaylist(it) }
        extractAppleMusicPlaylist(rawUrl)?.let { (sf, id) -> return fetchAppleMusicPlaylist(sf, id) }
        if (isSoundCloudSet(rawUrl)) {
            throw IllegalStateException("SoundCloud playlists aren't supported on mobile yet.")
        }
        return null // not a provider playlist page → caller parses as a tracklist document
    }

    // ── Achordion → public XSPF endpoint, then the standard tracklist parser ──
    private suspend fun fetchAchordionXspf(xspfUrl: String): ResolvedProtocolPlay {
        val resp = httpClient.get(xspfUrl)
        if (!resp.status.isSuccess()) throw IllegalStateException("Achordion playlist fetch failed: ${resp.status.value}")
        val body = resp.bodyAsText()
        if (body.length > MAX_BODY) throw IllegalArgumentException("Playlist response too large (max 100KB)")
        val parsed = parseProtocolTracklist(body, ::parseXspf)
        if (parsed.tracks.isEmpty()) throw IllegalStateException("Achordion playlist had no playable tracks.")
        return ResolvedProtocolPlay(displayName = parsed.title ?: parsed.creator ?: "Playlist", tracks = parsed.tracks)
    }

    private fun parseXspf(content: String): ParsedProtocolTracklist {
        val pl = XspfParser.parse(content)
        return ParsedProtocolTracklist(
            title = pl.title,
            creator = pl.creator,
            tracks = pl.tracks.map { ProtocolTrack(artist = it.artist, title = it.title, album = it.album) },
        )
    }

    // ── Spotify playlist page → Web API (rich), else embed fallback (editorial) ──
    private suspend fun fetchSpotifyPlaylist(playlistId: String): ResolvedProtocolPlay {
        if (spotifyAccessToken().isNullOrBlank()) {
            throw IllegalStateException("Connect Spotify in Settings to play Spotify playlists.")
        }
        // Prefer the Web API (real track IDs / album). Editorial & algorithmic
        // playlists (37i9…) return empty or 404 for third-party apps — fall through
        // to the embed page below (parachord-mobile#286).
        val fromApi = try {
            val full = spotifyClient.getPlaylist(playlistId)
            val tracks = mutableListOf<ProtocolTrack>()
            var offset = 0
            while (true) {
                val page = spotifyClient.getPlaylistTracks(playlistId, limit = 100, offset = offset)
                if (page.items.isEmpty()) break
                page.items.forEach { item ->
                    val t = item.track ?: return@forEach
                    if (t.name.isNullOrBlank()) return@forEach
                    tracks.add(ProtocolTrack(artist = t.artistName, title = t.name.orEmpty(), album = t.album?.name))
                }
                offset += page.items.size
                if (page.next == null) break
            }
            if (tracks.isNotEmpty()) ResolvedProtocolPlay(displayName = full.name ?: "Spotify Playlist", tracks = tracks)
            else null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Spotify Web API playlist fetch failed for $playlistId (${e.message}); trying embed fallback")
            null
        }
        if (fromApi != null) return fromApi

        fetchSpotifyEmbedPlaylist(playlistId)?.let { return it }
        throw IllegalStateException("Couldn't load that Spotify playlist — it may be private, empty, or region-locked.")
    }

    /**
     * Editorial/algorithmic fallback (#286): the public embed page
     * (`open.spotify.com/embed/playlist/<id>`) server-renders the tracklist as a
     * `__NEXT_DATA__` JSON blob — no API, no auth, no browser. Tracks carry only
     * title + artist (`subtitle`), which is enough for on-the-fly resolution. The
     * undocumented shape can change, so any failure returns null (caller surfaces
     * the normal "couldn't load" message). NOT how the browser extension does it
     * (that scrapes the rendered DOM, which needs a real browser).
     */
    private suspend fun fetchSpotifyEmbedPlaylist(playlistId: String): ResolvedProtocolPlay? {
        return try {
            val resp = httpClient.get("https://open.spotify.com/embed/playlist/$playlistId")
            if (!resp.status.isSuccess()) {
                Log.w(TAG, "Spotify embed HTTP ${resp.status.value} for $playlistId"); return null
            }
            val html = resp.bodyAsText()
            if (html.length > SPOTIFY_EMBED_MAX_BODY) {
                Log.w(TAG, "Spotify embed page too large for $playlistId"); return null
            }
            parseSpotifyEmbed(html)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Spotify embed fetch failed for $playlistId: ${e.message}"); null
        }
    }

    // ── Apple Music playlist page → shared catalog (getCatalogPlaylist) ──
    private suspend fun fetchAppleMusicPlaylist(storefront: String, plId: String): ResolvedProtocolPlay {
        if (appleMusicDeveloperToken.isBlank()) {
            throw IllegalStateException("Apple Music isn't configured for catalog access.")
        }
        val result = appleMusicClient.getCatalogPlaylist(storefront, plId, appleMusicDeveloperToken)
            ?: throw IllegalStateException("Couldn't load that Apple Music playlist — it may be private or unavailable in your region.")
        val tracks = result.tracks.map { s ->
            ProtocolTrack(artist = s.artist, title = s.title, album = s.album)
        }
        if (tracks.isEmpty()) throw IllegalStateException("That Apple Music playlist had no playable tracks.")
        return ResolvedProtocolPlay(displayName = result.name, tracks = tracks, albumArt = result.artworkUrl)
    }

    private fun extractSpotifyPlaylistId(url: String): String? =
        Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?playlist/([a-zA-Z0-9]+)").find(url)?.groupValues?.get(1)

    private fun extractAppleMusicPlaylist(url: String): Pair<String, String>? {
        val m = Regex("music\\.apple\\.com/([a-z]{2})/playlist/(?:[^/]+/)?(pl\\.[a-zA-Z0-9-]+)").find(url) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    private fun isSoundCloudSet(url: String): Boolean =
        Regex("(?:^|//)(?:[a-z0-9-]+\\.)?soundcloud\\.com/[^/]+/sets/").containsMatchIn(url.lowercase())
}

/**
 * Parse a Spotify embed page's `__NEXT_DATA__` JSON →
 * `props.pageProps.state.data.entity.{name,trackList[]}` (parachord-mobile#286).
 * Each track exposes `title` + `subtitle` (artist). Top-level + internal so it's
 * unit-testable against a captured fixture without standing up the resolver.
 * Returns null on any shape mismatch (the blob is undocumented and may change).
 */
internal fun parseSpotifyEmbed(html: String): ResolvedProtocolPlay? {
    val jsonText = NEXT_DATA_RE.find(html)?.groupValues?.get(1) ?: return null
    val entity = try {
        embedJson.parseToJsonElement(jsonText).jsonObject["props"]?.jsonObject
            ?.get("pageProps")?.jsonObject?.get("state")?.jsonObject
            ?.get("data")?.jsonObject?.get("entity")?.jsonObject
    } catch (e: Exception) {
        Log.w(TAG, "Spotify embed __NEXT_DATA__ parse failed: ${e.message}"); return null
    } ?: return null
    val name = entity["name"]?.jsonPrimitive?.contentOrNull
        ?: entity["title"]?.jsonPrimitive?.contentOrNull ?: "Spotify Playlist"
    val list = entity["trackList"]?.jsonArray ?: return null
    val tracks = list.mapNotNull { el ->
        val o = el.jsonObject
        val title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val artist = o["subtitle"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // The embed carries the real Spotify id in `uri` (spotify:track:<id>) —
        // keep it as a resolver hint so the track gets a Spotify source directly
        // instead of a title/artist search (#286).
        val spotifyId = o["uri"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("spotify:track:") }?.removePrefix("spotify:track:")?.takeIf { it.isNotBlank() }
        ProtocolTrack(artist = artist, title = title, spotifyId = spotifyId)
    }
    return if (tracks.isEmpty()) null else ResolvedProtocolPlay(displayName = name, tracks = tracks)
}
