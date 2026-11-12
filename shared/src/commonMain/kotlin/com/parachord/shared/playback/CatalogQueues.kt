package com.parachord.shared.playback

import com.parachord.shared.metadata.AlbumDetail
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.model.Track

/** Tracks to play or queue, and where they came from. */
data class CatalogQueue(val tracks: List<Track>, val context: PlaybackContext)

/**
 * "Play / Queue album" and "Play / Queue top songs" from a context menu, a
 * chart, a friend's page or the DJ chat (#588): the album's or artist's tracks
 * as a queue. One rule for both apps.
 *
 * - **The tracks stay metadata-only** (title, artist, album, length, cover, the
 *   Spotify id and recording MBID the provider gave) and resolve when they
 *   play, as every other metadata-only track does: the next few resolve
 *   ahead ([NextTrackPreparation]), and the play itself resolves a track
 *   nothing has resolved yet. Android used to resolve every track first, one
 *   after another, and drop the ones that missed.
 * - **The context** links back to the page: an album's name is its title and
 *   its id the artist (the album page's own form); an artist's name and id are
 *   the artist.
 */
class CatalogQueues(private val metadataService: MetadataService) {

    /** The album's tracks, or null when no provider has a track list. */
    suspend fun album(title: String, artist: String): CatalogQueue? =
        metadataService.loadAlbumTrackList(title, artist)?.let { albumQueue(it, title, artist) }

    /** The artist's top [TOP_SONGS] tracks, or null when there are none. */
    suspend fun artistTopSongs(name: String): CatalogQueue? =
        artistQueue(name, metadataService.getArtistTopTracks(name, TOP_SONGS))

    companion object {
        /** How many top songs "Play top songs" plays. */
        const val TOP_SONGS = 10

        fun albumContext(title: String, artist: String) = PlaybackContext(type = "album", name = title, id = artist)

        fun artistContext(name: String) = PlaybackContext(type = "artist", name = name, id = name)

        /** [detail]'s tracks as a queue; null when it has none. [title] and [artist] are what was asked for. */
        fun albumQueue(detail: AlbumDetail, title: String, artist: String): CatalogQueue? {
            val tracks = detail.tracks.map { catalogTrack(it, album = it.album ?: title, artworkUrl = detail.artworkUrl) }
            return tracks.takeIf { it.isNotEmpty() }?.let { CatalogQueue(it, albumContext(title, artist)) }
        }

        /** [top] as a queue; null when it's empty. */
        fun artistQueue(name: String, top: List<TrackSearchResult>): CatalogQueue? {
            val tracks = top.map { catalogTrack(it, album = it.album, artworkUrl = null) }
            return tracks.takeIf { it.isNotEmpty() }?.let { CatalogQueue(it, artistContext(name)) }
        }

        /**
         * A metadata-only track for [result]. Its id is the same for the same
         * song wherever it's queued from.
         */
        fun catalogTrack(result: TrackSearchResult, album: String?, artworkUrl: String?): Track = Track(
            id = "catalog:${result.title.lowercase()}|${result.artist.lowercase()}",
            title = result.title,
            artist = result.artist,
            album = album,
            duration = result.duration,
            artworkUrl = result.artworkUrl ?: artworkUrl,
            spotifyId = result.spotifyId,
            recordingMbid = result.mbid,
        )
    }
}
