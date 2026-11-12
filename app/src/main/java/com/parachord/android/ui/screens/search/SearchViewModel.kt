package com.parachord.android.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.dao.SearchHistoryDao
import com.parachord.android.data.db.entity.SearchHistoryEntity
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.ArtistInfo
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.metadata.TrackSearchResult
import com.parachord.android.data.metadata.AlbumSearchResult
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolvedSource
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import com.parachord.shared.metadata.SearchArtwork
import com.parachord.shared.search.SearchSession
import com.parachord.shared.search.SearchState
import com.parachord.shared.search.asSearchCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel constructor(
    private val repository: LibraryRepository,
    private val metadataService: MetadataService,
    private val playbackController: PlaybackController,
    private val searchHistoryDao: SearchHistoryDao,
    private val resolverManager: ResolverManager,
    private val settingsStore: SettingsStore,
    private val trackResolverCache: TrackResolverCache,
    private val musicBrainzClient: com.parachord.shared.api.MusicBrainzClient,
    private val searchArtwork: SearchArtwork,
) : ViewModel() {

    /** User-configured resolver priority order, used to sort resolver icons on track rows. */
    val resolverOrder: StateFlow<List<String>> = settingsStore.getResolverOrderFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * The screen's rules, shared with iOS (#590): searches from two characters,
     * 300 ms after the last keystroke, cancelling the superseded search; local
     * tracks + albums, the three remote categories and covers for rows without
     * one. The remote tracks resolve here once their search finishes.
     */
    private val session = SearchSession(
        scope = viewModelScope,
        catalog = metadataService.asSearchCatalog(),
        artwork = searchArtwork,
        localTracks = { q -> repository.searchTracks(q) },
        localAlbums = { q -> repository.searchAlbums(q) },
        resolvedTrackArt = { title, artist ->
            trackResolverCache.trackArtwork.map { it[trackKey(title, artist)] }.filterNotNull().first()
        },
        onRemoteTracksSettled = ::resolveTracksInBackground,
    )

    private fun <T> fromSession(pick: (SearchState) -> T): StateFlow<T> =
        session.state.map(pick).distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, pick(session.state.value))

    // Local library results
    val localTrackResults: StateFlow<List<TrackEntity>> = fromSession { it.localTracks }
    val localAlbumResults: StateFlow<List<AlbumEntity>> = fromSession { it.localAlbums }

    // Remote results from cascading metadata providers
    val remoteTrackResults: StateFlow<List<TrackSearchResult>> = fromSession { it.remoteTracks }
    val remoteAlbumResults: StateFlow<List<AlbumSearchResult>> = fromSession { it.remoteAlbums }
    val artistResults: StateFlow<List<ArtistInfo>> = fromSession { it.artists }

    /** True when MusicBrainz is throttling us and the client's retries didn't
     *  clear it. The screen shows this INSTEAD of "No results" — a throttled
     *  search has not established that nothing matched (#375). */
    val musicBrainzRateLimited: StateFlow<Boolean> = musicBrainzClient.rateLimited

    val isSearchingRemote: StateFlow<Boolean> = fromSession { it.isSearching }

    /** The remote search has finished, so empty lists mean no results. */
    val hasSearched: StateFlow<Boolean> = fromSession { it.hasSearched }

    /** Cached resolved sources for remote tracks, keyed by "title|artist" */
    private val _trackSources = MutableStateFlow<Map<String, List<ResolvedSource>>>(emptyMap())

    /** Resolver badge names for UI display from shared cache */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> = trackResolverCache.trackResolverConfidences

    /** Each resolved track's cover (its playing source's), keyed by [trackKey]: any row's last resort. */
    val trackArtwork: StateFlow<Map<String, String>> = trackResolverCache.trackArtwork

    /**
     * Covers found for rows that came without a usable one ([SearchArtwork]),
     * keyed by [SearchSession.artKey]. A row keeps the first cover found for it.
     */
    val searchArt: StateFlow<Map<String, String>> = fromSession { it.art }

    // Search history
    val searchHistory: StateFlow<List<SearchHistoryEntity>> = searchHistoryDao.getRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Pre-resolve local search results so resolver badges appear and playback is instant
        viewModelScope.launch {
            localTrackResults.collect { tracks ->
                if (tracks.isNotEmpty()) {
                    trackResolverCache.resolveInBackground(tracks)
                }
            }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        if (!SearchSession.isSearchable(newQuery)) _trackSources.value = emptyMap()
        session.query(newQuery)
    }

    fun playLocalTrack(track: TrackEntity) {
        val allTracks = localTrackResults.value
        val index = allTracks.indexOf(track).coerceAtLeast(0)
        playbackController.playQueue(allTracks, startIndex = index)
    }

    /**
     * Play a track from the search results, with the results after it queued
     * under a "Search" context (desktop search-page.js; iOS SearchView). A
     * result nothing has resolved yet resolves the moment it plays
     * (PlaybackController.resolveOnTheFly), ahead of the rows still resolving
     * in the background (desktop's priority lane).
     */
    fun playSearchTrack(track: TrackSearchResult) {
        val results = session.state.value.remoteTracks
        val index = results.indexOf(track)
        if (index < 0) return
        val queue = results.drop(index).map { it.toPlayable() }
        playbackController.playQueue(
            queue,
            startIndex = 0,
            context = com.parachord.android.playback.PlaybackContext(type = "search", name = _query.value.trim()),
        )
    }

    /** A playable track for a search row, with the cover the row shows. */
    private fun TrackSearchResult.toPlayable(): TrackEntity = TrackEntity(
        id = "search-${title.hashCode()}-${artist.hashCode()}",
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        artworkUrl = artworkUrl
            ?: session.state.value.art[SearchSession.trackArtKey(title, artist)]
            ?: trackResolverCache.trackArtwork.value[trackKey(title, artist)],
        spotifyId = spotifyId,
        recordingMbid = mbid,
        artistMbid = artistMbid,
    )

    /** Save a search history entry when user selects a result. */
    fun saveHistoryEntry(
        resultType: String,
        resultName: String,
        resultArtist: String? = null,
        artworkUrl: String? = null,
    ) {
        val q = _query.value.trim()
        if (q.isBlank()) return
        viewModelScope.launch {
            // Dedup: remove older entries with same query (case-insensitive)
            searchHistoryDao.deleteByQuery(q)
            searchHistoryDao.insert(
                SearchHistoryEntity(
                    query = q,
                    resultType = resultType,
                    resultName = resultName,
                    resultArtist = resultArtist,
                    artworkUrl = artworkUrl,
                )
            )
            searchHistoryDao.trimToLimit()
        }
    }

    fun deleteHistoryEntry(entry: SearchHistoryEntity) {
        viewModelScope.launch {
            searchHistoryDao.deleteById(entry.id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            searchHistoryDao.clearAll()
        }
    }

    private fun resolveTracksInBackground(tracks: List<TrackSearchResult>) {
        viewModelScope.launch {
            for (track in tracks) {
                val key = trackKey(track.title, track.artist)
                if (_trackSources.value.containsKey(key)) continue
                // The shared cache: its sources, a retry of resolvers that
                // couldn't answer, or a fresh resolve (#566).
                try {
                    val sources = trackResolverCache.resolveTrack(track.title, track.artist, spotifyId = track.spotifyId)
                    if (sources.isNotEmpty()) {
                        _trackSources.value = _trackSources.value + (key to sources)
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }
    }

    override fun onCleared() {
        session.close()
        super.onCleared()
    }
}
