package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the dB→percent conversion against desktop's `getEffectiveVolume`, which
 * is the reference implementation:
 *
 * ```js
 * const multiplier = Math.pow(10, totalOffsetDb / 20);
 * Math.max(0, Math.min(100, baseVolume * multiplier));
 * ```
 *
 * Drift here is inaudible per-track but compounds across a queue that switches
 * sources, which is the exact problem the offsets exist to solve.
 */
class ResolverVolumeTest {

    @Test
    fun `zero dB is a no-op`() {
        assertEquals(100, ResolverVolume.effectiveVolumePercent(100, 0))
        assertEquals(50, ResolverVolume.effectiveVolumePercent(50, 0))
        assertEquals(0, ResolverVolume.effectiveVolumePercent(0, 0))
    }

    @Test
    fun `the shipped defaults produce the expected attenuation`() {
        // bandcamp -3 dB ≈ 0.708×, youtube -6 dB ≈ 0.501× — the two non-zero
        // defaults in SettingsStore.defaultVolumeOffsets().
        assertEquals(71, ResolverVolume.effectiveVolumePercent(100, -3))
        assertEquals(50, ResolverVolume.effectiveVolumePercent(100, -6))
    }

    @Test
    fun `minus 6 dB is half amplitude, minus 12 a quarter`() {
        assertEquals(50, ResolverVolume.effectiveVolumePercent(100, -6))
        assertEquals(25, ResolverVolume.effectiveVolumePercent(100, ResolverVolume.MIN_OFFSET_DB))
    }

    @Test
    fun `offsets scale the base rather than replacing it`() {
        // Desktop multiplies; it does not set an absolute level.
        assertEquals(25, ResolverVolume.effectiveVolumePercent(50, -6))
        assertEquals(10, ResolverVolume.effectiveVolumePercent(20, -6))
    }

    // ── clamping ─────────────────────────────────────────────────────

    @Test
    fun `a positive offset at base 100 clamps and is inert`() {
        // Load-bearing on iOS, which has no volume slider and always passes
        // 100: you cannot amplify past a player's maximum, so boosting a quiet
        // source means attenuating the others instead.
        assertEquals(100, ResolverVolume.effectiveVolumePercent(100, ResolverVolume.MAX_OFFSET_DB))
        assertEquals(100, ResolverVolume.effectiveVolumePercent(100, 3))
    }

    @Test
    fun `a positive offset below 100 does raise the level`() {
        // +6 dB ≈ 2.0×
        assertEquals(100, ResolverVolume.effectiveVolumePercent(50, 6))
        assertEquals(50, ResolverVolume.effectiveVolumePercent(25, 6))
    }

    @Test
    fun `never escapes the 0 to 100 range`() {
        for (base in listOf(0, 1, 50, 99, 100)) {
            for (db in ResolverVolume.MIN_OFFSET_DB..ResolverVolume.MAX_OFFSET_DB) {
                val v = ResolverVolume.effectiveVolumePercent(base, db)
                assertTrue(v in 0..100, "base=$base db=$db produced $v")
            }
        }
    }

    @Test
    fun `silence stays silent at any offset`() {
        assertEquals(0, ResolverVolume.effectiveVolumePercent(0, -12))
        assertEquals(0, ResolverVolume.effectiveVolumePercent(0, 6))
    }

    @Test
    fun `fraction mirrors the percentage for player APIs`() {
        assertEquals(1.0f, ResolverVolume.effectiveVolumeFraction(100, 0))
        assertEquals(0.5f, ResolverVolume.effectiveVolumeFraction(100, -6))
        assertEquals(0.0f, ResolverVolume.effectiveVolumeFraction(0, 0))
    }

    // ── what can't be controlled ─────────────────────────────────────

    @Test
    fun `apple music is not controllable on any platform`() {
        // iOS has no per-app volume and ApplicationMusicPlayer follows the
        // system; desktop greys it out for the same reason. A slider here
        // would be a placebo.
        assertFalse(ResolverVolume.isVolumeControllable("applemusic"))
        assertEquals("System vol", ResolverVolume.uncontrollableReason("applemusic"))
    }

    @Test
    fun `browser-playback resolvers are not controllable`() {
        assertFalse(ResolverVolume.isVolumeControllable("bandcamp"))
        assertFalse(ResolverVolume.isVolumeControllable("youtube"))
        assertEquals("Opens in browser", ResolverVolume.uncontrollableReason("youtube"))
    }

    @Test
    fun `the in-app players are controllable`() {
        for (id in listOf("spotify", "soundcloud", "localfiles")) {
            assertTrue(ResolverVolume.isVolumeControllable(id), "$id should be controllable")
            assertNull(ResolverVolume.uncontrollableReason(id))
        }
    }
}
