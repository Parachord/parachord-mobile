package com.parachord.shared.sync

import com.parachord.shared.resolver.validateIsrc

/**
 * The canonical track key — the linchpin of the N-way merge. It's what lets us
 * diff a Spotify tracklist against an Apple Music one: the SAME song must
 * produce the SAME key regardless of which service it came from.
 *
 * Precedence (strongest identifier first):
 *  1. `isrc-<ISRC>`        — exact recording identifier (validated + uppercased).
 *  2. `mbid-<recordingMbid>` — MusicBrainz recording id (trimmed + lowercased).
 *  3. `norm-<artist>|<title>` — last resort when neither id is known
 *     (lower-cased, trimmed). `MbidEnrichmentService` backfills ids over time,
 *     upgrading a `norm-` key to an `isrc-`/`mbid-` key on a later sync.
 *
 * Pure + deterministic — part of the cross-engine fixture surface (the desktop
 * JS port must derive identical keys). No I/O.
 */
fun canonicalTrackKey(
    isrc: String?,
    recordingMbid: String?,
    artist: String?,
    title: String?,
): String {
    validateIsrc(isrc)?.let { return "isrc-$it" }
    recordingMbid?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { return "mbid-$it" }
    val a = artist?.trim()?.lowercase() ?: ""
    val t = title?.trim()?.lowercase() ?: ""
    return "norm-$a|$t"
}
