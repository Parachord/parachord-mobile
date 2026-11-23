package com.parachord.shared.playback.drm

import com.parachord.shared.plugin.PluginManager

/**
 * Which services follow Settings › General › Playback Quality, what each music
 * plug-in's sheet says about its quality, the General section's copy, Apple
 * Music's MusicKit bitrate and Now Playing's quality label. Desktop's rules
 * (`settings-page.js` Playback Quality section, `musickit-web.js setQuality`,
 * `widevine-quality.js`), in desktop's words. Pure.
 */

/** The app a rule is asked for: iOS has no Widevine engine and its Apple Music ignores the tier. */
enum class PlaybackPlatform { ANDROID, IOS }

/**
 * What this device can play of a Dolby Atmos mix, by either of the two routes
 * Android has (Dolby's guide for car and phone media apps):
 * - a decoder in the device (Android reads `MediaCodecList` for `audio/eac3`
 *   and `audio/eac3-joc`, once);
 * - pass-through: the current output takes the undecoded stream and decodes it
 *   itself (a car's amplifier, an HDMI soundbar). It depends on where audio is
 *   going right now, so Android asks the platform each time
 *   (`AudioTrack.isDirectPlaybackSupported`). ExoPlayer picks pass-through
 *   when both exist.
 * iOS has no plugin DRM engine, so [NONE].
 */
data class DeviceAudioCapabilities(
    /** An E-AC-3 (Dolby Digital Plus) decoder: plays an Atmos mix's 5.1 core. */
    val eac3: Boolean = false,
    /** An E-AC-3 JOC decoder (full Dolby Atmos). */
    val eac3Joc: Boolean = false,
    /** The current output takes E-AC-3 undecoded. */
    val eac3Passthrough: Boolean = false,
    /** The current output takes E-AC-3 JOC (Dolby Atmos) undecoded. */
    val eac3JocPassthrough: Boolean = false,
) {
    /** Desktop plays an Atmos mix's E-AC-3 core, so any of the four is enough. */
    val canPlayAtmos: Boolean get() = eac3 || eac3Joc || eac3Passthrough || eac3JocPassthrough

    companion object {
        val NONE = DeviceAudioCapabilities()
    }
}

/** The setting as the engine should apply it here: no Atmos preference where nothing can decode it. */
fun PlaybackQualitySettings.forDevice(device: DeviceAudioCapabilities): PlaybackQualitySettings =
    if (spatial && !device.canPlayAtmos) copy(spatial = false) else this

/** Desktop's Settings labels for the tiers. */
val PlaybackQualityTier.settingsLabel: String
    get() = when (this) {
        PlaybackQualityTier.MAX -> "Max (best available, lossless where playable)"
        PlaybackQualityTier.HIGH -> "High (up to 500 kbps)"
        PlaybackQualityTier.STANDARD -> "Standard (up to 200 kbps)"
        PlaybackQualityTier.SAVER -> "Data saver (up to 100 kbps)"
    }

/** The same label as a plug-in sheet's summary ("Max: best available, lossless where playable"). */
val PlaybackQualityTier.summaryLabel: String
    get() = when (this) {
        PlaybackQualityTier.MAX -> "Max: best available, lossless where playable"
        PlaybackQualityTier.HIGH -> "High: up to 500 kbps"
        PlaybackQualityTier.STANDARD -> "Standard: up to 200 kbps"
        PlaybackQualityTier.SAVER -> "Data saver: up to 100 kbps"
    }

/** The quality line in a music plug-in's settings sheet. */
sealed class PlaybackQualityLine {
    /** The plug-in plays at the Playback Quality setting; tapping opens it. */
    data class Follows(val summary: String) : PlaybackQualityLine()

    /** Something outside Parachord sets it; [detail] says where, when there's more to say. */
    data class ExternalControl(val title: String, val detail: String?) : PlaybackQualityLine()

    companion object {
        const val ADJUST_SUFFIX = " · Adjust to your connection"
        const val ATMOS_SUFFIX = " · Dolby Atmos when available"
        /** A plug-in's FairPlay HLS on iOS: AAC up to 256 kbps (#794). */
        const val IOS_FAIRPLAY_TITLE = "Up to 256 kbps AAC"

        /**
         * The line for [plugin], or null for a plug-in with no rule. Follows
         * is decided by capability: a Widevine plug-in (Android only), and
         * Apple Music where the engine honours the tier (Android's MusicKit
         * JS; iOS's MusicKit follows iOS's own setting).
         */
        fun forPlugin(
            plugin: PluginManager.PluginInfo,
            platform: PlaybackPlatform,
            settings: PlaybackQualitySettings,
            device: DeviceAudioCapabilities,
        ): PlaybackQualityLine? {
            if (followsWidevine(plugin, platform)) {
                var summary = settings.tier.summaryLabel
                if (settings.adaptive) summary += ADJUST_SUFFIX
                if (settings.spatial && device.canPlayAtmos) summary += ATMOS_SUFFIX
                return Follows(summary)
            }
            if (platform == PlaybackPlatform.IOS && plugin.capabilities["widevine"] == true && plugin.hasDrmPair) {
                // FairPlay HLS on iOS (#794): AAC only, whatever the plan.
                return ExternalControl(
                    IOS_FAIRPLAY_TITLE,
                    "${plugin.name} streams AAC on iPhone and iPad. Parachord’s Playback Quality doesn’t change it yet.",
                )
            }
            if (plugin.capabilities["html5Stream"] == true) {
                return ExternalControl("Set by your server", "Your server’s transcoding settings decide it.")
            }
            return when (plugin.id) {
                APPLE_MUSIC -> if (platform == PlaybackPlatform.ANDROID) {
                    Follows(settings.tier.summaryLabel)
                } else {
                    ExternalControl(
                        "Set in iOS Settings",
                        "Settings › Music › Audio Quality. Parachord’s Playback Quality doesn’t change it.",
                    )
                }
                "spotify" -> ExternalControl(
                    "Set in the Spotify app",
                    "Spotify › Settings › Audio quality. Parachord’s Playback Quality doesn’t change it.",
                )
                "soundcloud", "bandcamp" -> ExternalControl(
                    "Set by its player",
                    "${plugin.name} chooses the quality; it can’t be changed.",
                )
                "localfiles" -> ExternalControl("Plays the file as it is", null)
                else -> null
            }
        }

        /** The plug-in plays through the plugin-supplied Widevine engine here. */
        fun followsWidevine(plugin: PluginManager.PluginInfo, platform: PlaybackPlatform): Boolean =
            platform == PlaybackPlatform.ANDROID && plugin.capabilities["widevine"] == true && plugin.hasDrmPair

        private const val APPLE_MUSIC = "applemusic"
    }
}

/** The Playback Quality section's copy (Settings › General), from the plug-ins loaded. */
data class PlaybackQualityCopy(
    /** "Amazon Music and Apple Music use these settings. Other services choose their own quality." */
    val description: String,
    /** "Adjust to your connection"'s hint. */
    val adjustHint: String,
    /** "Prefer Dolby Atmos mixes"'s hint. */
    val atmosHint: String,
) {
    companion object {
        const val ADJUST_BODY = "Lowers quality when your connection is slow, never above the level you chose."
        const val ATMOS_BODY = "Plays the 5.1 core of a track’s Atmos mix when there is one. " +
            "This isn’t full Atmos: it’s mixed down on stereo output, and it plays instead of lossless FLAC when quality is Max."
        /** Shown in place of the Atmos switch on a phone with no E-AC-3 decoder. */
        const val NO_ATMOS_DECODER = "This phone can’t play Dolby Atmos"
        const val NO_ATMOS_DETAIL =
            "Dolby Atmos needs a Dolby decoder in the phone, or speakers or a car that take Dolby Atmos directly."

        /** [plugins] are the loaded plug-ins; the names come from the same rule as each sheet's line. */
        fun build(plugins: List<PluginManager.PluginInfo>, platform: PlaybackPlatform): PlaybackQualityCopy {
            val follows = plugins
                .filter {
                    PlaybackQualityLine.forPlugin(it, platform, PlaybackQualitySettings(), DeviceAudioCapabilities.NONE) is
                        PlaybackQualityLine.Follows
                }
                .map { it.name }.distinct().sorted()
            val widevine = plugins.filter { PlaybackQualityLine.followsWidevine(it, platform) }
                .map { it.name }.distinct().sorted()
            val description = when (follows.size) {
                0 -> "None of your plug-ins use these settings yet. Services choose their own quality."
                1 -> "${follows[0]} uses these settings. Other services choose their own quality."
                else -> "${joinNames(follows)} use these settings. Other services choose their own quality."
            }
            val prefix = if (widevine.isEmpty()) "None of your plug-ins use this yet." else "${joinNames(widevine)} only."
            return PlaybackQualityCopy(description, "$prefix $ADJUST_BODY", "$prefix $ATMOS_BODY")
        }

        /** "A", "A and B", "A, B and C". */
        fun joinNames(names: List<String>): String = when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }
}

/**
 * Apple Music's MusicKit JS bitrate for a tier (desktop's
 * `musicKitBitrateForQuality`): MusicKit has two, so Data saver is its
 * standard bitrate and every other tier its high one.
 */
object MusicKitQuality {
    const val HIGH_KBPS = 256
    const val STANDARD_KBPS = 64

    fun bitrateFor(tier: PlaybackQualityTier?): Int =
        if (tier == PlaybackQualityTier.SAVER) STANDARD_KBPS else HIGH_KBPS
}

/**
 * Now Playing's quality label for what the player actually picked: "Dolby
 * Atmos" for a spatial family, "Lossless" for FLAC, else nothing. Android
 * reads it from a plugin DRM session's selected audio format; iOS has no
 * DRM engine yet and can use it once it does.
 */
fun qualityLabel(codecFamily: String?, isSpatial: Boolean): String? = when {
    isSpatial || codecFamily in CodecFamilies.SPATIAL -> DOLBY_ATMOS_LABEL
    codecFamily == CodecFamilies.FLAC -> "Lossless"
    else -> null
}

/** [qualityLabel]'s name for a Dolby Atmos mix. */
const val DOLBY_ATMOS_LABEL = "Dolby Atmos"

/**
 * Whether the playing format, as [qualityLabel] named it, is immersive audio.
 * A car reads this as the media session's immersive-audio extra and should then
 * leave out sound effects that would interfere with it (Android for Cars,
 * "Indicate audio format").
 */
fun isImmersiveQuality(label: String?): Boolean = label == DOLBY_ATMOS_LABEL

/** A format's family from its codecs string, else its sample MIME type (`audio/flac`, `audio/eac3-joc`). */
fun codecFamilyOf(codecs: String?, sampleMimeType: String?): String? {
    CodecFamilies.of(codecs)?.let { return it }
    return when (sampleMimeType?.lowercase()) {
        null -> null
        "audio/flac" -> CodecFamilies.FLAC
        "audio/opus" -> CodecFamilies.OPUS
        "audio/mp4a-latm", "audio/aac" -> CodecFamilies.AAC
        "audio/eac3", "audio/eac3-joc" -> CodecFamilies.EC3
        "audio/ac4" -> CodecFamilies.AC4
        "audio/mhm1", "audio/mha1" -> CodecFamilies.MPEGH
        else -> null
    }
}
