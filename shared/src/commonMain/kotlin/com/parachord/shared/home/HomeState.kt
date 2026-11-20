package com.parachord.shared.home

import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.repository.CriticsPickAlbum
import com.parachord.shared.repository.FreshDrop

/** The "All" chip's id, which every source filter starts on. */
const val HOME_ALL_SOURCES = "All"

/** Recent loves Home takes from the collection (desktop's `recentLoves(…, 50)`). */
const val HOME_RECENT_LOVES = 50

/** A source chip. "All" isn't in the lists; a row shows its chips with any source ([showSourceChips]), "All" being the other chip. */
data class FilterChipSource(val id: String, val label: String)

/** The getting-started layout: its steps, step 1's tiles, and whether there's music to play yet. */
data class GettingStartedView(
    val status: GettingStartedStatus,
    val services: List<Pair<PluginInfo, ServiceState>>,
    val free: List<PluginInfo>,
    val folder: PluginInfo?,
    val hasMusic: Boolean,
)

/** A card in "Made from your listening". */
sealed class HomeMixCard {
    /** Recent Loves, which leads the row under All. [mosaic] has the newest loved albums. */
    data class Loves(val tracks: List<Track>, val mosaic: List<LovesMosaicAlbum>, val subtitle: String) : HomeMixCard()
    data class Mix(val mix: HistoryMix) : HomeMixCard()
}

/** A playlist card: the "Δ N" badge (null with no recent change) and the two-line subtitle. */
data class HomePlaylistCard(val playlist: Playlist, val badge: String?, val subtitle: PlaylistSubtitle)

/** Everything Home draws, worked out by [HomeSession]. */
data class HomeState(
    /** False until the getting-started decision is made; the page shows its skeleton until then. */
    val ready: Boolean = false,
    val greeting: String = "",
    /** "Daily Brew picked up 12 new tracks this morning", or null with no recent change. */
    val changeLine: String? = null,
    val hasCollection: Boolean = false,
    /** The "Finish setting up" pill; null when it has no item. */
    val setup: HomeSetup? = null,
    val banner: HomeBannerItem? = null,
    /** Non-null: the page shows only the getting-started layout, then Explore. */
    val gettingStarted: GettingStartedView? = null,
    val tiles: List<JumpTile> = emptyList(),
    /** "Build your collection": no collection and no playlists. */
    val showImportCard: Boolean = false,
    val mixRow: List<HomeMixCard> = emptyList(),
    val mixSources: List<FilterChipSource> = emptyList(),
    val mixSource: String = HOME_ALL_SOURCES,
    val playlists: List<HomePlaylistCard> = emptyList(),
    val albums: List<RecommendationItem> = emptyList(),
    val albumSources: List<FilterChipSource> = emptyList(),
    val albumSource: String = HOME_ALL_SOURCES,
    val albumsLoading: Boolean = false,
    val halves: HomeHalves = HomeHalves(fresh = false, critical = false, charts = false),
    /** Each at most [HOME_HALF_ROW_CARDS]. */
    val fresh: HomeFeed<FreshDrop> = HomeFeed(),
    val critical: HomeFeed<CriticsPickAlbum> = HomeFeed(),
    val charts: HomeFeed<ChartAlbum> = HomeFeed(),
    val concerts: List<ConcertEvent> = emptyList(),
    /** Concerts are loading with a location set (the row's skeleton). */
    val concertsLoading: Boolean = false,
    val askExample: String = "",
    val chatAvailable: Boolean? = null,
    val sections: HomeSections = HomeSections(
        banner = false, jump = false, importCard = false, mixes = false, playlists = false,
        albums = false, fresh = false, critical = false, concerts = false, ask = true,
    ),
)
