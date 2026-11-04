package com.parachord.android.playback.handlers

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.BandcampEmbed
import com.parachord.shared.playback.BandcampEmbedResolver

/**
 * Plays Bandcamp tracks through Bandcamp's embedded player
 * ([BandcampEmbedBridge]), matching desktop (parachord#1231). Needs no sign-in.
 *
 * The embed plays by numeric track id. A track row has no column for it, so it
 * comes from the track's resolved sources; failing that, [BandcampEmbedResolver]
 * looks it up from the page. A track with neither, or that the embed won't start,
 * throws [EmbedPlaybackRefusedException], which
 * [com.parachord.android.playback.PlaybackController] treats as a failed source.
 */
class BandcampPlaybackHandler constructor(
    private val embed: BandcampEmbedBridge,
    private val embedResolver: BandcampEmbedResolver,
    private val trackResolverCache: TrackResolverCache,
) : SourceHandler, EmbedPlaybackHandler {

    companion object {
        private const val TAG = "BandcampPlayback"
    }

    @Volatile override var volumeFraction: Float = 1f

    override var onFinished: (() -> Unit)?
        get() = embed.onFinished
        set(value) { embed.onFinished = value }

    override fun canHandle(track: TrackEntity): Boolean = track.resolver == "bandcamp"

    override suspend fun createMediaItem(track: TrackEntity) = null // External playback

    override suspend fun play(track: TrackEntity) {
        val pageLink = BandcampEmbed.pageLink(track.sourceUrl)
        val embedUrl = embedResolver.embedUrl(trackIdFor(track), track.sourceUrl)
            ?: throw EmbedPlaybackRefusedException(BandcampEmbed.SERVICE_NAME, pageLink, "unplayable")
        val startedAt = System.currentTimeMillis()
        when (val result = embed.load(embedUrl, volumeFraction)) {
            BandcampEmbedBridge.LoadResult.Started ->
                Log.d(TAG, "Playing '${track.title}' in the embed (started in ${System.currentTimeMillis() - startedAt} ms)")
            is BandcampEmbedBridge.LoadResult.Refused -> {
                // 'superseded' means another play took over; the controller
                // has moved on, so this is no failure of the track's.
                if (result.reason == "superseded") return
                Log.w(TAG, "The embed didn't start '${track.title}' (after ${System.currentTimeMillis() - startedAt} ms)")
                throw EmbedPlaybackRefusedException(BandcampEmbed.SERVICE_NAME, pageLink, result.reason)
            }
        }
    }

    private suspend fun trackIdFor(track: TrackEntity): String? {
        trackResolverCache.awaitLoaded()
        return trackResolverCache.trackSources.value[trackKey(track.title, track.artist)]
            ?.firstOrNull { it.resolver == "bandcamp" && !it.bandcampTrackId.isNullOrEmpty() }
            ?.bandcampTrackId
    }

    override suspend fun pause() = embed.pause()
    override suspend fun resume() = embed.play()
    override suspend fun seekTo(positionMs: Long) = embed.seekTo(positionMs)
    override suspend fun stop() = embed.stop()

    override val isConnected: Boolean get() = true

    override fun getPosition(): Long = embed.positionMs
    override fun isPlaying(): Boolean = embed.isPlaying
    override fun getDuration(): Long = embed.durationMs
}
