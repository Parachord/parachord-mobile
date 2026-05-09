package com.parachord.shared.deeplink

/**
 * All possible actions from a deep link or external URL.
 * Shared across Android and iOS — platform code maps native URL types to this.
 */
sealed class DeepLinkAction {
    // ── Playback ──
    data class Play(val artist: String, val title: String) : DeepLinkAction()
    data class Control(val action: String) : DeepLinkAction()
    data class QueueAdd(val artist: String, val title: String, val album: String?) : DeepLinkAction()
    data object QueueClear : DeepLinkAction()
    data class Shuffle(val enabled: Boolean) : DeepLinkAction()
    data class Volume(val level: Int) : DeepLinkAction()

    // ── Protocol play handlers (#119 / #120 / #121) ──
    /** `parachord://play/album` — start playback of one resolved album. */
    data class PlayAlbum(val input: ProtocolPlayInput) : DeepLinkAction()

    /**
     * `parachord://play/playlist` — start playback of a hosted or inline
     * playlist. Optional [title] / [creator] are display hints only;
     * [shuffle] toggles shuffle on the new context.
     */
    data class PlayPlaylist(
        val input: ProtocolPlayInput,
        val title: String? = null,
        val creator: String? = null,
        val shuffle: Boolean = false,
    ) : DeepLinkAction()

    /**
     * `parachord://play/radio` — start a radio context. [refillUrl] is the
     * URL the radio engine polls for fresh tracks (Mode A). [input]
     * supplies the initial pool / seed; [mode] selects Mode B (artist seed)
     * or Mode C (pool-based) for non-URL modes.
     */
    data class PlayRadio(
        val mode: RadioMode,
        val input: ProtocolPlayInput? = null,
        val refillUrl: String? = null,
        val name: String? = null,
        val shuffle: Boolean = false,
    ) : DeepLinkAction()

    /**
     * `parachord://listen-along` — mirror another user's now-playing.
     * [service] is the scrobbler id (`listenbrainz` / `lastfm`); [user] is
     * the platform username. Wired in Phase 3 (#121).
     */
    data class ListenAlong(val service: String, val user: String) : DeepLinkAction()

    // ── Navigation ──
    data object NavigateHome : DeepLinkAction()
    data class NavigateArtist(val name: String, val tab: String? = null) : DeepLinkAction()
    data class NavigateAlbum(val artist: String, val title: String) : DeepLinkAction()
    data class NavigateLibrary(val tab: String? = null) : DeepLinkAction()
    data class NavigateHistory(val tab: String? = null, val period: String? = null) : DeepLinkAction()
    data class NavigateFriend(val id: String, val tab: String? = null) : DeepLinkAction()
    data class NavigateRecommendations(val tab: String? = null) : DeepLinkAction()
    data object NavigateCharts : DeepLinkAction()
    data object NavigateCriticalDarlings : DeepLinkAction()
    data object NavigatePlaylists : DeepLinkAction()
    data class NavigatePlaylist(val id: String) : DeepLinkAction()
    data class NavigateSettings(val tab: String? = null) : DeepLinkAction()
    data class NavigateSearch(val query: String?, val source: String? = null) : DeepLinkAction()
    data class NavigateChat(val prompt: String? = null) : DeepLinkAction()

    // ── Import ──
    data class ImportPlaylist(val url: String) : DeepLinkAction()

    // ── External URL lookups ──
    data class SpotifyTrack(val trackId: String) : DeepLinkAction()
    data class SpotifyAlbum(val albumId: String) : DeepLinkAction()
    data class SpotifyPlaylist(val playlistId: String) : DeepLinkAction()
    data class SpotifyArtist(val artistId: String) : DeepLinkAction()
    data class AppleMusicSong(val songId: String) : DeepLinkAction()
    data class AppleMusicAlbum(val albumId: String) : DeepLinkAction()
    data class AppleMusicPlaylist(val playlistId: String) : DeepLinkAction()

    // ── Auth (pass-through) ──
    data class OAuthCallback(val uri: String) : DeepLinkAction()

    // ── Unknown ──
    data class Unknown(val uri: String) : DeepLinkAction()
}

/**
 * Parses Spotify and Apple Music HTTP URLs into [DeepLinkAction]s.
 * Platform-agnostic URL parsing — Android's DeepLinkHandler delegates here
 * for the URL parsing logic that can be shared across platforms.
 */
object ExternalUrlParser {

    /**
     * Parse a Spotify web URL like https://open.spotify.com/track/xxx
     * or https://open.spotify.com/intl-en/track/xxx
     */
    fun parseSpotifyUrl(host: String, pathSegments: List<String>): DeepLinkAction {
        if (host != "open.spotify.com") return DeepLinkAction.Unknown("https://$host/${pathSegments.joinToString("/")}")
        if (pathSegments.size < 2) return DeepLinkAction.Unknown("https://$host/${pathSegments.joinToString("/")}")

        // Handle /intl-xx/ prefix (e.g., /intl-en/track/xxx)
        val (type, id) = if (pathSegments[0].startsWith("intl-") && pathSegments.size >= 3) {
            pathSegments[1] to pathSegments[2]
        } else {
            pathSegments[0] to pathSegments[1]
        }

        return when (type) {
            "track" -> DeepLinkAction.SpotifyTrack(id)
            "album" -> DeepLinkAction.SpotifyAlbum(id)
            "playlist" -> DeepLinkAction.SpotifyPlaylist(id)
            "artist" -> DeepLinkAction.SpotifyArtist(id)
            else -> DeepLinkAction.Unknown("https://$host/${pathSegments.joinToString("/")}")
        }
    }

    /**
     * Parse an Apple Music URL like https://music.apple.com/us/album/xxx
     */
    fun parseAppleMusicUrl(pathSegments: List<String>, songIdParam: String?): DeepLinkAction {
        if (pathSegments.size < 3) return DeepLinkAction.Unknown("music.apple.com")

        // Skip country code (first segment)
        val type = pathSegments[1]
        val id = pathSegments.lastOrNull() ?: return DeepLinkAction.Unknown("music.apple.com")

        return when (type) {
            "album" -> {
                if (songIdParam != null) {
                    DeepLinkAction.AppleMusicSong(songIdParam)
                } else {
                    DeepLinkAction.AppleMusicAlbum(id)
                }
            }
            "song" -> DeepLinkAction.AppleMusicSong(id)
            "playlist" -> DeepLinkAction.AppleMusicPlaylist(id)
            "artist" -> {
                val artistName = if (pathSegments.size >= 4) pathSegments[2] else id
                DeepLinkAction.NavigateArtist(artistName.replace("-", " "))
            }
            else -> DeepLinkAction.Unknown("music.apple.com")
        }
    }

    /**
     * Parse a Spotify URI like spotify:track:xxx
     */
    fun parseSpotifyUri(parts: List<String>): DeepLinkAction {
        if (parts.size < 2) return DeepLinkAction.Unknown("spotify:${parts.joinToString(":")}")
        return when (parts[0]) {
            "track" -> DeepLinkAction.SpotifyTrack(parts[1])
            "album" -> DeepLinkAction.SpotifyAlbum(parts[1])
            "playlist" -> DeepLinkAction.SpotifyPlaylist(parts[1])
            "artist" -> DeepLinkAction.SpotifyArtist(parts[1])
            else -> DeepLinkAction.Unknown("spotify:${parts.joinToString(":")}")
        }
    }
}
