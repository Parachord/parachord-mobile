package com.parachord.shared.ui

import kotlin.concurrent.Volatile

/**
 * The design tokens both apps draw with, in one place (docs/DESIGN_SYSTEM.md).
 *
 * Values are plain `0xRRGGBB` ints, so Compose (`Color(0xFF000000 or v)`) and
 * SwiftUI (`UIColor(hex: UInt32(v))`) read the same numbers. Android's
 * `ui/theme/Color.kt` and iOS's `Theme.swift` only convert them; a value
 * changes here, never in a platform file. Desktop's `index.html` is the source
 * of truth for the values themselves.
 */
object DesignTokens {

    /** A colour that differs by theme. [darkAlpha] < 1 is a tint (accent-surface). */
    data class ThemeColor(val light: Int, val dark: Int, val darkAlpha: Float = 1f)

    // ── Theme colours ────────────────────────────────────────────────
    val bgPrimary = ThemeColor(0xFFFFFF, 0x161616)
    val bgSecondary = ThemeColor(0xF9FAFB, 0x1E1E1E)
    val bgElevated = ThemeColor(0xFFFFFF, 0x252525)
    val bgInset = ThemeColor(0xF3F4F6, 0x1A1A1A)
    val cardBg = ThemeColor(0xFFFFFF, 0x1E1E1E)
    val cardBorder = ThemeColor(0xE5E7EB, 0x2A2A2A)
    val textPrimary = ThemeColor(0x111827, 0xF3F4F6)
    val textSecondary = ThemeColor(0x6B7280, 0x9CA3AF)
    /** Placeholders only: fails 4.5:1 as label text in both themes. */
    val textTertiary = ThemeColor(0x9CA3AF, 0x6B7280)
    val borderDefault = ThemeColor(0xE5E7EB, 0x2E2E2E)
    val borderLight = ThemeColor(0xF3F4F6, 0x252525)

    /** Links, outlines, active icons, selected text. Never under white text. */
    val accent = ThemeColor(0x7C3AED, 0xA78BFA)
    val accentHover = ThemeColor(0x6D28D9, 0x8B5CF6)
    /** Accent-tinted surface: opaque in light, `#8b5cf6` at 15% in dark. */
    val accentSurface = ThemeColor(0xEDE9FE, 0x8B5CF6, darkAlpha = 0.15f)

    /**
     * Anything filled that carries white text (buttons, selected chips, play
     * buttons, badges), in both themes. White on the dark accent `#a78bfa` is
     * 2.7:1; on this it's 5.7:1.
     */
    const val FILL_ACCENT = 0x7C3AED
    const val ACCENT_SECONDARY = 0x8B5CF6
    const val ACCENT_TERTIARY = 0xA855F7
    const val ACCENT_SOFT = 0xA78BFA

    // ── Semantic ─────────────────────────────────────────────────────
    const val SUCCESS = 0x10B981
    const val WARNING = 0xF59E0B
    const val ERROR = 0xEF4444
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
     * Each page's colour (drawer / tab entry, "See all" links) and its hero
     * gradient, top-left to bottom-right, as desktop's `bg-gradient-to-br`.
     */
    enum class Page(val color: Int, val gradient: List<Int>) {
        HOME(0x7C3AED, listOf(0x7C3AED, 0x6D28D9)),
        COLLECTION(0x06B6D4, listOf(0x06B6D4, 0x0891B2)),
        HISTORY(0x3B82F6, listOf(0x06B6D4, 0x3B82F6, 0x4F46E5)),
        FRESH_DROPS(0x10B981, listOf(0x10B981, 0x14B8A6, 0x0891B2)),
        POP_OF_THE_TOPS(0xF97316, listOf(0xF97316, 0xEC4899, 0x9333EA)),
        CRITICAL_DARLINGS(0xEF4444, listOf(0xF59E0B, 0xF97316, 0xEF4444)),
        CONCERTS(0x10C9B4, listOf(0x14B8A6, 0x14B8A6, 0x0891B2)),
        PLAYLISTS(0xEC4899, listOf(0xF43F5E, 0xEC4899, 0xC026D3)),
        RECOMMENDATIONS(0xF59E0B, listOf(0xFBBF24, 0xF59E0B, 0xF97316)),
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
