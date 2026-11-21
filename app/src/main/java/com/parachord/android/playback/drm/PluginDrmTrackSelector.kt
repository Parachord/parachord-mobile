package com.parachord.android.playback.drm

import android.content.Context
import android.util.Pair
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.drm.DrmVariant
import com.parachord.shared.playback.drm.PlaybackQuality
import com.parachord.shared.playback.drm.PlaybackQualityConfig
import com.parachord.shared.playback.drm.PlaybackQualitySettings

/**
 * The quality policy over ExoPlayer's audio tracks (pure, testable): the
 * shared [PlaybackQuality] picks the variant, and the choice is that track
 * (locked), or (adaptive) the tracks of the same adaptation set at or below
 * the policy's cap. ExoPlayer adapts within one group only, and the plugin's
 * manifests put each codec family in its own adaptation set, so adaptive
 * roams the picked family's ladder (desktop roams across families under the
 * ceiling; a documented difference).
 */
object PluginDrmSelection {
    /** One audio track: its group and index, whether the decoder handles it, and its variant. */
    data class Track(val group: Int, val index: Int, val handled: Boolean, val variant: DrmVariant)

    data class Choice(val group: Int, val tracks: IntArray, val config: PlaybackQualityConfig, val picked: DrmVariant) {
        override fun equals(other: Any?): Boolean =
            other is Choice && group == other.group && tracks.contentEquals(other.tracks) && config == other.config && picked == other.picked
        override fun hashCode(): Int = group * 31 + tracks.contentHashCode()
    }

    fun choose(tracks: List<Track>, quality: PlaybackQualitySettings, blockedFamilies: Set<String>): Choice? {
        // Desktop's playable roster: what this build decodes, refused families dropped.
        val playable = tracks.filter {
            it.handled && PlaybackQuality.variantPlayable(it.variant, quality.spatial, blockedFamilies)
        }
        val config = PlaybackQuality.config(
            playable.map { it.variant }, quality.tier, quality.adaptive, quality.spatial,
            allVariants = tracks.map { it.variant },
        ) ?: return null
        val picked = playable.first { it.variant.id == config.variantId }
        val chosen = if (!config.adaptive) {
            intArrayOf(picked.index)
        } else {
            val cap = config.maxBandwidth ?: Long.MAX_VALUE
            playable
                .filter { it.group == picked.group && it.variant.bandwidth <= cap }
                .map { it.index }
                .plus(picked.index)
                .distinct()
                .sorted()
                .toIntArray()
        }
        return Choice(picked.group, chosen, config, picked.variant)
    }
}

/**
 * ExoPlayer's track selector: while the player is on a plugin DRM session
 * ([PluginDrmSessions.selectionPolicy]), the audio choice follows the user's
 * Playback Quality ([PluginDrmSelection]); otherwise, and when the policy
 * finds nothing playable, ExoPlayer's own choice, unchanged.
 */
@OptIn(UnstableApi::class)
class PluginDrmTrackSelector(
    context: Context,
    private val sessions: PluginDrmSessions,
) : DefaultTrackSelector(context) {

    override fun selectAudioTrack(
        mappedTrackInfo: MappingTrackSelector.MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: Parameters,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        val policy = sessions.selectionPolicy()
            ?: return super.selectAudioTrack(mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params)
        for (renderer in 0 until mappedTrackInfo.rendererCount) {
            if (mappedTrackInfo.getRendererType(renderer) != C.TRACK_TYPE_AUDIO) continue
            val groups = mappedTrackInfo.getTrackGroups(renderer)
            val tracks = buildList {
                for (g in 0 until groups.length) {
                    val group = groups[g]
                    for (t in 0 until group.length) {
                        val f = group.getFormat(t)
                        val handled = RendererCapabilities.getFormatSupport(rendererFormatSupports[renderer][g][t]) == C.FORMAT_HANDLED
                        add(PluginDrmSelection.Track(g, t, handled, f.toVariant("$g/$t")))
                    }
                }
            }
            if (tracks.isEmpty()) continue
            val choice = PluginDrmSelection.choose(tracks, policy.quality, policy.blockedFamilies) ?: continue
            Log.d(
                DRM_TAG,
                "Session ${policy.sessionId}: ${policy.quality.tier.key}${if (choice.config.adaptive) " adaptive" else " locked"} → " +
                    "${choice.picked.codecs} @ ${choice.picked.bandwidth / 1000} kbps (${choice.tracks.size} track(s) in the set)" +
                    if (policy.blockedFamilies.isNotEmpty()) ", refused families ${policy.blockedFamilies}" else "",
            )
            return Pair(ExoTrackSelection.Definition(groups[choice.group], *choice.tracks), renderer)
        }
        Log.w(DRM_TAG, "Session ${policy.sessionId}: nothing playable under the quality policy; ExoPlayer chooses")
        return super.selectAudioTrack(mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params)
    }

    private fun Format.toVariant(id: String) = DrmVariant(
        id = id,
        codecs = codecs,
        bandwidth = bitrate.takeIf { it != Format.NO_VALUE }?.toLong() ?: 0L,
        sampleRate = sampleRate.takeIf { it != Format.NO_VALUE },
        channels = channelCount.takeIf { it != Format.NO_VALUE },
    )
}
