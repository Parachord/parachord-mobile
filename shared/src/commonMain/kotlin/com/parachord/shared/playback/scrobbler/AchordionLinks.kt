package com.parachord.shared.playback.scrobbler

import com.parachord.shared.api.TrackLink
import com.parachord.shared.resolver.ResolvedSource

/**
 * Build the Achordion track-links submit payload's `links` from ALL high-confidence
 * resolved sources for a played track — not just the single played/top-ranked one.
 *
 * Byte-for-byte port of the desktop achordion plugin's `buildLinks` + `linkForSource`
 * (parachord-desktop/plugins/achordion.axe). Every resolved source at confidence
 * >= [PER_SOURCE_MIN_CONFIDENCE] that has a shareable URL becomes its own
 * `{url, host, label}` link, deduped by URL. This is what lets a recording page
 * surface "Listen on Spotify / Apple Music / SoundCloud / Bandcamp" for every
 * service the track actually resolved on, regardless of which one played (#276).
 *
 * The flat [com.parachord.shared.model.Track] can't represent the full source set
 * (one id per service, no Bandcamp/YouTube slot), so the caller feeds the resolver
 * cache's `List<ResolvedSource>` here; the scrobbler falls back to the flat fields
 * only on a cache miss.
 */
const val PER_SOURCE_MIN_CONFIDENCE = 0.95

private fun isHttpUrl(u: String?): Boolean =
    u != null && (u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true))

/** Map one resolved source to its shareable Achordion link, or null when it has none. */
private fun achordionLinkForSource(s: ResolvedSource): TrackLink? {
    if (s.noMatch) return null
    return when (s.resolver) {
        "spotify" -> s.spotifyId?.takeIf { it.isNotBlank() }?.let {
            TrackLink(url = "https://open.spotify.com/track/$it", host = "spotify.com", label = "Spotify")
        }
        "applemusic" -> s.appleMusicId?.takeIf { it.isNotBlank() }?.let {
            TrackLink(url = "https://music.apple.com/us/song/$it", host = "music.apple.com", label = "Apple Music")
        }
        // Bandcamp resolves but doesn't stream — its shareable URL is the source url.
        "bandcamp" -> s.url.takeIf { isHttpUrl(it) }?.let {
            TrackLink(url = it, host = "bandcamp.com", label = "Bandcamp")
        }
        "soundcloud" -> (s.soundcloudUrl ?: s.url).takeIf { isHttpUrl(it) }?.let {
            TrackLink(url = it, host = "soundcloud.com", label = "SoundCloud")
        }
        else -> null   // localfiles (file:// not shareable), direct, youtube (no id on mobile)
    }
}

/**
 * Every high-confidence source's shareable link, deduped by URL. Mirrors desktop
 * `buildLinks`: skip noMatch + sub-[PER_SOURCE_MIN_CONFIDENCE] sources, first
 * occurrence of each URL wins.
 */
fun buildAchordionLinks(sources: List<ResolvedSource>): List<TrackLink> {
    val out = mutableListOf<TrackLink>()
    val seen = mutableSetOf<String>()
    for (s in sources) {
        if (s.noMatch) continue
        if ((s.confidence ?: 0.0) < PER_SOURCE_MIN_CONFIDENCE) continue
        val link = achordionLinkForSource(s) ?: continue
        if (seen.add(link.url)) out.add(link)
    }
    return out
}
