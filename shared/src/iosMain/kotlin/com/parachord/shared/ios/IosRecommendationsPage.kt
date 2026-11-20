package com.parachord.shared.ios

import com.parachord.shared.api.LbPlaylistTrack
import com.parachord.shared.model.Track
import com.parachord.shared.playback.CatalogQueue
import com.parachord.shared.playback.resolverDisplayName
import com.parachord.shared.recommendations.ListenerItem
import com.parachord.shared.recommendations.ListenerPlay
import com.parachord.shared.recommendations.PageStatus
import com.parachord.shared.recommendations.RecommendationCopy
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationRow
import com.parachord.shared.recommendations.SHUFFLEUPAGUS_ID
import com.parachord.shared.recommendations.SectionView
import com.parachord.shared.recommendations.achordionUserUrl
import com.parachord.shared.recommendations.activeSourceFilter
import com.parachord.shared.recommendations.albumsSlotIndex
import com.parachord.shared.recommendations.itemSourceName
import com.parachord.shared.recommendations.listenerPlay
import com.parachord.shared.recommendations.listenersSkeleton
import com.parachord.shared.recommendations.pageStatus
import com.parachord.shared.recommendations.pendingSkeleton
import com.parachord.shared.recommendations.playlistTapOpensView
import com.parachord.shared.recommendations.recommendedSongTrack
import com.parachord.shared.recommendations.releaseYear
import com.parachord.shared.recommendations.rowView
import com.parachord.shared.recommendations.sectionViews
import com.parachord.shared.recommendations.showsAchordionLink
import com.parachord.shared.recommendations.upcomingLabel
import com.parachord.shared.recommendations.weeklyContext
import com.parachord.shared.recommendations.weeklyTracks
import com.parachord.shared.repository.RecommendationPageState
import com.parachord.shared.repository.unsavedFriendId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.datetime.LocalDate

/*
 * The Recommendations page for SwiftUI: the shared page (RecommendationPageView.kt)
 * flattened into plain DTOs, in the order Android draws it. No rules live here;
 * every decision is the shared one, applied as it is.
 */

/** The page as drawn. [status] is "loading", "notConfigured", "failed", "empty" or "content". */
data class IosRecPage(
    val status: String,
    /** The failure's text ("failed" only). */
    val statusText: String?,
    /** A source is picked, so the empty message says "this source". */
    val filteringOne: Boolean,
    val hero: String,
    /** The source pill in force, null for All (a pick that left the page falls back to All). */
    val selectedSource: String?,
    val sources: List<IosRecSource>,
    /** Only for "content". */
    val blocks: List<IosRecBlock>,
)

/** A filter chip. [colorId] is the service id the chip's colour comes from. */
data class IosRecSource(
    val id: String,
    val name: String,
    val colorId: String,
    /** Shuffleupagus's chip uses the fill accent, not a service colour. */
    val isShuffleupagus: Boolean,
)

/**
 * One block of the page. [kind] is "sectionTitle", "row", "personalSkeleton",
 * "albumsSkeleton", "pendingSkeleton" or "listenersSkeleton".
 */
data class IosRecBlock(
    /** Stable across rebuilds, for SwiftUI identity. */
    val id: String,
    val kind: String,
    /** Rows only. */
    val rowKey: String?,
    /** A section title, a row's header, or a skeleton's header (null for the pending skeleton). */
    val title: String?,
    /** A category row: its header is the smaller sub-header. */
    val subtitle: Boolean,
    /** A row's type ("song", "album", "artist", "playlist", "listener"); "" for other blocks. */
    val type: String,
    /** "See all" / "Show less", null when the row has no toggle. */
    val toggleLabel: String?,
    val expanded: Boolean,
    val items: List<IosRecItem>,
    val listeners: List<IosRecListener>,
    /** An open row whose next page is loading: skeletons after the items. */
    val loadingTail: Boolean,
    /** Skeleton cards for Shuffleupagus's coming picks. */
    val aiTail: Int,
)

/** One card or song. [type] is the wire type ("song", "album", "artist", "playlist"). */
data class IosRecItem(
    val id: String,
    val type: String,
    val name: String,
    val artist: String?,
    val imageUrl: String?,
    val url: String?,
    /** An album's year, else "". */
    val year: String,
    /** "Coming Feb 3, 2027" for an album not out yet. */
    val comingLabel: String?,
    /** The source that sent it, from its row's sources; "" when unknown. */
    val sourceName: String,
    val weeklyId: String?,
    val weeklyContextType: String?,
    val weeklyLabel: String?,
    val weeklyWeekLabel: String?,
    /** A (non-weekly) playlist with a link: a tap opens its view; otherwise a tap plays it. */
    val opensView: Boolean,
    /** A song's track id as it plays (for the now-playing highlight); null for other types. */
    val trackId: String?,
)

/** One card of the Listeners row. */
data class IosRecListener(
    val id: String,
    val name: String,
    val service: String,
    val serviceName: String,
    val avatarUrl: String?,
    /** The similarity as "88%", null when the service sent none. */
    val badge: String?,
    /** At most 3 names, comma-separated. */
    val topArtists: String,
    val showsAchordion: Boolean,
    /** The listener's Achordion page when [showsAchordion]. */
    val achordionUrl: String?,
    /** The profile to open (an unsaved friend id; `profileFriend` finds a saved one). */
    val friendId: String,
)

/** What a play tap produced: a queue to play, a message to show, or both. */
data class IosRecQueueResult(val queue: CatalogQueue?, val message: String?)

/** The page for [state] under the raw [filter] selection and the open [expanded] rows. */
fun iosRecommendationsPage(
    state: RecommendationPageState,
    filter: String?,
    expanded: Set<String>,
    today: LocalDate,
): IosRecPage {
    val active = activeSourceFilter(filter, state.sources)
    val status = pageStatus(state, active)
    return IosRecPage(
        status = when (status) {
            PageStatus.Loading -> "loading"
            PageStatus.NotConfigured -> "notConfigured"
            is PageStatus.Failed -> "failed"
            is PageStatus.Empty -> "empty"
            PageStatus.Content -> "content"
        },
        statusText = (status as? PageStatus.Failed)?.text,
        filteringOne = active != null,
        hero = state.hero,
        selectedSource = active,
        sources = state.sources.map {
            IosRecSource(it.id, it.name, colorId = it.id, isShuffleupagus = it.id == SHUFFLEUPAGUS_ID)
        },
        blocks = if (status == PageStatus.Content) pageBlocks(state, active, expanded, today) else emptyList(),
    )
}

/** Android's `PageContent` order: content sections (the Albums slot among them), pending, then Listeners. */
private fun pageBlocks(state: RecommendationPageState, filter: String?, expanded: Set<String>, today: LocalDate): List<IosRecBlock> {
    val out = mutableListOf<IosRecBlock>()
    val views = sectionViews(state, filter)
    val content = views.filter { it.section.type != null }
    val listenersView = views.firstOrNull { it.section.type == null }
    val slot = albumsSlotIndex(state, filter, views)
    content.forEachIndexed { i, view ->
        if (i == slot) out += skeleton("skeleton:albums-slot", "albumsSkeleton", RecommendationCopy.ALBUMS)
        out += sectionBlocks(view, state, filter, expanded, today)
    }
    if (slot == content.size) out += skeleton("skeleton:albums-slot", "albumsSkeleton", RecommendationCopy.ALBUMS)
    if (pendingSkeleton(state, filter)) out += skeleton("skeleton:pending", "pendingSkeleton", null)
    if (listenersView != null) {
        out += sectionBlocks(listenersView, state, filter, expanded, today)
    } else if (listenersSkeleton(state, filter)) {
        out += skeleton("skeleton:listeners", "listenersSkeleton", RecommendationCopy.LISTENERS)
    }
    return out
}

/** A section: its title when its rows are categories, then each row, with Shuffleupagus's pending row where it goes. */
private fun sectionBlocks(
    view: SectionView,
    state: RecommendationPageState,
    filter: String?,
    expanded: Set<String>,
    today: LocalDate,
): List<IosRecBlock> {
    val out = mutableListOf<IosRecBlock>()
    if (view.rows.any { it.category != null }) {
        out += IosRecBlock(
            id = "section:${view.section.type?.wire ?: "listeners"}", kind = "sectionTitle", rowKey = null,
            title = view.section.title, subtitle = false, type = "", toggleLabel = null, expanded = false,
            items = emptyList(), listeners = emptyList(), loadingTail = false, aiTail = 0,
        )
    }
    val personal = skeleton("skeleton:personal", "personalSkeleton", RecommendationCopy.BASED_ON_LISTENING)
    view.rows.forEachIndexed { index, row ->
        if (index == view.personalPendingAt) out += personal
        out += rowBlock(row, view, state, filter, row.key in expanded, today)
    }
    if (view.personalPendingAt == view.rows.size) out += personal
    return out
}

private fun rowBlock(
    row: RecommendationRow,
    view: SectionView,
    state: RecommendationPageState,
    filter: String?,
    expanded: Boolean,
    today: LocalDate,
): IosRecBlock {
    val rv = rowView(row, state, filter, expanded)
    return IosRecBlock(
        id = "row:${row.key}",
        kind = "row",
        rowKey = row.key,
        title = row.title ?: view.section.title,
        subtitle = row.category != null,
        type = row.type?.wire ?: "listener",
        toggleLabel = if (rv.showToggle) rv.toggleLabel else null,
        expanded = expanded,
        items = rv.items.map { iosRecItem(it, row, today) },
        listeners = rv.listeners.map { iosRecListener(it) },
        loadingTail = rv.loadingTail,
        aiTail = rv.aiTail,
    )
}

private fun skeleton(id: String, kind: String, title: String?) = IosRecBlock(
    id = id, kind = kind, rowKey = null, title = title, subtitle = kind == "personalSkeleton", type = "",
    toggleLabel = null, expanded = false, items = emptyList(), listeners = emptyList(), loadingTail = false, aiTail = 0,
)

private fun iosRecItem(item: RecommendationItem, row: RecommendationRow, today: LocalDate) = IosRecItem(
    id = item.id,
    type = item.type.wire,
    name = item.name,
    artist = item.artist,
    imageUrl = item.imageUrl,
    url = item.url,
    year = releaseYear(item.releaseDate),
    comingLabel = upcomingLabel(item.releaseDate, today),
    sourceName = itemSourceName(item, row),
    weeklyId = item.weekly?.id,
    weeklyContextType = item.weekly?.contextType,
    weeklyLabel = item.weekly?.label,
    weeklyWeekLabel = item.weekly?.weekLabel,
    opensView = playlistTapOpensView(item),
    trackId = recommendedSongTrack(item)?.id,
)

private fun iosRecListener(item: ListenerItem): IosRecListener {
    val achordion = showsAchordionLink(item.service)
    return IosRecListener(
        id = item.id,
        name = item.name,
        service = item.service,
        serviceName = resolverDisplayName(item.service),
        avatarUrl = item.avatarUrl,
        badge = RecommendationCopy.similarityBadge(item.similarity),
        topArtists = item.topArtists.joinToString(", "),
        showsAchordion = achordion,
        achordionUrl = if (achordion) achordionUserUrl(item.name) else null,
        friendId = unsavedFriendId(item.service, item.name),
    )
}

// ── Play taps (the container calls these; none throws) ───────────────

/** A listener's top tracks as a queue, or the shared message ([listenerPlay]). */
suspend fun listenerQueueResult(
    service: String,
    username: String,
    name: String,
    topTracks: suspend (String, String) -> List<Track>,
): IosRecQueueResult = try {
    when (val play = listenerPlay(service, username, name, topTracks)) {
        is ListenerPlay.Play -> IosRecQueueResult(play.queue, null)
        is ListenerPlay.Message -> IosRecQueueResult(null, play.text)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    IosRecQueueResult(null, RecommendationCopy.listenerTracksFailed(name))
}

/** A weekly playlist's tracks as a queue with "Playing …", or "Couldn't load …" (Android's `playWeekly`). */
suspend fun weeklyQueueResult(
    item: RecommendationItem,
    loadTracks: suspend (playlistId: String) -> List<LbPlaylistTrack>,
): IosRecQueueResult {
    val weekly = item.weekly ?: return IosRecQueueResult(null, RecommendationCopy.couldntLoad(item.name))
    val tracks = try {
        weeklyTracks(weekly, loadTracks(weekly.id))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
    if (tracks.isEmpty()) return IosRecQueueResult(null, RecommendationCopy.couldntLoad(item.name))
    return IosRecQueueResult(CatalogQueue(tracks, weeklyContext(weekly)), RecommendationCopy.playing(item.name))
}
