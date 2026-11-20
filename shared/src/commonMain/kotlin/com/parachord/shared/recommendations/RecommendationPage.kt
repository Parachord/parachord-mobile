package com.parachord.shared.recommendations

import com.parachord.shared.ai.AiAlbumSuggestion
import com.parachord.shared.ai.AiArtistSuggestion
import kotlinx.serialization.Serializable

/*
 * The Recommendations page: a port of desktop's page builder in
 * `src/renderer/lib/recommendation-model.js` (`buildRecommendationPage`,
 * `categoryRows`, `friendsRow`, `pageSources`, `filterRowBySource`,
 * `shuffleupagusSection`, `homeRecommendationAlbums`) and two helpers of
 * desktop's `home-model.js` (`filterBySource`, `albumsNeedingArt`).
 *
 * Five sections by type (Tracks, Albums, Artists, Playlists, Listeners), each
 * one row, except Albums and Playlists, which can be several category rows:
 *
 *  - a type's row (key `type:<type>`) blends every provider's items of that
 *    type, untitled and titled sections alike, interleaved round-robin by
 *    provider with Shuffleupagus last and deduplicated per type ([mergeOwned]);
 *    each merged item records every provider that sent it in `sourceIds`, so
 *    the page can filter by source. A provider's section titles are not shown;
 *  - weekly playlists (items carrying `weekly`) lead Playlists, Exploration
 *    before Jams, newest week of each only;
 *  - Albums and Playlists split into one row per category present (keys
 *    `albums:<category>` and `playlists:<category>`, in the table order of
 *    [RecommendationCategories]); with one category each stays its single
 *    `type:<type>` row;
 *  - Listeners is one blended row built from the providers' similar users.
 *
 * Filtering by source is not done here: [filterRowBySource] runs at render,
 * so picking a source never re-merges or refetches. `RecommendationModelTest`
 * holds desktop's vectors; change a rule on desktop first.
 */

/** One provider's normalized answer, in the order the page shows providers. */
data class ProviderRecommendations(
    val resolverId: String,
    val resolverName: String,
    val sections: List<RecommendationSection>,
    /** The plugin implements `expandRecommendationSection`: its titled sections with an id page on. */
    val canExpand: Boolean,
)

/**
 * The items a "load more" already fetched for one provider section, keyed by
 * [pagingKey], so a re-merge keeps them and the cursor.
 */
@Serializable
data class ExpandedSection(
    val items: List<RecommendationItem>,
    val nextCursor: String? = null,
    val exhausted: Boolean = false,
    /** Pages fetched so far; at `EXPANSION_PAGE_CAP` the section is exhausted. */
    val pages: Int = 0,
    /** The item types the last page brought: a row whose type it didn't bring stops its loading tail. */
    val lastPageTypes: List<RecommendationType> = emptyList(),
    /** The album / playlist categories the last page brought, typed (`album:new`, `playlist:personal`). */
    val lastPageCategories: List<String> = emptyList(),
)

/** Desktop's `EXPANSION_PAGE_CAP`: at most this many "load more" pages per provider section. */
const val EXPANSION_PAGE_CAP = 10

/** The key of a provider section's pages: `<resolverId>|<section id, else title>`. */
fun pagingKey(resolverId: String, section: RecommendationSection): String =
    "$resolverId|${section.id ?: section.title}"

/** A provider section that can still page items into a row. */
data class PageableSection(
    val resolverId: String,
    val resolverName: String,
    val sectionId: String,
    val title: String?,
    /** The section's own category, when valid for some type; else null. */
    val category: String?,
    val pagingKey: String,
    val nextCursor: String?,
)

/** A source on the page: a provider, Shuffleupagus or a similar-users service. [count] is how many items it sent. */
data class PageSource(val id: String, val name: String, val count: Int = 0)

/** A person a service finds listens like you. A null [similarity] sorts last. */
@Serializable
data class SimilarUser(
    val username: String,
    val similarity: Double? = null,
    val avatarUrl: String? = null,
    val topArtists: List<String> = emptyList(),
)

/**
 * Who the Listeners row leaves out. Keys are `<service>:<username lowercased>`.
 * Only [friendServices] (the plug-ins with `capabilities.friends`) make the
 * row: Follow looks the person up there.
 */
data class FriendExclusions(
    val friendServices: Set<String> = emptySet(),
    val friendKeys: Set<String> = emptySet(),
    val selfKeys: Set<String> = emptySet(),
)

/** One card of the Listeners row. */
data class ListenerItem(
    /** `similar:<service>:<username>`. */
    val id: String,
    val name: String,
    val service: String,
    val similarity: Double?,
    val avatarUrl: String?,
    /** At most 3. */
    val topArtists: List<String>,
    val resolverId: String,
    val sourceIds: List<String>,
)

/** One row of the page. */
data class RecommendationRow(
    /** `type:<type>`, `albums:<category>`, `playlists:<category>` or `friends:similar`. */
    val key: String,
    /** Null on the Listeners row. */
    val type: RecommendationType?,
    /** An album or playlist category row's category; null on every other row. */
    val category: String? = null,
    val title: String? = null,
    /** More than one source sent this row's items. */
    val blended: Boolean = false,
    /** The row's sources in page order; [PageSource.count] is this row's items from each. */
    val sources: List<PageSource> = emptyList(),
    val items: List<RecommendationItem> = emptyList(),
    /** The Listeners row's people (its [items] are empty). */
    val listeners: List<ListenerItem> = emptyList(),
    /** The provider sections opening this row pages. */
    val pageable: List<PageableSection> = emptyList(),
    val canLoadMore: Boolean = false,
)

/** One section of the page. The Listeners section has a null [type]. */
data class RecommendationPageSection(
    val type: RecommendationType?,
    val title: String,
    val rows: List<RecommendationRow>,
)

const val SHUFFLEUPAGUS_ID = "shuffleupagus"
const val SHUFFLEUPAGUS_NAME = "Shuffleupagus"
const val LISTENERS_ROW_KEY = "friends:similar"

/** The page's five sections in order; a null type is Listeners. */
val RECOMMENDATION_PAGE_SECTIONS: List<Pair<RecommendationType?, String>> = listOf(
    RecommendationType.SONG to "Tracks",
    RecommendationType.ALBUM to "Albums",
    RecommendationType.ARTIST to "Artists",
    RecommendationType.PLAYLIST to "Playlists",
    null to "Listeners",
)

private val WEEKLY_RANK = mapOf("weekly-exploration" to 0, "weekly-jam" to 1)

private val CATEGORY_ROW_PREFIX = mapOf(RecommendationType.PLAYLIST to "playlists", RecommendationType.ALBUM to "albums")

/** Each album's and playlist's category row, decided once (and an artist row's seed artist). Other types pass through. */
private fun stamp(item: RecommendationItem, section: RecommendationSection?): RecommendationItem {
    val category = RecommendationCategories.categoryFor(item.type, item, section?.category) ?: return item
    val seed = if (category == "artist" && section?.seedArtist != null) section.seedArtist else item.seedArtist
    return item.copy(category = category, seedArtist = seed)
}

/** The sources with at least one item in [items]'s `sourceIds`, in [order], with this list's count. */
private fun sourcesFor(sourceIds: List<List<String>>, order: List<String>, names: Map<String, String>): List<PageSource> {
    val counts = mutableMapOf<String, Int>()
    for (ids in sourceIds) for (id in ids) counts[id] = (counts[id] ?: 0) + 1
    return order.filter { it in counts }.map { PageSource(it, names[it] ?: it, counts.getValue(it)) }
}

private fun itemSources(items: List<RecommendationItem>, order: List<String>, names: Map<String, String>) =
    sourcesFor(items.map { it.sourceIds }, order, names)

/**
 * The page. [providers] are the providers' normalized answers in page order;
 * [shuffleupagus] is [shuffleupagusSection]'s, or null; [expanded] holds the
 * items "load more" fetched, by [pagingKey]; [similarUsers] is each similar-
 * users service's people, by service id, in page order (names from
 * [providerNames], else a provider's name, else the id); [excludePlaylistIds]
 * are Home's history mixes, left out (play history, not recommendations).
 * [blocked] is the blocklist ([blockedItemPick]), applied to the providers'
 * sections, the paged items and Shuffleupagus alike, so a block made since
 * any of those were filled takes effect at once.
 *
 * A row's `pageable` lists the provider sections that can still page items of
 * its type; an album or playlist category row lists the sections whose items
 * of its type fall in its category, so a section feeding two rows is in both
 * with one pagingKey. Paged items append after the blended first pages, in
 * provider order, so a page landing never moves a card already on screen.
 */
fun buildRecommendationPage(
    providers: List<ProviderRecommendations> = emptyList(),
    shuffleupagus: RecommendationSection? = null,
    expanded: Map<String, ExpandedSection> = emptyMap(),
    similarUsers: Map<String, List<SimilarUser>> = emptyMap(),
    exclude: FriendExclusions = FriendExclusions(),
    excludePlaylistIds: Set<String> = emptySet(),
    blocked: Set<String> = emptySet(),
    providerNames: Map<String, String> = emptyMap(),
): List<RecommendationPageSection> {
    val names = mutableMapOf<String, String>()
    val order = mutableListOf<String>()
    fun addProvider(id: String, name: String?) {
        if (id.isEmpty() || id in order) return
        order += id
        names[id] = name?.takeIf { it.isNotEmpty() } ?: id
    }
    fun notBlocked(items: List<RecommendationItem>) =
        if (blocked.isEmpty()) items else items.filterNot { blockedItemPick(it, blocked) }
    val types = RecommendationType.entries
    fun emptyByType() = types.associateWith { mutableListOf<RecommendationItem>() }

    val base = mutableListOf<Map<RecommendationType, MutableList<RecommendationItem>>>()
    val extra = mutableListOf<Map<RecommendationType, MutableList<RecommendationItem>>>()
    val pageable = types.associateWith { mutableListOf<PageableSection>() }
    val pageableByCategory = RecommendationCategories.TABLES.mapValues { (_, table) ->
        table.associate { it.id to mutableListOf<PageableSection>() }
    }

    for (provider in providers) {
        addProvider(provider.resolverId, provider.resolverName)
        val byType = emptyByType()
        val extraByType = emptyByType()
        val untitledSongs = mutableListOf<RecommendationItem>()
        for (section in provider.sections) {
            val items = notBlocked(section.items).map { stamp(it, section) }
            for (item in items) byType.getValue(item.type) += item
            if (section.title == null) {
                untitledSongs += items.filter { it.type == RecommendationType.SONG }
                continue
            }
            val key = pagingKey(provider.resolverId, section)
            val expansion = expanded[key]
            // Paged items carry the section's provider identity and category.
            val fetched = notBlocked(expansion?.items.orEmpty())
                .map { stamp(it.copy(resolverId = provider.resolverId), section) }
            for (item in fetched) extraByType.getValue(item.type) += item
            val sectionId = section.id
            if (sectionId == null || !provider.canExpand || expansion?.exhausted == true) continue
            val entry = PageableSection(
                resolverId = provider.resolverId,
                resolverName = provider.resolverName.ifEmpty { provider.resolverId },
                sectionId = sectionId,
                title = section.title,
                // The section's category if valid for any type, so an album section's `new` is kept too.
                category = section.category?.takeIf { RecommendationCategories.validForAny(it) },
                pagingKey = key,
                nextCursor = expansion?.nextCursor?.takeIf { it.isNotEmpty() }
                    ?: section.nextCursor?.takeIf { it.isNotEmpty() },
            )
            val both = items + fetched
            for (type in both.map { it.type }.distinct()) pageable.getValue(type) += entry
            for ((type, byCategory) in pageableByCategory) {
                for (category in both.filter { it.type == type }.mapNotNull { it.category }.distinct()) {
                    byCategory[category]?.add(entry)
                }
            }
        }
        byType.getValue(RecommendationType.ARTIST) += deriveArtistsFromSongs(untitledSongs)
        base += byType
        extra += extraByType
    }

    if (shuffleupagus != null) {
        addProvider(SHUFFLEUPAGUS_ID, SHUFFLEUPAGUS_NAME)
        val byType = emptyByType()
        // Its section is `personal`: its albums are Based on your listening.
        for (item in notBlocked(shuffleupagus.items)) byType.getValue(item.type) += stamp(item, shuffleupagus)
        base += byType
    }

    val page = mutableListOf<RecommendationPageSection>()
    for ((type, title) in RECOMMENDATION_PAGE_SECTIONS) {
        if (type == null) {
            val row = friendsRow(similarUsers, exclude, names + providerNames)
            if (row != null) page += RecommendationPageSection(null, title, listOf(row))
            continue
        }
        // One dedupe across the first pages and the extra pages, so a paged
        // item a first page already showed stays where it is.
        val owned = mergeOwned(base.map { it.getValue(type) } + extra.map { it.getValue(type) })
        var items = interleave(owned.take(base.size)) + owned.drop(base.size).flatten()
        // Each album's place in the blend, before the category rows split it,
        // so Home can put the rows back in blend order ([homeRecommendationAlbums]).
        if (type == RecommendationType.ALBUM) items = items.mapIndexed { i, item -> item.copy(blendIndex = i) }
        if (type == RecommendationType.PLAYLIST) items = playlistOrder(items, excludePlaylistIds)
        val rows = categoryRows(type, items, pageableByCategory[type], order, names)
        if (rows != null) {
            page += RecommendationPageSection(type, title, rows)
        } else if (items.isNotEmpty()) {
            val sources = itemSources(items, order, names)
            val rowPageable = pageable.getValue(type).toList()
            page += RecommendationPageSection(
                type, title,
                listOf(
                    RecommendationRow(
                        key = "type:${type.wire}", type = type, title = title,
                        blended = sources.size > 1, sources = sources, items = items,
                        pageable = rowPageable, canLoadMore = rowPageable.isNotEmpty(),
                    ),
                ),
            )
        }
    }
    return page
}

/**
 * The Playlists order: only the newest week of each weekly playlist (latest
 * `date`, else the provider's first, which is This Week), weekly playlists
 * first (Exploration before Jams), the rest in blended order; then the history
 * mixes go. They go after the newest-week pick: dropping this week's Jams
 * first would let last week's take its place.
 */
private fun playlistOrder(items: List<RecommendationItem>, excludePlaylistIds: Set<String>): List<RecommendationItem> {
    val newest = mutableMapOf<String, Int>()
    items.forEachIndexed { i, item ->
        val w = item.weekly ?: return@forEachIndexed
        val keptAt = newest[w.contextType]
        val keptDate = keptAt?.let { items[it].weekly?.date }
        if (keptAt == null || (w.date != null && keptDate != null && w.date > keptDate)) newest[w.contextType] = i
    }
    val keep = newest.values.toSet()
    fun rank(item: RecommendationItem) = item.weekly?.let { WEEKLY_RANK[it.contextType] ?: 2 } ?: 3
    return items.withIndex()
        .filter { (i, item) -> item.weekly == null || i in keep }
        .map { it.value }
        .sortedBy(::rank)
        .filter { it.id !in excludePlaylistIds }
}

/**
 * The Albums or Playlists category rows, or null when [items] have fewer
 * than two categories (the caller then builds the single `type:<type>` row).
 * Each row keeps its items' relative order.
 */
private fun categoryRows(
    type: RecommendationType,
    items: List<RecommendationItem>,
    pageableByCategory: Map<String, List<PageableSection>>?,
    order: List<String>,
    names: Map<String, String>,
): List<RecommendationRow>? {
    val table = RecommendationCategories.TABLES[type] ?: return null
    val rank = RecommendationCategories.CATEGORY_RANK.getValue(type)
    val byCategory = LinkedHashMap<String, MutableList<RecommendationItem>>()
    for (item in items) {
        val category = item.category?.takeIf { it in rank } ?: RecommendationCategories.OTHER
        byCategory.getOrPut(category) { mutableListOf() } += item
    }
    if (byCategory.size < 2) return null
    return table.mapNotNull { (id, title) ->
        val rowItems = byCategory[id] ?: return@mapNotNull null
        val sources = itemSources(rowItems, order, names)
        val rowPageable = pageableByCategory?.get(id).orEmpty().toList()
        RecommendationRow(
            key = "${CATEGORY_ROW_PREFIX.getValue(type)}:$id",
            type = type,
            category = id,
            title = if (id == "artist") RecommendationCategories.artistRowTitle(rowItems.map { it.seedArtist }) else title,
            blended = sources.size > 1,
            sources = sources,
            items = rowItems,
            pageable = rowPageable,
            canLoadMore = rowPageable.isNotEmpty(),
        )
    }
}

/**
 * The Listeners row: each service's people most similar first, then the
 * services alternating (they score on different scales, so one sort across
 * both would always put one service first). The same name on two services
 * stays two entries; followed, removed and self accounts are dropped by key.
 * Only [FriendExclusions.friendServices] make the row; null when nobody is left.
 */
fun friendsRow(
    similarUsers: Map<String, List<SimilarUser>>,
    exclude: FriendExclusions,
    providerNames: Map<String, String> = emptyMap(),
): RecommendationRow? {
    val lists = mutableListOf<List<ListenerItem>>()
    val order = mutableListOf<String>()
    for ((service, users) in similarUsers) {
        if (service !in exclude.friendServices) continue
        val seen = mutableSetOf<String>()
        val list = mutableListOf<ListenerItem>()
        for (user in users) {
            val username = user.username.trim()
            if (username.isEmpty()) continue
            val key = "$service:${username.lowercase()}"
            if (key in seen || key in exclude.friendKeys || key in exclude.selfKeys) continue
            seen += key
            list += ListenerItem(
                id = "similar:$service:$username",
                name = username,
                service = service,
                similarity = user.similarity,
                avatarUrl = user.avatarUrl?.takeIf { it.isNotEmpty() },
                topArtists = user.topArtists.filter { it.isNotBlank() }.take(3),
                resolverId = service,
                sourceIds = listOf(service),
            )
        }
        if (list.isEmpty()) continue
        order += service
        lists += list
    }
    fun score(item: ListenerItem) = item.similarity?.takeIf { it.isFinite() } ?: Double.NEGATIVE_INFINITY
    // Stable: a tie keeps the service's order.
    val items = interleave(lists.map { list -> list.sortedByDescending(::score) })
    if (items.isEmpty()) return null
    val sources = sourcesFor(items.map { it.sourceIds }, order, providerNames)
    return RecommendationRow(
        key = LISTENERS_ROW_KEY, type = null, title = "Listeners",
        blended = sources.size > 1, sources = sources, listeners = items,
    )
}

/**
 * The page's source filter: a row with the items [sourceId] sent, shared ones
 * included. Null or `all` returns [row] itself.
 */
fun filterRowBySource(row: RecommendationRow, sourceId: String?): RecommendationRow {
    if (sourceId == null || sourceId == "all") return row
    return row.copy(
        items = row.items.filter { sourceId in it.sourceIds },
        listeners = row.listeners.filter { sourceId in it.sourceIds },
    )
}

/** The filter bar's pills: every source on the page, in page order, with how many items it sent across the page. */
fun pageSources(sections: List<RecommendationPageSection>?): List<PageSource> {
    val order = mutableListOf<String>()
    val names = mutableMapOf<String, String>()
    val counts = mutableMapOf<String, Int>()
    for (section in sections.orEmpty()) for (row in section.rows) {
        for (source in row.sources) if (source.id !in names) {
            order += source.id
            names[source.id] = source.name
            counts[source.id] = 0
        }
        for (ids in row.items.map { it.sourceIds } + row.listeners.map { it.sourceIds }) {
            for (id in ids) counts[id]?.let { counts[id] = it + 1 }
        }
    }
    return order.map { PageSource(it, names.getValue(it), counts.getValue(it)) }
}

/** Every row's items, in page order (what resolution and enrichment walk). Listeners aren't items. */
fun flattenRecommendationItems(sections: List<RecommendationPageSection>?): List<RecommendationItem> =
    sections.orEmpty().flatMap { s -> s.rows.flatMap { it.items } }

/**
 * The Shuffleupagus (AI) provider's section: its albums and artists, with
 * their reasons, as one titled section. The section is `personal`, so its
 * albums go to Based on your listening. Null when nothing is left.
 */
fun shuffleupagusSection(albums: List<AiAlbumSuggestion>, artists: List<AiArtistSuggestion>): RecommendationSection? {
    val items = mutableListOf<RecommendationItem>()
    for (album in albums) {
        if (album.title.isEmpty() || album.artist.isEmpty()) continue
        items += RecommendationItem(
            type = RecommendationType.ALBUM,
            id = "$SHUFFLEUPAGUS_ID:album:${trackSlug(album.artist, album.title)}",
            name = album.title,
            artist = album.artist,
            reason = album.reason.ifEmpty { null },
            imageUrl = album.artworkUrl?.ifEmpty { null },
            resolverId = SHUFFLEUPAGUS_ID,
        )
    }
    for (artist in artists) {
        if (artist.name.isEmpty()) continue
        items += RecommendationItem(
            type = RecommendationType.ARTIST,
            id = "$SHUFFLEUPAGUS_ID:artist:${slug(artist.name)}",
            name = artist.name,
            reason = artist.reason.ifEmpty { null },
            imageUrl = artist.imageUrl?.ifEmpty { null },
            resolverId = SHUFFLEUPAGUS_ID,
        )
    }
    if (items.isEmpty()) return null
    return RecommendationSection(items = items, title = SHUFFLEUPAGUS_NAME, category = "personal")
}

/**
 * Home's "Albums you might love": every album the page shows plus
 * Shuffleupagus's own picks ([aiAlbums]), once each. Provider albums keep the
 * page's blend across its category rows ([RecommendationItem.blendIndex]; an
 * item without one sorts by its position), so the leading row can't fill
 * Home's row by itself. An album is dropped when its artist + title or its id
 * was already kept. Each carries its source's name in
 * [RecommendationItem.resolverName]; [imageFor] is the page's cover for an item.
 */
fun homeRecommendationAlbums(
    sections: List<RecommendationPageSection>?,
    aiAlbums: List<AiAlbumSuggestion>?,
    imageFor: ((RecommendationItem) -> String?)? = null,
): List<RecommendationItem> {
    val out = mutableListOf<RecommendationItem>()
    val seen = mutableSetOf<String>()
    val seenIds = mutableSetOf<String>()
    fun keyOf(artist: String?, title: String?) = "${artist.orEmpty().trim().lowercase()}|${title.orEmpty().trim().lowercase()}"
    fun keep(key: String, id: String?): Boolean {
        if (key in seen || (!id.isNullOrEmpty() && id in seenIds)) return false
        seen += key
        if (!id.isNullOrEmpty()) seenIds += id
        return true
    }
    data class Picked(val item: RecommendationItem, val row: RecommendationRow, val at: Int, val rank: Int)
    val picked = mutableListOf<Picked>()
    for (section in sections.orEmpty()) for (row in section.rows) for (item in row.items) {
        if (item.type != RecommendationType.ALBUM || item.name.isEmpty()) continue
        val at = picked.size
        picked += Picked(item, row, at, item.blendIndex ?: at)
    }
    for ((item, row) in picked.sortedWith(compareBy<Picked>({ it.rank }, { it.at }))) {
        if (!keep(keyOf(item.artist, item.name), item.id)) continue
        val label = if (item.resolverId == SHUFFLEUPAGUS_ID) SHUFFLEUPAGUS_NAME
        else row.sources.firstOrNull { it.id == item.resolverId }?.name ?: item.resolverId.orEmpty()
        out += item.copy(resolverName = label, imageUrl = if (imageFor != null) imageFor(item) else item.imageUrl)
    }
    for (album in aiAlbums.orEmpty()) {
        if (album.title.isEmpty()) continue
        val id = "$SHUFFLEUPAGUS_ID:album:${trackSlug(album.artist, album.title)}"
        if (!keep(keyOf(album.artist, album.title), id)) continue
        out += RecommendationItem(
            type = RecommendationType.ALBUM,
            id = id,
            name = album.title,
            artist = album.artist.ifEmpty { null },
            reason = album.reason.ifEmpty { null },
            imageUrl = album.artworkUrl?.ifEmpty { null },
            resolverId = SHUFFLEUPAGUS_ID,
            resolverName = SHUFFLEUPAGUS_NAME,
        )
    }
    return out
}

/** A Home album card's second line: its source, and why it was picked ("Shuffleupagus · peak"). */
fun recommendationWhy(item: RecommendationItem): String {
    val label = item.resolverName.orEmpty()
    return if (item.reason != null) "$label · ${item.reason}" else label
}

/** Home's source chip: the items [sourceId] sent (by `resolverId` or `sourceIds`). `All` or null keeps them all. Delegates to Home's generic rule. */
fun filterBySource(items: List<RecommendationItem>, sourceId: String?): List<RecommendationItem> =
    com.parachord.shared.home.filterBySource(items, sourceId ?: "All", { it.resolverId }, { it.sourceIds })

/**
 * The recommended albums Home asks a cover for: the first [perSource] of the
 * whole list and of each source's own list. Delegates to Home's generic rule.
 */
fun albumsNeedingArt(albums: List<RecommendationItem>?, perSource: Int = 6): List<RecommendationItem> =
    com.parachord.shared.home.albumsNeedingArt(albums.orEmpty(), perSource, { it.resolverId }, { it.sourceIds })
