package com.parachord.shared.db.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.Playlist_tracks
import com.parachord.shared.home.PlaylistFreshness
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Concrete DAO wrapping SQLDelight [PlaylistTrackQueries].
 * Drop-in replacement for the former Room @Dao interface.
 *
 * Stamps playlist freshness ([PlaylistFreshness]): [insertAll], [replaceAll]
 * and [deleteTrack] compare the playlist's track keys before and after the
 * write, in the same transaction, and set `playlists.tracksChangedAt` /
 * `lastChangeAdded` when they differ. Cover and id backfills don't stamp, nor
 * does [deleteByPlaylistId] (the whole playlist goes). Replace a playlist's
 * tracks with [replaceAll], not a delete then an insert: the delete alone would
 * compare against nothing and count every track as added.
 */
class PlaylistTrackDao(
    private val db: ParachordDb,
    private val nowMs: () -> Long = ::currentTimeMillis,
) {

    private val queries get() = db.playlistTrackQueries
    private val playlistQueries get() = db.playlistQueries

    /** The playlist's track keys, in order. Call inside a transaction. */
    private fun trackKeys(playlistId: String): List<String> =
        queries.getByPlaylistId(playlistId).executeAsList().map {
            PlaylistFreshness.trackKey(it.trackArtist, it.trackTitle)
        }

    /** Run [write] and stamp each of [playlistIds] whose tracks it changed.
     *  Call inside a transaction. */
    private fun stamping(playlistIds: Collection<String>, write: () -> Unit) {
        val before = playlistIds.associateWith { trackKeys(it) }
        write()
        val now = nowMs()
        for ((id, prevKeys) in before) {
            val prevAt = playlistQueries.getTrackStamp(id).executeAsOneOrNull()?.tracksChangedAt
            val stamp = PlaylistFreshness.stamp(prevKeys, trackKeys(id), prevAt, now) ?: continue
            playlistQueries.stampTrackChange(stamp.tracksChangedAt, stamp.lastChangeAdded.toLong(), id)
        }
    }

    private val _changes = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The id of each playlist whose tracks were just written (added, removed,
     * reordered, replaced, or given a cover). Every track write goes through
     * this DAO — sync pulls, local edits, hosted XSPF, iOS — so this is the one
     * place a reader can learn that a playlist changed. The playlist mosaic
     * follows its tracks this way ([com.parachord.shared.metadata.ImageEnrichmentService.watchTrackChanges]).
     * Best effort: with no collector, or a burst past the buffer, ids drop.
     */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    private fun changed(playlistId: String) { _changes.tryEmit(playlistId) }

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
            stamping(tracks.map { it.playlistId }.distinct()) { insertRows(tracks) }
        }
        tracks.map { it.playlistId }.distinct().forEach(::changed)
    }

    private fun insertRows(tracks: List<PlaylistTrack>) {
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

    /** Remove every track of a playlist that is itself going. Doesn't stamp. */
    suspend fun deleteByPlaylistId(playlistId: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteByPlaylistId(playlistId)
        changed(playlistId)
    }


    suspend fun deleteTrack(playlistId: String, position: Int): Unit = withContext(Dispatchers.Default) {
        queries.transaction {
            stamping(listOf(playlistId)) {
                queries.deleteTrack(playlistId = playlistId, position = position.toLong())
            }
        }
        changed(playlistId)
    }

    /**
     * Backfill an artwork URL onto a single playlist_track row, but only when
     * it currently has none — never overwrite art that came from the source
     * (Spotify / SoundCloud already give us per-track artwork).
     */
    /**
     * Fill a missing cover on the row at [position] — only if that row is still
     * [title] by [artist]. Positions shift on reorder/sync; the write must not
     * land on a different song.
     */
    suspend fun updateTrackArtwork(
        playlistId: String,
        position: Int,
        title: String,
        artist: String,
        artworkUrl: String,
    ): Unit =
        withContext(Dispatchers.Default) {
            queries.updateTrackArtwork(
                trackArtworkUrl = artworkUrl,
                playlistId = playlistId,
                position = position.toLong(),
                trackTitle = title,
                trackArtist = artist,
            )
            changed(playlistId)
        }

    /** Replace a dead cover URL on every playlist-track row that holds it. */
    suspend fun replaceTrackArtworkUrl(dead: String, fresh: String): Unit = withContext(Dispatchers.Default) {
        queries.replaceTrackArtworkUrl(fresh = fresh, dead = dead)
    }

    /**
     * Backfill resolver IDs onto a playlist_track row. Each parameter is
     * `COALESCE`-merged with the existing column, so a non-null source ID
     * (e.g. Spotify's canonical sync ID) is never overwritten — only NULL
     * slots get filled. Pass `null` for IDs that weren't discovered.
     *
     * Only touches the row at [position] if it is still [title] by [artist]:
     * a resolve can wait in a queue while the playlist is reordered or a sync
     * rewrites it, and the IDs must not land on another song.
     */
    suspend fun backfillResolverIds(
        playlistId: String,
        position: Int,
        title: String,
        artist: String,
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
            trackTitle = title,
            trackArtist = artist,
        )
    }

    /** Delete all tracks for a playlist then reinsert (a reorder, a pull, a
     *  hosted refresh), in one transaction. Stamps when the tracks changed. */
    suspend fun replaceAll(playlistId: String, tracks: List<PlaylistTrack>): Unit = withContext(Dispatchers.Default) {
        queries.transaction {
            stamping(listOf(playlistId)) {
                queries.deleteByPlaylistId(playlistId)
                insertRows(tracks)
            }
        }
        changed(playlistId)
    }
}
