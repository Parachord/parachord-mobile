package com.parachord.android.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import android.util.Log
import com.parachord.android.data.metadata.ImageEnrichmentService
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.resolver.TrackResolverCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
class LibraryViewModel constructor(
    private val repository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val imageEnrichmentService: ImageEnrichmentService,
    private val metadataService: MetadataService,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val settingsStore: SettingsStore,
    private val trackResolverCache: TrackResolverCache,
) : ViewModel() {

    companion object {
        private const val TAG = "LibraryViewModel"
    }

    // Track which items have been enriched this session to avoid re-triggering
    private val enrichedArtists = mutableSetOf<String>()
    private val enrichedAlbums = mutableSetOf<String>()

    /** Resolver badge names for UI display — shared across all screens via TrackResolverCache. */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> = trackResolverCache.trackResolverConfidences

    val tracks: StateFlow<List<TrackEntity>> = repository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<ArtistEntity>> = repository.getAllArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<AlbumEntity>> = repository.getAllAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> = repository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** User-configured resolver priority order, used to sort resolver icons on track rows. */
    val resolverOrder: StateFlow<List<String>> = settingsStore.getResolverOrderFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether ListenBrainz is authorized (token present). Gates the LB row in
     * the sync-provider picker — LB auth lives in Settings → Scrobblers, not
     * the sync wizard, so offering it before a token is set would dead-end.
     */
    val listenBrainzConnected: StateFlow<Boolean> = settingsStore.getListenBrainzTokenFlow()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // --- Sort state per tab ---

    private val _artistSort = MutableStateFlow(ArtistSort.ALPHA_ASC)
    val artistSort: StateFlow<ArtistSort> = _artistSort

    private val _albumSort = MutableStateFlow(AlbumSort.RECENT)
    val albumSort: StateFlow<AlbumSort> = _albumSort

    private val _trackSort = MutableStateFlow(TrackSort.RECENT)
    val trackSort: StateFlow<TrackSort> = _trackSort

    private val _friendSort = MutableStateFlow(FriendSort.ALPHA_ASC)
    val friendSort: StateFlow<FriendSort> = _friendSort

    // --- Search query (shared across tabs) ---

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    init {
        viewModelScope.launch {
            settingsStore.getSortArtists()?.let { name ->
                runCatching { ArtistSort.valueOf(name) }.getOrNull()?.let { _artistSort.value = it }
            }
            settingsStore.getSortAlbums()?.let { name ->
                runCatching { AlbumSort.valueOf(name) }.getOrNull()?.let { _albumSort.value = it }
            }
            settingsStore.getSortTracks()?.let { name ->
                runCatching { TrackSort.valueOf(name) }.getOrNull()?.let { _trackSort.value = it }
            }
            settingsStore.getSortFriends()?.let { name ->
                runCatching { FriendSort.valueOf(name) }.getOrNull()?.let { _friendSort.value = it }
            }
        }
        // Run full resolver pipeline against stored tracks in background (concurrent, deduplicated)
        viewModelScope.launch {
            tracks.collect { trackList ->
                if (trackList.isNotEmpty()) trackResolverCache.resolveInBackground(trackList)
            }
        }
    }

    fun setArtistSort(sort: ArtistSort) {
        _artistSort.value = sort
        viewModelScope.launch { settingsStore.setSortArtists(sort.name) }
    }
    fun setAlbumSort(sort: AlbumSort) {
        _albumSort.value = sort
        viewModelScope.launch { settingsStore.setSortAlbums(sort.name) }
    }
    fun setTrackSort(sort: TrackSort) {
        _trackSort.value = sort
        viewModelScope.launch { settingsStore.setSortTracks(sort.name) }
    }
    fun setFriendSort(sort: FriendSort) {
        _friendSort.value = sort
        viewModelScope.launch { settingsStore.setSortFriends(sort.name) }
    }
    fun setSearchQuery(query: String) { _searchQuery.value = query }

    // --- Sorted + filtered followed artists (ArtistEntity only) ---

    val sortedArtists: StateFlow<List<ArtistEntity>> = combine(
        artists, _artistSort, _searchQuery,
    ) { list, sort, query ->
        val filtered = if (query.isBlank()) list else {
            list.filter { it.name.contains(query, ignoreCase = true) }
        }
        when (sort) {
            ArtistSort.ALPHA_ASC -> filtered.sortedBy { it.name.lowercase() }
            ArtistSort.ALPHA_DESC -> filtered.sortedByDescending { it.name.lowercase() }
            ArtistSort.RECENT -> filtered.sortedByDescending { it.addedAt }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Sorted + filtered albums ---

    val sortedAlbums: StateFlow<List<AlbumEntity>> = combine(
        albums, _albumSort, _searchQuery,
    ) { list, sort, query ->
        val filtered = if (query.isBlank()) list else {
            list.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true)
            }
        }
        when (sort) {
            AlbumSort.ALPHA_ASC -> filtered.sortedBy { it.title.lowercase() }
            AlbumSort.ALPHA_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            AlbumSort.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
            AlbumSort.RECENT -> filtered.sortedByDescending { it.addedAt }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Sorted + filtered tracks ---

    val sortedTracks: StateFlow<List<TrackEntity>> = combine(
        tracks, _trackSort, _searchQuery,
    ) { list, sort, query ->
        val filtered = if (query.isBlank()) list else {
            list.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true) ||
                    (it.album?.contains(query, ignoreCase = true) == true)
            }
        }
        when (sort) {
            TrackSort.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
            TrackSort.TITLE_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            TrackSort.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
            TrackSort.ALBUM -> filtered.sortedBy { (it.album ?: "").lowercase() }
            TrackSort.DURATION -> filtered.sortedByDescending { it.duration ?: 0L }
            TrackSort.RECENT -> filtered.sortedByDescending { it.addedAt }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Lazily fetch an artist image if not already enriched this session. */
    fun enrichArtistImageIfNeeded(artistName: String) {
        if (artistName in enrichedArtists) return
        enrichedArtists.add(artistName)
        viewModelScope.launch {
            imageEnrichmentService.enrichArtistImage(artistName)
        }
    }

    /** Lazily fetch album artwork if not already enriched this session. */
    fun enrichAlbumArtIfNeeded(albumTitle: String, artistName: String) {
        val key = "$albumTitle|$artistName"
        if (key in enrichedAlbums) return
        enrichedAlbums.add(key)
        viewModelScope.launch {
            imageEnrichmentService.enrichAlbumArt(albumTitle, artistName)
        }
    }

    fun playTrack(track: TrackEntity) {
        val allTracks = tracks.value
        val index = allTracks.indexOf(track).coerceAtLeast(0)
        playbackController.playQueue(allTracks, startIndex = index)
    }

    // ── Context menu actions ─────────────────────────────────────────

    fun playNext(track: TrackEntity) {
        playbackController.insertNext(listOf(track))
    }

    fun addToQueue(track: TrackEntity) {
        playbackController.addToQueue(listOf(track))
    }

    fun addToPlaylist(playlist: PlaylistEntity, track: TrackEntity) {
        viewModelScope.launch {
            repository.addTracksToPlaylist(playlist.id, listOf(track))
        }
    }

    fun removeTrackFromCollection(track: TrackEntity) {
        viewModelScope.launch {
            repository.deleteTrackWithSync(track)
        }
    }

    fun removeAlbumFromCollection(album: AlbumEntity) {
        viewModelScope.launch {
            repository.deleteAlbumWithSync(album)
        }
    }

    fun removeArtistFromCollection(artist: ArtistEntity) {
        viewModelScope.launch {
            repository.deleteArtistWithSync(artist)
        }
    }

    /** Play all tracks from an album in the collection. */
    fun playAlbum(albumId: String, albumTitle: String) {
        viewModelScope.launch {
            try {
                val albumTracks = repository.getAlbumTracks(albumId).first()
                if (albumTracks.isEmpty()) return@launch
                playbackController.playQueue(
                    albumTracks,
                    startIndex = 0,
                    context = PlaybackContext(type = "album", name = albumTitle),
                )
            } catch (e: Exception) {
                Log.e("LibraryVM", "Failed to play album '$albumTitle'", e)
            }
        }
    }

    /** Add all tracks from an album to the queue without interrupting playback. */
    fun queueAlbum(albumId: String) {
        viewModelScope.launch {
            try {
                val albumTracks = repository.getAlbumTracks(albumId).first()
                if (albumTracks.isEmpty()) return@launch
                playbackController.addToQueue(albumTracks)
            } catch (e: Exception) {
                Log.e("LibraryVM", "Failed to queue album", e)
            }
        }
    }

    /** Fetch an artist's top tracks from metadata providers, resolve, and play. */
    fun playArtistTopSongs(artistName: String) {
        viewModelScope.launch {
            try {
                Log.d("LibraryVM", "Play top songs: fetching for '$artistName'")
                val topTracks = metadataService.getArtistTopTracks(artistName, limit = 10)
                Log.d("LibraryVM", "Play top songs: got ${topTracks.size} tracks")
                if (topTracks.isEmpty()) return@launch
                val entities = topTracks.mapNotNull { track ->
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.artist} - ${track.title}",
                        spotifyId = track.spotifyId,
                        targetTitle = track.title,
                        targetArtist = track.artist,
                    )
                    val best = resolverScoring.selectBest(sources) ?: return@mapNotNull null
                    TrackEntity(
                        id = "top-${track.title.hashCode()}-${track.artist.hashCode()}",
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        duration = track.duration,
                        artworkUrl = track.artworkUrl,
                        sourceType = best.sourceType,
                        sourceUrl = best.url,
                        resolver = best.resolver,
                        spotifyUri = best.spotifyUri,
                        soundcloudId = best.soundcloudId,
                        appleMusicId = best.appleMusicId,
                    )
                }
                if (entities.isNotEmpty()) {
                    playbackController.playQueue(
                        entities,
                        startIndex = 0,
                        context = PlaybackContext(type = "artist", name = artistName),
                    )
                }
            } catch (e: Exception) {
                Log.e("LibraryVM", "Failed to play top songs for '$artistName'", e)
            }
        }
    }

    /** Fetch an artist's top tracks, resolve, and add to queue without interrupting playback. */
    fun queueArtistTopSongs(artistName: String) {
        viewModelScope.launch {
            try {
                Log.d("LibraryVM", "Queue top songs: fetching for '$artistName'")
                val topTracks = metadataService.getArtistTopTracks(artistName, limit = 10)
                Log.d("LibraryVM", "Queue top songs: got ${topTracks.size} tracks")
                if (topTracks.isEmpty()) return@launch
                val entities = topTracks.mapNotNull { track ->
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.artist} - ${track.title}",
                        spotifyId = track.spotifyId,
                        targetTitle = track.title,
                        targetArtist = track.artist,
                    )
                    Log.d("LibraryVM", "Queue top songs: resolved '${track.title}' → ${sources.size} sources")
                    val best = resolverScoring.selectBest(sources) ?: return@mapNotNull null
                    TrackEntity(
                        id = "top-${track.title.hashCode()}-${track.artist.hashCode()}",
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        duration = track.duration,
                        artworkUrl = track.artworkUrl,
                        sourceType = best.sourceType,
                        sourceUrl = best.url,
                        resolver = best.resolver,
                        spotifyUri = best.spotifyUri,
                        soundcloudId = best.soundcloudId,
                        appleMusicId = best.appleMusicId,
                    )
                }
                Log.d("LibraryVM", "Queue top songs: resolved ${entities.size}/${topTracks.size} tracks")
                if (entities.isNotEmpty()) {
                    playbackController.addToQueue(entities)
                }
            } catch (e: Exception) {
                Log.e("LibraryVM", "Failed to queue top songs for '$artistName'", e)
            }
        }
    }
}
