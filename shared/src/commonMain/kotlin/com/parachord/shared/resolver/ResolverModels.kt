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
    /**
     * MusicBrainz recording MBID of the matched recording, when the resolver
     * knows it (a scanned local file's row, or an `.axe` result's `mbid` /
     * `recordingMbid`). Feeds the identity tier of [scoreConfidence] (#402):
     * the same recording MBID as the target is the same recording whatever the
     * title/artist strings say. Null when the resolver doesn't surface one.
     */
    val recordingMbid: String? = null,
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
 * Unicode letters + numbers as a regex class body — desktop's `\p{L}\p{N}`.
 *
 * **`\p{N}` is spelled out as `\p{Nd}\p{Nl}\p{No}` because Kotlin/Native's regex
 * engine rejects `\p{N}`** ("PatternSyntaxException: No such character class";
 * the JVM accepts it). N is by definition the union of Nd ∪ Nl ∪ No, so the match
 * set is identical. Worse than a bad match: [NOT_LETTER_OR_DIGIT] is a top-level
 * val, and on Native a top-level initializer that throws poisons the WHOLE FILE
 * (`FileFailedToInitializeException`, a Kotlin `Error`), so every function here —
 * `scoreConfidence`, `validateIsrc`, … — would fail on iOS while every JVM test
 * stayed green. Use this constant anywhere you'd write `\p{N}` in shared code.
 */
internal const val LETTER_OR_NUMBER_CLASS = "\\p{L}\\p{Nd}\\p{Nl}\\p{No}"

private val COMBINING_MARKS = Regex("\\p{M}")
private val NOT_LETTER_OR_DIGIT = Regex("[^$LETTER_OR_NUMBER_CLASS]")

/**
 * Normalize a string for comparison. **Byte-aligned with desktop's
 * `normalizeStr`** (`sync-providers/confidence-scoring.js`, parachord#943):
 *
 *     lowercase → NFKD → strip combining marks (\p{M}) → keep only \p{L}\p{N}
 *
 * Keep that ORDER — it's what makes the output identical to desktop's. (Desktop's
 * track-slug helper lowercases after NFKD instead; that's a different function.)
 *
 * Unicode-aware on purpose (#404). The old `[^a-z0-9]` version deleted every
 * non-ASCII character, so a Cyrillic / CJK / Arabic title normalized to `""`,
 * [stringsMatch] rejected it, and [scoreConfidence] scored every search-resolved
 * non-Latin track 0.50 — below the 0.60 floor, so it could never resolve by
 * search. It also lost accented letters instead of folding them
 * ("Beyoncé" → "beyonc", now "beyonce").
 */
fun normalizeStr(s: String?): String {
    if (s.isNullOrEmpty()) return ""
    return com.parachord.shared.platform.nfkd(s.lowercase())
        .replace(COMBINING_MARKS, "")
        .replace(NOT_LETTER_OR_DIGIT, "")
}

/**
 * Check if two normalized strings match via containment (either direction).
 * Matches the desktop's validateResolvedTrack() logic.
 */
private fun stringsMatch(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    return a.contains(b) || b.contains(a)
}

/**
 * Pick the best-matching result for a target track, in desktop `findBestMatch`'s
 * order: exact recording MBID, exact ISRC, exact normalized title + artist, a
 * containment match that isn't another version ([isOtherVersion]), then any
 * containment match. Name matches use the SAME bidirectional [stringsMatch] gate
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
    /**
     * Identity tiers (#402 / #279), checked BEFORE any name comparison, in
     * desktop `findBestMatch`'s order (parachord#1056): an exact recording MBID,
     * then an exact ISRC. Both are definitive recording identity, immune to
     * title/artist formatting. Omitted (the defaults) = name matching only.
     */
    targetMbid: String? = null,
    targetIsrc: String? = null,
    mbidOf: (T) -> String? = { null },
    isrcOf: (T) -> String? = { null },
): T? {
    // Tier 1: exact MBID. Desktop compares with `===`, i.e. case-sensitively.
    if (!targetMbid.isNullOrEmpty()) {
        results.firstOrNull { mbidOf(it) == targetMbid }?.let { return it }
    }
    // Tier 2: exact ISRC, trimmed + upper-cased on both sides (desktop has no
    // shape check here; a value only ever equals another real ISRC anyway).
    if (!targetIsrc.isNullOrEmpty()) {
        val ti = targetIsrc.trim().uppercase()
        results.firstOrNull { isrcOf(it)?.trim()?.uppercase() == ti }?.let { return it }
    }
    val nt = normalizeStr(targetTitle)
    val na = normalizeStr(targetArtist)
    if (nt.isEmpty() || na.isEmpty()) return null
    // Tier 3: exact normalized title and artist.
    results.firstOrNull {
        normalizeStr(titleOf(it)) == nt && normalizeStr(artistOf(it)) == na
    }?.let { return it }
    // Tier 4: a containment match that isn't another version (a live cut, remix
    // or cover the target doesn't name). Tier 5: any containment match, so
    // "Song - Remastered"-style catalog titles and variant-only results still
    // resolve (desktop parachord#1115).
    val contains: (T) -> Boolean = {
        stringsMatch(nt, normalizeStr(titleOf(it))) && stringsMatch(na, normalizeStr(artistOf(it)))
    }
    results.firstOrNull {
        contains(it) && !isOtherVersion(titleOf(it), artistOf(it), targetTitle, targetArtist)
    }?.let { return it }
    return results.firstOrNull(contains)
}

/**
 * The track a resolve is for, so a resolver that gets several search results
 * can pick the right one with [selectBestMatch]. Null fields mean "unknown".
 */
data class MatchTarget(
    val title: String? = null,
    val artist: String? = null,
    val isrc: String? = null,
    val mbid: String? = null,
)

/**
 * Version words: a result carrying one the requested name lacks is another
 * version of the track (a live cut, remix, cover, karaoke…). Byte-aligned with
 * desktop's `VARIANT_WORDS` (`src/renderer/lib/track-variants.js`), which the
 * Bandcamp plugin also copies; keep the lists the same.
 */
private val VARIANT_WORDS = Regex(
    "\\b(live|remix(es|ed)?|rmx|cover(s|ed)?|covering|tribute|karaoke|instrumental|acoustic|demo|unplugged|rework|bootleg|mashup|reprise|edit|jam track|sped up|slowed|in the style of)\\b",
    RegexOption.IGNORE_CASE,
)

private fun hasVariantWord(name: String?, target: String?): Boolean {
    if (name.isNullOrEmpty()) return false
    val t = target.orEmpty().lowercase()
    return VARIANT_WORDS.findAll(name).any { !t.contains(it.value.lowercase()) }
}

/**
 * Is a search result another version of the requested track? A version word
 * counts only when the requested name lacks it, so "Live Forever" and
 * "Take Me Out (Live)" still match themselves. Port of desktop's
 * `isOtherVersion` (parachord#1115).
 */
fun isOtherVersion(
    resultTitle: String?,
    resultArtist: String?,
    targetTitle: String?,
    targetArtist: String?,
): Boolean = hasVariantWord(resultTitle, targetTitle) || hasVariantWord(resultArtist, targetArtist)

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
 *
 * **Identity tier (#402, desktop parachord#1056 `calculateConfidence`).** Before
 * any name comparison, the same recording on both sides scores **1.0** outright:
 *
 * 1. both ISRCs present and equal after [validateIsrc] (trim + upper-case +
 *    shape check), or
 * 2. both recording MBIDs present and equal case-insensitively.
 *
 * A missing, malformed or different id falls through to the name gate
 * unchanged, so this only ever upgrades. ISRC is checked before MBID, as on
 * desktop. Desktop picks the target ISRC from the track then its sources; mobile
 * callers pass [targetIsrc] already picked (e.g. via [pickTrackIsrc]).
 * "Various Artists / Track 1" on a rip still scores 1.0 when its ISRC matches.
 */
fun scoreConfidence(
    targetTitle: String,
    targetArtist: String,
    matchedTitle: String?,
    matchedArtist: String?,
    targetIsrc: String? = null,
    targetMbid: String? = null,
    matchedIsrc: String? = null,
    matchedMbid: String? = null,
): Double {
    val ti = validateIsrc(targetIsrc)
    val mi = validateIsrc(matchedIsrc)
    if (ti != null && mi != null && ti == mi) return 1.0
    // Desktop: `targetMbid && foundMbid && lower(a) === lower(b)` — JS truthiness,
    // so only null/empty count as absent (no trim).
    if (!targetMbid.isNullOrEmpty() && !matchedMbid.isNullOrEmpty() &&
        targetMbid.lowercase() == matchedMbid.lowercase()
    ) return 1.0

    val normTarget = normalizeStr(targetTitle)
    val normArtist = normalizeStr(targetArtist)
    val normMatchTitle = normalizeStr(matchedTitle)
    val normMatchArtist = normalizeStr(matchedArtist)

    val titleMatch = stringsMatch(normTarget, normMatchTitle)
    val artistMatch = stringsMatch(normArtist, normMatchArtist)

    // Desktop's validateResolvedTrack: both must match.
    return if (titleMatch && artistMatch) 0.95 else 0.50
}

// ── Apple Music ids (#265) ─────────────────────────────────────────────────

/**
 * Is [id] an Apple Music CATALOG id (all digits, e.g. `1440857781`)? Library ids
 * (`i.…` songs, `l.…` older items, `p.…` playlists) are not, and the catalog
 * rejects them — a library id used as a play/lookup hint makes iOS's
 * MusicCatalogResourceRequest return nothing. Hint paths drop non-catalog ids so
 * stale data falls back to a normal search instead of a guaranteed miss.
 */
fun isAppleMusicCatalogId(id: String?): Boolean =
    !id.isNullOrBlank() && id.all { it.isDigit() }

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
