package com.parachord.shared.ui

import com.parachord.shared.ui.ArtistPattern.Gradient
import com.parachord.shared.ui.ArtistPattern.Stop
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Vectors from desktop's `lib/artist-pattern.js` (run under Node). */
class ArtistPatternTest {

    @Test
    fun hashMatchesDesktop() {
        assertEquals(969642917L, ArtistPattern.hash("radiohead"))
        assertEquals(2057984057L, ArtistPattern.hash("phoebe bridgers"))
        assertEquals(1287186912L, ArtistPattern.hash("sigur rós"))
    }

    @Test
    fun linear() {
        val p = ArtistPattern.of("Radiohead")
        assertEquals(Gradient.Linear(330, listOf(Stop(0f, 0x0c0c0c), Stop(0.5f, 0x1c1c1c), Stop(1f, 0x2d2d2d))), p.gradient)
        assertEquals("R", p.initials)
        assertEquals(0xff6b35, p.textColor)
        assertEquals(Gradient.Linear(30, listOf(Stop(0f, 0x283618), Stop(0.5f, 0x606c38), Stop(1f, 0xdda15e))), ArtistPattern.of("Sigur Rós").gradient)
    }

    @Test
    fun mesh() {
        val p = ArtistPattern.of("Phoebe Bridgers")
        assertEquals(
            Gradient.Linear(240, listOf(Stop(0f, 0x0c0c0c), Stop(0.25f, 0x1c1c1c), Stop(0.5f, 0x2d2d2d), Stop(0.75f, 0x1c1c1c), Stop(1f, 0x0c0c0c))),
            p.gradient,
        )
        assertEquals("PB", p.initials)
    }

    @Test
    fun softRadialAndRadial() {
        val beatles = ArtistPattern.of("The Beatles")
        assertEquals(Gradient.Radial(0.31f, 0.72f, listOf(Stop(0f, 0xf1faee), Stop(0.5f, 0x457b9d), Stop(1f, 0x1d3557))), beatles.gradient)
        assertEquals("TB", beatles.initials)
        assertEquals(Gradient.Radial(0.53f, 0.45f, listOf(Stop(0f, 0x9f86c0), Stop(0.4f, 0x5e548e), Stop(1f, 0x231942))), ArtistPattern.of("Kino").gradient)
    }

    @Test
    fun linearEndpointsSpanTheBoxAsCssDoes() {
        // 90deg runs left to right across the box's width.
        val (start, end) = Gradient.Linear(90, emptyList()).endpoints(100f, 100f)
        assertTrue(abs(start.first) < 0.01f && abs(start.second - 50f) < 0.01f)
        assertTrue(abs(end.first - 100f) < 0.01f && abs(end.second - 50f) < 0.01f)
        // 45deg: the line is the diagonal's length.
        val (s, e) = Gradient.Linear(45, emptyList()).endpoints(100f, 100f)
        val len = sqrt((e.first - s.first) * (e.first - s.first) + (e.second - s.second) * (e.second - s.second))
        assertTrue(abs(len - 141.42f) < 0.05f)
    }

    @Test
    fun radialReachesTheFarthestCorner() {
        assertTrue(abs(Gradient.Radial(0f, 0f, emptyList()).radius(100f, 100f) - 141.42f) < 0.05f)
        assertTrue(abs(Gradient.Radial(0.5f, 0.5f, emptyList()).radius(100f, 100f) - 70.71f) < 0.05f)
    }
}
