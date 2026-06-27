package com.parachord.shared.deeplink

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses `parachord://` deep links into the shared [DeepLinkAction] model.
 *
 * Ports the subset of Android's `DeepLinkHandler.parse` that iOS dispatches:
 * navigation, simple playback (play/control/queue/shuffle/volume), listen-along,
 * import, AND protocol play (album/playlist/radio, #256/#930). External
 * Spotify/Apple HTTP URL resolution is still Android-only (needs ExternalLinkResolver).
 *
 * Host + path + query-param names match Android's `DeepLinkHandler` exactly — keep
 * them in lockstep. When Android's handler is migrated to delegate here, this
 * becomes the single cross-platform parser.
 */
object DeepLinkParser {

    fun parse(uri: DeepLinkUri): DeepLinkAction {
        if (uri.scheme != "parachord") return DeepLinkAction.Unknown(raw(uri))
        val host = uri.host ?: return DeepLinkAction.Unknown(raw(uri))
        val path = uri.pathSegments
        fun q(name: String) = uri.queryParam(name)

        return when (host) {
            // ── Playback ──
            "play" -> parsePlayHost(uri, path, ::q)
            "control" -> path.firstOrNull()?.let { DeepLinkAction.Control(it) }
                ?: DeepLinkAction.Unknown(raw(uri))
            "queue" -> when (path.firstOrNull()) {
                "add" -> {
                    val artist = q("artist")
                    val title = q("title")
                    if (!artist.isNullOrBlank() && !title.isNullOrBlank()) {
                        DeepLinkAction.QueueAdd(artist, title, q("album"))
                    } else {
                        DeepLinkAction.Unknown(raw(uri))
                    }
                }
                "clear" -> DeepLinkAction.QueueClear
                else -> DeepLinkAction.Unknown(raw(uri))
            }
            "shuffle" -> when (path.firstOrNull()) {
                "on" -> DeepLinkAction.Shuffle(true)
                "off" -> DeepLinkAction.Shuffle(false)
                else -> DeepLinkAction.Unknown(raw(uri))
            }
            "volume" -> path.firstOrNull()?.toIntOrNull()
                ?.let { DeepLinkAction.Volume(it.coerceIn(0, 100)) }
                ?: DeepLinkAction.Unknown(raw(uri))

            // ── Listen-along ──
            "listen-along" -> {
                val service = q("service")
                val user = q("user")
                if (!service.isNullOrBlank() && !user.isNullOrBlank()) {
                    DeepLinkAction.ListenAlong(service, user)
                } else {
                    DeepLinkAction.Unknown(raw(uri))
                }
            }

            // ── Navigation ──
            "home" -> DeepLinkAction.NavigateHome
            "artist" -> path.firstOrNull()?.let { DeepLinkAction.NavigateArtist(it, q("tab")) }
                ?: DeepLinkAction.Unknown(raw(uri))
            "album" -> if (path.size >= 2) DeepLinkAction.NavigateAlbum(path[0], path[1])
                else DeepLinkAction.Unknown(raw(uri))
            "library" -> DeepLinkAction.NavigateLibrary(q("tab"))
            "history" -> DeepLinkAction.NavigateHistory(q("tab"), q("period"))
            "friend" -> path.firstOrNull()?.let { DeepLinkAction.NavigateFriend(it, q("tab")) }
                ?: DeepLinkAction.Unknown(raw(uri))
            "recommendations" -> DeepLinkAction.NavigateRecommendations(q("tab"))
            "charts" -> DeepLinkAction.NavigateCharts
            "critics-picks" -> DeepLinkAction.NavigateCriticalDarlings
            "playlists" -> DeepLinkAction.NavigatePlaylists
            "playlist" -> path.firstOrNull()?.let { DeepLinkAction.NavigatePlaylist(it) }
                ?: DeepLinkAction.Unknown(raw(uri))
            "settings" -> DeepLinkAction.NavigateSettings(q("tab"))
            "search" -> DeepLinkAction.NavigateSearch(q("q"), q("source"))
            "chat" -> DeepLinkAction.NavigateChat(q("prompt"))

            // ── Import (parsed; dispatch deferred on iOS) ──
            "import" -> q("url")?.takeIf { it.isNotBlank() }
                ?.let { DeepLinkAction.ImportPlaylist(it) }
                ?: DeepLinkAction.Unknown(raw(uri))

            else -> DeepLinkAction.Unknown(raw(uri))
        }
    }

    /**
     * `parachord://play[/album|/playlist|/radio]` (#256/#930). Mirrors Android
     * `DeepLinkHandler.parsePlayHost`. The sub-action is the first path segment,
     * or the `?type=` query param as a fallback (the website's HTTPS bounce may
     * emit `parachord://play?type=playlist&url=…` instead of the path form).
     */
    private fun parsePlayHost(uri: DeepLinkUri, path: List<String>, q: (String) -> String?): DeepLinkAction {
        return when (val sub = path.firstOrNull() ?: q("type")) {
            null -> {
                val artist = q("artist")
                val title = q("title")
                if (!artist.isNullOrBlank() && !title.isNullOrBlank()) DeepLinkAction.Play(artist, title)
                else DeepLinkAction.Unknown(raw(uri))
            }
            "album" -> parseProtocolPlayInput(q)?.let { DeepLinkAction.PlayAlbum(it) } ?: DeepLinkAction.Unknown(raw(uri))
            "playlist" -> parseProtocolPlayInput(q)?.let {
                DeepLinkAction.PlayPlaylist(
                    input = it,
                    title = q("title"),
                    creator = q("creator"),
                    shuffle = q("shuffle") == "1",
                )
            } ?: DeepLinkAction.Unknown(raw(uri))
            "radio" -> parsePlayRadio(uri, q)
            else -> DeepLinkAction.Unknown(raw(uri))
        }
    }

    /** Build a [ProtocolPlayInput] from query params; null if no usable identifier. */
    private fun parseProtocolPlayInput(q: (String) -> String?): ProtocolPlayInput? {
        val tracks = q("tracks")?.let {
            try { decodeInlineTracks(it) } catch (e: IllegalArgumentException) { return null }
        }
        val input = ProtocolPlayInput(
            mbid = q("mbid"), spotify = q("spotify"), applemusic = q("applemusic"),
            url = q("url"), tracks = tracks, artist = q("artist"), title = q("title"),
        )
        if (input.mbid.isNullOrBlank() && input.spotify.isNullOrBlank() &&
            input.applemusic.isNullOrBlank() && input.url.isNullOrBlank() &&
            input.tracks.isNullOrEmpty() && input.artist.isNullOrBlank()
        ) return null
        return input
    }

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
                artist = artist, title = title,
                album = (obj["album"] as? JsonPrimitive)?.contentOrNull,
                mbid = (obj["mbid"] as? JsonPrimitive)?.contentOrNull,
                isrc = (obj["isrc"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }

    /** `parachord://play/radio` — Mode B (artist seed) / Mode C (pool). Mirrors Android. */
    private fun parsePlayRadio(uri: DeepLinkUri, q: (String) -> String?): DeepLinkAction {
        val input = parseProtocolPlayInput(q)
        val refillUrl = q("refill") ?: q("refillUrl")
        val name = q("name")
        val shuffle = q("shuffle") == "1"
        val artist = q("artist")
        val title = q("title")
        val hasUrl = !input?.url.isNullOrBlank()
        val hasTracks = input?.tracks?.isNotEmpty() == true
        val hasArtist = !artist.isNullOrBlank()
        val mode: RadioMode = when {
            hasArtist && !hasUrl && !hasTracks -> RadioMode.ArtistSeed(artist!!, title)
            hasUrl || hasTracks -> RadioMode.PoolBased
            else -> return DeepLinkAction.Unknown(raw(uri))
        }
        return DeepLinkAction.PlayRadio(mode = mode, input = input, refillUrl = refillUrl, name = name, shuffle = shuffle)
    }

    private fun raw(uri: DeepLinkUri): String =
        "${uri.scheme}://${uri.host ?: ""}/${uri.pathSegments.joinToString("/")}"
}
