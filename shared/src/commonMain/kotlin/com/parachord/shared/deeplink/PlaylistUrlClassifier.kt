package com.parachord.shared.deeplink

import io.ktor.http.Url

/**
 * Classify a `parachord://play/playlist?url=<url>` target so the protocol handler
 * knows how to turn it into tracks (parachord#930). Provider playlist *pages*
 * (Spotify / Apple Music / SoundCloud) are NOT tracklist documents — they go
 * through the provider playlist path — and two hosts need a special pre-step.
 *
 * Pure: no I/O, no resolver dependency (the orchestrator does the fetching /
 * sniffing). A direct Kotlin port of desktop's `window.classifyPlaylistUrl`
 * (parachord#930, app.js) — keep the decision byte-identical with that + its
 * test mirror `tests/helpers/playlist-url-classify.js`.
 */
sealed class PlaylistUrlKind {
    /** `achordion.xyz/playlist/<mbid>` → its public, un-challenged XSPF endpoint. */
    data class Achordion(val xspfUrl: String) : PlaylistUrlKind()

    /** `on.soundcloud.com/<id>` → 302s to the canonical `/sets/` URL; resolve first. */
    data object SoundCloudShort : PlaylistUrlKind()

    /** Everything else: try provider playlist lookup, else parse as a tracklist document. */
    data object Standard : PlaylistUrlKind()
}

private val ACHORDION_PLAYLIST_PATH =
    Regex("^/playlist/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/?$", RegexOption.IGNORE_CASE)

fun classifyPlaylistUrl(urlString: String?): PlaylistUrlKind {
    if (urlString.isNullOrBlank()) return PlaylistUrlKind.Standard
    val u = try { Url(urlString) } catch (e: Exception) { return PlaylistUrlKind.Standard }
    val host = u.host.lowercase()

    // Achordion playlist page → its public XSPF endpoint. The MBID in
    // /playlist/<mbid> is the ListenBrainz playlist id Achordion mirrors.
    if (host == "achordion.xyz" || host == "www.achordion.xyz") {
        val m = ACHORDION_PLAYLIST_PATH.find(u.encodedPath)
        return if (m != null) {
            PlaylistUrlKind.Achordion("https://achordion.xyz/api/playlist/${m.groupValues[1].lowercase()}/xspf")
        } else {
            PlaylistUrlKind.Standard
        }
    }

    // SoundCloud short link → resolve the redirect before sniffing (no /sets/ segment).
    if (host == "on.soundcloud.com") return PlaylistUrlKind.SoundCloudShort

    return PlaylistUrlKind.Standard
}
