package com.parachord.shared.playback.drm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop's `tests/renderer/widevine-quality.test.js` vectors (the live HD-tier
 * and Atmos Amazon manifests), the cross-platform contract for the quality
 * policy. Shaka-only parts (the load-time cap, the seed bandwidth) aren't
 * ported: ExoPlayer has neither.
 */
class PlaybackQualityTest {

    private fun v(id: String, codecs: String, bandwidth: Long, sr: Int? = 48000, ch: Int? = 2) =
        DrmVariant(id, codecs, bandwidth, sr, ch)

    private val live = listOf(
        v("1", "opus", 53059), v("2", "opus", 197343), v("3", "opus", 325595),
        v("4", "opus", 53059), v("5", "opus", 165280),
        v("6", "flac", 1062133, 44100), v("7", "flac", 1902230, 48000),
        v("8", "mha1.0x0e", 1612557, ch = null), v("10", "mhm1.0x0d", 1077894, ch = null),
        v("11", "mha1.0x0d", 675720, ch = null), v("12", "mhm1.0x0c", 339893, ch = null),
        v("13", "mhm1.0x0e", 1610228, ch = null), v("14", "mha1.0x0c", 341861, ch = null),
        v("15", "mhm1.0x0c", 341861, ch = null),
    )

    private val spatialManifest = listOf(
        v("1", "opus", 51352), v("3", "opus", 323352),
        v("6", "flac", 1018140, 44100), v("7", "flac", 1723851, 44100),
        v("8", "ec-3", 770130, ch = 6), v("9", "ec-3", 450130, ch = 6),
        v("10", "ac-4.02.02.00", 259980), v("11", "ac-4.02.02.00", 115479),
    )

    @Test
    fun `the four tiers pass through and legacy names map to them`() {
        for (t in PlaybackQualityTier.ORDERED) assertEquals(t, PlaybackQualityTier.normalize(t.key))
        assertEquals(PlaybackQualityTier.SAVER, PlaybackQualityTier.normalize("low"))
        assertEquals(PlaybackQualityTier.STANDARD, PlaybackQualityTier.normalize("balanced"))
        assertEquals(PlaybackQualityTier.STANDARD, PlaybackQualityTier.normalize("STANDARD"))
        assertEquals(PlaybackQualityTier.MAX, PlaybackQualityTier.normalize("BEST_AVAILABLE"))
        assertEquals(PlaybackQualityTier.MAX, PlaybackQualityTier.normalize("HD"))
        assertEquals(PlaybackQualityTier.SAVER, PlaybackQualityTier.normalize("DATA_SAVER"))
        assertEquals(PlaybackQualityTier.MAX, PlaybackQualityTier.normalize("MAX"))
    }

    @Test
    fun `empty and unknown values normalize to null`() {
        assertNull(PlaybackQualityTier.normalize(""))
        assertNull(PlaybackQualityTier.normalize(null))
        assertNull(PlaybackQualityTier.normalize("ultra-mega-whatever"))
    }

    @Test
    fun `the ceilings are the corridor values`() {
        assertEquals(100_000L, PlaybackQualityTier.SAVER.ceilingBps)
        assertEquals(200_000L, PlaybackQualityTier.STANDARD.ceilingBps)
        assertEquals(500_000L, PlaybackQualityTier.HIGH.ceilingBps)
        assertNull(PlaybackQualityTier.MAX.ceilingBps)
        assertEquals(listOf("max", "high", "standard", "saver"), PlaybackQualityTier.ORDERED.map { it.key })
    }

    @Test
    fun `spatial codecs are out of the roster without the opt-in`() {
        assertFalse(PlaybackQuality.variantPlayable(v("a", "mha1.0x0e", 1)))
        assertFalse(PlaybackQuality.variantPlayable(v("a", "mhm1.0x0c", 1)))
        assertFalse(PlaybackQuality.variantPlayable(v("a", "ec-3", 1)))
        assertFalse(PlaybackQuality.variantPlayable(v("a", "ac-4.02.02.00", 1)))
        assertTrue(PlaybackQuality.variantPlayable(v("a", "flac", 1)))
        assertTrue(PlaybackQuality.variantPlayable(v("a", "opus", 1)))
        assertTrue(PlaybackQuality.variantPlayable(v("a", "mp4a.40.2", 1)))
    }

    @Test
    fun `max picks the highest playable never spatial`() {
        val pick = PlaybackQuality.selectVariant(live, PlaybackQualityTier.MAX)!!
        assertEquals(1902230L, pick.bandwidth)
        assertEquals("flac", pick.codecs)
    }

    @Test
    fun `high standard and saver pick the highest under their ceiling`() {
        val high = PlaybackQuality.selectVariant(live, PlaybackQualityTier.HIGH)!!
        assertEquals(325595L, high.bandwidth)
        assertEquals("opus", high.codecs)
        assertEquals(197343L, PlaybackQuality.selectVariant(live, PlaybackQualityTier.STANDARD)!!.bandwidth)
        val saver = PlaybackQuality.selectVariant(live, PlaybackQualityTier.SAVER)!!
        assertEquals(53059L, saver.bandwidth)
        assertEquals("1", saver.id)
    }

    @Test
    fun `nothing under the ceiling floors at the cheapest playable`() {
        val onlyHi = listOf(v("a", "opus", 400000, sr = null), v("b", "opus", 300000, sr = null))
        assertEquals(300000L, PlaybackQuality.selectVariant(onlyHi, PlaybackQualityTier.SAVER)!!.bandwidth)
    }

    @Test
    fun `null for an empty roster or only spatial audio`() {
        assertNull(PlaybackQuality.selectVariant(emptyList(), PlaybackQualityTier.MAX))
        assertNull(PlaybackQuality.selectVariant(live.filter { it.codecs!!.startsWith("mh") }, PlaybackQualityTier.MAX))
    }

    @Test
    fun `locked plays the tier rung`() {
        assertEquals(
            PlaybackQualityConfig(adaptive = false, variantId = "2", variantBandwidth = 197343, codecFamily = "opus"),
            PlaybackQuality.config(live, PlaybackQualityTier.STANDARD, adaptive = false),
        )
    }

    @Test
    fun `adaptive caps at the tier ceiling and max is uncapped`() {
        assertEquals(
            PlaybackQualityConfig(true, "1", 53059, "opus", maxBandwidth = 100000),
            PlaybackQuality.config(live, PlaybackQualityTier.SAVER, adaptive = true),
        )
        assertEquals(
            PlaybackQualityConfig(true, "7", 1902230, "flac", maxBandwidth = null),
            PlaybackQuality.config(live, PlaybackQualityTier.MAX, adaptive = true),
        )
    }

    @Test
    fun `the adaptive floor never caps below every variant`() {
        val onlyHi = listOf(v("a", "opus", 400000), v("b", "opus", 300000))
        val cfg = PlaybackQuality.config(onlyHi, PlaybackQualityTier.SAVER, adaptive = true)!!
        assertEquals("b", cfg.variantId)
        assertEquals(300000L, cfg.maxBandwidth)
    }

    @Test
    fun `the config is null when nothing plays`() {
        assertNull(PlaybackQuality.config(emptyList(), PlaybackQualityTier.MAX, adaptive = true))
    }

    @Test
    fun `codec families cover the spatial strings`() {
        assertEquals("ec3", CodecFamilies.of("ec-3"))
        assertEquals("ec3", CodecFamilies.of("EC-3"))
        assertEquals("ac4", CodecFamilies.of("ac-4.02.02.00"))
        assertEquals("mpegh", CodecFamilies.of("mha1.0x0e"))
        assertEquals("mpegh", CodecFamilies.of("mhm1.0x0c"))
        assertEquals("aac", CodecFamilies.of("mp4a.40.2"))
        assertTrue("ec3" in CodecFamilies.SPATIAL)
    }

    @Test
    fun `only ec3 is admitted and only on the opt-in`() {
        assertTrue(PlaybackQuality.variantSpatial(v("a", "ec-3", 1)))
        assertTrue(PlaybackQuality.variantSpatial(v("a", "ac-4.02.02.00", 1)))
        assertTrue(PlaybackQuality.variantSpatial(v("a", "mha1.0x0e", 1)))
        assertFalse(PlaybackQuality.variantSpatial(v("a", "flac", 1)))
        assertTrue(PlaybackQuality.variantPlayable(v("a", "ec-3", 1), spatialAllowed = true))
        assertFalse(PlaybackQuality.variantPlayable(v("a", "ec-3", 1), spatialAllowed = false))
        assertFalse(PlaybackQuality.variantPlayable(v("a", "ac-4.02.02.00", 1), spatialAllowed = true))
        assertFalse(PlaybackQuality.variantPlayable(v("a", "mha1.0x0e", 1), spatialAllowed = true))
        assertFalse(PlaybackQuality.variantPlayable(v("a", "mhm1.0x0c", 1), spatialAllowed = true))
        assertTrue(PlaybackQuality.variantPlayable(v("a", "flac", 1), spatialAllowed = true))
    }

    @Test
    fun `spatial on — max and high pick within the spatial rungs and lower tiers let the ceiling win`() {
        assertEquals("8", PlaybackQuality.selectVariant(spatialManifest, PlaybackQualityTier.MAX, spatial = true)!!.id)
        assertEquals("9", PlaybackQuality.selectVariant(spatialManifest, PlaybackQualityTier.HIGH, spatial = true)!!.id)
        assertEquals("1", PlaybackQuality.selectVariant(spatialManifest, PlaybackQualityTier.STANDARD, spatial = true)!!.id)
        assertEquals("1", PlaybackQuality.selectVariant(spatialManifest, PlaybackQualityTier.SAVER, spatial = true)!!.id)
        val cheapSpatial = spatialManifest + v("12", "ec-3", 95000, ch = 6)
        assertEquals("12", PlaybackQuality.selectVariant(cheapSpatial, PlaybackQualityTier.SAVER, spatial = true)!!.id)
    }

    @Test
    fun `spatial on with nothing spatial playable selects as before`() {
        val stereoOnly = spatialManifest.filter { !PlaybackQuality.variantSpatial(it) }
        assertEquals("7", PlaybackQuality.selectVariant(stereoOnly, PlaybackQualityTier.MAX, spatial = true)!!.id)
        val ac4Only = stereoOnly + v("10", "ac-4.02.02.00", 259980)
        assertEquals("7", PlaybackQuality.selectVariant(ac4Only, PlaybackQualityTier.MAX, spatial = true)!!.id)
    }

    @Test
    fun `spatial off never picks spatial`() {
        assertEquals("7", PlaybackQuality.selectVariant(spatialManifest, PlaybackQualityTier.MAX)!!.id)
        assertEquals("7", PlaybackQuality.selectVariant(spatialManifest, PlaybackQualityTier.MAX, spatial = false)!!.id)
    }

    @Test
    fun `a spatial pick is always locked`() {
        val expected = PlaybackQualityConfig(false, "8", 770130, "ec3")
        assertEquals(expected, PlaybackQuality.config(spatialManifest, PlaybackQualityTier.MAX, adaptive = true, spatial = true))
        assertEquals(expected, PlaybackQuality.config(spatialManifest, PlaybackQualityTier.MAX, adaptive = false, spatial = true))
        assertEquals(
            PlaybackQualityConfig(true, "7", 1902230, "flac", maxBandwidth = null),
            PlaybackQuality.config(live, PlaybackQualityTier.MAX, adaptive = true, spatial = true),
        )
    }

    @Test
    fun `a refused family is left out and adaptive is capped below it`() {
        val pick = PlaybackQuality.selectVariant(live, PlaybackQualityTier.MAX, blockedFamilies = setOf("flac"))!!
        assertEquals("3", pick.id)
        val playable = live.filter { PlaybackQuality.variantPlayable(it, blockedFamilies = setOf("flac")) }
        val cfg = PlaybackQuality.config(playable, PlaybackQualityTier.MAX, adaptive = true, allVariants = live)!!
        assertEquals("3", cfg.variantId)
        // The FLAC rungs sit above every opus rung: adaptive can't climb back into them.
        assertEquals(325595L, cfg.maxBandwidth)
    }
}
