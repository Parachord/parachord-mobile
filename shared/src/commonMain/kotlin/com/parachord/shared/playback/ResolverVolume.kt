package com.parachord.shared.playback

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Per-resolver loudness balancing, shared so every platform computes the same
 * number from the same stored offsets (`SettingsStore.getResolverVolumeOffsets`).
 *
 * Byte-parity with desktop's `getEffectiveVolume` (`app.js`):
 *
 * ```js
 * const multiplier = Math.pow(10, totalOffsetDb / 20);
 * const effectiveVolume = Math.max(0, Math.min(100, baseVolume * multiplier));
 * ```
 *
 * Sources are mastered at wildly different levels — Spotify normalizes to
 * -14 LUFS, a YouTube rip or a loud Bandcamp master can be several dB hotter —
 * so switching resolvers mid-queue otherwise means lunging for the volume.
 */
object ResolverVolume {

    /** Slider bounds, matching desktop's `min: '-12', max: '6', step: '1'`. */
    const val MIN_OFFSET_DB = -12
    const val MAX_OFFSET_DB = 6

    /**
     * [baseVolume] (0–100) attenuated by [offsetDb], as a 0–100 percentage.
     *
     * Note the asymmetry when [baseVolume] is already 100 (iOS, which has no
     * in-app volume slider): a POSITIVE offset multiplies above 100 and clamps
     * straight back to 100, so it is inert. That is correct rather than a bug
     * — these offsets exist to bring loud sources DOWN to the quietest one,
     * and you cannot amplify past a player's maximum. The way to make a quiet
     * source louder is to stop attenuating the others.
     */
    fun effectiveVolumePercent(baseVolume: Int, offsetDb: Int): Int =
        (baseVolume * 10.0.pow(offsetDb / 20.0)).roundToInt().coerceIn(0, 100)

    /** Convenience for AVPlayer/ExoPlayer, which take a 0.0–1.0 gain. */
    fun effectiveVolumeFraction(baseVolume: Int, offsetDb: Int): Float =
        effectiveVolumePercent(baseVolume, offsetDb) / 100f

    /**
     * What a plug-in's volume row offers, decided by the plug-in's declared
     * capabilities and never by its id (desktop parachord#1522, settings-page.js).
     *
     * - `capabilities.systemVolume` (its player follows the system volume:
     *   Apple Music's MusicKit): off, reason "System vol".
     * - Neither `stream` nor `embedPlayer` (it plays in the browser): off,
     *   reason "Browser".
     * - **iOS difference ([embedVolumeApplies] false):** a plug-in that plays in
     *   an embedded page (`embedPlayer`: SoundCloud's widget, Bandcamp's embed)
     *   is off too, reason "System vol". Both pages set an HTML media element's
     *   `volume`, which WebKit on iOS ignores (the element follows the device
     *   volume), so a slider there would be a placebo. Android's WebView
     *   applies it, so Android passes true.
     * - Otherwise on. Unknown capabilities ([capabilities] null, the plug-in
     *   not loaded yet) count as on.
     */
    fun controllability(capabilities: Map<String, Boolean>?, embedVolumeApplies: Boolean): VolumeControl {
        if (capabilities == null) return VolumeControl.ON
        val systemVolume = capabilities["systemVolume"] == true
        val stream = capabilities["stream"] == true
        val embed = capabilities["embedPlayer"] == true
        return when {
            systemVolume -> VolumeControl(false, REASON_SYSTEM, TipKind.SYSTEM)
            !stream && !embed -> VolumeControl(false, REASON_BROWSER, TipKind.BROWSER)
            embed && !embedVolumeApplies -> VolumeControl(false, REASON_SYSTEM, TipKind.SYSTEM)
            else -> VolumeControl.ON
        }
    }

    const val REASON_SYSTEM = "System vol"
    const val REASON_BROWSER = "Browser"

    /**
     * A changed offset is re-applied at once only to the playback it belongs
     * to: the track now playing came from [changedResolver].
     */
    fun reappliesLive(changedResolver: String, currentResolver: String?): Boolean =
        currentResolver != null && currentResolver == changedResolver

    enum class TipKind { SYSTEM, BROWSER }

    data class VolumeControl(val controllable: Boolean, val reason: String?, val tipKind: TipKind?) {
        /** Desktop's tooltip for a disabled row. */
        fun tip(pluginName: String): String? = when (tipKind) {
            TipKind.SYSTEM -> "Use system volume to control $pluginName"
            TipKind.BROWSER -> "Browser playback - volume control not available"
            null -> null
        }

        companion object {
            val ON = VolumeControl(true, null, null)
        }
    }
}
