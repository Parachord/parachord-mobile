package com.parachord.android.deeplink

import android.net.Uri
import com.parachord.shared.platform.Log
import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ExternalUrlParser
import com.parachord.shared.deeplink.ProtocolPlayInput
import com.parachord.shared.deeplink.ProtocolTrack
import com.parachord.shared.deeplink.RadioMode
import com.parachord.shared.deeplink.decodeBase64Utf8Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "DeepLinkHandler"

/**
 * Parses incoming URIs into [DeepLinkAction]s.
 *
 * Supports:
 * - parachord:// protocol URLs (play, control, queue, shuffle, volume, navigation, import)
 * - Spotify web URLs (open.spotify.com/track/, /album/, /playlist/, /artist/)
 * - Spotify URIs (spotify:track:, spotify:album:, etc.)
 * - Apple Music URLs (music.apple.com/.../album/, /song/, /playlist/)
 *
 * Desktop equivalent: protocol URL handler in app.js:9648-10122,
 * external URL parsing in resolver-loader.js:217-413
 */
class DeepLinkHandler constructor() {

    companion object {
        /**
         * Max length for deep-link query parameters. Prevents abuse via
         * extremely long strings in chat prompts, search queries, artist
         * names, etc. that could consume excessive memory or be used for
         * fingerprinting. Normal values are well under 1 KB.
         * security: L3
         */
        private const val MAX_PARAM_LENGTH = 2000
    }

    /** Clamp a query parameter to [MAX_PARAM_LENGTH]. */
    private fun Uri.clampedParam(name: String): String? =
        getQueryParameter(name)?.take(MAX_PARAM_LENGTH)

    fun parse(uri: Uri): DeepLinkAction {
        Log.d(TAG, "Parsing URI: $uri")

        return when (uri.scheme) {
            "parachord" -> parseParachord(uri)
            "spotify" -> parseSpotifyUri(uri)
            "https", "http" -> parseHttpUrl(uri)
            else -> DeepLinkAction.Unknown(uri.toString())
        }
    }

    private fun parseParachord(uri: Uri): DeepLinkAction {
        val host = uri.host ?: return DeepLinkAction.Unknown(uri.toString())
        val pathSegments = uri.pathSegments

        return when (host) {
            "auth" -> DeepLinkAction.OAuthCallback(uri.toString())

            "play" -> parsePlayHost(uri, pathSegments)

            "listen-along" -> {
                // parachord://listen-along?service=listenbrainz&user=jesse
                // (Phase 3 will wire the actual mirror; the action is emitted now.)
                val service = uri.clampedParam("service") ?: return DeepLinkAction.Unknown(uri.toString())
                val user = uri.clampedParam("user") ?: return DeepLinkAction.Unknown(uri.toString())
                DeepLinkAction.ListenAlong(service, user)
            }

            "control" -> {
                val action = pathSegments.firstOrNull() ?: return DeepLinkAction.Unknown(uri.toString())
                DeepLinkAction.Control(action)
            }

            "queue" -> {
                when (pathSegments.firstOrNull()) {
                    "add" -> {
                        val artist = uri.clampedParam("artist") ?: return DeepLinkAction.Unknown(uri.toString())
                        val title = uri.clampedParam("title") ?: return DeepLinkAction.Unknown(uri.toString())
                        val album = uri.clampedParam("album")
                        DeepLinkAction.QueueAdd(artist, title, album)
                    }
                    "clear" -> DeepLinkAction.QueueClear
                    else -> DeepLinkAction.Unknown(uri.toString())
                }
            }

            "shuffle" -> {
                when (pathSegments.firstOrNull()) {
                    "on" -> DeepLinkAction.Shuffle(enabled = true)
                    "off" -> DeepLinkAction.Shuffle(enabled = false)
                    else -> DeepLinkAction.Unknown(uri.toString())
                }
            }

            "volume" -> {
                val level = pathSegments.firstOrNull()?.toIntOrNull()
                    ?: return DeepLinkAction.Unknown(uri.toString())
                DeepLinkAction.Volume(level.coerceIn(0, 100))
            }

            "home" -> DeepLinkAction.NavigateHome
            "artist" -> {
                val name = pathSegments.firstOrNull() ?: return DeepLinkAction.Unknown(uri.toString())
                val tab = pathSegments.getOrNull(1)
                DeepLinkAction.NavigateArtist(name, tab)
            }
            "album" -> {
                val artist = pathSegments.getOrNull(0) ?: return DeepLinkAction.Unknown(uri.toString())
                val title = pathSegments.getOrNull(1) ?: return DeepLinkAction.Unknown(uri.toString())
                DeepLinkAction.NavigateAlbum(artist, title)
            }
            "library" -> {
                val tab = pathSegments.firstOrNull()
                DeepLinkAction.NavigateLibrary(tab)
            }
            "history" -> {
                val tab = pathSegments.firstOrNull()
                val period = uri.clampedParam("period")
                DeepLinkAction.NavigateHistory(tab, period)
            }
            "friend" -> {
                val id = pathSegments.firstOrNull() ?: return DeepLinkAction.Unknown(uri.toString())
                val tab = pathSegments.getOrNull(1)
                DeepLinkAction.NavigateFriend(id, tab)
            }
            "recommendations" -> {
                val tab = pathSegments.firstOrNull()
                DeepLinkAction.NavigateRecommendations(tab)
            }
            "charts" -> DeepLinkAction.NavigateCharts
            "critics-picks" -> DeepLinkAction.NavigateCriticalDarlings
            "playlists" -> DeepLinkAction.NavigatePlaylists
            "playlist" -> {
                val id = pathSegments.firstOrNull() ?: return DeepLinkAction.Unknown(uri.toString())
                DeepLinkAction.NavigatePlaylist(id)
            }
            "settings" -> {
                val tab = pathSegments.firstOrNull()
                DeepLinkAction.NavigateSettings(tab)
            }
            "search" -> {
                val query = uri.clampedParam("q")
                val source = uri.clampedParam("source")
                DeepLinkAction.NavigateSearch(query, source)
            }
            "chat" -> {
                val prompt = uri.clampedParam("prompt")
                DeepLinkAction.NavigateChat(prompt)
            }
            "import" -> {
                val url = uri.clampedParam("url") ?: return DeepLinkAction.Unknown(uri.toString())
                DeepLinkAction.ImportPlaylist(url)
            }

            else -> DeepLinkAction.Unknown(uri.toString())
        }
    }

    // ── parachord://play[/album|/playlist|/radio] (#119–#121) ─────────

    /**
     * Dispatch the `parachord://play[/sub]` family by path segment.
     *
     * - Bare `parachord://play?artist=&title=` → existing single-track [DeepLinkAction.Play].
     * - `parachord://play/album?…` → [DeepLinkAction.PlayAlbum].
     * - `parachord://play/playlist?…` → [DeepLinkAction.PlayPlaylist].
     * - `parachord://play/radio?…` → [DeepLinkAction.PlayRadio] (Phase 3 wiring).
     *
     * All input fields (`mbid`, `spotify`, `applemusic`, `url`, `tracks`,
     * `artist`, `title`) flow through into [ProtocolPlayInput]. Per-command
     * gating happens later in `resolveProtocolPlayInput` — the parser is
     * permissive and forwards everything the URI carries.
     */
    private fun parsePlayHost(uri: Uri, pathSegments: List<String>): DeepLinkAction {
        // Sub-action is the path segment, or ?type= as a fallback so the query form
        // (parachord://play?type=playlist&url=…) routes the same as the path form —
        // the website's HTTPS bounce may emit either shape (parachord#930).
        return when (pathSegments.firstOrNull() ?: uri.clampedParam("type")) {
            null -> {
                // Existing single-track shape: requires both fields.
                val artist = uri.clampedParam("artist") ?: return DeepLinkAction.Unknown(uri.toString())
                val title = uri.clampedParam("title") ?: return DeepLinkAction.Unknown(uri.toString())
                DeepLinkAction.Play(artist, title)
            }
            "album" -> {
                val input = parseProtocolPlayInput(uri) ?: return DeepLinkAction.Unknown(uri.toString())
                DeepLinkAction.PlayAlbum(input)
            }
            "playlist" -> {
                val input = parseProtocolPlayInput(uri) ?: return DeepLinkAction.Unknown(uri.toString())
                DeepLinkAction.PlayPlaylist(
                    input = input,
                    title = uri.clampedParam("title"),
                    creator = uri.clampedParam("creator"),
                    shuffle = uri.clampedParam("shuffle") == "1",
                )
            }
            "radio" -> parsePlayRadio(uri)
            else -> DeepLinkAction.Unknown(uri.toString())
        }
    }

    /**
     * Build a [ProtocolPlayInput] from URI query params. Any combination
     * of identifiers is allowed at this layer; the resolver enforces
     * per-command gating downstream.
     *
     * `tracks=` is a UTF-8-base64 JSON array of `{artist,title,album?,mbid?,isrc?}`
     * objects (per `decodeBase64Utf8Json` from #119). Decode failures
     * return null → caller surfaces `Unknown` so the chooser doesn't
     * silently drop the URI.
     */
    private fun parseProtocolPlayInput(uri: Uri): ProtocolPlayInput? {
        val tracks = uri.clampedParam("tracks")?.let { encoded ->
            try {
                decodeInlineTracks(encoded)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Failed to decode tracks= payload: ${e.message}")
                return null
            }
        }
        val input = ProtocolPlayInput(
            mbid = uri.clampedParam("mbid"),
            spotify = uri.clampedParam("spotify"),
            applemusic = uri.clampedParam("applemusic"),
            url = uri.clampedParam("url"),
            tracks = tracks,
            artist = uri.clampedParam("artist"),
            title = uri.clampedParam("title"),
        )
        // Reject inputs that carry no usable identifier at all.
        if (input.mbid.isNullOrBlank() && input.spotify.isNullOrBlank() &&
            input.applemusic.isNullOrBlank() && input.url.isNullOrBlank() &&
            input.tracks.isNullOrEmpty() && input.artist.isNullOrBlank()
        ) {
            return null
        }
        return input
    }

    /**
     * Decode a base64 JSON `tracks=` payload into a list of [ProtocolTrack].
     * Accepts either a bare array `[{artist,title,…},…]` or an object
     * `{tracks:[…]}` for symmetry with [com.parachord.shared.deeplink.parseProtocolTracklist].
     */
    private fun decodeInlineTracks(encoded: String): List<ProtocolTrack> {
        val element = decodeBase64Utf8Json(encoded)
        val arr: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["tracks"] as? JsonArray)
                ?: throw IllegalArgumentException("tracks= JSON object must have a 'tracks' array")
            else -> throw IllegalArgumentException("tracks= must be a JSON array or object")
        }
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val artist = (obj["artist"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            ProtocolTrack(
                artist = artist,
                title = title,
                album = (obj["album"] as? JsonPrimitive)?.contentOrNull,
                mbid = (obj["mbid"] as? JsonPrimitive)?.contentOrNull,
                isrc = (obj["isrc"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }

    /**
     * Parse `parachord://play/radio?...` into [DeepLinkAction.PlayRadio].
     *
     * Mode selection (mirrors desktop `protocol-schema.md` §Radio):
     * - `?artist=` alone (no `?url=`/`?tracks=`) → Mode B ([RadioMode.ArtistSeed]).
     * - `?url=` or `?tracks=` present → Mode C ([RadioMode.PoolBased]). `?url=`
     *   is the initial pool URL; `?tracks=` is an inline base64 JSON pool.
     * - `?refill=` (or legacy alias `?refillUrl=`) is the URL used for
     *   subsequent refills only — NOT a valid initial pool source. Alone, it
     *   yields [DeepLinkAction.Unknown].
     * - none of the above → [DeepLinkAction.Unknown].
     *
     * `?name=` is the station display name; `PlayRadio.refillUrl` carries
     * either `?refill=` or `?refillUrl=` for downstream refill fetches.
     */
    private fun parsePlayRadio(uri: Uri): DeepLinkAction {
        val input = parseProtocolPlayInput(uri)
        // ?refill= is the explicit refill URL for Mode C; ?refillUrl= kept as
        // a legacy alias for back-compat with anything generated against the
        // Phase 2 build.
        val refillUrl = uri.clampedParam("refill") ?: uri.clampedParam("refillUrl")
        val name = uri.clampedParam("name")
        val shuffle = uri.clampedParam("shuffle") == "1"
        val artist = uri.clampedParam("artist")
        val title = uri.clampedParam("title")

        val hasUrl = !input?.url.isNullOrBlank()
        val hasTracks = input?.tracks?.isNotEmpty() == true
        val hasArtist = !artist.isNullOrBlank()

        val mode: RadioMode = when {
            // Mode B: artist seed, no inline pool, no URL pool
            hasArtist && !hasUrl && !hasTracks -> RadioMode.ArtistSeed(artist!!, title)
            // Mode C: explicit pool (URL or inline)
            hasUrl || hasTracks -> RadioMode.PoolBased
            else -> return DeepLinkAction.Unknown(uri.toString())
        }
        return DeepLinkAction.PlayRadio(
            mode = mode,
            input = input,
            refillUrl = refillUrl,
            name = name,
            shuffle = shuffle,
        )
    }

    private fun parseSpotifyUri(uri: Uri): DeepLinkAction {
        // Handle Branch.io referrer format used by Spotify's link sharing:
        // spotify://open?_branch_referrer=<gzip+base64 encoded data>
        // The referrer contains $full_url=https://open.spotify.com/artist/xxx
        if (uri.host == "open" && uri.getQueryParameter("_branch_referrer") != null) {
            val extracted = extractSpotifyUrlFromBranchReferrer(
                uri.getQueryParameter("_branch_referrer")!!
            )
            if (extracted != null) {
                Log.d(TAG, "Extracted Spotify URL from Branch referrer: $extracted")
                return parseSpotifyUrl(Uri.parse(extracted))
            }
        }

        // Standard format: spotify:track:6rqhFgbbKwnb9MLmUQDhG6
        val ssp = uri.schemeSpecificPart ?: return DeepLinkAction.Unknown(uri.toString())
        val parts = ssp.split(":")
        if (parts.size < 2) return DeepLinkAction.Unknown(uri.toString())

        return when (parts[0]) {
            "track" -> DeepLinkAction.SpotifyTrack(parts[1])
            "album" -> DeepLinkAction.SpotifyAlbum(parts[1])
            "playlist" -> DeepLinkAction.SpotifyPlaylist(parts[1])
            "artist" -> DeepLinkAction.SpotifyArtist(parts[1])
            else -> DeepLinkAction.Unknown(uri.toString())
        }
    }

    /**
     * Spotify's link sharing wraps real URLs in a Branch.io referrer:
     * gzip-compressed, base64-encoded data containing $full_url parameter.
     * Decode it to extract the actual open.spotify.com URL.
     */
    private fun extractSpotifyUrlFromBranchReferrer(referrer: String): String? {
        return try {
            val compressed = android.util.Base64.decode(referrer, android.util.Base64.DEFAULT)
            // security: L2 — cap decompressed size to defend against gzip bombs.
            // Normal Branch referrer payloads are ~200 bytes decompressed.
            val gzipStream = java.util.zip.GZIPInputStream(compressed.inputStream())
            val buffer = CharArray(64 * 1024) // 64 KB max
            val reader = gzipStream.bufferedReader()
            val read = reader.read(buffer)
            if (read < 0) return null
            val decompressed = String(buffer, 0, read)
            // Parse the decompressed URL and extract $full_url parameter
            val refUri = Uri.parse(decompressed)
            val fullUrl = refUri.getQueryParameter("\$full_url")
                ?: refUri.getQueryParameter("\$fallback_url")
            // Strip tracking params to get clean Spotify URL
            if (fullUrl != null) {
                val spotifyUri = Uri.parse(fullUrl)
                "${spotifyUri.scheme}://${spotifyUri.host}${spotifyUri.path}"
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode Branch referrer: ${e.message}")
            null
        }
    }

    private fun parseHttpUrl(uri: Uri): DeepLinkAction {
        return when (uri.host) {
            "open.spotify.com" -> parseSpotifyUrl(uri)
            "music.apple.com" -> parseAppleMusicUrl(uri)
            "parachord.com" -> parseParachordHttps(uri)
            else -> DeepLinkAction.Unknown(uri.toString())
        }
    }

    /**
     * Map a Universal-Link / App-Link of the form
     *   `https://parachord.com/<verb>[/<sub>]?<query>`
     * onto the same [DeepLinkAction] the equivalent
     *   `parachord://<verb>[/<sub>]?<query>`
     * URL would produce.
     *
     * Strategy: rewrite the HTTPS URI into the matching custom-scheme URI
     * (first path segment becomes the authority, rest becomes the path,
     * query carries verbatim) and delegate to [parseParachord]. That makes
     * every existing AND future verb HTTPS-compatible for free — adding a
     * new `parachord://` verb in [parseParachord] automatically gains
     * `https://parachord.com/...` coverage without touching this method.
     *
     * Pairs with the matching `<intent-filter android:autoVerify="true">`
     * in AndroidManifest.xml. See `docs/plans/2026-05-21-android-app-links-design.md`.
     */
    private fun parseParachordHttps(uri: Uri): DeepLinkAction {
        val segments = uri.pathSegments
        if (segments.isEmpty()) return DeepLinkAction.Unknown(uri.toString())
        val rebuilt = Uri.Builder()
            .scheme("parachord")
            .authority(segments.first())
            .apply { segments.drop(1).forEach { appendPath(it) } }
            .encodedQuery(uri.encodedQuery)
            .build()
        return parseParachord(rebuilt)
    }

    private fun parseSpotifyUrl(uri: Uri): DeepLinkAction {
        val segments = uri.pathSegments
        if (segments.size < 2) return DeepLinkAction.Unknown(uri.toString())

        // Handle /intl-xx/ prefix (e.g., /intl-en/track/xxx)
        val (type, id) = if (segments[0].startsWith("intl-") && segments.size >= 3) {
            segments[1] to segments[2]
        } else {
            segments[0] to segments[1]
        }

        return when (type) {
            "track" -> DeepLinkAction.SpotifyTrack(id)
            "album" -> DeepLinkAction.SpotifyAlbum(id)
            "playlist" -> DeepLinkAction.SpotifyPlaylist(id)
            "artist" -> DeepLinkAction.SpotifyArtist(id)
            else -> DeepLinkAction.Unknown(uri.toString())
        }
    }

    private fun parseAppleMusicUrl(uri: Uri): DeepLinkAction {
        val segments = uri.pathSegments
        if (segments.size < 3) return DeepLinkAction.Unknown(uri.toString())

        // Skip country code (first segment)
        val type = segments[1]
        val id = segments.lastOrNull() ?: return DeepLinkAction.Unknown(uri.toString())

        val songId = uri.getQueryParameter("i")

        return when (type) {
            "album" -> {
                if (songId != null) {
                    DeepLinkAction.AppleMusicSong(songId)
                } else {
                    DeepLinkAction.AppleMusicAlbum(id)
                }
            }
            "song" -> DeepLinkAction.AppleMusicSong(id)
            "playlist" -> DeepLinkAction.AppleMusicPlaylist(id)
            "artist" -> {
                val artistName = if (segments.size >= 4) segments[2] else id
                DeepLinkAction.NavigateArtist(artistName.replace("-", " "))
            }
            else -> DeepLinkAction.Unknown(uri.toString())
        }
    }
}
