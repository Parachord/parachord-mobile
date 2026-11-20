package com.parachord.shared.home

import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.SHUFFLEUPAGUS_ID
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.ServiceColors

// What the three card rows draw (desktop `home-sections.js`: Made from your
// listening, Your playlists freshest first, Albums you might love), for both
// apps. The platforms only draw.

/** A source chip's colour (`0xRRGGBB`): the plug-in's manifest colour, Shuffleupagus the fill accent. */
fun homeSourceChipColor(id: String): Int =
    if (id == SHUFFLEUPAGUS_ID) DesignTokens.FILL_ACCENT else ServiceColors.color(id)

/** A stable key for a "Made from your listening" card. */
fun mixCardKey(card: HomeMixCard): String = when (card) {
    is HomeMixCard.Loves -> "loves"
    is HomeMixCard.Mix -> "mix:${card.mix.resolverId.orEmpty()}:${card.mix.id}"
}

/**
 * A mix card's art, by the same rules as its Jump back in tile ([jumpTileArt]):
 * Recent Loves is a 2x2 of the four newest loved albums when all four have
 * covers, else the newest love's cover; a mix shows its image, else its mosaic.
 */
fun mixCardArt(card: HomeMixCard): JumpTileArt = when (card) {
    is HomeMixCard.Loves -> jumpTileArt(JumpTile.Loves(card.tracks), card.tracks)
    is HomeMixCard.Mix -> jumpTileArt(JumpTile.Mix(card.mix), emptyList())
}

/** A mix card's line under the title: the mix's description, else blank (desktop `description || subtitle`; a mix has no subtitle). */
fun mixCardSubtitle(card: HomeMixCard): String = when (card) {
    is HomeMixCard.Loves -> card.subtitle
    is HomeMixCard.Mix -> card.mix.description?.trim().orEmpty()
}

/** Whether a row shows its source chips: with any source, since "All" is a chip too (desktop `chips.length > 1`). */
fun showSourceChips(sources: List<FilterChipSource>): Boolean = sources.isNotEmpty()

/**
 * The reason badge on an album card (desktop `shuffleupagusBadgeProps`): an
 * item Shuffleupagus sent (its own, or a merged item whose sources include it)
 * says why ([RecommendationItem.reason], else "Picked by Shuffleupagus");
 * other sources get none. Critical Darlings' badge is on its own half.
 */
fun albumReasonBadge(item: RecommendationItem): String? {
    val fromShuffleupagus = item.resolverId == SHUFFLEUPAGUS_ID || SHUFFLEUPAGUS_ID in item.sourceIds
    if (!fromShuffleupagus) return null
    return item.reason?.trim()?.takeIf { it.isNotEmpty() } ?: HomeCopy.PICKED_BY_SHUFFLEUPAGUS
}
