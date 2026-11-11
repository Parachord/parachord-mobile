package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Deterministic gradient placeholder matching the desktop's generateArtistPattern().
 * Given a name, produces a consistent gradient background with initials overlay.
 *
 * 15 distinct color palettes, 6 gradient pattern variants.
 */

/** The three colors of each palette, as ARGB. */
private val palettes: List<IntArray> = listOf(
    // Deep blue / red
    intArrayOf(0xFF1A1A2E.toInt(), 0xFF16213E.toInt(), 0xFF0F3460.toInt()),
    // Ocean blues
    intArrayOf(0xFF1E3A5F.toInt(), 0xFF3D5A80.toInt(), 0xFF98C1D9.toInt()),
    // Forest green
    intArrayOf(0xFF1B4332.toInt(), 0xFF2D6A4F.toInt(), 0xFF40916C.toInt()),
    // Warm amber
    intArrayOf(0xFF3E2723.toInt(), 0xFF5D4037.toInt(), 0xFF8D6E63.toInt()),
    // Plum/purple
    intArrayOf(0xFF2D1B69.toInt(), 0xFF4A2C82.toInt(), 0xFF6C3FA0.toInt()),
    // Teal
    intArrayOf(0xFF0D3B3E.toInt(), 0xFF1A5C5E.toInt(), 0xFF2E8B8B.toInt()),
    // Slate/charcoal
    intArrayOf(0xFF1A1A2E.toInt(), 0xFF2D2D44.toInt(), 0xFF3F3F5C.toInt()),
    // Crimson
    intArrayOf(0xFF2E0A1E.toInt(), 0xFF5C1A3A.toInt(), 0xFF8B2252.toInt()),
    // Olive
    intArrayOf(0xFF2C3E1E.toInt(), 0xFF4A5E30.toInt(), 0xFF6B7F45.toInt()),
    // Midnight
    intArrayOf(0xFF0A0E27.toInt(), 0xFF141B41.toInt(), 0xFF1E2A5A.toInt()),
    // Rust
    intArrayOf(0xFF3E1E0E.toInt(), 0xFF6B3420.toInt(), 0xFF984A32.toInt()),
    // Sage
    intArrayOf(0xFF283828.toInt(), 0xFF3E5A3E.toInt(), 0xFF5A7A5A.toInt()),
    // Steel blue
    intArrayOf(0xFF1A2A3E.toInt(), 0xFF2A3E5A.toInt(), 0xFF3E5A7A.toInt()),
    // Bronze
    intArrayOf(0xFF2E1E0E.toInt(), 0xFF5A3E1E.toInt(), 0xFF7A5A2E.toInt()),
    // Indigo night
    intArrayOf(0xFF0E0A2E.toInt(), 0xFF1E1A5A.toInt(), 0xFF2E2A7A.toInt()),
)

/**
 * Hash a name into a deterministic integer for palette/pattern selection.
 */
private fun stableHash(name: String): Int {
    var hash = 0
    for (char in name) {
        hash = 31 * hash + char.code
    }
    return abs(hash)
}

/**
 * Extract initials from a name (max 2 characters).
 */
internal fun placeholderInitials(name: String): String {
    val words = name.trim().split("\\s+".toRegex())
    return when {
        words.size >= 2 -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
        words.size == 1 && words[0].length >= 2 -> words[0].take(2).uppercase()
        words.size == 1 -> words[0].take(1).uppercase()
        else -> "?"
    }
}

/**
 * The placeholder's gradient for a name, independent of how it's drawn: the
 * palette's colors (ARGB) and which of the six patterns. Compose draws it here
 * ([gradientForName]); the home-screen widget draws the same into a bitmap
 * (`widget/WidgetArtPlaceholder.kt`).
 */
internal class PlaceholderGradient(val colors: IntArray, val pattern: Int)

internal fun placeholderGradient(name: String): PlaceholderGradient {
    val hash = stableHash(name)
    return PlaceholderGradient(palettes[hash % palettes.size], (hash / palettes.size) % 6)
}

/**
 * Create a deterministic gradient brush from a name string. Offsets are in
 * pixels, as Compose takes them.
 */
fun gradientForName(name: String): Brush {
    val gradient = placeholderGradient(name)
    val colors = gradient.colors.map { Color(it) }

    return when (gradient.pattern) {
        0 -> Brush.linearGradient(colors, start = Offset.Zero, end = Offset(1000f, 1000f))
        1 -> Brush.radialGradient(colors, center = Offset(500f, 500f), radius = 700f)
        2 -> Brush.linearGradient(colors, start = Offset(0f, 1000f), end = Offset(1000f, 0f))
        3 -> Brush.radialGradient(colors.reversed(), center = Offset(300f, 300f), radius = 800f)
        4 -> Brush.linearGradient(colors, start = Offset(0f, 0f), end = Offset(0f, 1000f))
        else -> Brush.linearGradient(
            colors + colors.reversed(),
            start = Offset(0f, 500f),
            end = Offset(1000f, 500f),
        )
    }
}

/**
 * Gradient placeholder with centered initials overlay.
 * Matches the desktop's generateArtistPattern() behavior.
 */
@Composable
fun GradientPlaceholder(
    name: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 18.sp,
) {
    val brush = remember(name) { gradientForName(name) }
    val initials = remember(name) { placeholderInitials(name) }

    Box(
        modifier = modifier.background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White.copy(alpha = 0.85f),
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
        )
    }
}
