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

/** Result from the ListenBrainz MBID Mapper lookup. */
data class MbidMapperResult(
    val artistMbid: String?,
    val artistCreditName: String?,
    val recordingName: String?,
    val recordingMbid: String?,
    val releaseName: String?,
    val releaseMbid: String?,
    val confidence: Double = 0.0,
)
