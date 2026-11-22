package com.parachord.android.playback.drm

import android.media.MediaCodecList
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.drm.DeviceAudioCapabilities

/**
 * This phone's Dolby decoders, read once from `MediaCodecList`: an E-AC-3
 * decoder plays an Atmos mix's 5.1 core, an E-AC-3 JOC decoder full Atmos.
 * Without either, Playback Quality shows "This phone can't play Dolby Atmos"
 * and the Atmos preference is ignored ([com.parachord.shared.playback.drm.forDevice]).
 * A Pixel 9a has neither.
 */
object AudioDecoders {
    const val EAC3 = "audio/eac3"
    const val EAC3_JOC = "audio/eac3-joc"

    val capabilities: DeviceAudioCapabilities by lazy { read() }

    private fun read(): DeviceAudioCapabilities = try {
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
}
