package com.parachord.android.playback

import com.parachord.shared.platform.Log
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.handlers.DirectStreamHandler
import com.parachord.android.playback.handlers.ExternalPlaybackHandler
import com.parachord.android.playback.handlers.LocalFileHandler
import com.parachord.android.playback.handlers.PlaybackAction
import com.parachord.android.playback.handlers.AppleMusicPlaybackHandler
import com.parachord.android.playback.handlers.BandcampPlaybackHandler
import com.parachord.android.playback.handlers.SoundCloudPlaybackHandler
import com.parachord.android.playback.handlers.SourceHandler
import com.parachord.android.playback.handlers.SpotifyPlaybackHandler

/**
 * Routes tracks to the appropriate playback handler based on resolver type.
 *
 * The routing order matches the desktop app's logic:
 * 1. Spotify tracks → Web API / Spotify Connect (external playback)
 * 2. Apple Music tracks → MusicKit JS WebView (external playback)
 * 3. SoundCloud tracks → SoundCloud's embeddable widget (hidden WebView)
 * 4. Bandcamp tracks → Bandcamp's embedded player (hidden WebView)
 * 5. Local files → ExoPlayer with content:// URI
 * 6. Direct streams → ExoPlayer with HTTP URL
 */
class PlaybackRouter constructor(
    private val spotifyHandler: SpotifyPlaybackHandler,
    private val appleMusicHandler: AppleMusicPlaybackHandler,
    private val soundCloudHandler: SoundCloudPlaybackHandler,
    private val bandcampHandler: BandcampPlaybackHandler,
    private val pluginManager: com.parachord.android.plugin.PluginManager,
) {
    companion object {
        private const val TAG = "PlaybackRouter"
    }

    private val localFileHandler = LocalFileHandler()
    private val directStreamHandler = DirectStreamHandler()

    /** The handler currently managing external playback, if any. */
    var activeExternalHandler: ExternalPlaybackHandler? = null
        private set

    /** All handlers in priority order. */
    private val handlers: List<SourceHandler>
        get() = listOf(spotifyHandler, appleMusicHandler, soundCloudHandler, localFileHandler, directStreamHandler)

    /**
     * Determine how to play a track and return the appropriate action.
     * Returns null if no handler can play this track.
     */
    suspend fun route(track: TrackEntity): PlaybackAction? {
        // Bandcamp plays in its embed (#513). Ahead of the check below, because
        // bandcamp.axe keeps `stream: false` so older app versions still open the
        // browser, and ahead of the handler walk, because a track re-routed here
        // can still carry another service's ids.
        if (bandcampHandler.canHandle(track)) {
            Log.d(TAG, "Routing '${track.title}' to BandcampPlaybackHandler")
            stopExternalPlayback()
            activeExternalHandler = bandcampHandler
            return PlaybackAction.ExternalPlayback(bandcampHandler)
        }

        // Check if this track's resolver is another non-streaming .axe plugin.
        // These plugins resolve tracks (find them) but can't stream audio — open in browser.
        val resolver = track.resolver
        if (resolver != null && track.sourceUrl != null) {
            val plugin = pluginManager.plugins.value.find { it.id == resolver }
            if (plugin != null && plugin.capabilities["stream"] == false && plugin.capabilities["resolve"] == true) {
                Log.d(TAG, "Non-streaming resolver '$resolver' — routing to browser: ${track.sourceUrl}")
                return PlaybackAction.BrowserPlayback(track.sourceUrl!!, track)
            }
        }

        // Find the first handler that can handle this track
        for (handler in handlers) {
            if (!handler.canHandle(track)) continue

            Log.d(TAG, "Routing '${track.title}' to ${handler::class.simpleName}")

            // External playback handlers manage their own lifecycle (Spotify, Apple Music, SoundCloud)
            if (handler is ExternalPlaybackHandler) {
                stopExternalPlayback()
                activeExternalHandler = handler
                return PlaybackAction.ExternalPlayback(handler)
            }

            // All other handlers produce MediaItems for ExoPlayer
            val mediaItem = handler.createMediaItem(track)
            if (mediaItem != null) {
                stopExternalPlayback()
                return PlaybackAction.ExoPlayerItem(mediaItem)
            }
        }

        Log.w(TAG, "No handler found for track: ${track.title} (resolver=${track.resolver}, sourceType=${track.sourceType})")
        return null
    }

    /** Stop any externally-managed playback. */
    suspend fun stopExternalPlayback() {
        activeExternalHandler?.stop()
        activeExternalHandler = null
    }

    /** Get the Spotify handler for state observation. */
    fun getSpotifyHandler(): SpotifyPlaybackHandler = spotifyHandler

    /** Get the Apple Music handler for state observation. */
    fun getAppleMusicHandler(): AppleMusicPlaybackHandler = appleMusicHandler

    /** Get the SoundCloud handler for state observation. */
    fun getSoundCloudHandler(): SoundCloudPlaybackHandler = soundCloudHandler
}
