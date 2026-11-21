package com.parachord.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Parachord Material 3 color schemes — maps the desktop CSS tokens
 * into Material 3 color slots. Dynamic color is intentionally disabled.
 */
private val DarkColorScheme = darkColorScheme(
    // primary is the theme accent (text, icons, outlines). White on it fails
    // contrast in dark (2.7:1), so anything Material fills with it gets dark
    // content; a fill that carries white uses FillAccent instead
    // (docs/DESIGN_SYSTEM.md, "White text never sits on the theme accent").
    primary = PurpleDark,
    onPrimary = BgPrimaryDark,
    primaryContainer = PurpleHoverDark,
    secondary = TextSecondaryDark,
    tertiary = TextTertiaryDark,
    background = BgPrimaryDark,
    onBackground = TextPrimaryDark,
    surface = BgSecondaryDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = BgElevatedDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = TextTertiaryDark,
    outlineVariant = BorderDefaultDark,
    error = ErrorDark,
)

private val LightColorScheme = lightColorScheme(
    primary = PurpleLight,
    onPrimary = Color.White,
    primaryContainer = PurpleHoverLight,
    secondary = TextSecondaryLight,
    tertiary = TextTertiaryLight,
    background = BgPrimaryLight,
    onBackground = TextPrimaryLight,
    surface = BgSecondaryLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = BgInsetLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = BorderDefaultLight,
    outlineVariant = BorderLightLight,
    error = ErrorLight,
)

// ── Extended Colors ──────────────────────────────────────────────────
// Colors that don't map to Material 3 slots (alpha variants, resolver
// colors, always-dark player surfaces, card-specific styles).

@Immutable
data class ParachordExtendedColors(
    // Accent alpha variants
    val accentAlpha06: Color,
    val accentAlpha10: Color,
    val accentAlpha20: Color,
    val accentAlpha40: Color,
    val accentAlpha60: Color,
    val accentSurface: Color,

    // Card styling
    val cardBackground: Color,
    val cardBorder: Color,

    // Always-dark player surfaces (same in both themes)
    val playerSurface: Color = PlayerSurface,
    val playerSurfaceElevated: Color = PlayerSurfaceElevated,
    val playerTextPrimary: Color = PlayerTextPrimary,
    val playerTextSecondary: Color = PlayerTextSecondary,
)

private val LightExtendedColors = ParachordExtendedColors(
    accentAlpha06 = PurpleAlpha06,
    accentAlpha10 = PurpleAlpha10,
    accentAlpha20 = PurpleAlpha20,
    accentAlpha40 = PurpleAlpha40,
    accentAlpha60 = PurpleAlpha60,
    accentSurface = PurpleSurface,
    cardBackground = CardBgLight,
    cardBorder = CardBorderLight,
)

private val DarkExtendedColors = ParachordExtendedColors(
    accentAlpha06 = PurpleDarkAlpha10,
    accentAlpha10 = PurpleDarkAlpha10,
    accentAlpha20 = PurpleDarkAlpha20,
    accentAlpha40 = PurpleAlpha40,
    accentAlpha60 = PurpleAlpha60,
    accentSurface = PurpleSurfaceDark,
    cardBackground = CardBgDark,
    cardBorder = CardBorderDark,
)

val LocalParachordColors = staticCompositionLocalOf { LightExtendedColors }

/**
 * Whether the app is currently using a dark theme.
 *
 * Use this instead of [isSystemInDarkTheme] — the app supports a manual
 * dark/light toggle in settings, so the system setting may not reflect
 * the actual theme. [ParachordTheme] provides the correct value.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/** User-configured resolver priority order, provided at the app root. */
val LocalResolverOrder = staticCompositionLocalOf { emptyList<String>() }

/** The plug-ins that are on but don't play (shared `notPlayingIds`, #707), provided at the app root. */
val LocalNotPlaying = staticCompositionLocalOf { emptyList<String>() }

// ── Theme Accessor ───────────────────────────────────────────────────

object ParachordTheme {
    val colors: ParachordExtendedColors
        @Composable get() = LocalParachordColors.current

    val isDark: Boolean
        @Composable get() = LocalIsDarkTheme.current
}

// ── Theme Composable ─────────────────────────────────────────────────

@Composable
fun ParachordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(
        LocalParachordColors provides extendedColors,
        LocalIsDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ParachordTypography,
            shapes = ParachordShapes,
            content = content,
        )
    }
}
