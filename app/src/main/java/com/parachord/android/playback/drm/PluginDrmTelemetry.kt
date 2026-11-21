package com.parachord.android.playback.drm

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.drm.PluginPlayerEvents

/**
 * The plugin's player events from ExoPlayer, for the session the player is
 * on (desktop's element listeners in widevine-player.js): `play-started` the
 * first time it plays, `resume` after that, `pause`, `seek`, `ended`, and
 * `stopped` when the player leaves the session. Registered on the service's
 * ExoPlayer, so it sees the same player the selector and the factory serve,
 * and it tells [PluginDrmSessions] which session is on the player.
 */
@OptIn(UnstableApi::class)
class PluginDrmTelemetry(
    private val player: ExoPlayer,
    private val sessions: PluginDrmSessions,
) : Player.Listener {

    private var lastPositionMs = 0L

    private fun currentSessionId(): String? =
        PluginDrmUris.sessionIdOf(player.currentMediaItem?.localConfiguration?.uri?.toString())

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        sessions.onPlayerSession(PluginDrmUris.sessionIdOf(mediaItem?.localConfiguration?.uri?.toString()), lastPositionMs)
        lastPositionMs = 0
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val session = sessions.get(currentSessionId()) ?: return
        lastPositionMs = player.currentPosition.coerceAtLeast(0)
        if (!isPlaying) return
        val event = session.tracker.onPlaying(lastPositionMs) {
            val format = player.audioFormat
            PluginPlayerEvents.playStarted(
                asin = session.extras.substituteAsin ?: session.extras.asin,
                albumAsin = session.albumAsin,
                durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: session.durationMs,
                initialDelaySeconds = sessions.sinceOpenedMs(session) / 1000.0,
                variantFacts = format?.let {
                    PluginPlayerEvents.VariantFacts(
                        codec = it.codecs?.substringBefore('.'),
                        sampleRate = it.sampleRate.takeIf { r -> r > 0 },
                        channels = it.channelCount.takeIf { c -> c > 0 },
                        bandwidth = it.bitrate.takeIf { b -> b > 0 }?.toLong(),
                    )
                },
                cdnType = session.extras.cdnType,
            ).also {
                Log.d(
                    DRM_TAG,
                    "Session ${session.id} playing ${format?.codecs ?: "?"} @ ${(format?.bitrate ?: 0) / 1000} kbps",
                )
            }
        }
        sessions.send(session.id, event)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady || player.playbackState == Player.STATE_ENDED) return
        val session = sessions.get(currentSessionId()) ?: return
        lastPositionMs = player.currentPosition.coerceAtLeast(0)
        sessions.send(session.id, session.tracker.onPaused(lastPositionMs))
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        val session = sessions.get(currentSessionId()) ?: return
        lastPositionMs = newPosition.positionMs
        sessions.send(session.id, session.tracker.onSeek(newPosition.positionMs))
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val session = sessions.get(currentSessionId()) ?: return
        if (playbackState != Player.STATE_ENDED) return
        lastPositionMs = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        sessions.send(session.id, session.tracker.onEnded(lastPositionMs, duration))
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (currentSessionId() != null) lastPositionMs = player.currentPosition.coerceAtLeast(0)
    }
}
