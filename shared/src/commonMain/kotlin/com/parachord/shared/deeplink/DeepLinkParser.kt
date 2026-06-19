package com.parachord.shared.deeplink

/**
 * Parses `parachord://` deep links into the shared [DeepLinkAction] model.
 *
 * This is a faithful port of the subset of Android's `DeepLinkHandler.parse`
 * that iOS currently dispatches (#228): navigation, simple playback
 * (play/control/queue/shuffle/volume), and listen-along. Protocol play
 * (album/playlist/radio), playlist import, and external Spotify/Apple URL
 * resolution are intentionally NOT parsed here yet — they need iOS machinery
 * that doesn't exist (tracklist resolver, importer, ExternalLinkResolver) and
 * are tracked as a follow-up. Those patterns return [DeepLinkAction.Unknown]
 * so the dispatcher can surface a "not supported yet" ack rather than failing
 * silently.
 *
 * Host + path + query-param names match Android exactly — keep them in lockstep.
 * When Android's handler is migrated to delegate here (follow-up), this becomes
 * the single cross-platform parser.
 */
object DeepLinkParser {

    fun parse(uri: DeepLinkUri): DeepLinkAction {
        if (uri.scheme != "parachord") return DeepLinkAction.Unknown(raw(uri))
        val host = uri.host ?: return DeepLinkAction.Unknown(raw(uri))
        val path = uri.pathSegments
        fun q(name: String) = uri.queryParam(name)

        return when (host) {
            // ── Playback ──
            "play" -> {
                // parachord://play?artist=&title=  (single track).
                // parachord://play/album|playlist|radio  → deferred (Unknown).
                if (path.isEmpty()) {
                    val artist = q("artist")
                    val title = q("title")
                    if (!artist.isNullOrBlank() && !title.isNullOrBlank()) {
                        DeepLinkAction.Play(artist, title)
                    } else {
                        DeepLinkAction.Unknown(raw(uri))
                    }
                } else {
                    DeepLinkAction.Unknown(raw(uri)) // play/album|playlist|radio — follow-up
                }
            }
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

    private fun raw(uri: DeepLinkUri): String =
        "${uri.scheme}://${uri.host ?: ""}/${uri.pathSegments.joinToString("/")}"
}
