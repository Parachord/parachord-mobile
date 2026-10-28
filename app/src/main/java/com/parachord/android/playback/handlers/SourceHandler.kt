package com.parachord.android.playback.handlers

import androidx.media3.common.MediaItem
import com.parachord.android.data.db.entity.TrackEntity

/**
 * Routes resolved sources to appropriate playback mechanisms.
 *
 * Each handler knows how to create a MediaItem (or trigger an external app)
 * for its source type:
 * - DirectStreamHandler: ExoPlayer with URL + headers
 * - LocalFileHandler: ExoPlayer with content:// URI
 * - SpotifyPlaybackHandler: Spotify Web API (Spotify Connect)
 * - SoundCloudPlaybackHandler: SoundCloud stream API + ExoPlayer
 */
interface SourceHandler {
    /** Whether this handler can play the given source type or resolver. */
    fun canHandle(track: TrackEntity): Boolean

    /** Create a MediaItem for ExoPlayer, or null if playback is external (e.g. Spotify App Remote). */
    suspend fun createMediaItem(track: TrackEntity): MediaItem?
}

/** Result of preparing playback — either ExoPlayer-based or externally managed. */
sealed class PlaybackAction {
    /** Play through ExoPlayer with this MediaItem. */
    data class ExoPlayerItem(val mediaItem: MediaItem) : PlaybackAction()
    /** Playback is managed externally (e.g. Spotify App Remote). Position/state updates come from the handler. */
    data class ExternalPlayback(val handler: ExternalPlaybackHandler) : PlaybackAction()
    /** Open URL in the system browser (for non-streaming resolvers like Bandcamp). */
    data class BrowserPlayback(val url: String, val track: com.parachord.android.data.db.entity.TrackEntity) : PlaybackAction()
}

/**
 * Extended handler for sources that manage their own playback lifecycle
 * (e.g. Spotify App Remote, which controls the Spotify app directly).
 */
interface ExternalPlaybackHandler {
    suspend fun play(track: TrackEntity)
    suspend fun pause()
    suspend fun resume()
    suspend fun seekTo(positionMs: Long)
    suspend fun stop()
    val isConnected: Boolean
}

class DirectStreamHandler : SourceHandler {
    override fun canHandle(track: TrackEntity): Boolean =
        track.resolver == null && (track.sourceType == "direct" || track.sourceType == "stream")

    override suspend fun createMediaItem(track: TrackEntity): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.sourceUrl ?: "")
            .build()
}

class LocalFileHandler : SourceHandler {
    override fun canHandle(track: TrackEntity): Boolean =
        track.sourceType == "local" || track.resolver == "localfiles"

    override suspend fun createMediaItem(track: TrackEntity): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.sourceUrl ?: "")
            .build()
}

/**
 * A handler's play didn't start within its time limit: usually the network
 * dropped mid-start. [com.parachord.android.playback.PlaybackController]
 * catches it, stops the spinner and says so, instead of holding the playback
 * lock until the app is killed.
 */
class PlaybackStartTimeoutException(message: String) : Exception(message)
