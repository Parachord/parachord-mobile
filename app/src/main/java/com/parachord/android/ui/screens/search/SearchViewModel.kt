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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel constructor(
    private val repository: LibraryRepository,
    private val metadataService: MetadataService,
    private val playbackController: PlaybackController,
    private val searchHistoryDao: SearchHistoryDao,
    private val resolverManager: ResolverManager,
    private val settingsStore: SettingsStore,
    private val trackResolverCache: TrackResolverCache,
    private val musicBrainzClient: com.parachord.shared.api.MusicBrainzClient,
) : ViewModel() {

    /** User-configured resolver priority order, used to sort resolver icons on track rows. */
    val resolverOrder: StateFlow<List<String>> = settingsStore.getResolverOrderFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val debouncedQuery = _query.debounce(300)

    // Local library results (instant, from Room)
    val localTrackResults: StateFlow<List<TrackEntity>> = debouncedQuery
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else repository.searchTracks("%$q%")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val localAlbumResults: StateFlow<List<AlbumEntity>> = debouncedQuery
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else repository.searchAlbums("%$q%")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Remote results from cascading metadata providers
    private val _remoteTrackResults = MutableStateFlow<List<TrackSearchResult>>(emptyList())
    val remoteTrackResults: StateFlow<List<TrackSearchResult>> = _remoteTrackResults.asStateFlow()

    private val _remoteAlbumResults = MutableStateFlow<List<AlbumSearchResult>>(emptyList())
    val remoteAlbumResults: StateFlow<List<AlbumSearchResult>> = _remoteAlbumResults.asStateFlow()

    private val _artistResults = MutableStateFlow<List<ArtistInfo>>(emptyList())
    val artistResults: StateFlow<List<ArtistInfo>> = _artistResults.asStateFlow()

    /** True when MusicBrainz is throttling us and the client's retries didn't
     *  clear it. The screen shows this INSTEAD of "No results" — a throttled
     *  search has not established that nothing matched (#375). */
    val musicBrainzRateLimited: StateFlow<Boolean> = musicBrainzClient.rateLimited

    private val _isSearchingRemote = MutableStateFlow(false)
    val isSearchingRemote: StateFlow<Boolean> = _isSearchingRemote.asStateFlow()

    /** Cached resolved sources for remote tracks, keyed by "title|artist" */
    private val _trackSources = MutableStateFlow<Map<String, List<ResolvedSource>>>(emptyMap())

    /** Resolver badge names for UI display from shared cache */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> = trackResolverCache.trackResolverConfidences

    // Search history
    val searchHistory: StateFlow<List<SearchHistoryEntity>> = searchHistoryDao.getRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Launch remote search whenever the debounced query changes
        viewModelScope.launch {
            debouncedQuery.collect { q ->
                if (q.isBlank()) {
                    _remoteTrackResults.value = emptyList()
                    _remoteAlbumResults.value = emptyList()
                    _artistResults.value = emptyList()
                    _isSearchingRemote.value = false
                    _trackSources.value = emptyMap()
                } else {
                    searchRemote(q)
                }
            }
        }
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
    }

    fun playLocalTrack(track: TrackEntity) {
        val allTracks = localTrackResults.value
        val index = allTracks.indexOf(track).coerceAtLeast(0)
        playbackController.playQueue(allTracks, startIndex = index)
    }

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
                // Check shared cache first (cross-context dedup)
                val cached = trackResolverCache.getSources(track.title, track.artist)
                if (cached != null) {
                    _trackSources.value = _trackSources.value + (key to cached)
                    trackResolverCache.putSources(track.title, track.artist, cached)
                    continue
                }
                try {
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.title} ${track.artist}",
                        spotifyId = track.spotifyId,
                        targetTitle = track.title,
                        targetArtist = track.artist,
                    )
                    if (sources.isNotEmpty()) {
                        _trackSources.value = _trackSources.value + (key to sources)
                        trackResolverCache.putSources(track.title, track.artist, sources)
                    }
                } catch (_: Exception) { /* skip */ }
            }
        }
    }

    private suspend fun searchRemote(query: String) {
        _isSearchingRemote.value = true
        try {
            val tracks = metadataService.searchTracks(query)
            val albums = metadataService.searchAlbums(query)
            val artists = metadataService.searchArtists(query)

            // Re-rank results using fuzzy matching (desktop formula: 60% fuzzy + 40% source)
            _remoteTrackResults.value = rankTracks(query, tracks)
            resolveTracksInBackground(_remoteTrackResults.value)
            _remoteAlbumResults.value = rankAlbums(query, albums)
            _artistResults.value = rankArtists(query, artists)
        } catch (_: Exception) {
            // Silently fail — local results still available
        } finally {
            _isSearchingRemote.value = false
        }
    }

    private fun rankTracks(query: String, tracks: List<TrackSearchResult>): List<TrackSearchResult> =
        tracks.map { track ->
            val titleScore = FuzzyMatch.score(query, track.title)
            val artistScore = FuzzyMatch.score(query, track.artist)
            val fuzzy = maxOf(titleScore, artistScore * 0.9)
            val priority = providerPriority(track.provider)
            track to FuzzyMatch.combinedScore(fuzzy, priority)
        }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun rankAlbums(query: String, albums: List<AlbumSearchResult>): List<AlbumSearchResult> =
        albums.map { album ->
            val titleScore = FuzzyMatch.score(query, album.title)
            val artistScore = FuzzyMatch.score(query, album.artist)
            val fuzzy = maxOf(titleScore, artistScore * 0.9)
            val priority = providerPriority(album.provider)
            album to FuzzyMatch.combinedScore(fuzzy, priority)
        }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun rankArtists(query: String, artists: List<ArtistInfo>): List<ArtistInfo> =
        artists.map { artist ->
            val fuzzy = FuzzyMatch.score(query, artist.name)
            val priority = providerPriority(artist.provider)
            artist to FuzzyMatch.combinedScore(fuzzy, priority)
        }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .map { it.first }

    /** Map provider name to priority value for scoring. */
    private fun providerPriority(provider: String): Int = when {
        "musicbrainz" in provider -> 0
        "lastfm" in provider -> 10
        "spotify" in provider -> 20
        else -> 10
    }
}
