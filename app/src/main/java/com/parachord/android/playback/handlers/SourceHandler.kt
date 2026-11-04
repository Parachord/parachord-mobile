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
 * - SoundCloudPlaybackHandler: SoundCloud's embeddable widget (hidden WebView)
 * - BandcampPlaybackHandler: Bandcamp's embedded player (hidden WebView)
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

    /** Position of the current track, in ms. */
    fun getPosition(): Long

    /** Whether the service reports the current track playing. */
    fun isPlaying(): Boolean
}

/**
 * An external handler playing through a service's embedded player in a hidden
 * WebView (SoundCloud's widget, Bandcamp's embed). The controller mirrors these
 * the same way and treats their refusals as failed sources.
 */
interface EmbedPlaybackHandler : ExternalPlaybackHandler {
    /** Gain (0..1) for the next [play]: the resolver's dB offset, set by the controller. */
    var volumeFraction: Float

    /** Length of the current track in ms, from the player; 0 when unknown. */
    fun getDuration(): Long

    /** Called (on a background thread) when the current track finishes. */
    var onFinished: (() -> Unit)?
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

/**
 * A service's embedded player won't play this track here (SoundCloud's widget
 * loads a track SoundCloud won't let it embed and never starts it, desktop
 * parachord#1209; Bandcamp's embed doesn't start, #513). [com.parachord.android.playback.PlaybackController] treats
 * it as a failed source: another source plays, the queue moves on, or the user
 * is offered [pageUrl].
 */
class EmbedPlaybackRefusedException(
    val serviceName: String,
    /** The track's page on the service, for "Open in …"; null when unknown. */
    val pageUrl: String?,
    /** `preview` (only a 30-second preview plays), or why it won't play at all. */
    val reason: String,
) : Exception("$serviceName won't play this track here ($reason)")
