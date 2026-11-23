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
import com.parachord.shared.recommendations.RecommendationPageBlock
import com.parachord.shared.recommendations.RecommendationRow
import com.parachord.shared.recommendations.SHUFFLEUPAGUS_ID
import com.parachord.shared.recommendations.achordionUserUrl
import com.parachord.shared.recommendations.activeSourceFilter
import com.parachord.shared.recommendations.itemSourceName
import com.parachord.shared.recommendations.key
import com.parachord.shared.recommendations.listenerPlay
import com.parachord.shared.recommendations.pageStatus
import com.parachord.shared.recommendations.playlistTapOpensView
import com.parachord.shared.recommendations.recommendationPageLayout
import com.parachord.shared.recommendations.recommendedSongTrack
import com.parachord.shared.recommendations.releaseYear
import com.parachord.shared.recommendations.showsAchordionLink
import com.parachord.shared.recommendations.upcomingLabel
import com.parachord.shared.recommendations.weeklyContext
import com.parachord.shared.recommendations.weeklyTracks
import com.parachord.shared.repository.RecommendationPageState
import com.parachord.shared.repository.unsavedFriendId
import com.parachord.shared.platform.Log
import com.parachord.shared.recommendations.filterRowBySource
import com.parachord.shared.recommendations.songQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
    /** Why Shuffleupagus picked it (the shared `albumReasonBadge`), for the reason badge; null otherwise. */
    val reasonBadge: String? = null,
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

/** A recommended playlist's save: its saved playlist id, or null when the import failed. */
data class IosSaveResult(val playlistId: String?)

/**
 * What a play tap produced: a queue to play, a message to show, or both.
 * [cover] is a service playlist lookup's own cover (null for other plays).
 */
data class IosRecQueueResult(val queue: CatalogQueue?, val message: String?, val cover: String? = null)

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

/** The shared [recommendationPageLayout], block for block, as DTOs. */
private fun pageBlocks(state: RecommendationPageState, filter: String?, expanded: Set<String>, today: LocalDate): List<IosRecBlock> =
    recommendationPageLayout(state, filter, expanded).map { block ->
        when (block) {
            is RecommendationPageBlock.SectionTitle -> IosRecBlock(
                id = block.key, kind = "sectionTitle", rowKey = null,
                title = block.section.title, subtitle = false, type = "", toggleLabel = null, expanded = false,
                items = emptyList(), listeners = emptyList(), loadingTail = false, aiTail = 0,
            )
            is RecommendationPageBlock.Row -> rowBlock(block, today)
            RecommendationPageBlock.PersonalSkeleton ->
                skeleton(block.key, "personalSkeleton", RecommendationCopy.BASED_ON_LISTENING)
            RecommendationPageBlock.AlbumsSkeleton ->
                skeleton(block.key, "albumsSkeleton", RecommendationCopy.ALBUMS)
            RecommendationPageBlock.PendingSkeleton -> skeleton(block.key, "pendingSkeleton", null)
            RecommendationPageBlock.ListenersSkeleton ->
                skeleton(block.key, "listenersSkeleton", RecommendationCopy.LISTENERS)
        }
    }

private fun rowBlock(block: RecommendationPageBlock.Row, today: LocalDate): IosRecBlock {
    val row = block.row
    val rv = block.view
    return IosRecBlock(
        id = block.key,
        kind = "row",
        rowKey = row.key,
        title = block.title,
        subtitle = block.subtitle,
        type = row.type?.wire ?: "listener",
        toggleLabel = if (rv.showToggle) rv.toggleLabel else null,
        expanded = block.expanded,
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

/** A card with no row (Home's albums): its source is the item's own ([itemSourceName]). */
internal fun iosRecItem(item: RecommendationItem, today: LocalDate): IosRecItem = iosRecItem(item, null, today)

internal fun iosRecItem(item: RecommendationItem, row: RecommendationRow?, today: LocalDate) = IosRecItem(
    id = item.id,
    type = item.type.wire,
    name = item.name,
    artist = item.artist,
    imageUrl = item.imageUrl,
    url = item.url,
    year = releaseYear(item.releaseDate),
    comingLabel = upcomingLabel(item.releaseDate, today),
    sourceName = if (row != null) itemSourceName(item, row) else itemSourceName(item),
    weeklyId = item.weekly?.id,
    weeklyContextType = item.weekly?.contextType,
    weeklyLabel = item.weekly?.label,
    weeklyWeekLabel = item.weekly?.weekLabel,
    opensView = playlistTapOpensView(item),
    trackId = recommendedSongTrack(item)?.id,
    reasonBadge = com.parachord.shared.home.albumReasonBadge(item),
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
@Throws(Throwable::class)
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

/**
 * A weekly playlist's tracks as a queue with "Playing [name]", or "Couldn't load [name]"
 * (Android's `playWeekly`). [item] is the card found on the page, null when it's gone;
 * [name] is the card's title as Swift drew it.
 */
@Throws(Throwable::class)
suspend fun weeklyQueueResult(
    item: RecommendationItem?,
    name: String,
    loadTracks: suspend (playlistId: String) -> List<LbPlaylistTrack>,
): IosRecQueueResult {
    val weekly = item?.weekly ?: return IosRecQueueResult(null, RecommendationCopy.couldntLoad(name))
    val tracks = try {
        weeklyTracks(weekly, loadTracks(weekly.id))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
    if (tracks.isEmpty()) return IosRecQueueResult(null, RecommendationCopy.couldntLoad(name))
    return IosRecQueueResult(CatalogQueue(tracks, weeklyContext(weekly)), RecommendationCopy.playing(name))
}

/**
 * One Recommendations screen's session, as Android's `RecommendationsViewModel`
 * keeps it: the picked source and the open rows. Each screen makes its own
 * (`IosContainer.makeRecommendationsSession`), so a second Recommendations
 * screen (opened from Now Playing's "Playing from") starts at All with every
 * row collapsed and leaves the first one's as it was. The page data is the
 * repository's, shared by every session.
 */
class IosRecommendationsSession internal constructor(
    private val page: StateFlow<RecommendationPageState>,
    private val scope: CoroutineScope,
    private val openRow: suspend (rowKey: String) -> Unit,
    private val today: () -> LocalDate,
) {
    private val filter = MutableStateFlow<String?>(null)
    private val expanded = MutableStateFlow<Set<String>>(emptySet())

    /** The page as Swift draws it ([iosRecommendationsPage]), rebuilt on every page, filter or row change. */
    fun watch(): Flow<IosRecPage> =
        combine(page, filter, expanded) { state, f, open -> iosRecommendationsPage(state, f, open, today()) }

    /** The source chip picked; null is All. */
    fun setFilter(id: String?) {
        filter.value = id
    }

    /** "See all" / "Show less"; opening a row pages it (the repository's `openRow`). */
    fun toggleRow(rowKey: String) {
        val open = rowKey in expanded.value
        expanded.value = if (open) expanded.value - rowKey else expanded.value + rowKey
        if (!open) scope.launch {
            try {
                openRow(rowKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("IosRecommendationsSession", "openRow($rowKey) failed: ${e.message}")
            }
        }
    }

    /** The row's songs under this session's filter, as drawn. */
    private fun filteredSongs(rowKey: String): Pair<RecommendationRow, List<RecommendationItem>>? {
        val state = page.value
        val row = state.sections.flatMap { it.rows }.firstOrNull { it.key == rowKey } ?: return null
        return row to filterRowBySource(row, activeSourceFilter(filter.value, state.sources)).items
    }

    /** The tapped song and the rest of its row ([songQueue]); null when it can't play. Build it on the tap only. */
    fun songQueue(rowKey: String, index: Int): CatalogQueue? {
        val (row, items) = filteredSongs(rowKey) ?: return null
        return songQueue(items, index, row)
    }

    /** The tapped song alone, for its track menu ([recommendedSongTrack]); null when it can't play. */
    fun songTrack(rowKey: String, index: Int): Track? =
        filteredSongs(rowKey)?.second?.getOrNull(index)?.let { recommendedSongTrack(it) }
}
