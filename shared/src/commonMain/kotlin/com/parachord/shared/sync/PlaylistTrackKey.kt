package com.parachord.shared.sync

import com.parachord.shared.model.PlaylistTrack
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
    // Same conservative remaster-strip as [trackKeysOf] so the string-key and
    // TrackKeys paths agree (cross-engine: desktop must match).
    val t = normalizeTitleForKey(title)
    return "norm-$a|$t"
}

/**
 * Canonical key for a LOCAL playlist track. [PlaylistTrack] carries no ISRC —
 * ISRC lives on the `Track` row, not the playlist-track join — so a local
 * baseline keys off `recordingMbid` → `norm-artist|title`. `MbidEnrichmentService`
 * backfills the MBID over time. (When a PROVIDER tracklist is keyed in Phase 4
 * it CAN supply ISRC, hence the residual cross-service mismatch the design
 * accepts + enrichment minimizes.)
 */
fun canonicalTrackKey(track: PlaylistTrack): String = canonicalTrackKey(
    isrc = null,
    recordingMbid = track.trackRecordingMbid,
    artist = track.trackArtist,
    title = track.trackTitle,
)

/**
 * The ordered baseline key list for a playlist's local tracks — the 3-way-merge
 * ancestor seeded at N-way migration (Phase 2). Position-ordered; pure.
 */
fun nwayBaselineKeys(tracks: List<PlaylistTrack>): List<String> =
    tracks.sortedBy { it.position }.map { canonicalTrackKey(it) }
