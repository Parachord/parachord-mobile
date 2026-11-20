package com.parachord.shared.ui

import kotlin.concurrent.Volatile

/**
 * The design tokens both apps draw with, in one place (docs/DESIGN_SYSTEM.md).
 *
 * Values are plain `0xRRGGBB` ints, so Compose (`Color(0xFF000000 or v)`) and
 * SwiftUI (`UIColor(hex: UInt32(v))`) read the same numbers. Android's
 * `ui/theme/Color.kt` and iOS's `Theme.swift` only convert them; a value
 * changes here, never in a platform file. Desktop's `index.html` and
 * `lib/page-colors.js` are the source of truth for the values themselves
 * (desktop's design audit, parachord#1393).
 */
object DesignTokens {

    /** A colour that differs by theme. [darkAlpha] < 1 is a tint (accent-surface). */
    data class ThemeColor(val light: Int, val dark: Int, val darkAlpha: Float = 1f)

    // ── Theme colours ────────────────────────────────────────────────
    val bgPrimary = ThemeColor(0xFFFFFF, 0x161616)
    val bgSecondary = ThemeColor(0xF9FAFB, 0x1E1E1E)
    /** Sheets, dialogs, menus. */
    val bgElevated = ThemeColor(0xFFFFFF, 0x252525)
    val bgInset = ThemeColor(0xF3F4F6, 0x1A1A1A)
    val cardBg = ThemeColor(0xFFFFFF, 0x252525)
    val cardBorder = ThemeColor(0xE5E7EB, 0x2E2E2E)
    /** Image placeholders, skeleton bars. */
    val placeholderBg = ThemeColor(0xE5E7EB, 0x383838)
    val textPrimary = ThemeColor(0x111827, 0xF3F4F6)
    /** Labels, metadata. Passes 4.5:1 on the page and on cards. */
    val textSecondary = ThemeColor(0x4B5563, 0x9CA3AF)
    /** Hints, counts, timestamps. Passes 4.5:1 too, distinct from secondary. */
    val textTertiary = ThemeColor(0x6B7280, 0x8B8F97)
    val borderDefault = ThemeColor(0xE5E7EB, 0x2E2E2E)
    val borderLight = ThemeColor(0xF3F4F6, 0x252525)
    val borderStrong = ThemeColor(0xD1D5DB, 0x404040)
    /** A switch track that's off. */
    val controlOff = ThemeColor(0xD1D5DB, 0x707070)

    /** Links, outlines, active icons, selected text. Never under white text. */
    val accent = ThemeColor(0x7C3AED, 0xA78BFA)
    val accentHover = ThemeColor(0x6D28D9, 0x8B5CF6)
    val accentSecondary = ThemeColor(0x8B5CF6, 0xC4B5FD)
    val accentSoft = ThemeColor(0xA78BFA, 0xC4B5FD)
    /** Accent-tinted surface: opaque in light, `#8b5cf6` at 15% in dark. */
    val accentSurface = ThemeColor(0xEDE9FE, 0x8B5CF6, darkAlpha = 0.15f)
    const val ACCENT_TERTIARY = 0xA855F7

    /**
     * Anything filled that carries white text (buttons, selected chips, play
     * buttons, badges, switch tracks), in both themes. White on the dark
     * accent `#a78bfa` is 2.7:1; on this it's 5.7:1.
     */
    const val FILL_ACCENT = 0x7C3AED
    const val FILL_ACCENT_HOVER = 0x6D28D9
    /** Text on any fill. */
    const val ON_ACCENT = 0xFFFFFF
    /**
     * A white chip over art or a cover (a reason badge), the same in both
     * themes: white at [ON_COLOR_CHIP_ALPHA] (desktop `--on-color-chip`).
     */
    const val ON_COLOR_CHIP = 0xFFFFFF
    const val ON_COLOR_CHIP_ALPHA = 0.95f
    /** Dark ink on an [ON_COLOR_CHIP] over art, both themes (desktop `--on-art-ink`). */
    const val ON_ART_INK = 0x111827

    // ── Status ───────────────────────────────────────────────────────
    /** Dots, icons and indicators. */
    val success = ThemeColor(0x10B981, 0x34D399)
    val warning = ThemeColor(0xF59E0B, 0xFBBF24)
    val error = ThemeColor(0xEF4444, 0xF87171)
    val info = ThemeColor(0x2563EB, 0x60A5FA)
    /** Status text on a page or card (≥ 4.5:1 in both themes). */
    val textSuccess = ThemeColor(0x15803D, 0x4ADE80)
    val textWarning = ThemeColor(0xB45309, 0xFBBF24)
    val textDanger = ThemeColor(0xB91C1C, 0xF87171)
    val textInfo = ThemeColor(0x1D4ED8, 0x93C5FD)
    /** Status fills with white text (toasts, banners, a confirm button), both themes. */
    const val FILL_SUCCESS = 0x15803D
    const val FILL_WARNING = 0xB45309
    const val FILL_ERROR = 0xB91C1C
    const val FILL_ERROR_HOVER = 0x991B1B
    const val FILL_INFO = 0x2563EB

    const val ON_TOUR = 0x10C9B4
    const val HOSTED_FG = 0x3B82F6
    const val HOSTED_BG = 0xEFF6FF

    // ── Always-dark player surfaces (mini player, Now Playing, queue) ─
    const val PLAYER_BG = 0x161616
    const val PLAYER_BG_ELEVATED = 0x1E1E1E
    const val PLAYER_TEXT_PRIMARY = 0xF3F4F6
    const val PLAYER_TEXT_SECONDARY = 0x9CA3AF

    // ── Page colours ─────────────────────────────────────────────────
    /**
     * Each page's colour (drawer / tab entry, its tint, "See all" links, its
     * own controls), [fill] (a darker shade for white-text buttons, ≥ 4.5:1)
     * and hero gradient, top-left to bottom-right (desktop's
     * `bg-gradient-to-br`). A port of desktop's `lib/page-colors.js`; no two
     * heroes start in the same colour family. Home's colour is the theme
     * accent ([accent]); [color] holds its light value.
     */
    enum class Page(val title: String, val color: Int, val fill: Int, val gradient: List<Int>) {
        HOME("Home", 0x7C3AED, FILL_ACCENT, listOf(0xA855F7, 0x7C3AED, 0x4F46E5)),
        PLAYLISTS("Playlists", 0xEC4899, 0xBE185D, listOf(0xF43F5E, 0xEC4899, 0xC026D3)),
        COLLECTION("Collection", 0x06B6D4, 0x0E7490, listOf(0x06B6D4, 0x0891B2, 0x1D4ED8)),
        HISTORY("History", 0x3B82F6, 0x1D4ED8, listOf(0x0EA5E9, 0x3B82F6, 0x4F46E5)),
        FRESH_DROPS("Fresh Drops", 0x22C55E, 0x15803D, listOf(0x84CC16, 0x22C55E, 0x059669)),
        RECOMMENDATIONS("Recommendations", 0xF59E0B, 0xB45309, listOf(0xFBBF24, 0xF59E0B, 0xF97316)),
        POP_OF_THE_TOPS("Pop of the Tops", 0xF97316, 0xC2410C, listOf(0xF97316, 0xEC4899, 0x9333EA)),
        CRITICAL_DARLINGS("Critical Darlings", 0xEF4444, 0xB91C1C, listOf(0xEF4444, 0xDC2626, 0xBE123C)),
        CONCERTS("Concerts", 0x10C9B4, 0x0F766E, listOf(0x14B8A6, 0x14B8A6, 0x0891B2)),
    }

    // ── Release types ────────────────────────────────────────────────
    /**
     * One colour per release type, for every screen (Fresh Drops, a
     * discography, an album page). Album / EP / Single are desktop's Fresh
     * Drops chips; anything unknown is grey.
     */
    fun releaseTypeColor(type: String?): Int = when (type?.lowercase()) {
        "album" -> 0x6366F1
        "ep" -> 0xA855F7
        "single" -> 0xDB2777
        "live" -> 0xD97706
        "compilation" -> 0x0EB3A0
        else -> 0x9CA3AF
    }

    // ── Scales ───────────────────────────────────────────────────────
    /** Font sizes (sp / pt). Small uppercase labels: 11, 600, 0.08em. */
    val typeScale = listOf(11, 12, 13, 14, 16, 20, 24, 28, 48)
    /** Spacing, 4-point steps. The phone gutter is [SCREEN_GUTTER]. */
    val spacingScale = listOf(2, 4, 6, 8, 12, 16, 20, 24, 32, 40, 48)
    const val SCREEN_GUTTER = 16

    const val RADIUS_BADGE = 4
    const val RADIUS_ART = 6
    const val RADIUS_BUTTON = 8
    const val RADIUS_CARD = 10
    const val RADIUS_PANEL = 12
    const val RADIUS_SHEET = 16
    const val RADIUS_PILL = 999
}

/**
 * A service's colour is its plugin's manifest `color`, as on desktop. The
 * shared `PluginManager` reports each loaded plugin's colour here ([update]);
 * until then (and for an id no plugin carries) [FALLBACK] answers, which a
 * test keeps equal to the bundled manifests.
 */
object ServiceColors {
    /** Brand purple, for a service with no colour at all. */
    const val DEFAULT = DesignTokens.FILL_ACCENT

    /** The bundled `.axe` manifests' colours, plus services with no bundled plugin. */
    val FALLBACK: Map<String, Int> = mapOf(
        "spotify" to 0x1DB954,
        "applemusic" to 0xFA243C,
        "amazonmusic" to 0x25D1DA,
        "youtube" to 0xFF0000,
        "bandcamp" to 0x629AA9,
        "soundcloud" to 0xFF5500,
        "subsonic" to 0x14B8A6,
        "localfiles" to 0x6366F1,
        "qobuz" to 0x4285F4,
        "lastfm" to 0xD51007,
        "listenbrainz" to 0x353070,
        "librefm" to 0x4CAF50,
        "discogs" to 0x333333,
        "wikipedia" to 0x000000,
        "customllm" to 0x8B5CF6,
        "chatgpt" to 0x10A37F,
        "claude" to 0xD97757,
        "gemini" to 0x4285F4,
        "ollama" to 0x000000,
        "ticketmaster" to 0x026CDF,
        "seatgeek" to 0xFC4C02,
        "bandsintown" to 0x00B4B3,
        "songkick" to 0xF80046,
        "achordion" to 0x7C3AED,
    )

    @Volatile
    private var fromPlugins: Map<String, Int> = emptyMap()

    /** Record the loaded plugins' manifest colours (id → "#RRGGBB"). */
    fun update(manifestColors: Map<String, String>) {
        fromPlugins = manifestColors.mapNotNull { (id, hex) ->
            parseHex(hex)?.let { id.lowercase() to it }
        }.toMap()
    }

    /** The service's colour, or null when neither a plugin nor the fallback has one. */
    fun colorOrNull(serviceId: String?): Int? {
        val id = serviceId?.lowercase()?.replace(" ", "") ?: return null
        return fromPlugins[id] ?: FALLBACK[id]
    }

    /** The service's colour, else brand purple. */
    fun color(serviceId: String?): Int = colorOrNull(serviceId) ?: DEFAULT

    /** "#1DB954" / "1db954" / "#fff" → 0x1DB954; anything else → null. */
    fun parseHex(hex: String?): Int? {
        val s = hex?.trim()?.removePrefix("#") ?: return null
        val six = when (s.length) {
            6 -> s
            3 -> s.map { "$it$it" }.joinToString("")
            else -> return null
        }
        return six.toIntOrNull(16)
    }
}
