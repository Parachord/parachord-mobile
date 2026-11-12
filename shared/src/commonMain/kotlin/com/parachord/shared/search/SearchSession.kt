package com.parachord.shared.search

import com.parachord.shared.metadata.AlbumSearchResult
import com.parachord.shared.metadata.ArtistInfo
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.metadata.SearchArtistList
import com.parachord.shared.metadata.SearchArtwork
import com.parachord.shared.metadata.SearchRanking
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.model.Album
import com.parachord.shared.model.Track
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/** What Search asks the catalogs: the three categories, each progressively, and an artist's photo. */
interface SearchCatalog {
    fun tracks(query: String): Flow<List<TrackSearchResult>>
    fun albums(query: String): Flow<List<AlbumSearchResult>>
    fun artists(query: String): Flow<List<ArtistInfo>>
    suspend fun artistImage(name: String): String?
}

/** [MetadataService]'s progressive searches (#475) and its artist-photo chain. */
fun MetadataService.asSearchCatalog(): SearchCatalog = object : SearchCatalog {
    override fun tracks(query: String) = searchTracksProgressively(query, SearchSession.TRACK_LIMIT)
    override fun albums(query: String) = searchAlbumsProgressively(query, SearchSession.ALBUM_LIMIT)
    override fun artists(query: String) = searchArtistsProgressively(query, SearchSession.ARTIST_LIMIT)
    override suspend fun artistImage(name: String): String? = getArtistImage(name)
}

/** One moment of the Search screen. */
data class SearchState(
    /** The trimmed query these results are for; empty when nothing is searched. */
    val query: String = "",
    val localTracks: List<Track> = emptyList(),
    val localAlbums: List<Album> = emptyList(),
    val remoteTracks: List<TrackSearchResult> = emptyList(),
    val remoteAlbums: List<AlbumSearchResult> = emptyList(),
    val artists: List<ArtistInfo> = emptyList(),
    /** Covers found for rows that came without a usable one, keyed by [SearchSession.artKey]. */
    val art: Map<String, String> = emptyMap(),
    /** The remote search is running. */
    val isSearching: Boolean = false,
    /** The remote search for [query] has finished (so empty lists mean no results). */
    val hasSearched: Boolean = false,
) {
    val hasAnyResults: Boolean
        get() = localTracks.isNotEmpty() || localAlbums.isNotEmpty() || remoteTracks.isNotEmpty() ||
            remoteAlbums.isNotEmpty() || artists.isNotEmpty()
}

/**
 * The Search screen's rules, for both apps (#590):
 *
 * - A query searches once it is [MIN_QUERY_LENGTH] characters after trimming
 *   (desktop `handleSearchInput`); below that the results clear and the screen
 *   shows recent searches.
 * - It searches [DEBOUNCE_MS] after the last keystroke. A new keystroke cancels
 *   the running search at once, so its MusicBrainz requests leave the 1 req/s
 *   queue instead of holding up the query the user wants (#475). Results on
 *   screen stay until the new search replaces them.
 * - Local tracks and local albums come from [localTracks] / [localAlbums] (Flows,
 *   so they follow library changes); remote tracks, albums and artists come
 *   from [catalog], each shown as its providers answer and re-ranked every time
 *   it grows ([SearchRanking]); the Artists list is MusicBrainz's plus the
 *   artists the services' rows name ([SearchArtistList]).
 * - The first [SearchArtwork.LIMIT] rows of each remote category that came
 *   without a usable cover get one looked up ([SearchArtwork]), and so does any
 *   other row once it's on screen ([trackShown], [albumShown], [artistShown]);
 *   a row keeps the first cover found. A new query drops the old query's lookups.
 *
 * Resolving the result rows stays with each app: Android resolves the remote
 * tracks once their search finishes ([onRemoteTracksSettled]); iOS resolves
 * the rows on screen.
 */
class SearchSession(
    private val scope: CoroutineScope,
    private val catalog: SearchCatalog,
    private val artwork: SearchArtwork,
    private val localTracks: (query: String) -> Flow<List<Track>>,
    private val localAlbums: (query: String) -> Flow<List<Album>>,
    /**
     * The cover of the source a track resolved to, waiting for it if need be;
     * a track row's last resort. Null where the app shows that cover itself.
     */
    private val resolvedTrackArt: (suspend (title: String, artist: String) -> String?)? = null,
    /** The ranked remote tracks once their search has finished. */
    private val onRemoteTracksSettled: (List<TrackSearchResult>) -> Unit = {},
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var searched = ""

    /** Bumped for each query; an update from an older one is dropped. */
    @Volatile
    private var generation = 0

    /** Hand in the search field's text on every change. */
    fun query(text: String) {
        val q = text.trim()
        if (q == searched) return
        searched = q
        val gen = ++generation
        searchJob?.cancel()
        lookups?.job?.cancel()
        lookups = null
        if (!isSearchable(q)) {
            searchJob = null
            _state.value = SearchState()
            return
        }
        searchJob = scope.launch {
            delay(DEBOUNCE_MS)
            run(q, gen)
        }
    }

    /** Stop everything (the screen is gone). */
    fun close() {
        generation++
        searchJob?.cancel()
        searchJob = null
        lookups?.job?.cancel()
        lookups = null
    }

    /**
     * A result row came on screen: look up its cover if it has none, as for
     * the first [SearchArtwork.LIMIT] rows. Each row is looked up once per
     * query. Both apps call these from each row (iOS `.onAppear`, Android a
     * `LaunchedEffect`), so a row further down still gets its art.
     */
    fun trackShown(track: TrackSearchResult) { lookups?.findTrackArt(track) }

    fun albumShown(album: AlbumSearchResult) { lookups?.findAlbumArt(album) }

    fun artistShown(artist: ArtistInfo) { lookups?.findArtistArt(artist) }

    /** [artistShown] for a row that carries only the artist's name and photo (iOS's rows). */
    fun artistShown(name: String, imageUrl: String?) = artistShown(ArtistInfo(name = name, imageUrl = imageUrl))

    @Volatile
    private var lookups: Lookups? = null

    /** The current query's cover lookups; a new query cancels them ([job]). */
    private inner class Lookups(private val gen: Int) {
        val job = SupervisorJob()
        private val mutex = Mutex()
        private val requested = mutableSetOf<String>()

        private fun lookUpArt(key: String, find: suspend () -> String?) {
            scope.launch(job) {
                if (!mutex.withLock { requested.add(key) }) return@launch
                val url = try {
                    find()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                if (!url.isNullOrBlank()) {
                    ifCurrent(gen) { s -> if (key in s.art) s else s.copy(art = s.art + (key to url)) }
                }
            }
        }

        fun findTrackArt(track: TrackSearchResult) {
            if (!track.artworkUrl.isNullOrBlank()) return
            val toCheck = track.artworkCandidates.filter(SearchArtwork::needsCheck)
            val service = track.artworkCandidates.firstOrNull { !SearchArtwork.needsCheck(it) }
            lookUpArt(trackArtKey(track.title, track.artist)) {
                artwork.settle(toCheck) { service ?: resolvedTrackArt?.invoke(track.title, track.artist) }
            }
        }

        fun findAlbumArt(album: AlbumSearchResult) {
            val all = listOfNotNull(album.artworkUrl) + album.artworkCandidates
            // A provider's own cover shows as it is.
            if (all.isEmpty() || !SearchArtwork.needsCheck(all.first())) return
            lookUpArt(albumArtKey(album.title, album.artist)) {
                artwork.settle(all.filter(SearchArtwork::needsCheck)) { all.firstOrNull { !SearchArtwork.needsCheck(it) } }
            }
        }

        fun findArtistArt(artist: ArtistInfo) {
            if (!artist.imageUrl.isNullOrBlank()) return
            lookUpArt(artistArtKey(artist.name)) { artwork.lookup { catalog.artistImage(artist.name) } }
        }
    }

    private inline fun ifCurrent(gen: Int, change: (SearchState) -> SearchState) {
        if (gen == generation) _state.update { if (gen == generation) change(it) else it }
    }

    private suspend fun run(q: String, gen: Int) = coroutineScope {
        ifCurrent(gen) { it.copy(query = q, art = emptyMap(), isSearching = true, hasSearched = false) }
        val art = Lookups(gen)
        lookups?.job?.cancel()
        lookups = art

        fun showArtists(ranked: List<ArtistInfo>) {
            ifCurrent(gen) { it.copy(artists = ranked) }
            ranked.take(SearchArtwork.LIMIT).forEach(art::findArtistArt)
        }

        // Library rows follow library changes until the query changes.
        launch {
            try {
                localTracks(q).collect { rows -> ifCurrent(gen) { it.copy(localTracks = rows) } }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        launch {
            try {
                localAlbums(q).collect { rows -> ifCurrent(gen) { it.copy(localAlbums = rows) } }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }

        // MusicBrainz's artists plus those the services' rows name (desktop).
        val artistList = SearchArtistList(q)
        try {
            coroutineScope {
                launch {
                    var ranked = emptyList<TrackSearchResult>()
                    catalog.tracks(q).collect { rows ->
                        ranked = SearchRanking.rankTracks(q, rows)
                        ifCurrent(gen) { it.copy(remoteTracks = ranked) }
                        ranked.take(SearchArtwork.LIMIT).forEach(art::findTrackArt)
                        showArtists(artistList.withTracks(ranked))
                    }
                    if (gen == generation) onRemoteTracksSettled(ranked)
                }
                launch {
                    catalog.albums(q).collect { rows ->
                        val ranked = SearchRanking.rankAlbums(q, rows)
                        ifCurrent(gen) { it.copy(remoteAlbums = ranked) }
                        ranked.take(SearchArtwork.LIMIT).forEach(art::findAlbumArt)
                        showArtists(artistList.withAlbums(ranked))
                    }
                }
                launch {
                    catalog.artists(q).collect { rows -> showArtists(artistList.withMusicBrainz(rows)) }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // A failed remote search leaves what arrived; local results still show.
        }
        ifCurrent(gen) { it.copy(isSearching = false, hasSearched = true) }
    }

    companion object {
        /** Characters (after trimming) a query needs before it searches (desktop `value.length >= 2`). */
        const val MIN_QUERY_LENGTH = 2

        /** The pause after the last keystroke before a query searches. */
        const val DEBOUNCE_MS = 300L

        const val TRACK_LIMIT = 20
        const val ALBUM_LIMIT = 10
        const val ARTIST_LIMIT = 10

        /** Whether [text] searches; below this the screen shows recent searches. */
        fun isSearchable(text: String): Boolean = text.trim().length >= MIN_QUERY_LENGTH

        /** [SearchState.art]'s key for a row: [kind] is track, album or artist. */
        fun artKey(kind: String, name: String, artist: String): String =
            "$kind|${name.lowercase().trim()}|${artist.lowercase().trim()}"

        fun trackArtKey(title: String, artist: String): String = artKey("track", title, artist)
        fun albumArtKey(title: String, artist: String): String = artKey("album", title, artist)
        fun artistArtKey(name: String): String = artKey("artist", name, "")
    }
}
