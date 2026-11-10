package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import kotlinx.serialization.Serializable

/**
 * Serializable representation of a track for queue persistence.
 * Mirrors [TrackEntity] fields but without Room annotations.
 */
@Serializable
data class SerializableTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumId: String? = null,
    val duration: Long? = null,
    val artworkUrl: String? = null,
    val sourceType: String? = null,
    val sourceUrl: String? = null,
    val resolver: String? = null,
    val spotifyUri: String? = null,
    val spotifyId: String? = null,
    val soundcloudId: String? = null,
    val appleMusicId: String? = null,
)

/**
 * Full persisted queue state — serialized to JSON in DataStore.
 */
@Serializable
data class PersistedQueueState(
    val currentTrack: SerializableTrack? = null,
    val upNext: List<SerializableTrack> = emptyList(),
    val playHistory: List<SerializableTrack> = emptyList(),
    val playbackContext: PlaybackContext? = null,
    val shuffleEnabled: Boolean = false,
    val originalOrder: List<SerializableTrack>? = null,
    /**
     * Whether each [playHistory] entry belongs to the queue (repeat all wraps
     * only those, `QueueManager.historyInQueue`). Null or short in an older
     * blob: the missing entries count as in the queue.
     */
    val playHistoryInQueue: List<Boolean>? = null,
    /** The current track was a spinoff pool track (`QueueManager.currentIsPoolTrack`). */
    val currentIsPoolTrack: Boolean = false,
    /** Whether each [upNext] entry belongs to the lap; null or short = in the lap. */
    val upNextInLap: List<Boolean>? = null,
    /** The current track belongs to the lap (`QueueManager.currentInLap`). */
    val currentInLap: Boolean = true,
)

fun TrackEntity.toSerializable() = SerializableTrack(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    duration = duration,
    artworkUrl = artworkUrl,
    sourceType = sourceType,
    sourceUrl = sourceUrl,
    resolver = resolver,
    spotifyUri = spotifyUri,
    spotifyId = spotifyId,
    soundcloudId = soundcloudId,
    appleMusicId = appleMusicId,
)

fun SerializableTrack.toTrackEntity() = TrackEntity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    duration = duration,
    artworkUrl = artworkUrl,
    sourceType = sourceType,
    sourceUrl = sourceUrl,
    resolver = resolver,
    spotifyUri = spotifyUri,
    spotifyId = spotifyId,
    soundcloudId = soundcloudId,
    appleMusicId = appleMusicId,
)
