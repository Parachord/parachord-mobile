package com.parachord.android.ui.screens.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.ai.AiRecommendationService
import com.parachord.android.ai.AiRecommendations
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.ChartsRepository
import com.parachord.android.data.repository.CriticalDarlingsRepository
import com.parachord.android.data.repository.FreshDropsRepository
import com.parachord.android.data.repository.FriendsRepository
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.data.repository.RecommendationsRepository
import com.parachord.shared.model.Resource
import com.parachord.android.data.repository.WeeklyPlaylistEntry
import com.parachord.android.data.repository.WeeklyPlaylistsRepository
import com.parachord.android.data.scanner.MediaScanner
import com.parachord.android.data.scanner.ScanProgress
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.resolver.TrackResolverCache
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
data class CollectionStats(
    val tracks: Int = 0,
    val albums: Int = 0,
    val artists: Int = 0,
    val playlists: Int = 0,
    val friends: Int = 0,
)

/** Preview snippet shown on a Discover card. */
data class DiscoverPreview(
    val title: String,
    val subtitle: String,
    val artworkUrl: String? = null,
    val label: String = "",
)

class HomeViewModel constructor(
    private val repository: LibraryRepository,
    private val friendsRepository: FriendsRepository,
    private val playbackController: PlaybackController,
    private val playbackStateHolder: PlaybackStateHolder,
    private val mediaScanner: MediaScanner,
    private val recommendationsRepository: RecommendationsRepository,
    private val criticalDarlingsRepository: CriticalDarlingsRepository,
    private val freshDropsRepository: FreshDropsRepository,
    private val chartsRepository: ChartsRepository,
    private val aiRecommendationService: AiRecommendationService,
    private val weeklyPlaylistsRepository: WeeklyPlaylistsRepository,
    private val settingsStore: SettingsStore,
    private val trackResolverCache: TrackResolverCache,
    private val metadataService: MetadataService,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
) : ViewModel() {

    /** User-configured resolver priority order, used to sort resolver icons on track rows. */
    val resolverOrder: StateFlow<List<String>> = settingsStore.getResolverOrderFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Resolver badge names for UI display — shared across all screens via TrackResolverCache. */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> = trackResolverCache.trackResolverConfidences

    val recentTracks: StateFlow<List<TrackEntity>> = repository.getAllTracks()
        .map { tracks -> tracks.sortedByDescending { it.addedAt }.take(20) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** null = still loading, true/false = resolved. */
    val hasLibrary: StateFlow<Boolean?> = repository.getAllTracks()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recentAlbums: StateFlow<List<AlbumEntity>> = repository.getAllAlbums()
        .map { albums -> albums.sortedByDescending { it.addedAt }.take(8) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentPlaylists: StateFlow<List<PlaylistEntity>> = repository.getAllPlaylists()
        .map { playlists ->
            playlists.sortedByDescending {
                if (it.lastModified > 0L) it.lastModified else it.updatedAt
            }.take(6)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val friendActivity: StateFlow<List<FriendEntity>> = friendsRepository.getAllFriends()
        .map { friends ->
            friends
                .filter { it.cachedTrackName != null }
                .sortedWith(compareByDescending<FriendEntity> { it.isOnAir }
                    .thenByDescending { it.cachedTrackTimestamp })
                .take(5)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val collectionStats: StateFlow<CollectionStats> = combine(
        repository.getAllTracks().map { it.size },
        repository.getAllAlbums().map { it.size },
        repository.getAllArtists().map { it.size },
        repository.getAllPlaylists().map { it.size },
        friendsRepository.getAllFriends().map { it.size },
    ) { tracks, albums, artists, playlists, friends ->
        CollectionStats(tracks, albums, artists, playlists, friends)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionStats())

    val scanProgress: StateFlow<ScanProgress> = mediaScanner.progress

    /** Current playback state for Continue Listening card. */
    val playbackState = playbackStateHolder.state

    // ── Discover previews ───────────────────────────────────────────

    private val _forYouPreview = MutableStateFlow<DiscoverPreview?>(null)
    val forYouPreview: StateFlow<DiscoverPreview?> = _forYouPreview

    private val _criticalDarlingsPreview = MutableStateFlow<DiscoverPreview?>(null)
    val criticalDarlingsPreview: StateFlow<DiscoverPreview?> = _criticalDarlingsPreview

    private val _freshDropsPreview = MutableStateFlow<DiscoverPreview?>(null)
    val freshDropsPreview: StateFlow<DiscoverPreview?> = _freshDropsPreview

    private val _popOfTheTopsPreview = MutableStateFlow<DiscoverPreview?>(null)
    val popOfTheTopsPreview: StateFlow<DiscoverPreview?> = _popOfTheTopsPreview

    // ── AI Shuffleupagus ──────────────────────────────────────────

    private val _hasAiPlugins = MutableStateFlow<Boolean?>(null) // null = checking
    val hasAiPlugins: StateFlow<Boolean?> = _hasAiPlugins

    private val _aiRecommendations = MutableStateFlow<AiRecommendations?>(null)
    val aiRecommendations: StateFlow<AiRecommendations?> = _aiRecommendations

    private val _aiLoading = MutableStateFlow(false)
    val aiLoading: StateFlow<Boolean> = _aiLoading

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError

    // ── Weekly Playlists (ListenBrainz) ─────────────────────────────

    private val _weeklyJams = MutableStateFlow<List<WeeklyPlaylistEntry>?>(null)
    val weeklyJams: StateFlow<List<WeeklyPlaylistEntry>?> = _weeklyJams

    private val _weeklyExploration = MutableStateFlow<List<WeeklyPlaylistEntry>?>(null)
    val weeklyExploration: StateFlow<List<WeeklyPlaylistEntry>?> = _weeklyExploration

    /** Covers for each playlist entry ID → list of up to 4 album art URLs. */
    private val _weeklyCovers = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val weeklyCovers: StateFlow<Map<String, List<String>>> = _weeklyCovers

    /** Track counts per playlist entry ID. */
    private val _weeklyTrackCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val weeklyTrackCounts: StateFlow<Map<String, Int>> = _weeklyTrackCounts

    init {
        loadDiscoverPreviews()
        checkAiPlugins()
        loadWeeklyPlaylists()
        // Run full resolver pipeline against stored tracks in background (concurrent, deduplicated)
        viewModelScope.launch {
            recentTracks.collect { tracks ->
                if (tracks.isNotEmpty()) trackResolverCache.resolveInBackground(tracks)
            }
        }
    }

    private fun checkAiPlugins() {
        viewModelScope.launch {
            val hasPlugins = aiRecommendationService.hasEnabledProvider()
            _hasAiPlugins.value = hasPlugins
            if (hasPlugins) {
                loadAiRecommendations()
            }
        }
    }

    private fun loadAiRecommendations() {
        viewModelScope.launch {
            // Stale-while-revalidate, like desktop (which refetches on every Home
            // visit) and iOS: show the cached suggestions immediately, then always
            // fetch fresh ones behind them. This used to return as soon as a cache
            // existed, so the suggestions never changed after the first run unless
            // the user tapped refresh.
            aiRecommendationService.getCachedRecommendations()?.let { cached ->
                _aiRecommendations.value = cached
            }
            _aiLoading.value = true
            _aiError.value = null
            try {
                val recs = aiRecommendationService.loadRecommendations()
                _aiRecommendations.value = recs
                _aiError.value = null
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                _aiError.value = msg
                android.util.Log.e("HomeViewModel", "AI recommendations failed", e)
            }
            _aiLoading.value = false
        }
    }

    fun refreshAiRecommendations() {
        loadAiRecommendations()
    }

    private fun loadDiscoverPreviews() {
        // For You — top recommended artist
        viewModelScope.launch {
            recommendationsRepository.getRecommendedArtists().collect { resource ->
                if (resource is Resource.Success) {
                    resource.data.firstOrNull()?.let { artist ->
                        _forYouPreview.value = DiscoverPreview(
                            title = artist.name,
                            subtitle = artist.reason ?: "Based on your listening",
                            artworkUrl = artist.imageUrl,
                            label = "Top pick",
                        )
                    }
                }
            }
        }
        // Critical Darlings — latest reviewed album
        viewModelScope.launch {
            criticalDarlingsRepository.getCriticsPicks().collect { resource ->
                if (resource is Resource.Success) {
                    resource.data.firstOrNull()?.let { album ->
                        _criticalDarlingsPreview.value = DiscoverPreview(
                            title = album.title,
                            subtitle = album.artist,
                            artworkUrl = album.albumArt,
                            label = "Latest review",
                        )
                    }
                }
            }
        }
        // Fresh Drops — latest new release
        viewModelScope.launch {
            freshDropsRepository.getFreshDrops().collect { resource ->
                if (resource is Resource.Success) {
                    resource.data.firstOrNull()?.let { release ->
                        _freshDropsPreview.value = DiscoverPreview(
                            title = release.title,
                            subtitle = release.artist,
                            artworkUrl = release.albumArt,
                            label = "Latest",
                        )
                    }
                }
            }
        }
        // Pop of the Tops — top charting album
        viewModelScope.launch {
            try {
                val albums = chartsRepository.getAppleMusicAlbums("us")
                albums.firstOrNull()?.let { album ->
                    _popOfTheTopsPreview.value = DiscoverPreview(
                        title = album.title,
                        subtitle = album.artist,
                        artworkUrl = album.artworkUrl,
                        label = "#1 Album",
                    )
                }
            } catch (_: Exception) { /* skip preview */ }
        }
    }

    private fun loadWeeklyPlaylists() {
        viewModelScope.launch {
            val result = weeklyPlaylistsRepository.loadWeeklyPlaylists(forceRefresh = true) ?: return@launch
            _weeklyJams.value = result.jams
            _weeklyExploration.value = result.exploration

            // Load covers and track counts for all entries in parallel
            val allEntries = (result.jams.orEmpty()) + (result.exploration.orEmpty())
            if (allEntries.isEmpty()) return@launch

            // Load covers and track counts concurrently per entry
            allEntries.forEach { entry ->
                launch {
                    val tracks = weeklyPlaylistsRepository.loadPlaylistTracks(entry.id)
                    _weeklyTrackCounts.value = _weeklyTrackCounts.value + (entry.id to tracks.size)
                    val covers = tracks.mapNotNull { it.albumArt }.distinct().take(4)
                    if (covers.isNotEmpty()) {
                        _weeklyCovers.value = _weeklyCovers.value + (entry.id to covers)
                    }
                }
            }
        }
    }

    /**
     * Play all tracks from a weekly playlist entry.
     */
    fun playWeeklyPlaylist(entry: WeeklyPlaylistEntry, contextType: String) {
        viewModelScope.launch {
            val tracks = weeklyPlaylistsRepository.loadPlaylistTracks(entry.id)
            if (tracks.isEmpty()) return@launch
            val trackEntities = tracks.map { lbTrack ->
                com.parachord.android.data.db.entity.TrackEntity(
                    id = lbTrack.id,
                    title = lbTrack.title,
                    artist = lbTrack.artist,
                    album = lbTrack.album ?: "",
                    duration = lbTrack.durationMs ?: 0, // Track.duration is ms (#349)
                    artworkUrl = lbTrack.albumArt,
                )
            }
            playbackController.playQueue(
                tracks = trackEntities,
                context = com.parachord.android.playback.PlaybackContext(
                    type = contextType,
                    name = "${entry.weekLabel}'s ${if (contextType == "weekly-jam") "Weekly Jams" else "Weekly Exploration"}",
                ),
            )
        }
    }

    fun togglePlayPause() {
        playbackController.togglePlayPause()
    }

    fun playTrack(track: TrackEntity) {
        val tracks = recentTracks.value
        val index = tracks.indexOf(track).coerceAtLeast(0)
        playbackController.playQueue(tracks, startIndex = index)
    }

    fun scanLocalMusic() {
        viewModelScope.launch {
            mediaScanner.scan()
        }
    }

    val playlists: StateFlow<List<PlaylistEntity>> =
        repository.getAllPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /**
     * Remove a track from the user's collection. Wired into the Recent
     * Loves long-press context menu — without this, the toggle item in
     * the menu rendered ("Remove from collection") and the toast fired
     * unconditionally, but the action itself was a silent no-op because
     * `TrackContextMenuHost`'s `onToggleCollection` parameter wasn't
     * passed at the call site. Same `deleteTrackWithSync` plumbing as
     * `LibraryViewModel.removeTrackFromCollection` so multi-provider
     * sync removal still propagates.
     */
    fun removeTrackFromCollection(track: TrackEntity) {
        viewModelScope.launch {
            repository.deleteTrackWithSync(track)
        }
    }

    /**
     * One-shot toast surface for Home — currently fed by
     * [deletePlaylist] when an Apple Music mirror returns
     * `DeleteResult.Unsupported` (Decision D8). The screen collects this
     * as `LaunchedEffect` and forwards to a Toast, mirroring the
     * `PlaylistsViewModel` pattern.
     */
    private val _toastEvents = Channel<String>(Channel.BUFFERED)
    val toastEvents: Flow<String> = _toastEvents.receiveAsFlow()

    /**
     * Play all tracks in a saved playlist. Mirrors
     * [PlaylistsViewModel.playPlaylist] so the Home → Your Playlists
     * carousel's long-press menu can fire playback without forcing
     * the user to first navigate into the playlist detail screen.
     */
    fun playPlaylist(playlistId: String, playlistName: String) {
        viewModelScope.launch {
            val tracks = repository.getPlaylistTracks(playlistId).first()
            if (tracks.isEmpty()) return@launch
            val entities = tracks.map { repository.playlistTrackToTrackEntity(it) }
            playbackController.playQueue(
                entities,
                startIndex = 0,
                context = PlaybackContext(type = "playlist", name = playlistName),
            )
        }
    }

    /**
     * Sync-aware delete from the Home → Your Playlists long-press menu.
     * Routes through `deletePlaylistWithSync` so any linked Spotify /
     * Apple Music mirrors get the deletion attempt too. AM returns 401
     * for `DELETE /me/library/playlists/{id}` — surface that to the
     * user via the toast channel rather than swallowing it (Decision
     * D8 — same pattern as PlaylistsViewModel/PlaylistDetailViewModel).
     */
    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            val attempts = repository.deletePlaylistWithSync(playlist)
            val unsupported = attempts.filter {
                it.result is com.parachord.shared.sync.DeleteResult.Unsupported
            }
            if (unsupported.isNotEmpty()) {
                val names = unsupported.joinToString(", ") { it.providerDisplayName }
                _toastEvents.trySend(
                    "Removed from Parachord. $names doesn't allow deletion via the API — " +
                        "remove manually in the $names app."
                )
            }
        }
    }

    /**
     * Save an ephemeral ListenBrainz weekly playlist to the local
     * library. Mirrors `WeeklyPlaylistViewModel.saveToLibrary` so the
     * Home Weekly Jams / Exploration carousel's long-press menu can
     * save a playlist directly without first opening the detail screen.
     * Quietly no-ops when the entry has no resolved tracks yet (the
     * card hasn't finished progressive enrichment) — saving an empty
     * playlist would create a useless library row.
     */
    fun saveWeeklyPlaylist(entry: WeeklyPlaylistEntry, contextType: String) {
        viewModelScope.launch {
            val tracks = weeklyPlaylistsRepository.loadPlaylistTracks(entry.id)
            if (tracks.isEmpty()) {
                _toastEvents.trySend("Couldn't save — no tracks loaded yet")
                return@launch
            }
            val trackEntities = tracks.map { lbTrack ->
                TrackEntity(
                    id = lbTrack.id,
                    title = lbTrack.title,
                    artist = lbTrack.artist,
                    album = lbTrack.album ?: "",
                    duration = lbTrack.durationMs ?: 0, // Track.duration is ms (#349)
                    artworkUrl = lbTrack.albumArt,
                )
            }
            val savedPlaylistId = "listenbrainz-${entry.id}"
            val playlistName = "${entry.weekLabel}'s ${
                if (contextType == "weekly-jam") "Weekly Jams" else "Weekly Exploration"
            }"
            val playlist = PlaylistEntity(
                id = savedPlaylistId,
                name = playlistName,
                description = "",
                trackCount = trackEntities.size,
                artworkUrl = trackEntities.firstNotNullOfOrNull { it.artworkUrl },
                ownerName = "ListenBrainz",
            )
            repository.createPlaylistWithTracks(playlist, trackEntities)
            _toastEvents.trySend("Saved \"$playlistName\" to library")
        }
    }

    fun togglePin(friend: FriendEntity) {
        viewModelScope.launch {
            friendsRepository.pinFriend(friend.id, !friend.pinnedToSidebar)
        }
    }

    // ── Artist actions ─────────────────────────────────────────────

    fun playArtistTopSongs(artistName: String) {
        viewModelScope.launch {
            try {
                val topTracks = metadataService.getArtistTopTracks(artistName, limit = 10)
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
                        title = track.title, artist = track.artist, album = track.album,
                        duration = track.duration, artworkUrl = track.artworkUrl,
                        sourceType = best.sourceType, sourceUrl = best.url, resolver = best.resolver,
                        spotifyUri = best.spotifyUri, soundcloudId = best.soundcloudId, appleMusicId = best.appleMusicId,
                    )
                }
                if (entities.isNotEmpty()) {
                    playbackController.playQueue(
                        entities, startIndex = 0,
                        context = PlaybackContext(type = "artist", name = artistName),
                    )
                }
            } catch (e: Exception) {
                Log.e("HomeVM", "Failed to play top songs for '$artistName'", e)
            }
        }
    }

    fun queueArtistTopSongs(artistName: String) {
        viewModelScope.launch {
            try {
                val topTracks = metadataService.getArtistTopTracks(artistName, limit = 10)
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
                        title = track.title, artist = track.artist, album = track.album,
                        duration = track.duration, artworkUrl = track.artworkUrl,
                        sourceType = best.sourceType, sourceUrl = best.url, resolver = best.resolver,
                        spotifyUri = best.spotifyUri, soundcloudId = best.soundcloudId, appleMusicId = best.appleMusicId,
                    )
                }
                if (entities.isNotEmpty()) playbackController.addToQueue(entities)
            } catch (e: Exception) {
                Log.e("HomeVM", "Failed to queue top songs for '$artistName'", e)
            }
        }
    }

    fun toggleArtistCollection(artistName: String, imageUrl: String?, isInCollection: Boolean) {
        viewModelScope.launch {
            if (isInCollection) {
                repository.deleteArtistByName(artistName)
            } else {
                repository.addArtist(
                    ArtistEntity(
                        id = "manual-${java.util.UUID.randomUUID()}",
                        name = artistName,
                        imageUrl = imageUrl,
                    )
                )
            }
        }
    }

    // ── Album actions ────────────────────────────────────────────────

    fun addAlbumToCollection(title: String, artist: String, artworkUrl: String?) {
        viewModelScope.launch {
            val album = AlbumEntity(
                id = "album-${title.hashCode()}-${artist.hashCode()}",
                title = title,
                artist = artist,
                artworkUrl = artworkUrl,
            )
            repository.addAlbum(album)
        }
    }

    fun removeAlbumFromCollection(title: String, artist: String) {
        viewModelScope.launch {
            val existing = repository.getAlbumByTitleAndArtist(title, artist)
            if (existing != null) {
                repository.deleteAlbumWithSync(existing)
            }
        }
    }

    /** Fetch an album's tracklist from metadata providers, resolve each track, and queue. */
    fun queueAlbumByName(albumTitle: String, artistName: String) {
        viewModelScope.launch {
            try {
                val detail = metadataService.getAlbumTracks(albumTitle, artistName)
                if (detail == null || detail.tracks.isEmpty()) {
                    Log.w("HomeVM", "No tracks found for album '$albumTitle' by '$artistName'")
                    return@launch
                }
                val entities = detail.tracks.mapNotNull { track ->
                    val sources = resolverManager.resolveWithHints(
                        query = "${track.artist} - ${track.title}",
                        spotifyId = track.spotifyId,
                        targetTitle = track.title,
                        targetArtist = track.artist,
                    )
                    val best = resolverScoring.selectBest(sources) ?: return@mapNotNull null
                    TrackEntity(
                        id = "resolved-${track.title.hashCode()}-${track.artist.hashCode()}-${albumTitle.hashCode()}",
                        title = track.title,
                        artist = track.artist,
                        album = albumTitle,
                        duration = track.duration,
                        artworkUrl = track.artworkUrl ?: detail.artworkUrl,
                        sourceType = best.sourceType,
                        sourceUrl = best.url,
                        resolver = best.resolver,
                        spotifyUri = best.spotifyUri,
                        soundcloudId = best.soundcloudId,
                        appleMusicId = best.appleMusicId,
                    )
                }
                if (entities.isNotEmpty()) {
                    playbackController.addToQueue(entities)
                }
            } catch (e: Exception) {
                Log.e("HomeVM", "Failed to queue album '$albumTitle'", e)
            }
        }
    }

}
