package com.parachord.shared.home

import com.parachord.shared.ai.AiAlbumSuggestion
import com.parachord.shared.api.Announcement
import com.parachord.shared.model.ChartAlbum
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import com.parachord.shared.playback.PlaybackContext
import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.recommendations.RecommendationPageSection
import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.repository.CriticsPickAlbum
import com.parachord.shared.repository.FreshDrop
import com.parachord.shared.repository.HistoryMixesState
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One of Home's remote lists as the platform holds it. [loading] is true while
 * a load runs; a [HomeFeedLoader] also starts it true, before its first load
 * has answered (concerts excepted, which load only with a location), while a
 * bare `HomeFeed()` (the session's state before any source answers) is not
 * loading. [failed] after a load that couldn't answer (the last items, if
 * any, stay).
 */
data class HomeFeed<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/**
 * What a recommendations load did, for Home's throttle
 * ([shouldStampHomeRecommendationsLoad]): providers asked, how many of those
 * failed, and the sections there are to show.
 */
data class HomeRecsLoad(val fetched: Int, val failed: Int, val sectionCount: Int)

/**
 * Everything [HomeSession] reads, one member per input. Each platform maps its
 * own repositories, playback state and settings onto these; the session holds
 * every rule.
 *
 * The flows should emit real values only: the getting-started decision is made
 * once, from the first values of [accounts], [playbackResolvers],
 * [folderAdded], [scrobblerAccounts], [hasCollection] and [playlists] once
 * [plugins] is non-empty (the session waits for the plugins, as desktop gates
 * on loaded resolvers), so a placeholder (`false` before the database
 * answers, no accounts before the tokens are read) can show the layout to a
 * user who doesn't need it.
 */
interface HomeSources {
    val playlists: Flow<List<Playlist>>
    /** Every track row; Home takes the loves from it ([com.parachord.shared.repository.recentLoves]). */
    val collectionTracks: Flow<List<Track>>
    /** Tracks or albums in the collection (desktop's `hasCollection`). */
    val hasCollection: Flow<Boolean>
    /** The playing queue's context, for Jump back in's active check. */
    val playingContext: Flow<PlaybackContext?>
    /** Something is playing (not paused, not stopped on a failed track). Marks getting-started's step 3. */
    val isPlaying: Flow<Boolean>
    /** Already filtered (surface, version, expiry, dismissals). */
    val announcements: Flow<List<Announcement>>

    val historyMixes: StateFlow<HistoryMixesState>
    /**
     * `HistoryMixesRepository.load`: providers asked, how many failed, and the
     * mixes there are to show, for the throttle. May throw; the session catches it.
     */
    suspend fun loadHistoryMixes(force: Boolean): HomeRecsLoad

    /** The Recommendations page's sections; null before the first answer. */
    val recommendationSections: Flow<List<RecommendationPageSection>?>
    /** Shuffleupagus's cached album picks; null before the first answer. */
    val aiAlbums: Flow<List<AiAlbumSuggestion>?>
    suspend fun loadRecommendations(force: Boolean): HomeRecsLoad

    val freshDrops: Flow<HomeFeed<FreshDrop>>
    val criticalDarlings: Flow<HomeFeed<CriticsPickAlbum>>
    val charts: Flow<HomeFeed<ChartAlbum>>
    /**
     * Loads Fresh Drops, Critical Darlings and Pop of the Tops where they
     * aren't loaded or loading (a return visit asks nothing). `force` also
     * loads again whatever failed; a loaded list stays.
     */
    suspend fun loadFeeds(force: Boolean)

    val concerts: Flow<HomeFeed<ConcertEvent>>
    /** The saved concert location, or null with none. */
    suspend fun concertLocation(): SettingsStore.ConcertLocation?
    /** Loads the personalized concerts unless they're loaded or loading. Only asked with a location. */
    suspend fun loadConcerts()
    /**
     * The app-wide concert artist photos (the same instance the Concerts page
     * uses). The session asks it for the shown cards' artists.
     */
    val concertArtistImages: ConcertArtistImages

    /** A chat plugin is set up; null while unknown. */
    val chatAvailable: Flow<Boolean?>

    val plugins: Flow<List<PluginInfo>>
    /** Plug-in id to whether the host knows it's signed in. */
    val accounts: Flow<Map<String, Boolean>>
    /** The resolvers that are on and can play. */
    val playbackResolvers: Flow<List<String>>
    /** A music folder (local files) is set up. */
    val folderAdded: Flow<Boolean>
    /**
     * Scrobbling plug-in id (`lastfm`, `listenbrainz`, `librefm`) to whether its
     * account is connected (desktop's `scrobblerStates`: a saved username).
     */
    val scrobblerAccounts: Flow<Map<String, Boolean>>

    /**
     * Home came on screen: read again what has no flow (a token, the AI keys).
     * The session calls it first on each visit.
     */
    suspend fun refresh() {}

    /** Reports the announcement Home painted (the `view` event). The session calls it once per id. */
    suspend fun bannerViewed(id: String) {}

    fun nowMs(): Long
    /** The local hour, 0 to 23. */
    fun hour(): Int
}
