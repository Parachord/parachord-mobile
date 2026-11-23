package com.parachord.android.playback.drm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodecList
import android.os.Build
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.drm.DeviceAudioCapabilities

/**
 * What this phone can play of a Dolby Atmos mix, by either route Android has
 * (Dolby's guide for car and phone media apps):
 * - **a decoder**: an E-AC-3 decoder plays an Atmos mix's 5.1 core, an E-AC-3
 *   JOC decoder full Atmos. Read once from `MediaCodecList`; a Pixel 9a has
 *   neither.
 * - **pass-through**: the current output takes the undecoded stream and decodes
 *   it itself (a car's amplifier, an HDMI soundbar). It changes with where audio
 *   goes, so it's asked on every read (`AudioTrack.isDirectPlaybackSupported`,
 *   Android 10 and later). ExoPlayer picks pass-through when both exist.
 *
 * With none of them, Playback Quality shows "This phone can't play Dolby Atmos"
 * and the play ignores the Atmos preference
 * ([com.parachord.shared.playback.drm.forDevice]).
 */
object AudioDecoders {
    const val EAC3 = "audio/eac3"
    const val EAC3_JOC = "audio/eac3-joc"

    private val decoders: DeviceAudioCapabilities by lazy { readDecoders() }

    /** The decoders (read once) and the current output's pass-through (read now). */
    val capabilities: DeviceAudioCapabilities
        get() = decoders.copy(
            eac3Passthrough = passthrough(AudioFormat.ENCODING_E_AC3),
            eac3JocPassthrough = passthrough(AudioFormat.ENCODING_E_AC3_JOC),
        )

    private fun readDecoders(): DeviceAudioCapabilities = try {
        val types = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.map(String::lowercase) }
            .toSet()
        DeviceAudioCapabilities(eac3 = EAC3 in types, eac3Joc = EAC3_JOC in types).also {
            Log.d(DRM_TAG, "Audio decoders: E-AC-3 ${it.eac3}, E-AC-3 JOC ${it.eac3Joc}")
        }
    } catch (e: Exception) {
        Log.w(DRM_TAG, "Couldn't read the audio decoders: ${e.message}")
        DeviceAudioCapabilities.NONE
    }

    /** Whether the current output takes [encoding] undecoded, as Dolby's guide checks it. */
    private fun passthrough(encoding: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            AudioTrack.isDirectPlaybackSupported(
                AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(encoding)
                    .setSampleRate(48_000)
                    .build(),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
        } catch (e: Exception) {
            false
        }
    }
}
