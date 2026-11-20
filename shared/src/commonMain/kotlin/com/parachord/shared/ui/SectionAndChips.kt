package com.parachord.shared.ui

import kotlin.math.pow

/**
 * The text colour for a coloured fill: white whenever it reads (3:1 contrast,
 * WCAG's floor for bold text and controls), else whichever of white or
 * near-black contrasts more. So Apple Music's red and SoundCloud's orange get
 * white, as on their own buttons, and only fills too light for white
 * (Spotify's green, Amazon's cyan) get near-black. A port of desktop's
 * `lib/readable-text.js`; its vectors are in SectionAndChipsTest.
 */
object ReadableText {
    const val LIGHT = 0xFFFFFF
    const val DARK = 0x111827

    /** [LIGHT] or [DARK] for text on [rgb]; null (no colour) gets [LIGHT]. */
    fun on(rgb: Int?): Int {
        if (rgb == null) return LIGHT
        val l = luminance(rgb)
        val onWhite = contrast(1.0, l)
        if (onWhite >= 3.0) return LIGHT
        return if (onWhite >= contrast(l, luminance(DARK))) LIGHT else DARK
    }

    private fun luminance(rgb: Int): Double {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin((rgb shr 16) and 0xFF) + 0.7152 * lin((rgb shr 8) and 0xFF) + 0.0722 * lin(rgb and 0xFF)
    }

    private fun contrast(a: Double, b: Double): Double {
        val hi = maxOf(a, b); val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }
}

/**
 * The section title over a row or grid (desktop `section-header.js`, #1370):
 * 20 / 700 text-primary in sentence case, an optional 12 text-secondary
 * subtitle 2 below, and on the right an optional link, 13 / 600, in the
 * destination page's colour (the accent when it acts in place). A
 * subsection title is 16 / 600. 16 below it before the content.
 */
object SectionHeaderSpec {
    const val TITLE_SIZE = 20
    const val TITLE_WEIGHT = 700
    const val SUB_TITLE_SIZE = 16
    const val SUB_TITLE_WEIGHT = 600
    const val SUBTITLE_SIZE = 12
    const val SUBTITLE_GAP = 2
    const val LINK_SIZE = 13
    const val LINK_WEIGHT = 600
    /** Between the title block and the link. */
    const val ROW_GAP = 12
    /** When the link doesn't fit beside the title, it goes on its own line this far under the title block. */
    const val LINK_GAP_BELOW_TITLE = 4
    /** Under the header, before its content. */
    const val GAP_BELOW = 16

    /** The link to a page: "See all in Playlists". */
    fun seeAllLabel(page: DesignTokens.Page): String = "See all in ${page.title}"
}

/**
 * Pick-one filter buttons (desktop `filter-chips.js`, #1371): a pill, 12 /
 * 600, padding 8×12, at least 36 high (48 / 44 touch target), 8 apart. A
 * chip with a colour shows an 8-point dot when idle and fills with it when
 * picked; a chip without one fills with fill-accent, or the page's fill on a
 * page with its own colour.
 */
object FilterChipSpec {
    const val TEXT_SIZE = 12
    const val TEXT_WEIGHT = 600
    const val PAD_VERTICAL = 8
    const val PAD_HORIZONTAL = 12
    const val MIN_HEIGHT = 36
    const val GAP = 8
    const val DOT = 8

    /**
     * How one chip looks. A null [fill] means the theme's bg-secondary and a
     * null [text] the theme's text-primary (the idle chip); [dot] is the idle
     * coloured chip's dot.
     */
    data class Look(val fill: Int?, val text: Int?, val dot: Int?)

    /**
     * [color] is the chip's own colour (a service, a release type), else
     * null; [pageFill] the page's white-text fill ([DesignTokens.Page.fill])
     * for a page with its own colour, else null (fill-accent).
     */
    fun look(selected: Boolean, color: Int?, pageFill: Int? = null): Look = when {
        !selected -> Look(fill = null, text = null, dot = color)
        color != null -> Look(fill = color, text = ReadableText.on(color), dot = null)
        else -> Look(fill = pageFill ?: DesignTokens.FILL_ACCENT, text = DesignTokens.ON_ACCENT, dot = null)
    }
}

/**
 * The play button over art (desktop `card-art.js` `APP_PLAY_BTN` / `PLAY_ICON`):
 * a white disc with a large shadow and the play triangle in on-art ink
 * ([DesignTokens.ON_ART_INK]), the icon [ICON_FRACTION] of the disc and nudged
 * right [ICON_NUDGE] of it to look centred. Always shown on a phone (no
 * hover), and the art isn't dimmed. A standalone Play control is the
 * fill-accent circle instead.
 */
object ArtPlayButtonSpec {
    /** The disc on a 140-wide card. */
    const val SIZE = 40
    /** The disc on a 72-high Jump back in tile (half its art, so the cover still reads). */
    const val TILE_SIZE = 36
    const val MIN_TOUCH = 48
    const val ICON_FRACTION = 0.46f
    const val ICON_NUDGE = 0.04f
    /** Elevation standing in for desktop's `shadow-lg`. */
    const val ELEVATION = 6

    fun label(title: String): String = if (title.isBlank()) "Play" else "Play $title"
}
