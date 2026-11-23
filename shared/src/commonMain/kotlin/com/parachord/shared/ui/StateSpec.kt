package com.parachord.shared.ui

/**
 * The empty and error states (desktop `states.js`, #1385 step 8): one of each
 * per platform, Android `EmptyState` / `ErrorState`
 * (`ui/components/States.kt`) and iOS `PCEmptyState` / `PCErrorState`.
 *
 * Each is an icon in a soft circle (bg-inset), a title (text-primary, 600),
 * a body line saying why or what to do (text-secondary) and optional
 * actions, the first filled and the rest outlined. An empty state's icon is
 * tertiary (Music unless the page names one); an error's is AlertTriangle in
 * the danger colour, its title "Something went wrong" unless the page says
 * what failed, and its action "Try again". [COMPACT] is for a section rather
 * than a page.
 */
object StateSpec {
    const val GAP = 12
    const val PAGE_PADDING_V = 48
    const val PAGE_PADDING_H = 24
    const val COMPACT_PADDING_V = 24
    const val COMPACT_PADDING_H = 16
    const val CIRCLE = 56
    const val CIRCLE_COMPACT = 44
    const val ICON = 24
    const val ICON_COMPACT = 20
    const val TITLE_SIZE = 16
    const val TITLE_SIZE_COMPACT = 14
    const val TITLE_WEIGHT = 600
    const val BODY_SIZE = 14
    /** The body's line height, as a multiple of its size. */
    const val BODY_LINE_HEIGHT = 1.5f
    /** The widest the body runs (desktop's 32rem). */
    const val BODY_MAX_WIDTH = 512
    /** Above the actions, on top of [GAP]. */
    const val ACTIONS_TOP = 4
    const val ACTIONS_GAP = 8
    const val ERROR_TITLE = "Something went wrong"
}
