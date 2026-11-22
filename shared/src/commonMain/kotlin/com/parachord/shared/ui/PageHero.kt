package com.parachord.shared.ui

import com.parachord.shared.ui.icons.IconPart
import com.parachord.shared.ui.icons.IconSpec

/**
 * The page hero: the coloured bar at the top of a page (desktop
 * `components/page-hero.js`, collapsed). Home, Collection, Playlists,
 * History (and a friend's history), Recommendations, Pop of the Tops, Fresh
 * Drops, Critical Darlings, Concerts and Search all draw it: the page's gradient
 * ([DesignTokens.Page.gradient], top-left to bottom-right), its pattern tiled
 * over it ([PageHero.pattern]), and the title in light, tracked capitals. A
 * page that has a line to say (its description, a count) shows it under the
 * title, as desktop's full hero does. Android `PageHero`, iOS `PCPageHero`
 * draw it; neither keeps its own copy.
 */
object PageHero {
    /** The bar's height with the title alone (desktop's 80 px collapsed bar, on a phone). */
    const val HEIGHT = 64
    /** The bar's height with a line under the title. */
    const val HEIGHT_WITH_LINE = 80
    const val TITLE_SIZE = 24
    /** Letter spacing, in ems (desktop's collapsed bar: 0.2em). */
    const val TITLE_TRACKING = 0.2f
    const val LINE_SIZE = 13
    /** The line's white, as an alpha. */
    const val LINE_ALPHA = 0.85f

    /**
     * The page's description, the first part of the line under the title
     * (desktop's full hero says the same under its title). Recommendations'
     * line is the page's own sentence ([com.parachord.shared.recommendations]),
     * so it has none here; Home, Collection and Playlists have none.
     */
    fun description(page: DesignTokens.Page): String? = when (page) {
        DesignTokens.Page.HISTORY -> "Your top tracks, albums, artists and recent plays"
        DesignTokens.Page.POP_OF_THE_TOPS -> "What’s trending around the world"
        DesignTokens.Page.FRESH_DROPS -> "New music from artists in your collection and listening history"
        DesignTokens.Page.CRITICAL_DARLINGS -> "Top-rated albums from leading music publications"
        DesignTokens.Page.CONCERTS -> "Upcoming concerts from artists you love"
        DesignTokens.Page.HOME, DesignTokens.Page.COLLECTION, DesignTokens.Page.PLAYLISTS,
        DesignTokens.Page.RECOMMENDATIONS, DesignTokens.Page.SEARCH -> null
    }

    /** "12 albums", in the page's own word for what it lists; null for none, or a page without a count. [tracks]: Pop of the Tops' Tracks tab. */
    fun countLabel(page: DesignTokens.Page, count: Int, tracks: Boolean = false): String? {
        if (count <= 0) return null
        val noun = when (page) {
            DesignTokens.Page.PLAYLISTS -> "playlist"
            DesignTokens.Page.FRESH_DROPS -> "release"
            DesignTokens.Page.CRITICAL_DARLINGS -> "album"
            DesignTokens.Page.CONCERTS -> "concert"
            DesignTokens.Page.POP_OF_THE_TOPS -> if (tracks) "track" else "album"
            else -> return null
        }
        return com.parachord.shared.format.countOf(count, noun)
    }

    /**
     * The line under the title: the description and the count, joined by
     * " · ", or null when the page has neither (then the hero is the title
     * alone, [HEIGHT]). [count] null or 0 leaves the count out (still loading).
     */
    fun line(page: DesignTokens.Page, count: Int? = null, tracks: Boolean = false): String? =
        listOfNotNull(description(page), count?.let { countLabel(page, it, tracks) })
            .joinToString(" · ").ifEmpty { null }

    /**
     * A selected tab's (or drawer entry's) icon and label colour: the page's
     * colour, as desktop's sidebar draws the active page. On a light surface
     * it's the page's darker [DesignTokens.Page.fill], since the bright colour
     * fails 4.5:1 as text on white (Collection's cyan is about 2.4:1); on a
     * dark one the bright [DesignTokens.Page.color]. Home is the theme accent
     * and Search, which has no page colour of its own, keeps it too.
     */
    fun tabColor(page: DesignTokens.Page, onDark: Boolean): Int = when (page) {
        DesignTokens.Page.HOME, DesignTokens.Page.SEARCH ->
            if (onDark) DesignTokens.accent.dark else DesignTokens.accent.light
        else -> if (onDark) page.color else page.fill
    }

    /** A selected drawer entry's background: the page's colour at 10% (desktop's `pageTint`). */
    const val TAB_TINT_ALPHA = 0.1f

    /** Pop of the Tops' line: its description, then both charts' counts. */
    fun chartsLine(albums: Int, tracks: Int): String =
        listOfNotNull(
            description(DesignTokens.Page.POP_OF_THE_TOPS),
            countLabel(DesignTokens.Page.POP_OF_THE_TOPS, albums),
            countLabel(DesignTokens.Page.POP_OF_THE_TOPS, tracks, tracks = true),
        ).joinToString(" · ")

    /** A page's background pattern: [tile] repeated over the hero at [opacity]. */
    class Pattern(val tile: IconSpec, val opacity: Float)

    /** The page's pattern (desktop `lib/page-colors.js` `pattern` / `patternOpacity`; Home's from `home-page.js`); null for Search's plain neutral hero. */
    fun pattern(page: DesignTokens.Page): Pattern? = when (page) {
        DesignTokens.Page.SEARCH -> null
        DesignTokens.Page.HOME -> Pattern(
            tile(100f, stroke(circle(50f, 50f, 40f), 2f), stroke(circle(50f, 50f, 25f), 1.5f), stroke(circle(50f, 50f, 10f), 1f)),
            0.1f,
        )
        DesignTokens.Page.PLAYLISTS -> Pattern(
            tile(60f, stroke("M5 15h50M5 30h35M5 45h45", 2f), fill(circle(50f, 30f, 5f))),
            0.15f,
        )
        DesignTokens.Page.COLLECTION -> Pattern(
            tile(60f, stroke(circle(30f, 30f, 20f), 2f), stroke(circle(30f, 30f, 12f), 1f), fill(circle(30f, 30f, 4f))),
            0.08f,
        )
        DesignTokens.Page.HISTORY -> Pattern(
            tile(60f, stroke(circle(30f, 30f, 12f), 1.5f), stroke("M30 22v8l5 5", 1.5f)),
            0.15f,
        )
        DesignTokens.Page.FRESH_DROPS -> Pattern(
            tile(
                100f,
                fill(circle(25f, 25f, 2f)), fill(circle(75f, 25f, 1.5f)), fill(circle(50f, 50f, 2.5f)),
                fill(circle(25f, 75f, 1.5f)), fill(circle(75f, 75f, 2f)), fill(circle(10f, 50f, 1f)), fill(circle(90f, 50f, 1f)),
            ),
            0.2f,
        )
        DesignTokens.Page.RECOMMENDATIONS, DesignTokens.Page.CRITICAL_DARLINGS -> Pattern(tile(80f, fill(STAR)), 0.15f)
        DesignTokens.Page.POP_OF_THE_TOPS -> Pattern(
            tile(
                60f,
                fill(
                    "M36 34v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zm0-30V0h-2v4h-4v2h4v4h2V6h4V4h-4z" +
                        "M6 34v-4H4v4H0v2h4v4h2v-4h4v-2H6zM6 4V0H4v4H0v2h4v4h2V6h4V4H6z",
                    alpha = 0.4f,
                ),
            ),
            0.15f,
        )
        DesignTokens.Page.CONCERTS -> Pattern(
            tile(
                80f,
                fill(circle(20f, 20f, 3f), 0.4f), fill(circle(60f, 20f, 2f), 0.4f), fill(circle(40f, 40f, 2.5f), 0.4f),
                fill(circle(20f, 60f, 2f), 0.4f), fill(circle(60f, 60f, 3f), 0.4f),
                stroke("M10 45l4-15v12", 1f), stroke("M65 35l4-15v12", 1f),
            ),
            0.15f,
        )
    }

    private const val STAR = "M40 5l4.5 13.8h14.5l-11.7 8.5 4.5 13.8L40 32.6l-11.8 8.5 4.5-13.8-11.7-8.5h14.5z"

    private fun tile(size: Float, vararg parts: IconPart) = IconSpec(0f, 0f, size, size, parts.toList())
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
    private fun stroke(d: String, width: Float) = IconPart(d, fill = false, stroke = true, strokeWidth = width)
    private fun fill(d: String, alpha: Float = 1f) = IconPart(d, fill = true, stroke = false, strokeWidth = 0f, alpha = alpha)
}
