package com.parachord.shared.deeplink

/**
 * Multi-source identifier bag for `parachord://play/...` commands.
 *
 * Mirrors desktop's `protocol-schema.md` — every play command accepts an
 * input that may carry one or more of the following identifiers:
 *
 * - [mbid] — MusicBrainz UUID (recording / release / artist depending on
 *   the verb). 36-char canonical form.
 * - [spotify] — Spotify URI (`spotify:track:…`) or web URL.
 * - [applemusic] — Apple Music ID (numeric for tracks/albums).
 * - [url] — generic playlist URL (XSPF/JSPF/M3U/JSON tracklist hosted
 *   anywhere — fetched + parsed via [ProtocolTracklistParser]).
 * - [tracks] — inline tracklist (UTF-8 base64-encoded JSON in the wire
 *   format; decoded into [ProtocolTrack] before this struct is built).
 * - [artist] / [title] — last-resort textual identifier; only used by
 *   commands that document acceptance of artist+title (e.g. radio Mode B).
 *
 * The resolver in `ProtocolInputResolver` walks these in priority order
 * (`mbid → spotify → applemusic → url → tracks → artist+title`) and gates
 * which fields are accepted per command via a small options struct.
 *
 * Album-only inputs (e.g. an album MBID passed to `play/playlist`) silently
 * fall through for non-album commands; the resolver returns `null` and the
 * caller surfaces a "wrong input" toast.
 */
data class ProtocolPlayInput(
    val mbid: String? = null,
    val spotify: String? = null,
    val applemusic: String? = null,
    val url: String? = null,
    val tracks: List<ProtocolTrack>? = null,
    val artist: String? = null,
    val title: String? = null,
)

/**
 * One track in an inline / parsed tracklist (XSPF / JSPF / generic JSON).
 *
 * The minimal viable shape on every track is `(artist, title)`. Optional
 * MBIDs and ISRCs lift resolution quality (skip catalog search; route
 * directly via the resolver pipeline). Album hints help disambiguate
 * cover-of cases.
 *
 * Per-track `id`, `sources`, and `_playbackContext` tagging happens at
 * queue-build time, NOT at parse time — this struct is the on-the-wire
 * shape, not the runtime queue shape.
 */
data class ProtocolTrack(
    val artist: String,
    val title: String,
    val album: String? = null,
    val mbid: String? = null,
    val isrc: String? = null,
)

/**
 * Radio mode for `parachord://play/radio`.
 *
 * Mirrors desktop's three-mode model:
 * - Mode A (URL-fed) — handled via `PlayRadio.refillUrl` + initial
 *   `tracks` payload; no enum variant needed because the radio engine
 *   just polls the URL on each refill.
 * - [ArtistSeed] — Mode B. Server / client builds the queue from one
 *   seed artist (and optional song hint); refills are computed from
 *   the same seed.
 * - [PoolBased] — Mode C. Initial pool of tracks supplied inline
 *   (or via [ProtocolPlayInput.tracks]); subsequent refills draw from
 *   that pool. No external seed.
 */
sealed class RadioMode {
    /** Mode B — single seed artist (and optional song title hint). */
    data class ArtistSeed(val artist: String, val title: String? = null) : RadioMode()

    /** Mode C — initial pool fully supplied by the caller; no external seed. */
    data object PoolBased : RadioMode()
}

/**
 * Strict regex for MusicBrainz IDs in `urn:` / catalog-URL-style
 * identifiers and bare-UUID payloads.
 *
 * Matches the canonical 8-4-4-4-12 lowercase hex form. Mirrors desktop's
 * regex exactly; do NOT loosen to accept uppercase or strip dashes here
 * — the resolver normalizes input before matching.
 */
val MBID_REGEX: Regex = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")

/** True if [s] is a 36-char canonical lowercase MBID. */
fun isValidMbid(s: String?): Boolean = s != null && MBID_REGEX.matches(s)
