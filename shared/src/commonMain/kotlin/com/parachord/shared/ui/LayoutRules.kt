package com.parachord.shared.ui

import kotlin.math.floor

/**
 * How card rows, grids and wide content size themselves to the space they
 * have, on a phone, a tablet and in landscape (both apps). All sizes are in
 * points (iOS) / dp (Android).
 *
 * The apps were drawn for a phone in portrait, so on a tablet a Home row of six
 * 140-wide cards stopped two-thirds of the way across, grids with a fixed two
 * columns drew 600-wide covers, and bars and text ran the full width of the
 * screen. These rules keep the phone exactly as it was and let wider screens
 * fill their width the way desktop's rows and `auto-fill` grids do.
 */
object LayoutRules {
    /** A row card's width on a phone, and the smallest it ever gets. */
    const val ROW_CARD_MIN = 140.0

    /** The widest a row card grows to on a large screen. */
    const val ROW_CARD_MAX = 240.0

    /** The narrowest a cover in an album or playlist grid gets before the grid drops a column. */
    const val GRID_CARD_MIN = 150.0

    /** The same for a grid of artist circles. */
    const val GRID_CIRCLE_MIN = 110.0

    /** From this width, two-column things (Jump back in, the Home halves) go side by side or to four. */
    const val WIDE_MIN = 700.0

    /**
     * From this width Home's halves (Fresh Drops, Critical Darlings, Pop of the
     * Tops) sit side by side: each half then still has room for its three
     * 140-wide cards. Narrower, they stack, full width.
     */
    const val HALVES_SIDE_BY_SIDE_MIN = 1000.0

    /** Text, forms, settings rows and chat bubbles stop growing here and sit in a column. */
    const val READABLE_MAX = 680.0

    /** The mini player and tab bar stop growing here and sit centred. */
    const val BAR_MAX = 640.0

    /** Now Playing's controls (title, scrubber, transport, actions) stop growing here. */
    const val PLAYER_CONTROLS_MAX = 560.0

    /** A full-bleed page header's tallest, shortest, and share of the window's height ([heroHeight]). */
    const val HERO_MAX = 360.0
    const val HERO_MIN = 160.0
    const val HERO_SHARE = 0.45

    /** On a wide screen the AI chat is a drawer this wide at the trailing edge. */
    const val CHAT_DRAWER_MAX = 480.0

    /** Track lists and the page headers above them (playlist, album, search) sit in a column this wide. */
    const val LIST_MAX = 900.0

    /**
     * The width of each card in a row that shows [cards] cards across
     * [available] (the row's width inside its gutters) with [gap] between them.
     * On a phone the result is below [ROW_CARD_MIN], so cards stay at the
     * minimum and the row scrolls, as before; on a wider screen they grow to
     * fill the row, up to [ROW_CARD_MAX].
     */
    fun rowCardWidth(available: Double, cards: Int, gap: Double): Double {
        if (cards <= 0 || available <= 0.0) return ROW_CARD_MIN
        val fill = (available - gap * (cards - 1)) / cards
        return fill.coerceIn(ROW_CARD_MIN, ROW_CARD_MAX)
    }

    /**
     * How many columns a grid gets: as many cards of at least [minCard] as fit
     * across [available] with [gap] between them, never fewer than [minColumns].
     * Each column then stretches to fill the line.
     */
    fun gridColumns(available: Double, gap: Double, minCard: Double = GRID_CARD_MIN, minColumns: Int = 2): Int {
        if (available <= 0.0 || minCard <= 0.0) return minColumns
        return maxOf(minColumns, floor((available + gap) / (minCard + gap)).toInt())
    }

    /** Whether two-column content gets its wide form at [available] wide. */
    fun isWide(available: Double): Boolean = available >= WIDE_MIN

    /**
     * A full-bleed page header's height (the artist page's photo) for a window
     * [windowHeight] tall: 360 on a phone held upright, at most 45 % of the
     * height so a landscape phone still shows the page under it, never under 160.
     */
    fun heroHeight(windowHeight: Double): Double = (windowHeight * HERO_SHARE).coerceIn(HERO_MIN, HERO_MAX)

    /** Whether Home's halves go side by side at [available] wide. */
    fun halvesSideBySide(available: Double): Boolean = available >= HALVES_SIDE_BY_SIDE_MIN

    /** Jump back in's columns: its four tiles on one line when wide, else two by two. */
    fun jumpBackInColumns(available: Double): Int = if (isWide(available)) 4 else 2
}
