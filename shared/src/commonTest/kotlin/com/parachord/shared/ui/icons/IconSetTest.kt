package com.parachord.shared.ui.icons

import com.parachord.shared.ui.icons.PathCommand.Close
import com.parachord.shared.ui.icons.PathCommand.CubicTo
import com.parachord.shared.ui.icons.PathCommand.LineTo
import com.parachord.shared.ui.icons.PathCommand.MoveTo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IconSetTest {

    private fun near(a: Float, b: Float) = abs(a - b) < 0.01f

    @Test
    fun parsesLinesRelativeAndImplicitRepeats() {
        // Lucide's X, and a relative path with implicit lineto pairs after M.
        assertEquals(listOf(MoveTo(18f, 6f), LineTo(6f, 18f)), SvgPath.parse("M18 6 6 18"))
        assertEquals(
            listOf(MoveTo(1f, 1f), LineTo(3f, 1f), LineTo(3f, 1f), LineTo(3f, 4f), LineTo(1f, 4f), Close), // h0 is a zero-length line
            SvgPath.parse("m1 1 2 0h0v3H1z"),
        )
    }

    @Test
    fun readsCompactNumbersAndArcFlags() {
        // "-.197.639" is two numbers; "a.352.352 0 0 0" carries flags.
        val cmds = SvgPath.parse("M16.572 4.904a.352.352 0 0 0-.197.639")
        assertEquals(MoveTo(16.572f, 4.904f), cmds.first())
        val end = cmds.last() as CubicTo
        assertTrue(near(end.x, 16.375f) && near(end.y, 5.543f), "$end")
        // Flags with no separators ("0 01").
        val compact = SvgPath.parse("M0 0a5 5 0 015 5")
        val last = compact.last() as CubicTo
        assertTrue(near(last.x, 5f) && near(last.y, 5f))
    }

    @Test
    fun arcsBecomeCubicsThatEndWhereTheArcEnds() {
        // A full circle drawn as two half arcs, as the generator writes circles.
        val cmds = SvgPath.parse("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z")
        assertTrue(cmds.count { it is CubicTo } >= 4)
        val firstHalfEnd = cmds.filterIsInstance<CubicTo>()[1]
        assertTrue(near(firstHalfEnd.x, 22f) && near(firstHalfEnd.y, 12f), "$firstHalfEnd")
        assertEquals(Close, cmds.last())
    }

    @Test
    fun smoothAndQuadraticCurves() {
        val s = SvgPath.parse("M0 0C1 2 3 2 4 0S7 -2 8 0") .filterIsInstance<CubicTo>()
        assertEquals(CubicTo(5f, -2f, 7f, -2f, 8f, 0f), s[1]) // reflected control point
        val q = SvgPath.parse("M0 0Q3 3 6 0").last() as CubicTo
        assertTrue(near(q.x1, 2f) && near(q.y1, 2f) && near(q.x, 6f))
    }

    @Test
    fun everyIconAndLogoParses() {
        AppIcon.entries.forEach { icon ->
            icon.spec.parts.forEach { part ->
                assertTrue(part.commands.isNotEmpty(), "${icon.name}: ${part.d}")
                assertTrue(part.commands.first() is MoveTo, icon.name)
            }
        }
        ServiceLogos.IDS.forEach { id ->
            val spec = assertNotNull(ServiceLogos.spec(id), id)
            spec.parts.forEach { assertTrue(it.commands.size > 1, id) }
        }
        ServiceLogos.LARGE_IDS.forEach { id ->
            val spec = assertNotNull(ServiceLogos.large(id), id)
            spec.parts.forEach { assertTrue(it.commands.size > 1, id) }
        }
    }

    @Test
    fun matchesDesktopsConventions() {
        assertTrue(AppIcon.Play.spec.parts.all { it.fill }, "transport icons are solid")
        assertTrue(AppIcon.Heart.spec.parts.all { it.stroke && !it.fill && it.strokeWidth == 2f })
        assertEquals(32f, AppIcon.Spinoff.spec.width) // its own 32-point box, as desktop
        assertEquals(0.25f, AppIcon.Spinner.spec.parts.first().alpha)
        assertNull(ServiceLogos.spec("nothing"))
        // Desktop's own Wikipedia badge path is malformed; the generator uses
        // Simple Icons' until desktop is fixed (scripts/sync-desktop-icons.js).
        assertTrue(ServiceLogos.spec("wikipedia")!!.parts.single().d.startsWith("M12.09 13.119"))
        assertTrue("l.136.046 2.411-4.81" in ServiceLogos.spec("wikipedia")!!.parts.single().d)
        assertTrue("soundcloud" in ServiceLogos.BITMAPS && "subsonic" in ServiceLogos.BITMAPS)
    }
}
