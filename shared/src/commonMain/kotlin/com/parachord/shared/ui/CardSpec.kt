package com.parachord.shared.ui

/**
 * The album and playlist cards (desktop `album-card.js` / `playlist-card.js`,
 * on `card-art.js`'s `ArtBox` and `home-skeletons.js`'s `HOME_CARD_BOX`): one
 * of each per platform, Android `ParachordAlbumCard` / `ParachordPlaylistCard`,
 * iOS `PCAlbumCard` / `PCPlaylistCard`, and their skeleton (`CardSkeleton` /
 * `PCCardSkeleton`), used by every row and grid. Each draws the inset card:
 * the card colour and shadow, [PADDING] inside, [RADIUS] corners; the square
 * art at [ART_RADIUS]; then the title, a secondary line and an optional
 * tertiary one. What goes on the art is decided here ([CardPill], the rank,
 * the counts); each app only draws it.
 */
object CardSpec {
    /** The card's inside padding (desktop's `HOME_CARD_BOX`). */
    const val PADDING = 10
    const val RADIUS = DesignTokens.RADIUS_CARD
    /** The art's corners inside the card (desktop's `HOME_CARD_ART_RADIUS`). */
    const val ART_RADIUS = 8
    /** From the art to the title. */
    const val TEXT_GAP = 10
    /** Between the title and each line under it (desktop's 10 gap less its −6 margin). */
    const val LINE_GAP = 4
    const val TITLE_SIZE = 13
    /** The title's weight (desktop's 500). */
    const val TITLE_WEIGHT = 500
    const val LINE_SIZE = 12
    /** Where a badge sits on the art, from its corner. */
    const val BADGE_INSET = 8
    /** The artist card's corners (desktop's `rounded-lg`): its photo runs to the edges. */
    const val ARTIST_RADIUS = 8
    /** Around the artist card's name and subtitle (desktop's `p-3`). */
    const val ARTIST_TEXT_PADDING = 12
    /** The artist card's name (desktop's `text-sm`, 500). */
    const val ARTIST_NAME_SIZE = 14
    /**
     * The reason badge (desktop `reason-badge.js`): a [REASON_DISC] disc with
     * Shuffleupagus's logo at [REASON_LOGO] (desktop's 26 / 18, on the 4-point
     * and icon scales), in a [REASON_TOUCH] tap target, at the cover's lower
     * left (the play disc holds the lower right on a phone).
     */
    const val REASON_DISC = 28
    const val REASON_LOGO = 20
    const val REASON_TOUCH = 48
    /** A pill's text (the release type, "Coming", "Δ 12"). */
    const val PILL_SIZE = 11

    /**
     * A card's whole width around art [artSize] wide: the card box sits
     * around the cover, so a row keeps its 140 covers and the card is wider.
     */
    fun cardWidth(artSize: Int): Int = artSize + 2 * PADDING

    /** History's and a friend's rank on the cover (desktop's `AlbumRankBadge`). */
    fun rankLabel(rank: Int): String = "#$rank"
}

/**
 * A pill at the art's top right: the text and its fill (`0xRRGGBB`), white
 * text on it. The release type (desktop's `releaseBadge`, the type's colour
 * at 90%) or a "Coming …" date (the fill accent).
 */
data class CardPill(val text: String, val color: Int, val alpha: Float = 1f) {
    companion object {
        /** "Album", "EP", "Single", …; null for no type. */
        fun releaseType(type: String?): CardPill? {
            val label = com.parachord.shared.home.releaseTypeLabel(type) ?: return null
            return CardPill(label, DesignTokens.releaseTypeColor(type), alpha = 0.9f)
        }

        /** A release that isn't out yet ("Coming Dec 5"); null for none. */
        fun coming(label: String?): CardPill? =
            label?.takeIf { it.isNotBlank() }?.let { CardPill(it, DesignTokens.FILL_ACCENT) }
    }
}
