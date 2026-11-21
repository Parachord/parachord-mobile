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

/**
 * A source chip on a row. [colorRgb] is `0xRRGGBB`; null for "All", a neutral
 * chip that fills with the page's colour when picked.
 */
data class HomeSourceChip(val id: String, val label: String, val colorRgb: Int?)

/** A row's chips: "All" first, then one per source in its colour. Both apps draw these. */
fun homeSourceChips(sources: List<FilterChipSource>): List<HomeSourceChip> =
    listOf(HomeSourceChip(HOME_ALL_SOURCES, HomeCopy.ALL, null)) +
        sources.map { HomeSourceChip(it.id, it.label, homeSourceChipColor(it.id)) }

/** An album card's key: artist and title (unique in the row; `homeRecommendationAlbums` dedups by it, while an id may be blank). */
fun homeAlbumCardKey(item: RecommendationItem): String = "${item.artist.orEmpty()}\u0000${item.name}"

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

/**
 * Which of Home's sections draw, worked out once for both apps from
 * [HomeState.sections] and what each section has to show (Android's sections
 * and iOS's bridge both read it).
 */
data class HomeSectionVisibility(
    /** The announcement banner. */
    val showBanner: Boolean,
    /** Jump back in: its tiles, or the import card in their place. */
    val showJump: Boolean,
    /** The "Build your collection" card, in Jump back in's place. */
    val showImport: Boolean,
    val showMixes: Boolean,
    val showPlaylists: Boolean,
    /** With albums, or while they're loading ([albumsLoading]). */
    val showAlbums: Boolean,
    /** No albums yet and a load is coming: the row shows skeleton cards. */
    val albumsLoading: Boolean,
    val showConcerts: Boolean,
    val showAsk: Boolean,
)

fun homeSectionVisibility(state: HomeState): HomeSectionVisibility {
    val sections = state.sections
    val hasTiles = state.tiles.take(HOME_JUMP_TILES).isNotEmpty()
    val albumsLoading = state.albums.isEmpty() && state.albumsLoading
    return HomeSectionVisibility(
        showBanner = state.banner != null,
        showJump = hasTiles || state.showImportCard,
        showImport = !hasTiles && state.showImportCard,
        showMixes = sections.mixes && state.mixRow.isNotEmpty(),
        showPlaylists = sections.playlists && state.playlists.isNotEmpty(),
        showAlbums = albumsLoading || (sections.albums && state.albums.isNotEmpty()),
        albumsLoading = albumsLoading,
        showConcerts = sections.concerts && state.concerts.isNotEmpty(),
        showAsk = sections.ask,
    )
}
