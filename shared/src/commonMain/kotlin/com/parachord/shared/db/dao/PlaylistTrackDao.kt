package com.parachord.shared.db.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.Playlist_tracks
import com.parachord.shared.model.PlaylistTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [PlaylistTrackQueries].
 * Drop-in replacement for the former Room @Dao interface.
 */
class PlaylistTrackDao(private val db: ParachordDb) {

    private val queries get() = db.playlistTrackQueries

    /* ---- Mapping ---- */

    private fun Playlist_tracks.toPlaylistTrack() = PlaylistTrack(
        playlistId = playlistId,
        position = position.toInt(),
        trackTitle = trackTitle,
        trackArtist = trackArtist,
        trackAlbum = trackAlbum,
        trackDuration = trackDuration,
        trackArtworkUrl = trackArtworkUrl,
        trackSourceUrl = trackSourceUrl,
        trackResolver = trackResolver,
        trackSpotifyUri = trackSpotifyUri,
        trackSoundcloudId = trackSoundcloudId,
        trackSpotifyId = trackSpotifyId,
        trackAppleMusicId = trackAppleMusicId,
        trackRecordingMbid = trackRecordingMbid,
        trackIsrc = trackIsrc,
        addedAt = addedAt,
    )

    /* ---- Queries returning Flow ---- */

    fun getByPlaylistId(playlistId: String): Flow<List<PlaylistTrack>> =
        queries.getByPlaylistId(playlistId).asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toPlaylistTrack() } }

    /* ---- Suspend one-shot reads ---- */

    suspend fun getByPlaylistIdSync(playlistId: String): List<PlaylistTrack> = withContext(Dispatchers.Default) {
        queries.getByPlaylistId(playlistId).executeAsList().map { it.toPlaylistTrack() }
    }

    suspend fun getMaxPosition(playlistId: String): Int = withContext(Dispatchers.Default) {
        queries.getMaxPosition(playlistId).executeAsOne().toInt()
    }

    /* ---- Writes ---- */

    suspend fun insertAll(tracks: List<PlaylistTrack>): Unit = withContext(Dispatchers.Default) {
        queries.transaction {
            for (track in tracks) {
                queries.insert(
                    playlistId = track.playlistId,
                    position = track.position.toLong(),
                    trackTitle = track.trackTitle,
                    trackArtist = track.trackArtist,
                    trackAlbum = track.trackAlbum,
                    trackDuration = track.trackDuration,
                    trackArtworkUrl = track.trackArtworkUrl,
                    trackSourceUrl = track.trackSourceUrl,
                    trackResolver = track.trackResolver,
                    trackSpotifyUri = track.trackSpotifyUri,
                    trackSoundcloudId = track.trackSoundcloudId,
                    trackSpotifyId = track.trackSpotifyId,
                    trackAppleMusicId = track.trackAppleMusicId,
                    trackRecordingMbid = track.trackRecordingMbid,
                    trackIsrc = track.trackIsrc,
                    addedAt = track.addedAt,
                )
            }
        }
    }

    suspend fun deleteByPlaylistId(playlistId: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteByPlaylistId(playlistId)
    }


    suspend fun deleteTrack(playlistId: String, position: Int): Unit = withContext(Dispatchers.Default) {
        queries.deleteTrack(playlistId = playlistId, position = position.toLong())
    }

    /**
     * Backfill an artwork URL onto a single playlist_track row, but only when
     * it currently has none — never overwrite art that came from the source
     * (Spotify / SoundCloud already give us per-track artwork).
     */
    suspend fun updateTrackArtwork(playlistId: String, position: Int, artworkUrl: String): Unit =
        withContext(Dispatchers.Default) {
            queries.updateTrackArtwork(
                trackArtworkUrl = artworkUrl,
                playlistId = playlistId,
                position = position.toLong(),
            )
        }

    /**
     * Backfill resolver IDs onto a playlist_track row. Each parameter is
     * `COALESCE`-merged with the existing column, so a non-null source ID
     * (e.g. Spotify's canonical sync ID) is never overwritten — only NULL
     * slots get filled. Pass `null` for IDs that weren't discovered.
     */
    suspend fun backfillResolverIds(
        playlistId: String,
        position: Int,
        spotifyId: String?,
        spotifyUri: String?,
        appleMusicId: String?,
        soundcloudId: String?,
        recordingMbid: String?,
        isrc: String? = null,
    ): Unit = withContext(Dispatchers.Default) {
        // SQLDelight generates positional `value`, `value_`, … for the COALESCE
        // params; keep the order matching the SQL (Spotify ID, Spotify URI, Apple
        // Music ID, SoundCloud ID, Recording MBID, ISRC).
        queries.backfillResolverIds(
            value = spotifyId,
            value_ = spotifyUri,
            value__ = appleMusicId,
            value___ = soundcloudId,
            value____ = recordingMbid,
            value_____ = isrc,
            playlistId = playlistId,
            position = position.toLong(),
        )
    }

    /** Delete all tracks for a playlist then reinsert — used for reorder. */
    suspend fun replaceAll(playlistId: String, tracks: List<PlaylistTrack>): Unit = withContext(Dispatchers.Default) {
        queries.transaction {
            queries.deleteByPlaylistId(playlistId)
            for (track in tracks) {
                queries.insert(
                    playlistId = track.playlistId,
                    position = track.position.toLong(),
                    trackTitle = track.trackTitle,
                    trackArtist = track.trackArtist,
                    trackAlbum = track.trackAlbum,
                    trackDuration = track.trackDuration,
                    trackArtworkUrl = track.trackArtworkUrl,
                    trackSourceUrl = track.trackSourceUrl,
                    trackResolver = track.trackResolver,
                    trackSpotifyUri = track.trackSpotifyUri,
                    trackSoundcloudId = track.trackSoundcloudId,
                    trackSpotifyId = track.trackSpotifyId,
                    trackAppleMusicId = track.trackAppleMusicId,
                    trackRecordingMbid = track.trackRecordingMbid,
                    trackIsrc = track.trackIsrc,
                    addedAt = track.addedAt,
                )
            }
        }
    }
}
