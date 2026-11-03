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
import com.parachord.shared.metadata.SearchArtistList
import com.parachord.shared.metadata.SearchArtwork
import com.parachord.shared.metadata.SearchRanking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val searchArtwork: SearchArtwork,
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

    /** Each resolved track's cover (its playing source's), keyed by [trackKey]: any row's last resort. */
    val trackArtwork: StateFlow<Map<String, String>> = trackResolverCache.trackArtwork

    /**
     * Covers found for rows that came without a usable one ([SearchArtwork]),
     * keyed by [artKey]. A row keeps the first cover found for it.
     */
    private val _searchArt = MutableStateFlow<Map<String, String>>(emptyMap())
    val searchArt: StateFlow<Map<String, String>> = _searchArt.asStateFlow()

    /** The current query's cover lookups; cancelled when the query changes. */
    private var artJob = kotlinx.coroutines.SupervisorJob()
    private val artRequested = mutableSetOf<String>()

    // Search history
    val searchHistory: StateFlow<List<SearchHistoryEntity>> = searchHistoryDao.getRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Launch remote search whenever the debounced query changes. collectLatest
        // cancels the running search when a newer query arrives, so its
        // MusicBrainz requests leave the 1 req/s queue instead of holding up the
        // query the user wants (#475).
        viewModelScope.launch {
            debouncedQuery.collectLatest { q ->
                resetSearchArt()
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

    /**
     * Play a track from the search results, with the results after it queued
     * under a "Search" context (desktop search-page.js; iOS SearchView). A
     * result nothing has resolved yet resolves the moment it plays
     * (PlaybackController.resolveOnTheFly), ahead of the rows still resolving
     * in the background (desktop's priority lane).
     */
    fun playSearchTrack(track: TrackSearchResult) {
        val results = _remoteTrackResults.value
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
            ?: _searchArt.value[artKey("track", title, artist)]
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

    /**
     * The three categories run at once, and each one's rows show as its
     * providers answer (#475, desktop parachord#1186): Spotify and Last.fm in a
     * few hundred ms, MusicBrainz seconds later through its 1 req/s gate. Each
     * list is re-ranked using fuzzy matching (desktop formula: 60% fuzzy + 40%
     * source) every time it grows.
     */
    private suspend fun searchRemote(query: String) {
        _isSearchingRemote.value = true
        // MusicBrainz's artists plus those the services' rows name (desktop).
        val artistList = SearchArtistList(query)
        try {
            coroutineScope {
                launch {
                    var ranked = emptyList<TrackSearchResult>()
                    metadataService.searchTracksProgressively(query).collect { tracks ->
                        ranked = SearchRanking.rankTracks(query, tracks)
                        _remoteTrackResults.value = ranked
                        ranked.take(SearchArtwork.LIMIT).forEach(::findTrackArt)
                        showArtists(artistList.withTracks(ranked))
                    }
                    resolveTracksInBackground(ranked)
                }
                launch {
                    metadataService.searchAlbumsProgressively(query).collect { albums ->
                        val ranked = SearchRanking.rankAlbums(query, albums)
                        _remoteAlbumResults.value = ranked
                        ranked.take(SearchArtwork.LIMIT).forEach(::findAlbumArt)
                        showArtists(artistList.withAlbums(ranked))
                    }
                }
                launch {
                    metadataService.searchArtistsProgressively(query).collect { artists ->
                        showArtists(artistList.withMusicBrainz(artists))
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Silently fail — local results still available
        } finally {
            _isSearchingRemote.value = false
        }
    }

    private fun showArtists(ranked: List<ArtistInfo>) {
        _artistResults.value = ranked
        ranked.take(SearchArtwork.LIMIT).forEach(::findArtistImage)
    }

    // ── Covers for rows without one (desktop fetchSearchAlbumArt) ──────

    private fun resetSearchArt() {
        artJob.cancel()
        artJob = kotlinx.coroutines.SupervisorJob()
        artRequested.clear()
        _searchArt.value = emptyMap()
    }

    /**
     * A track's cover: its album's Cover Art Archive cover (MusicBrainz's
     * rows), else, after [SearchArtwork.FALLBACK_MS], a merged provider row's
     * cover or the art of the source the track resolved to (search rows are
     * resolved in the background, which is where their badges come from).
     */
    private fun findTrackArt(track: TrackSearchResult) {
        if (!track.artworkUrl.isNullOrBlank()) return
        val toCheck = track.artworkCandidates.filter(SearchArtwork::needsCheck)
        val service = track.artworkCandidates.firstOrNull { !SearchArtwork.needsCheck(it) }
        val resolved = trackKey(track.title, track.artist)
        lookUpArt(artKey("track", track.title, track.artist)) {
            searchArtwork.settle(toCheck) {
                service ?: trackResolverCache.trackArtwork.map { it[resolved] }.filterNotNull().first()
            }
        }
    }

    /**
     * An album's cover: MusicBrainz's Cover Art Archive covers once checked,
     * else a merged provider row's cover.
     */
    private fun findAlbumArt(album: AlbumSearchResult) {
        val all = listOfNotNull(album.artworkUrl) + album.artworkCandidates
        // A provider's own cover shows as it is.
        if (all.isEmpty() || !SearchArtwork.needsCheck(all.first())) return
        lookUpArt(artKey("album", album.title, album.artist)) {
            searchArtwork.settle(all.filter(SearchArtwork::needsCheck)) {
                all.firstOrNull { !SearchArtwork.needsCheck(it) }
            }
        }
    }

    /** An artist's photo when the search row had none (desktop SearchArtistCard). */
    private fun findArtistImage(artist: ArtistInfo) {
        if (!artist.imageUrl.isNullOrBlank()) return
        lookUpArt(artKey("artist", artist.name)) { searchArtwork.lookup { metadataService.getArtistImage(artist.name) } }
    }

    private fun lookUpArt(key: String, find: suspend () -> String?) {
        if (!artRequested.add(key)) return
        viewModelScope.launch(artJob) {
            val url = try {
                find()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } ?: return@launch
            _searchArt.value = _searchArt.value + (key to url)
        }
    }

    override fun onCleared() {
        artJob.cancel()
        super.onCleared()
    }

    companion object {
        /** [searchArt]'s key for a row: `kind` is track, album or artist. */
        fun artKey(kind: String, name: String, artist: String = ""): String =
            "$kind|${name.lowercase().trim()}|${artist.lowercase().trim()}"
    }
}
