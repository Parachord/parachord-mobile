package com.parachord.shared.ui.icons

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * One drawn part of an icon: SVG path data, filled and/or stroked. A part is
 * drawn in the icon's tint unless it has its own [fillColor] / [strokeColor]
 * (`0xRRGGBB`, Achordion's logo). [strokeWidth] is in the icon's own units
 * (its viewBox), so it scales with the icon, as on desktop. [alpha] is the
 * part's opacity (Spinner's faint ring).
 */
class IconPart(
    val d: String,
    val fill: Boolean,
    val stroke: Boolean,
    val strokeWidth: Float,
    val alpha: Float = 1f,
    val evenOdd: Boolean = false,
    val fillColor: Int? = null,
    val strokeColor: Int? = null,
) {
    /** The path as move / line / cubic / close commands, absolute, in viewBox units. */
    val commands: List<PathCommand> by lazy { SvgPath.parse(d) }
}

/**
 * An icon or logo: its viewBox ([minX], [minY], [width], [height]) and parts.
 * Strokes use round caps and joins (desktop's `strokeLinecap` /
 * `strokeLinejoin: round`).
 */
class IconSpec(
    val minX: Float,
    val minY: Float,
    val width: Float,
    val height: Float,
    val parts: List<IconPart>,
)

/** What a platform replays to draw a path. Every coordinate is absolute. */
sealed class PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand()
    data class LineTo(val x: Float, val y: Float) : PathCommand()
    data class CubicTo(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val x: Float, val y: Float,
    ) : PathCommand()
    data object Close : PathCommand()
}

/**
 * SVG path data → [PathCommand]s. Handles every command (M L H V C S Q T A Z,
 * absolute and relative), turns H / V into lines, Q / T into cubics, and arcs
 * into cubics (SVG spec F.6), so a renderer needs only four operations.
 */
object SvgPath {

    fun parse(d: String): List<PathCommand> {
        val t = Tokens(d)
        val out = ArrayList<PathCommand>()
        var cx = 0f; var cy = 0f          // current point
        var sx = 0f; var sy = 0f          // subpath start
        var lastCubic: Pair<Float, Float>? = null  // last cubic's 2nd control point
        var lastQuad: Pair<Float, Float>? = null   // last quad's control point
        var cmd = ' '
        while (true) {
            val next = t.command()
            if (next != null) cmd = next
            // No new command: repeat the last one with the next numbers, if any
            // (never a Z, which takes none).
            else if (!t.hasNumber() || cmd.uppercaseChar() == 'Z') break
            if (cmd == ' ') break
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            var cubic: Pair<Float, Float>? = null
            var quad: Pair<Float, Float>? = null
            when (cmd.uppercaseChar()) {
                'M' -> {
                    cx = ox + t.num(); cy = oy + t.num(); sx = cx; sy = cy
                    out += PathCommand.MoveTo(cx, cy)
                    // Further pairs after a moveto are linetos.
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> { cx = ox + t.num(); cy = oy + t.num(); out += PathCommand.LineTo(cx, cy) }
                'H' -> { cx = ox + t.num(); out += PathCommand.LineTo(cx, cy) }
                'V' -> { cy = oy + t.num(); out += PathCommand.LineTo(cx, cy) }
                'C' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num()
                    val x2 = ox + t.num(); val y2 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    out += PathCommand.CubicTo(x1, y1, x2, y2, cx, cy); cubic = x2 to y2
                }
                'S' -> {
                    val (x1, y1) = lastCubic?.let { (px, py) -> 2 * cx - px to 2 * cy - py } ?: (cx to cy)
                    val x2 = ox + t.num(); val y2 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    out += PathCommand.CubicTo(x1, y1, x2, y2, cx, cy); cubic = x2 to y2
                }
                'Q' -> {
                    val qx = ox + t.num(); val qy = oy + t.num()
                    val x = ox + t.num(); val y = oy + t.num()
                    out += quadToCubic(cx, cy, qx, qy, x, y); cx = x; cy = y; quad = qx to qy
                }
                'T' -> {
                    val (qx, qy) = lastQuad?.let { (px, py) -> 2 * cx - px to 2 * cy - py } ?: (cx to cy)
                    val x = ox + t.num(); val y = oy + t.num()
                    out += quadToCubic(cx, cy, qx, qy, x, y); cx = x; cy = y; quad = qx to qy
                }
                'A' -> {
                    val rx = t.num(); val ry = t.num(); val rot = t.num()
                    val large = t.flag(); val sweep = t.flag()
                    val x = ox + t.num(); val y = oy + t.num()
                    out += arcToCubics(cx, cy, rx, ry, rot, large, sweep, x, y); cx = x; cy = y
                }
                'Z' -> { out += PathCommand.Close; cx = sx; cy = sy }
                else -> break
            }
            lastCubic = cubic; lastQuad = quad
        }
        return out
    }

    private fun quadToCubic(x0: Float, y0: Float, qx: Float, qy: Float, x: Float, y: Float) =
        PathCommand.CubicTo(
            x0 + 2f / 3f * (qx - x0), y0 + 2f / 3f * (qy - y0),
            x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y),
            x, y,
        )

    /** An elliptical arc as cubic Béziers (one per ≤ 90° slice). */
    private fun arcToCubics(
        x1: Float, y1: Float, rxIn: Float, ryIn: Float, angleDeg: Float,
        large: Boolean, sweep: Boolean, x2: Float, y2: Float,
    ): List<PathCommand> {
        if (x1 == x2 && y1 == y2) return emptyList()
        var rx = abs(rxIn.toDouble()); var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) return listOf(PathCommand.LineTo(x2, y2))
        val phi = angleDeg * PI / 180.0
        val cosP = cos(phi); val sinP = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cosP * dx + sinP * dy
        val y1p = -sinP * dx + cosP * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt(maxOf(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * (-ry * x1p / rx)
        val cx = cosP * cxp - sinP * cyp + (x1 + x2) / 2.0
        val cy = sinP * cxp + cosP * cyp + (y1 + y2) / 2.0
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            return a
        }
        val theta1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && delta > 0) delta -= 2 * PI
        if (sweep && delta < 0) delta += 2 * PI
        val segments = maxOf(1, ceil(abs(delta) / (PI / 2)).toInt())
        val step = delta / segments
        val k = 4.0 / 3.0 * tan(step / 4)
        val out = ArrayList<PathCommand>(segments)
        var a = theta1
        for (i in 0 until segments) {
            val a2 = a + step
            val cosA = cos(a); val sinA = sin(a); val cosB = cos(a2); val sinB = sin(a2)
            fun px(ex: Double, ey: Double) = (cosP * rx * ex - sinP * ry * ey + cx).toFloat()
            fun py(ex: Double, ey: Double) = (sinP * rx * ex + cosP * ry * ey + cy).toFloat()
            val e1x = cosA - k * sinA; val e1y = sinA + k * cosA
            val e2x = cosB + k * sinB; val e2y = sinB - k * cosB
            val endX = if (i == segments - 1) x2 else px(cosB, sinB)
            val endY = if (i == segments - 1) y2 else py(cosB, sinB)
            out += PathCommand.CubicTo(px(e1x, e1y), py(e1x, e1y), px(e2x, e2y), py(e2x, e2y), endX, endY)
            a = a2
        }
        return out
    }

    /** A cursor over path data: command letters, numbers and arc flags. */
    private class Tokens(private val s: String) {
        private var i = 0
        private fun skip() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }

        fun command(): Char? {
            skip()
            if (i < s.length && s[i].isLetter() && s[i] != 'e' && s[i] != 'E') return s[i++]
            return null
        }

        fun hasNumber(): Boolean {
            skip()
            return i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.')
        }

        fun num(): Float {
            skip()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) i++
                else if (c == '.' && !dot) { dot = true; i++ }
                else if ((c == 'e' || c == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')) {
                    i += 2; while (i < s.length && s[i].isDigit()) i++; break
                } else break
            }
            require(i > start) { "expected a number at $start in path" }
            return s.substring(start, i).toFloat()
        }

        /** An arc flag: a single 0 or 1, which may run into the next number ("011"). */
        fun flag(): Boolean {
            skip()
            require(i < s.length && (s[i] == '0' || s[i] == '1')) { "expected an arc flag at $i in path" }
            return s[i++] == '1'
        }
    }
}
