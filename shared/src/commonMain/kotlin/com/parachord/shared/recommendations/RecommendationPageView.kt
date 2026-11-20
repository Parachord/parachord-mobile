package com.parachord.shared.recommendations

import com.parachord.shared.repository.RecommendationPageState
import com.parachord.shared.repository.RecommendationsError

/*
 * What the Recommendations page shows for a [RecommendationPageState]: a port
 * of desktop's `renderSubsection`, `renderTypeSection` and the page's state
 * chain (`recommendations-page.js`). Both apps draw these views and keep no
 * rules of their own. Desktop previews two rows of cards (as many as fit
 * across); the phones preview [CARD_PREVIEW].
 */

const val SONG_PREVIEW = 5
const val CARD_PREVIEW = 12
const val LISTENER_PREVIEW = 12

/** Shuffleupagus's trailing skeleton cards on the rows its picks will land in. */
const val AI_TAIL = 3
val AI_TAIL_ROWS: Set<String> = setOf("type:album", "type:artist", "albums:personal")

/** The source pill in force: [selected] while it's still on the page, else null (All). */
fun activeSourceFilter(selected: String?, sources: List<PageSource>): String? =
    selected?.takeIf { id -> sources.any { it.id == id } }

/** One row as drawn. */
data class RowView(
    /** The items to draw: the first preview of the filtered list, or all of it when [expanded]. */
    val items: List<RecommendationItem>,
    /** The Listeners row's people, previewed the same way. */
    val listeners: List<ListenerItem>,
    val showToggle: Boolean,
    val toggleLabel: String,
    /** An open row whose next page is in flight: skeletons where the cards will land. */
    val loadingTail: Boolean,
    /** Skeleton cards for Shuffleupagus's coming picks. */
    val aiTail: Int,
)

fun rowView(row: RecommendationRow, state: RecommendationPageState, filter: String?, expanded: Boolean): RowView {
    val filtered = filterRowBySource(row, filter)
    val isListeners = row.type == null
    val preview = when {
        isListeners -> LISTENER_PREVIEW
        row.type == RecommendationType.SONG -> SONG_PREVIEW
        else -> CARD_PREVIEW
    }
    val total = if (isListeners) filtered.listeners.size else filtered.items.size
    val showToggle = expanded || total > preview || row.canLoadMore
    val loadingTail = expanded && row.pageable.any { p ->
        if (p.pagingKey !in state.loadingMore) return@any false
        val expansion = state.paging[p.pagingKey] ?: return@any true
        if (row.category != null) {
            "${row.type?.wire}:${row.category}" in expansion.lastPageCategories
        } else {
            row.type != null && row.type in expansion.lastPageTypes
        }
    }
    val aiTail = if (state.aiLoading && row.key in AI_TAIL_ROWS && (filter == null || filter == SHUFFLEUPAGUS_ID)) AI_TAIL else 0
    return RowView(
        items = if (expanded) filtered.items else filtered.items.take(preview),
        listeners = if (expanded) filtered.listeners else filtered.listeners.take(preview),
        showToggle = showToggle,
        toggleLabel = if (expanded) RecommendationCopy.SHOW_LESS else RecommendationCopy.SEE_ALL,
        loadingTail = loadingTail,
        aiTail = aiTail,
    )
}

/** One section as drawn: its visible rows, and where Shuffleupagus's "Based on your listening" skeleton row goes. */
data class SectionView(
    val section: RecommendationPageSection,
    /** The rows with something to show under the filter (or a page for them in flight). */
    val rows: List<RecommendationRow>,
    /** The index in [rows] for the skeleton row, null for none. */
    val personalPendingAt: Int?,
)

/** The sections to draw: content first, Listeners last; a section with no visible rows is dropped. */
fun sectionViews(state: RecommendationPageState, filter: String?): List<SectionView> {
    val views = state.sections.mapNotNull { section ->
        val rows = section.rows.filter { row ->
            val filtered = filterRowBySource(row, filter)
            filtered.items.isNotEmpty() || filtered.listeners.isNotEmpty() || row.pageable.any { p ->
                p.pagingKey in state.loadingMore && (filter == null || p.resolverId == filter)
            }
        }
        // Shuffleupagus thinking, Albums in category rows and no Based on your
        // listening row yet: its skeleton row goes after New for you, else first.
        val personalPending = section.type == RecommendationType.ALBUM && state.aiLoading &&
            (filter == null || filter == SHUFFLEUPAGUS_ID) &&
            rows.any { it.category != null } && rows.none { it.key == "albums:personal" }
        val pendingAt = if (personalPending) rows.indexOfFirst { it.key == "albums:new" } + 1 else null
        if (rows.isEmpty()) null else SectionView(section, rows, pendingAt)
    }
    return views.filter { it.section.type != null } + views.filter { it.section.type == null }
}

/**
 * Desktop's `llmPlaceholder`: Shuffleupagus is picking, the page has no album
 * section and no row for its picks to tail, and no source is picked. One
 * skeleton section then stands in the Albums slot.
 */
fun albumsSkeletonSlot(state: RecommendationPageState, filter: String?): Boolean =
    state.aiLoading && filter == null &&
        state.sections.none { it.type == RecommendationType.ALBUM } &&
        state.sections.none { s -> s.rows.any { it.key in AI_TAIL_ROWS } }

/**
 * Where the [albumsSkeletonSlot] section goes in [views] (as [sectionViews]
 * returned them): before the first content section that isn't Songs, else
 * after the last content section (desktop's `llmSlot`). Null when there's no
 * slot. Insert the skeleton before `views[index]`; an index past the content
 * is the spot right before Listeners.
 */
fun albumsSlotIndex(state: RecommendationPageState, filter: String?, views: List<SectionView>): Int? {
    if (!albumsSkeletonSlot(state, filter)) return null
    val content = views.count { it.section.type != null }
    val firstNonSongs = views.indexOfFirst { it.section.type != null && it.section.type != RecommendationType.SONG }
    return if (firstNonSongs < 0) content else firstNonSongs
}

/**
 * Desktop's unlabeled pending skeleton: providers still answering with
 * nothing on screen, under All only. It goes after the content and before
 * Listeners.
 */
fun pendingSkeleton(state: RecommendationPageState, filter: String?): Boolean =
    filter == null && state.pendingProviders.isNotEmpty()

/**
 * Desktop's `friendsPending` placeholder: the similar-users lookup is out, no
 * Listeners section is on the page, and no source is picked.
 */
fun listenersSkeleton(state: RecommendationPageState, filter: String?): Boolean =
    filter == null && state.listenersPending && state.sections.none { it.type == null }

/** What the page is in. */
sealed class PageStatus {
    data object Loading : PageStatus()
    data object NotConfigured : PageStatus()
    data class Failed(val text: String) : PageStatus()
    /** [filteringOne]: a source is picked, so the message says "this source". */
    data class Empty(val filteringOne: Boolean) : PageStatus()
    data object Content : PageStatus()
}

/** Desktop's state chain: loading, not configured, failed, empty, else the sections. */
fun pageStatus(state: RecommendationPageState, filter: String?): PageStatus = when {
    state.loading && state.sections.isEmpty() -> PageStatus.Loading
    state.error is RecommendationsError.NotConfigured -> PageStatus.NotConfigured
    state.error is RecommendationsError.Message -> PageStatus.Failed(state.error.text)
    // Desktop's contentBlocks also hold the Albums skeleton, and its Listeners
    // placeholder shows only with no source picked (`friendsPending`).
    state.sections.isEmpty() && state.pendingProviders.isEmpty() &&
        !listenersSkeleton(state, filter) && !albumsSkeletonSlot(state, filter) -> PageStatus.Empty(filter != null)
    else -> PageStatus.Content
}

/** An album's year for its card's third line: the date's first four characters, else "". */
fun releaseYear(date: String?): String = date?.take(4) ?: ""

/** The name of the source that sent [item], from [row]'s sources; "" when unknown. */
fun itemSourceName(item: RecommendationItem, row: RecommendationRow): String =
    row.sources.firstOrNull { it.id == item.resolverId }?.name ?: ""
