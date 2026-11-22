package com.parachord.shared.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The stand-in art for an artist with no photo (desktop
 * `lib/artist-pattern.js`): a gradient from one of fifteen palettes and the
 * initials, picked by a hash of the name so an artist always gets the same
 * one. The palettes are generated art, the same in both themes.
 *
 * Each app draws [gradient]: a [Gradient.Linear] at a CSS angle, or a
 * [Gradient.Radial] around a point, its stops as given ([Stop.at] 0..1; two
 * stops at one place make desktop's hard edge). [Gradient.Linear.endpoints] and
 * [Gradient.Radial.radius] give the geometry for a box, as CSS draws it.
 */
data class ArtistPattern(
    val gradient: Gradient,
    val initials: String,
    /** The initials' colour (`0xRRGGBB`); desktop draws them at 40% opacity. */
    val textColor: Int,
) {
    data class Stop(val at: Float, val color: Int)

    sealed class Gradient {
        abstract val stops: List<Stop>

        /** CSS `linear-gradient(<angle>deg, …)`: 0 points up, clockwise. */
        data class Linear(val angleDeg: Int, override val stops: List<Stop>) : Gradient() {
            /**
             * Start and end in a [width] × [height] box, as CSS places the
             * gradient line: through the centre, long enough that the corners
             * reach the first and last stops.
             */
            fun endpoints(width: Float, height: Float): Pair<Pair<Float, Float>, Pair<Float, Float>> {
                val a = angleDeg * PI / 180.0
                val dx = sin(a)
                val dy = -cos(a)
                val half = (abs(width * sin(a)) + abs(height * cos(a))) / 2.0
                val cx = width / 2.0
                val cy = height / 2.0
                return ((cx - dx * half).toFloat() to (cy - dy * half).toFloat()) to
                    ((cx + dx * half).toFloat() to (cy + dy * half).toFloat())
            }

            /** [endpoints] as start x, start y, end x, end y (for Swift, which can't read nested pairs easily). */
            fun line(width: Float, height: Float): FloatArray {
                val (s, e) = endpoints(width, height)
                return floatArrayOf(s.first, s.second, e.first, e.second)
            }
        }

        /**
         * CSS `radial-gradient(circle at x y, …)` with the default size, the
         * farthest corner. [centerX] / [centerY] are fractions of the box.
         * Desktop's soft radial is an ellipse; on a square card the circle
         * reaching the same corner is the same shape.
         */
        data class Radial(val centerX: Float, val centerY: Float, override val stops: List<Stop>) : Gradient() {
            fun radius(width: Float, height: Float): Float {
                val cx = centerX * width
                val cy = centerY * height
                return max(
                    max(hypot(cx, cy), hypot(width - cx, cy)),
                    max(hypot(cx, height - cy), hypot(width - cx, height - cy)),
                )
            }
        }
    }

    companion object {
        /** The initials' opacity over the pattern (desktop's 0.4). */
        const val INITIALS_ALPHA = 0.4f

        private val PALETTES = listOf(
            listOf(0x1a1a2e, 0x16213e, 0x0f3460, 0xe94560),
            listOf(0x2d3436, 0x636e72, 0xb2bec3, 0xdfe6e9),
            listOf(0x0c0c0c, 0x1c1c1c, 0x2d2d2d, 0xff6b35),
            listOf(0x1e3a5f, 0x3d5a80, 0x98c1d9, 0xe0fbfc),
            listOf(0x2b2d42, 0x8d99ae, 0xedf2f4, 0xef233c),
            listOf(0x003049, 0xd62828, 0xf77f00, 0xfcbf49),
            listOf(0x0a0908, 0x22333b, 0xeae0d5, 0xc6ac8f),
            listOf(0x10002b, 0x240046, 0x3c096c, 0x5a189a),
            listOf(0x1b4332, 0x2d6a4f, 0x40916c, 0x52b788),
            listOf(0x212529, 0x343a40, 0x495057, 0xadb5bd),
            listOf(0x003459, 0x007ea7, 0x00a8e8, 0xffffff),
            listOf(0x1d3557, 0x457b9d, 0xa8dadc, 0xf1faee),
            listOf(0x283618, 0x606c38, 0xdda15e, 0xbc6c25),
            listOf(0x231942, 0x5e548e, 0x9f86c0, 0xbe95c4),
            listOf(0x0b090a, 0x161a1d, 0x660708, 0xa4161a),
        )

        /**
         * Desktop's `hashString`: `hash = (hash << 5) - hash + charCode` in 32
         * bits, then its absolute value. Kept as a Long so `abs` of the
         * smallest Int is positive, as JavaScript's `Math.abs` gives.
         */
        fun hash(text: String): Long {
            var h = 0
            for (c in text) h = (h shl 5) - h + c.code
            return abs(h.toLong())
        }

        fun of(artistName: String): ArtistPattern {
            val hash = hash(artistName.lowercase())
            // JavaScript's `>>` works on the 32-bit value.
            val h32 = hash.toInt()
            val p = PALETTES[(hash % PALETTES.size).toInt()]
            val type = (h32 shr 4) % 6
            val angle = ((h32 shr 8) % 12) * 30
            val gradient = when (type) {
                0 -> Gradient.Linear(angle, listOf(Stop(0f, p[0]), Stop(0.5f, p[1]), Stop(1f, p[2])))
                1 -> Gradient.Radial(
                    (30 + hash % 40) / 100f, (30 + (h32 shr 2) % 40) / 100f,
                    listOf(Stop(0f, p[2]), Stop(0.4f, p[1]), Stop(1f, p[0])),
                )
                2 -> Gradient.Linear(
                    angle,
                    listOf(
                        Stop(0f, p[0]), Stop(0.45f, p[0]), Stop(0.45f, p[2]),
                        Stop(0.55f, p[2]), Stop(0.55f, p[1]), Stop(1f, p[1]),
                    ),
                )
                3 -> Gradient.Radial(
                    (hash % 80 + 10) / 100f, ((h32 shr 3) % 80 + 10) / 100f,
                    listOf(Stop(0f, p[3]), Stop(0.5f, p[1]), Stop(1f, p[0])),
                )
                4 -> {
                    val corner = (h32 shr 6) % 4
                    Gradient.Radial(
                        if (corner == 1 || corner == 3) 1f else 0f,
                        if (corner >= 2) 1f else 0f,
                        listOf(Stop(0f, p[2]), Stop(0.4f, p[1]), Stop(1f, p[0])),
                    )
                }
                else -> Gradient.Linear(
                    angle,
                    listOf(Stop(0f, p[0]), Stop(0.25f, p[1]), Stop(0.5f, p[2]), Stop(0.75f, p[1]), Stop(1f, p[0])),
                )
            }
            val initials = artistName.split(' ').map { it.take(1).uppercase() }.take(2).joinToString("")
            return ArtistPattern(gradient, initials, p[3])
        }
    }
}
