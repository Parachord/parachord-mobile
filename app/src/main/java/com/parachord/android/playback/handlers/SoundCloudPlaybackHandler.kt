package com.parachord.android.playback.handlers

import com.parachord.shared.platform.Log
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.playback.SoundCloudWidget

/**
 * Plays SoundCloud tracks through SoundCloud's embeddable widget
 * ([SoundCloudWidgetBridge]), matching desktop (parachord#1209).
 *
 * This used to fetch a stream URL from the API and hand it to ExoPlayer, the
 * port of desktop's old path. The API no longer gives full tracks (see
 * [SoundCloudWidgetBridge]), so that ended on a preview URL that answers
 * 403/429. Playing needs no sign-in now; search and resolution still use the
 * API and the user's SoundCloud connection.
 *
 * A track the widget won't play throws [EmbedPlaybackRefusedException], which
 * [com.parachord.android.playback.PlaybackController] treats as a failed
 * source: another source plays, or the queue moves on, or the user is offered
 * the track on SoundCloud.
 */
class SoundCloudPlaybackHandler constructor(
    private val widget: SoundCloudWidgetBridge,
) : SourceHandler, ExternalPlaybackHandler {

    companion object {
        private const val TAG = "SoundCloudPlayback"
        const val SERVICE_NAME = SoundCloudWidget.SERVICE_NAME

        /** What the widget loads ([SoundCloudWidget.widgetLink]). */
        fun widgetLink(track: TrackEntity): String? =
            SoundCloudWidget.widgetLink(url = track.sourceUrl, soundcloudUrl = null, soundcloudId = track.soundcloudId)

        /** The track's soundcloud.com page, for "Open in SoundCloud" ([SoundCloudWidget.pageLink]). */
        fun pageLink(track: TrackEntity): String? =
            SoundCloudWidget.pageLink(url = track.sourceUrl, soundcloudUrl = null)
    }

    /** Gain (0..1) for the next [play]: the resolver's dB offset, set by the controller. */
    @Volatile var volumeFraction: Float = 1f

    val bridge: SoundCloudWidgetBridge get() = widget

    override fun canHandle(track: TrackEntity): Boolean =
        track.resolver == "soundcloud" && widgetLink(track) != null

    override suspend fun createMediaItem(track: TrackEntity) = null // External playback

    override suspend fun play(track: TrackEntity) {
        val link = widgetLink(track)
            ?: throw EmbedPlaybackRefusedException(SERVICE_NAME, null, "no track link")
        val startedAt = System.currentTimeMillis()
        when (val result = widget.load(link, volumeFraction)) {
            is SoundCloudWidgetBridge.LoadResult.Started ->
                Log.d(TAG, "Playing '${track.title}' in the widget (started in ${System.currentTimeMillis() - startedAt} ms)")
            is SoundCloudWidgetBridge.LoadResult.Refused -> {
                // 'superseded' means another play took over; the controller
                // has moved on, so this is no failure of the track's.
                if (result.reason == "superseded") return
                Log.w(TAG, "The widget won't play '${track.title}' (${result.reason}, after ${System.currentTimeMillis() - startedAt} ms)")
                throw EmbedPlaybackRefusedException(SERVICE_NAME, pageLink(track), result.reason)
            }
        }
    }

    override suspend fun pause() = widget.pause()
    override suspend fun resume() = widget.play()
    override suspend fun seekTo(positionMs: Long) = widget.seekTo(positionMs)
    override suspend fun stop() = widget.stop()

    override val isConnected: Boolean get() = true

    override fun getPosition(): Long = widget.positionMs
    override fun isPlaying(): Boolean = widget.isPlaying
    fun getDuration(): Long = widget.durationMs
}
