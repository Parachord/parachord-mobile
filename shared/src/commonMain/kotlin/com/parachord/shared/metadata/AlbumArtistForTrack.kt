package com.parachord.shared.metadata

import com.parachord.shared.model.Track

/*
 * Opening a track's album by the album's artist (#839, a port of desktop's
 * `album-track-artist.js`, parachord#1660).
 *
 * A compilation's tracks play under their own artists, but the album is
 * credited to "Various Artists": looked up as "Trainspotting" by Iggy Pop it
 * isn't found. So such a track also carries the album's artist
 * (`Track.albumArtist`), and links to its album ask by that.
 */

/**
 * The album artist a track built from an album's list carries: [albumArtist]
 * when none of the track's credited names is one of the album's
 * ([AlbumCatalog.ownCatalogTrackArtist]), else null. So an ordinary album's
 * tracks, a featured guest and a split album carry nothing.
 */
fun trackAlbumArtist(trackArtist: String?, albumArtist: String?): String? =
    AlbumCatalog.ownCatalogTrackArtist(trackArtist, albumArtist)?.let { albumArtist?.trim() }

private val UNKNOWN_ARTIST = Regex("^unknown( artist)?$", RegexOption.IGNORE_CASE)

/**
 * Whose album [albumName] is, for [track]: its album artist when [albumName]
 * is the track's own album, else the track's artist. Now Playing can show the
 * stream's album, which isn't the one the field describes.
 */
fun albumArtistForTrack(track: Track, albumName: String?): String {
    val own = track.albumArtist?.trim().orEmpty()
    val album = track.album?.trim().orEmpty()
    val usable = own.isNotEmpty() && !UNKNOWN_ARTIST.matches(own) &&
        album.isNotEmpty() && album == albumName?.trim()
    return if (usable) own else track.artist
}
