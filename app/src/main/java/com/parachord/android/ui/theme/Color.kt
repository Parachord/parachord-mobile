package com.parachord.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.ServiceColors

/**
 * Parachord colour palette. The values live once, in the shared
 * [DesignTokens] (both apps read it; see docs/DESIGN_SYSTEM.md). This file
 * only turns them into Compose colours: change a value there, not here.
 */

/** A shared `0xRRGGBB` token as an opaque Compose colour. */
fun tokenColor(rgb: Int, alpha: Float = 1f): Color =
    Color(0xFF000000L or rgb.toLong()).copy(alpha = alpha)

private fun DesignTokens.ThemeColor.lightColor() = tokenColor(light)
private fun DesignTokens.ThemeColor.darkColor() = tokenColor(dark, darkAlpha)

// ── Accent Purple Scale ──────────────────────────────────────────────
val PurpleLight = DesignTokens.accent.lightColor()          // --accent-primary (light)
val PurpleDark = DesignTokens.accent.darkColor()            // --accent-primary (dark)
val PurpleHoverLight = DesignTokens.accentHover.lightColor()
val PurpleHoverDark = DesignTokens.accentHover.darkColor()
val PurpleSecondary = tokenColor(DesignTokens.ACCENT_SECONDARY)
val PurpleTertiary = tokenColor(DesignTokens.ACCENT_TERTIARY)
val PurpleSoft = tokenColor(DesignTokens.ACCENT_SOFT)
val PurpleSurface = DesignTokens.accentSurface.lightColor() // --accent-surface (light)
val PurpleSurfaceDark = DesignTokens.accentSurface.darkColor() // #8b5cf6 at 15%

/**
 * Fill accent: anything filled that carries white text, in BOTH themes.
 * White on the dark accent [PurpleDark] fails contrast.
 */
val FillAccent = tokenColor(DesignTokens.FILL_ACCENT)

// Alpha variants (matching desktop rgba(124,58,237,x))
val PurpleAlpha06 = PurpleLight.copy(alpha = 0.06f)
val PurpleAlpha10 = PurpleLight.copy(alpha = 0.10f)
val PurpleAlpha20 = PurpleLight.copy(alpha = 0.20f)
val PurpleAlpha40 = PurpleLight.copy(alpha = 0.40f)
val PurpleAlpha60 = PurpleLight.copy(alpha = 0.60f)

// Dark theme alpha variants
val PurpleDarkAlpha10 = PurpleDark.copy(alpha = 0.10f)
val PurpleDarkAlpha20 = PurpleDark.copy(alpha = 0.20f)

// ── Semantic Colors ──────────────────────────────────────────────────
val Success = tokenColor(DesignTokens.SUCCESS)
val Warning = tokenColor(DesignTokens.WARNING)
val Error = tokenColor(DesignTokens.ERROR)
val OnTour = tokenColor(DesignTokens.ON_TOUR)

// ── Background Colors ────────────────────────────────────────────────
val BgPrimaryLight = DesignTokens.bgPrimary.lightColor()
val BgSecondaryLight = DesignTokens.bgSecondary.lightColor()
val BgElevatedLight = DesignTokens.bgElevated.lightColor()
val BgInsetLight = DesignTokens.bgInset.lightColor()

val BgPrimaryDark = DesignTokens.bgPrimary.darkColor()
val BgSecondaryDark = DesignTokens.bgSecondary.darkColor()
val BgElevatedDark = DesignTokens.bgElevated.darkColor()
val BgInsetDark = DesignTokens.bgInset.darkColor()

// ── Text Colors ──────────────────────────────────────────────────────
val TextPrimaryLight = DesignTokens.textPrimary.lightColor()
val TextSecondaryLight = DesignTokens.textSecondary.lightColor()
val TextTertiaryLight = DesignTokens.textTertiary.lightColor()

val TextPrimaryDark = DesignTokens.textPrimary.darkColor()
val TextSecondaryDark = DesignTokens.textSecondary.darkColor()
val TextTertiaryDark = DesignTokens.textTertiary.darkColor()

// ── Border Colors ────────────────────────────────────────────────────
val BorderDefaultLight = DesignTokens.borderDefault.lightColor()
val BorderLightLight = DesignTokens.borderLight.lightColor()
val BorderDefaultDark = DesignTokens.borderDefault.darkColor()
val BorderLightDark = DesignTokens.borderLight.darkColor()

// ── Card Colors ──────────────────────────────────────────────────────
val CardBgLight = DesignTokens.cardBg.lightColor()
val CardBorderLight = DesignTokens.cardBorder.lightColor()
val CardBgDark = DesignTokens.cardBg.darkColor()
val CardBorderDark = DesignTokens.cardBorder.darkColor()

// ── Always-Dark Player Surface ───────────────────────────────────────
val PlayerSurface = tokenColor(DesignTokens.PLAYER_BG)
val PlayerSurfaceElevated = tokenColor(DesignTokens.PLAYER_BG_ELEVATED)
val PlayerTextPrimary = tokenColor(DesignTokens.PLAYER_TEXT_PRIMARY)
val PlayerTextSecondary = tokenColor(DesignTokens.PLAYER_TEXT_SECONDARY)

// ── Page and release-type colours ────────────────────────────────────
val DesignTokens.Page.composeColor: Color get() = tokenColor(color)
val DesignTokens.Page.composeGradient: List<Color> get() = gradient.map { tokenColor(it) }

fun releaseTypeColor(type: String?): Color = tokenColor(DesignTokens.releaseTypeColor(type))

// ── Service colours ──────────────────────────────────────────────────

/** A service's colour: its plugin manifest's `color` (shared [ServiceColors]). */
fun serviceColor(serviceId: String?): Color = tokenColor(ServiceColors.color(serviceId))

/** As [serviceColor], or null for a service with no colour at all. */
fun serviceColorOrNull(serviceId: String?): Color? =
    ServiceColors.colorOrNull(serviceId)?.let { tokenColor(it) }
