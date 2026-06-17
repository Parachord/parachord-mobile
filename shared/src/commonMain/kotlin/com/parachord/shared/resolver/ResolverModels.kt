package com.parachord.shared.resolver

import kotlinx.serialization.Serializable

@Serializable
data class ResolverInfo(
    val id: String,
    val name: String,
    val version: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class ResolvedSource(
    val url: String,
    val sourceType: String,
    val resolver: String,
    val quality: Int? = null,
    val headers: Map<String, String>? = null,
    val spotifyUri: String? = null,
    val spotifyId: String? = null,
    val soundcloudId: String? = null,
    val soundcloudUrl: String? = null,
    val appleMusicId: String? = null,
    /**
     * ISRC of the matched recording, when the resolver's response carries one
     * (Spotify `external_ids.isrc`, Apple Music catalog `attributes.isrc`, or an
     * `.axe` result's `isrc`). The service-agnostic supply side of the ISRC →
     * recording-MBID fallback: [com.parachord.shared.metadata.MbidEnrichmentService]
     * consumes this (via [com.parachord.shared.model.Track.isrc]) when the
     * ListenBrainz mapper is unavailable, looking it up through
     * MusicBrainz `/ws/2/isrc/{isrc}`. Resolvers without an ISRC leave it null.
     */
    val isrc: String? = null,
    /** Match confidence from the resolver (0.0–1.0). Desktop defaults to 0.9 for successful resolves. */
    val confidence: Double? = null,
    /** Whether the resolver explicitly couldn't match this track. */
    val noMatch: Boolean = false,
    /** The title returned by the resolver's search result (for confidence scoring). */
    val matchedTitle: String? = null,
    /** The artist returned by the resolver's search result (for confidence scoring). */
    val matchedArtist: String? = null,
    /** Duration in ms from the resolver's result (for confidence scoring). */
    val matchedDurationMs: Long? = null,
    /**
     * Album/track artwork URL surfaced by the resolver, when available.
     *
     * Apple Music (via MusicKit JS or iTunes Search), Spotify, and Last.fm
     * all return artwork URLs alongside their track-search results. Without
     * this field, that data was discarded at the resolver boundary, forcing
     * [com.parachord.shared.metadata.ImageEnrichmentService.enrichPlaylistArt]
     * to RE-ASK metadata providers for art the resolver had moments earlier
     * — wasteful and a problem when those providers rate-limit (the
     * primary symptom is a hosted-XSPF playlist whose tracks resolve via
     * AM but whose mosaic still says "no art" because the cascade
     * fell through).
     *
     * Persisted onto `playlist_tracks.trackArtworkUrl` /
     * `tracks.artworkUrl` by [com.parachord.android.resolver.TrackResolverCache.backfillResolverIds]
     * when set, so the next render of the row displays it without any
     * additional network traffic.
     */
    val artworkUrl: String? = null,
)

// ── Confidence Scoring ──────────────────────────────────────────────

/**
 * Normalize a string for comparison: lowercase, strip everything except
 * alphanumeric characters. Matches the desktop's normalizeStr().
 */
fun normalizeStr(s: String?): String =
    s?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: ""

/**
 * Check if two normalized strings match via containment (either direction).
 * Matches the desktop's validateResolvedTrack() logic.
 */
private fun stringsMatch(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    return a.contains(b) || b.contains(a)
}

/**
 * Pick the best-matching result for a target track: the first whose title AND
 * artist BOTH bidirectionally substring-match — the SAME [stringsMatch] gate
 * [scoreConfidence] uses, so a selected candidate is guaranteed to score 0.95
 * rather than 0.50. Returns null when nothing matches; callers decide whether
 * to fall back to the raw first result.
 *
 * Mirrors the desktop's `applemusic.axe` `matchFromResults`, but uses the
 * bidirectional validate gate (not desktop's one-directional `includes`) so a
 * target carrying extra words (e.g. "THE Tallest Man on Earth" vs the catalog's
 * "Tallest Man on Earth") still matches instead of falling through to a weaker
 * first result. Generic over the result type via field selectors so every
 * resolver tier (MusicKit, iTunes, AM catalog JSON) can share one matcher.
 */
fun <T> selectBestMatch(
    results: List<T>,
    targetTitle: String,
    targetArtist: String,
    titleOf: (T) -> String?,
    artistOf: (T) -> String?,
): T? {
    val nt = normalizeStr(targetTitle)
    val na = normalizeStr(targetArtist)
    if (nt.isEmpty() || na.isEmpty()) return null
    return results.firstOrNull {
        stringsMatch(nt, normalizeStr(titleOf(it))) && stringsMatch(na, normalizeStr(artistOf(it)))
    }
}

/**
 * Calculate match confidence by comparing the resolver's result against the
 * target track.
 *
 * Models the desktop's two-stage gate: validation first
 * (`validateResolvedTrack` — requires BOTH title AND artist substring match),
 * then confidence within validated sources (`calculateConfidence`). On
 * desktop, a result that fails `validateResolvedTrack` is not added to
 * `track.sources` at all, so it never reaches the priority sort. We
 * mirror that here by collapsing single-axis matches to 0.50 (a "no match"
 * sentinel that the [ResolverScoring.MIN_CONFIDENCE_THRESHOLD] = 0.60 floor
 * then filters out before priority-based selection runs).
 *
 * - Title + artist match → 0.95   (validated; enters the priority sort)
 * - Title match only     → 0.50   (artist mismatch — wrong-song candidate, dropped)
 * - Artist match only    → 0.50   (wrong song by the right artist, dropped)
 * - No match             → 0.50
 *
 * **Why this matters:** without the both-axes gate, Apple Music returning
 * a different artist's "Mariana" (title-only match → 0.85) was outranking
 * the correct local file (both-axes match → 0.95), because applemusic sits
 * above localfiles in the priority order and confidence is only a tiebreaker
 * within the same priority tier. Tightening the gate filters wrong-song
 * matches before they enter the sort, restoring desktop parity.
 *
 * Direct ID matches (spotifyId, appleMusicId, soundcloudId) bypass this and
 * keep their 0.95 confidence since the ID is authoritative.
 */
fun scoreConfidence(
    targetTitle: String,
    targetArtist: String,
    matchedTitle: String?,
    matchedArtist: String?,
): Double {
    val normTarget = normalizeStr(targetTitle)
    val normArtist = normalizeStr(targetArtist)
    val normMatchTitle = normalizeStr(matchedTitle)
    val normMatchArtist = normalizeStr(matchedArtist)

    val titleMatch = stringsMatch(normTarget, normMatchTitle)
    val artistMatch = stringsMatch(normArtist, normMatchArtist)

    // Desktop's validateResolvedTrack: both must match.
    return if (titleMatch && artistMatch) 0.95 else 0.50
}

// ── ISRC capture / walk (desktop parity, parachord#894 / mobile #217) ────────

/**
 * Canonical ISRC shape: 2-letter country, 3-char alphanumeric registrant,
 * 7 digits (2-digit year + 5-digit designation). Matches desktop's
 * `/^[A-Z]{2}[A-Z0-9]{3}\d{7}$/`.
 */
private val ISRC_REGEX = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

/**
 * Normalize + validate a raw ISRC: trim, uppercase, then require the canonical
 * shape. Returns null for malformed/blank input. Every resolver that captures
 * an ISRC into a [ResolvedSource] runs the raw value through this so a malformed
 * ISRC never poisons a source record (desktop drops rather than stores) — and so
 * the value handed to MusicBrainz `/ws/2/isrc/{isrc}` is always well-formed.
 */
fun validateIsrc(raw: String?): String? =
    raw?.trim()?.uppercase()?.takeIf { ISRC_REGEX.matches(it) }

/**
 * Pick the best ISRC for a track, mirroring desktop's `window.pickTrackIsrc`:
 * the track's own (carried) ISRC first, then walk the resolved sources in order,
 * skipping `noMatch`. Every candidate is [validateIsrc]-normalized, so the result
 * is always canonical or null. Byte-equivalent precedence with desktop so the
 * same track yields the same ISRC→MBID fallback result on either client.
 *
 * Mobile has no `track.sources` map (sources live in the resolver cache as a
 * `List<ResolvedSource>`), so this takes the carried top-level ISRC + that list
 * rather than a single track object.
 */
fun pickTrackIsrc(topLevelIsrc: String?, sources: List<ResolvedSource>): String? {
    validateIsrc(topLevelIsrc)?.let { return it }
    for (source in sources) {
        if (source.noMatch) continue
        validateIsrc(source.isrc)?.let { return it }
    }
    return null
}
