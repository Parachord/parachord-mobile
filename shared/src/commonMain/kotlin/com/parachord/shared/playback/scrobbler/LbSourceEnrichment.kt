package com.parachord.shared.playback.scrobbler

import com.parachord.shared.model.Track

/**
 * Derived ListenBrainz `additional_info` enrichment for a played track.
 *
 * Three independently-optional groups, mirroring the desktop's
 * `_deriveSourceEnrichment(track)` (parachord-desktop/scrobbler-loader.js):
 *
 *  1. [originUrl] / [musicService] / [musicServiceName] — the resolver that
 *     actually streamed. Tied 1:1: emitted together or not at all (issue #170
 *     spec). Lets readers (Achordion's track-links cache, LB's "listen with"
 *     link) know the exact URL the user heard. Skipped for localfiles — a
 *     `file://` URI is not shareable.
 *  2. [spotifyId] — a full `https://open.spotify.com/track/{id}` URL, emitted
 *     whenever the row carries a Spotify ID regardless of which source played.
 *     A verified Spotify ID is a known-correct cross-platform anchor for the
 *     read-side even if playback went through Apple Music.
 *  3. [recordingMbid] / [releaseMbid] / [artistMbids] — when present (typically
 *     via MBID Mapper enrichment). LB skips its own mapping hop; Achordion keys
 *     its match-cache row by MBID.
 */
data class LbSourceEnrichment(
    val originUrl: String? = null,
    val musicService: String? = null,
    val musicServiceName: String? = null,
    val spotifyId: String? = null,
    val recordingMbid: String? = null,
    val releaseMbid: String? = null,
    val artistMbids: List<String> = emptyList(),
)

/** Strict 36-char MusicBrainz Identifier (UUID) shape. */
private val MBID_PATTERN =
    Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$", RegexOption.IGNORE_CASE)

private fun String?.asMbid(): String? = this?.takeIf { MBID_PATTERN.matches(it) }

private fun String?.isHttpUrl(): Boolean =
    this != null && (startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true))

/**
 * Build the LB source/MBID enrichment for a played [Track].
 *
 * Designed never to throw — every field is independently optional. The flat
 * Track model has no per-resolver `sources` map; the scrobbler receives the
 * single resolved row, so [Track.resolver] is the played source and the flat
 * IDs (`spotifyId` / `appleMusicId` / `sourceUrl`) are that source's.
 *
 * No explicit confidence gate: a resolved row is already the high-confidence
 * winner (MIN_CONFIDENCE_THRESHOLD filtered <0.60 out of selection), and the
 * scrobble threshold is the empirical confidence signal (issue #170).
 */
// Public (not internal) so the :app unit test can exercise it — :app can't see
// shared-module internals (CLAUDE.md KMP rule #10).
fun deriveLbSourceEnrichment(track: Track): LbSourceEnrichment {
    // Group 2: cross-platform Spotify anchor — independent of the played source.
    val spotifyAnchor = track.spotifyId
        ?.takeIf { it.isNotBlank() }
        ?.let { "https://open.spotify.com/track/$it" }

    // Group 1: the played source's shareable URL + service identity.
    var originUrl: String? = null
    var musicService: String? = null
    var musicServiceName: String? = null
    when (track.resolver) {
        "spotify" -> track.spotifyId?.takeIf { it.isNotBlank() }?.let {
            originUrl = "https://open.spotify.com/track/$it"
            musicService = "spotify.com"
            musicServiceName = "Spotify"
        }
        "applemusic" -> track.appleMusicId?.takeIf { it.isNotBlank() }?.let {
            originUrl = "https://music.apple.com/us/song/$it"
            musicService = "music.apple.com"
            musicServiceName = "Apple Music"
        }
        "soundcloud" -> track.sourceUrl?.takeIf { it.isHttpUrl() }?.let {
            originUrl = it
            musicService = "soundcloud.com"
            musicServiceName = "SoundCloud"
        }
        "bandcamp" -> track.sourceUrl?.takeIf { it.isHttpUrl() }?.let {
            originUrl = it
            musicService = "bandcamp.com"
            musicServiceName = "Bandcamp"
        }
        // localfiles (file:// not shareable) and any other resolver: skip.
    }

    return LbSourceEnrichment(
        originUrl = originUrl,
        musicService = musicService,
        musicServiceName = musicServiceName,
        spotifyId = spotifyAnchor,
        recordingMbid = track.recordingMbid.asMbid(),
        releaseMbid = track.releaseMbid.asMbid(),
        artistMbids = listOfNotNull(track.artistMbid.asMbid()),
    )
}
