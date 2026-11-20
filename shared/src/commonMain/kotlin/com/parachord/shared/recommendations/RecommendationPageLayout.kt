package com.parachord.shared.recommendations

import com.parachord.shared.repository.RecommendationPageState

/*
 * The Recommendations page's blocks in drawing order, built only from the
 * rules in RecommendationPageView.kt. Both apps draw this list: Android's
 * RecommendationsScreen as lazy-list items, iOS through IosRecommendationsPage.
 *
 * The order: the content sections (Tracks, Albums, Artists, Playlists) with
 * the Albums skeleton at [albumsSlotIndex]; within a section, its title when
 * its rows are categories, then each row with Shuffleupagus's "Based on your
 * listening" skeleton at [SectionView.personalPendingAt]; then the unlabeled
 * pending skeleton; then Listeners, or its skeleton.
 */

/** One block of the page. */
sealed class RecommendationPageBlock {
    /** A section's title, shown above its category rows. */
    data class SectionTitle(val section: RecommendationPageSection) : RecommendationPageBlock()

    /** One row with what it draws. */
    data class Row(
        val row: RecommendationRow,
        val section: RecommendationPageSection,
        val view: RowView,
        val expanded: Boolean,
    ) : RecommendationPageBlock() {
        /** The row's header: its own title, else its section's. */
        val title: String get() = row.title ?: section.title
        /** A category row's header is the smaller sub-header. */
        val subtitle: Boolean get() = row.category != null
    }

    /** Shuffleupagus's placeholder row in a categorized Albums section. */
    data object PersonalSkeleton : RecommendationPageBlock()

    /** Desktop's `llmPlaceholder`: a skeleton section standing in the Albums slot. */
    data object AlbumsSkeleton : RecommendationPageBlock()

    /** Providers still answering with nothing on screen. */
    data object PendingSkeleton : RecommendationPageBlock()

    /** The similar-users lookup is out and no Listeners section is on the page. */
    data object ListenersSkeleton : RecommendationPageBlock()
}

/**
 * A block's identity, stable across rebuilds: both apps key their lists by it
 * (Android's LazyColumn, iOS's `IosRecBlock.id`), and a change in the page's
 * keys is what tells a screen pinned at its top to stay there.
 */
val RecommendationPageBlock.key: String
    get() = when (this) {
        is RecommendationPageBlock.SectionTitle -> "section:${section.type?.wire ?: "listeners"}"
        is RecommendationPageBlock.Row -> "row:${row.key}"
        RecommendationPageBlock.PersonalSkeleton -> "skeleton:personal"
        RecommendationPageBlock.AlbumsSkeleton -> "skeleton:albums-slot"
        RecommendationPageBlock.PendingSkeleton -> "skeleton:pending"
        RecommendationPageBlock.ListenersSkeleton -> "skeleton:listeners"
    }

/**
 * The page's blocks for [state] under [filter] (the source in force, already
 * through [activeSourceFilter]), with the rows in [expanded] open.
 */
fun recommendationPageLayout(
    state: RecommendationPageState,
    filter: String?,
    expanded: Set<String>,
): List<RecommendationPageBlock> {
    val out = mutableListOf<RecommendationPageBlock>()
    val views = sectionViews(state, filter)
    val content = views.filter { it.section.type != null }
    val listeners = views.firstOrNull { it.section.type == null }
    val slot = albumsSlotIndex(state, filter, views)
    content.forEachIndexed { i, view ->
        if (i == slot) out += RecommendationPageBlock.AlbumsSkeleton
        out += sectionBlocks(view, state, filter, expanded)
    }
    if (slot == content.size) out += RecommendationPageBlock.AlbumsSkeleton
    if (pendingSkeleton(state, filter)) out += RecommendationPageBlock.PendingSkeleton
    if (listeners != null) {
        out += sectionBlocks(listeners, state, filter, expanded)
    } else if (listenersSkeleton(state, filter)) {
        out += RecommendationPageBlock.ListenersSkeleton
    }
    return out
}

private fun sectionBlocks(
    view: SectionView,
    state: RecommendationPageState,
    filter: String?,
    expanded: Set<String>,
): List<RecommendationPageBlock> {
    val out = mutableListOf<RecommendationPageBlock>()
    if (view.rows.any { it.category != null }) out += RecommendationPageBlock.SectionTitle(view.section)
    view.rows.forEachIndexed { index, row ->
        if (index == view.personalPendingAt) out += RecommendationPageBlock.PersonalSkeleton
        val open = row.key in expanded
        out += RecommendationPageBlock.Row(row, view.section, rowView(row, state, filter, open), open)
    }
    if (view.personalPendingAt == view.rows.size) out += RecommendationPageBlock.PersonalSkeleton
    return out
}
