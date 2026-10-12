package com.parachord.shared.api

/**
 * ListenBrainz API public model classes.
 * Consumed by [ListenBrainzClient]; private @Serializable wire models live
 * in that file and are mapped to these on read.
 */

/** A single listen from the ListenBrainz API. */
data class LbListen(
    val artistName: String,
    val trackName: String,
    val releaseName: String? = null,
    val listenedAt: Long = 0,
)

/** Artist stat from ListenBrainz stats API. */
data class LbArtistStat(
    val name: String,
    val listenCount: Int = 0,
)

/** Recording (track) stat from ListenBrainz stats API. */
data class LbRecordingStat(
    val trackName: String,
    val artistName: String,
    val releaseName: String? = null,
    val listenCount: Int = 0,
)

/** Release (album) stat from ListenBrainz stats API. */
data class LbReleaseStat(
    val releaseName: String,
    val artistName: String,
    val listenCount: Int = 0,
)

/** A recommended track from a ListenBrainz recommendation playlist. */
data class LbRecommendedTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
)

/** A playlist "created for" a user by ListenBrainz. */
data class LbCreatedForPlaylist(
    val id: String,
    val title: String,
    val date: String,
    val annotation: String = "",
)

/**
 * A user-owned playlist as returned by `GET /1/user/{userName}/playlists`.
 * Distinct from [LbCreatedForPlaylist] (the system-generated `createdfor`
 * playlists like Weekly Jams).
 */
data class LbPlaylist(
    val mbid: String,
    val title: String,
    val annotation: String = "",
    val lastModifiedAt: String? = null,
)

/** A track from a ListenBrainz playlist with album art info. */
data class LbPlaylistTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArt: String? = null,
    val durationMs: Long? = null,
    val mbid: String? = null,
)

/**
 * Result from a recording-MBID lookup — the ListenBrainz MBID Mapper, or the
 * labs `acr-lookup` dataset when the mapper can't be reached. Both are shaped
 * identically so every consumer works unchanged; [source] says which answered.
 */
data class MbidMapperResult(
    val artistMbid: String?,
    val artistCreditName: String?,
    val recordingName: String?,
    val recordingMbid: String?,
    val releaseName: String?,
    val releaseMbid: String?,
    val confidence: Double = 0.0,
    /** [MbidLookupSource.MAPPER] or [MbidLookupSource.ACR_LOOKUP]. */
    val source: String = MbidLookupSource.MAPPER,
)

/** Which service answered a recording-MBID lookup. */
object MbidLookupSource {
    const val MAPPER = "mapper"
    const val ACR_LOOKUP = "acr-lookup"
}

/**
 * Outcome of [ListenBrainzClient.resolveRecordingMbid] — three cases, not two.
 *
 * A plain `null` used to mean both "no match" and "the request failed", and
 * during the 2026-09 mapper outage every failure was recorded as a miss. The
 * distinction is load-bearing for every cache downstream (parachord-mobile#394;
 * desktop parachord#991 `mbid-lookup.js`).
 */
sealed class MbidLookupOutcome {
    /** A service matched; [result] is mapper-shaped, `result.source` says which. */
    data class Hit(val result: MbidMapperResult) : MbidLookupOutcome()

    /**
     * A service ANSWERED and has no match. Trust an [MbidLookupSource.ACR_LOOKUP]
     * miss less than a mapper miss — it's a stricter (semi-exact) matcher, so a
     * mapper that comes back might still match.
     */
    data class Miss(val source: String) : MbidLookupOutcome()

    /**
     * Nothing answered (network/TLS error or non-2xx from both). NOT a miss:
     * never cache it and never count it against the track. [errors] says what
     * each service did, for logging.
     */
    data class Unreachable(val errors: Map<String, String>) : MbidLookupOutcome()
}

/** Thrown by the labs gate if a cooldown is ever armed; surfaces as [MbidLookupOutcome.Unreachable]. */
class ListenBrainzLabsRateLimitedException(val retryAfterSeconds: Long?) :
    Exception("ListenBrainz labs rate-limited (retry after ${retryAfterSeconds ?: "?"}s)")
