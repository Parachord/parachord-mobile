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
     * Whether a resolver's loudness can actually be controlled in-app.
     *
     * **Apple Music cannot be**, on any platform, and a slider that pretended
     * otherwise would be a placebo. iOS has no per-app volume and
     * `ApplicationMusicPlayer` follows the system volume; desktop reached the
     * same conclusion and greys the row out as "System vol". Resolvers that
     * hand playback to a browser (bandcamp, youtube) are equally out of reach.
     */
    fun isVolumeControllable(resolverId: String): Boolean =
        resolverId !in UNCONTROLLABLE_RESOLVERS

    /** Why [isVolumeControllable] said no — shown next to a disabled slider. */
    fun uncontrollableReason(resolverId: String): String? = when (resolverId) {
        "applemusic" -> "System vol"
        in UNCONTROLLABLE_RESOLVERS -> "Opens in browser"
        else -> null
    }

    private val UNCONTROLLABLE_RESOLVERS = setOf("applemusic", "bandcamp", "youtube")
}
