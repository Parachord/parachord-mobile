package com.parachord.shared.playback.drm

/**
 * The global playback-quality setting for plugin-supplied Widevine streams
 * (Amazon Music today), and the pure choice of one variant of a manifest. A
 * port of the POLICY half of desktop's `src/renderer/lib/widevine-quality.js`
 * (its Shaka configuration is desktop's engine, not policy): the vectors in
 * `PlaybackQualityTest` are desktop's `widevine-quality.test.js`.
 *
 * Tiers are bandwidth ceilings and nothing else: saver ≤ 100 kbps, standard
 * ≤ 200 kbps, high ≤ 500 kbps, max = the best available. With adaptive
 * playback the player starts at the tier's rung and roams at or below it;
 * locked plays exactly the tier's rung. Spatial (Dolby Atmos, E-AC-3 JOC) is
 * a separate opt-in, never a tier: `ec-3` is admitted only when the user
 * prefers Atmos mixes, AC-4 and MPEG-H never (desktop's rule: no decoder).
 *
 * Stored in `playback_quality` / `playback_abr` / `playback_spatial`
 * ([com.parachord.shared.settings.SettingsStore]), desktop's keys and defaults.
 */
enum class PlaybackQualityTier(val key: String) {
    MAX("max"),
    HIGH("high"),
    STANDARD("standard"),
    SAVER("saver"),
    ;

    /** The tier's ceiling in bits per second; null for [MAX]. */
    val ceilingBps: Long?
        get() = when (this) {
            SAVER -> 100_000L
            STANDARD -> 200_000L
            HIGH -> 500_000L
            MAX -> null
        }

    companion object {
        /** Top to bottom, the Settings list's order. */
        val ORDERED: List<PlaybackQualityTier> = listOf(MAX, HIGH, STANDARD, SAVER)

        /** Desktop's `DEFAULT_QUALITY`. */
        val DEFAULT: PlaybackQualityTier = MAX

        private val LEGACY = mapOf(
            "low" to SAVER,
            "balanced" to STANDARD,
            "best_available" to MAX,
            "best" to MAX,
            "data_saver" to SAVER,
            "hd" to MAX,
        )

        /**
         * A stored value as a tier: the four names (any case), or an earlier
         * build's name (`low`, `balanced`, `BEST_AVAILABLE`, `HD`, `DATA_SAVER`).
         * Null for an empty or unknown value.
         */
        fun normalize(value: String?): PlaybackQualityTier? {
            val v = value?.trim()?.lowercase().orEmpty()
            if (v.isEmpty()) return null
            entries.firstOrNull { it.key == v }?.let { return it }
            return LEGACY[v]
        }
    }
}

/** Desktop's `playback_abr` default: adaptive. */
const val DEFAULT_PLAYBACK_ABR: Boolean = true

/** Desktop's `playback_spatial` default: no Atmos preference. */
const val DEFAULT_PLAYBACK_SPATIAL: Boolean = false

/** The user's three settings together, as the engine reads them at a play's start. */
data class PlaybackQualitySettings(
    val tier: PlaybackQualityTier = PlaybackQualityTier.DEFAULT,
    val adaptive: Boolean = DEFAULT_PLAYBACK_ABR,
    val spatial: Boolean = DEFAULT_PLAYBACK_SPATIAL,
)

/** One audio variant of a manifest, as the policy sees it. */
data class DrmVariant(
    val id: String,
    val codecs: String?,
    val bandwidth: Long,
    val sampleRate: Int? = null,
    val channels: Int? = null,
)

/** The codec families, desktop's `widevineCodecFamily` vocabulary. */
object CodecFamilies {
    const val FLAC = "flac"
    const val OPUS = "opus"
    const val AAC = "aac"
    const val EC3 = "ec3"
    const val AC4 = "ac4"
    const val MPEGH = "mpegh"

    /** Spatial / immersive families. */
    val SPATIAL: Set<String> = setOf(EC3, AC4, MPEGH)

    /** Stereo / lossless families: always candidates (the decoder permitting). */
    val PLAYABLE: Set<String> = setOf(FLAC, OPUS, AAC)

    /** The family of a codec string (`mp4a.40.2` → `aac`, `ec-3` → `ec3`); null for none. */
    fun of(codec: String?): String? {
        val c = codec?.lowercase().orEmpty()
        return when {
            c.startsWith("flac") -> FLAC
            c.startsWith("opus") -> OPUS
            c.startsWith("mp4a") || c.startsWith("aac") -> AAC
            c.startsWith("ec-3") -> EC3
            c.startsWith("ac-4") -> AC4
            c.startsWith("mha") || c.startsWith("mhm") -> MPEGH
            c.isEmpty() -> null
            else -> c
        }
    }
}

/** What the engine should do with a manifest's audio: desktop's `shakaPlaybackConfig`, without Shaka. */
data class PlaybackQualityConfig(
    /** Adaptive within [maxBandwidth]; false = exactly [variantId]. */
    val adaptive: Boolean,
    val variantId: String,
    val variantBandwidth: Long,
    val codecFamily: String?,
    /** Adaptive only: the top bandwidth the player may roam to; null = no cap. */
    val maxBandwidth: Long? = null,
)

object PlaybackQuality {

    fun variantSpatial(variant: DrmVariant): Boolean =
        CodecFamilies.of(variant.codecs) in CodecFamilies.SPATIAL

    /**
     * Whether [variant] may play: stereo families always; `ec3` only on the
     * Atmos opt-in; AC-4 and MPEG-H never; a family in [blockedFamilies]
     * (refused for this account, [DrmFamilyTable]) never.
     */
    fun variantPlayable(variant: DrmVariant, spatialAllowed: Boolean = false, blockedFamilies: Set<String> = emptySet()): Boolean {
        val family = CodecFamilies.of(variant.codecs)
        if (family != null && family in blockedFamilies) return false
        if (family == CodecFamilies.EC3) return spatialAllowed
        if (family in CodecFamilies.SPATIAL) return false
        return family in CodecFamilies.PLAYABLE
    }

    /** Bandwidth, then sample rate, then channels, highest first; then id. Stable for a manifest. */
    fun sorted(variants: List<DrmVariant>): List<DrmVariant> =
        variants.sortedWith(
            compareByDescending<DrmVariant> { it.bandwidth }
                .thenByDescending { it.sampleRate ?: 0 }
                .thenByDescending { it.channels ?: 0 }
                .thenBy { it.id },
        )

    /**
     * The tier's variant (desktop's `selectShakaVariant`):
     * - spatial on and the roster has playable spatial rungs: max/high pick
     *   within them (never a silent drop to stereo); standard/saver let the
     *   ceiling win (a spatial rung under it, else stereo);
     * - max: the highest-bandwidth playable variant;
     * - otherwise the highest at or below the ceiling, else the cheapest
     *   playable one (a floor, never a zero-variant pick).
     * Null when nothing is playable.
     */
    fun selectVariant(
        variants: List<DrmVariant>,
        tier: PlaybackQualityTier?,
        spatial: Boolean = false,
        blockedFamilies: Set<String> = emptySet(),
    ): DrmVariant? {
        if (variants.isEmpty()) return null
        val t = tier ?: PlaybackQualityTier.DEFAULT
        var playable = variants.filter { variantPlayable(it, spatial, blockedFamilies) }
        if (spatial) {
            val spatialPlayable = playable.filter(::variantSpatial)
            if (spatialPlayable.isNotEmpty()) {
                val ceiling = t.ceilingBps
                val underCeiling = if (ceiling != null) spatialPlayable.filter { it.bandwidth <= ceiling } else spatialPlayable
                playable = if (t == PlaybackQualityTier.STANDARD || t == PlaybackQualityTier.SAVER) {
                    underCeiling.ifEmpty { playable.filter { !variantSpatial(it) } }
                } else {
                    spatialPlayable
                }
            }
        }
        if (playable.isEmpty()) return null
        val ordered = sorted(playable)
        if (t == PlaybackQualityTier.MAX) return ordered.first()
        val ceiling = t.ceilingBps ?: return null
        return ordered.firstOrNull { it.bandwidth <= ceiling } ?: ordered.last()
    }

    /**
     * The family-drop cap (desktop's `familyDropCapBps`): when [allVariants]
     * holds a playable-family rung above every rung [playable] can stream (a
     * family the account was refused sits above the lossy ladder), adaptive
     * playback is capped at the top streamable rung so it can't climb back
     * into the refused family. Null = no cap.
     */
    fun familyDropCap(playable: List<DrmVariant>, allVariants: List<DrmVariant>?, spatial: Boolean): Long? {
        if (allVariants.isNullOrEmpty()) return null
        val top = sorted(playable.filter { variantPlayable(it, spatial) }).firstOrNull()?.bandwidth ?: return null
        if (top <= 0) return null
        for (v in allVariants) {
            if (CodecFamilies.of(v.codecs) !in CodecFamilies.PLAYABLE) continue
            if (v.bandwidth > top) return top
        }
        return null
    }

    /**
     * The engine's configuration for (tier, adaptive): desktop's
     * `shakaPlaybackConfig`. Locked plays the tier's rung; adaptive roams at
     * or below the tier's ceiling (never below the picked rung, the floor);
     * a spatial pick is always locked (a bandwidth band can't pin a family).
     * [playable] is the roster with refused families already dropped;
     * [allVariants] the whole manifest, for [familyDropCap]. Null when
     * nothing is playable.
     */
    fun config(
        playable: List<DrmVariant>,
        tier: PlaybackQualityTier?,
        adaptive: Boolean,
        spatial: Boolean = false,
        allVariants: List<DrmVariant>? = null,
    ): PlaybackQualityConfig? {
        val variant = selectVariant(playable, tier, spatial) ?: return null
        val family = CodecFamilies.of(variant.codecs)
        if (!adaptive || variantSpatial(variant)) {
            return PlaybackQualityConfig(false, variant.id, variant.bandwidth, family)
        }
        val t = tier ?: PlaybackQualityTier.DEFAULT
        val ceiling = t.ceilingBps
        val dropCap = familyDropCap(playable, allVariants, spatial)
        val tierCap = ceiling?.let { maxOf(it, variant.bandwidth) }
        val maxBandwidth = when {
            dropCap == null -> tierCap
            tierCap == null -> dropCap
            else -> minOf(dropCap, tierCap)
        }
        return PlaybackQualityConfig(true, variant.id, variant.bandwidth, family, maxBandwidth)
    }
}
