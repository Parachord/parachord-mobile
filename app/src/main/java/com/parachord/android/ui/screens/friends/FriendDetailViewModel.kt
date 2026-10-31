package com.parachord.android.ui.screens.friends

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.MetadataService
import com.parachord.android.data.repository.FriendsRepository
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.shared.model.HistoryAlbum
import com.parachord.shared.model.HistoryArtist
import com.parachord.shared.model.HistoryTrack
import com.parachord.shared.model.RecentTrack
import com.parachord.shared.model.Resource
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.ResolvedSource
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import com.parachord.shared.deeplink.HistoryLinkTab
import com.parachord.shared.deeplink.parseFriendLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
class FriendDetailViewModel constructor(
    savedStateHandle: SavedStateHandle,
    private val friendsRepository: FriendsRepository,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val playbackController: PlaybackController,
    private val trackResolverCache: TrackResolverCache,
    private val metadataService: MetadataService,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "FriendDetailVM"
    }

    private val friendId: String = savedStateHandle["friendId"] ?: ""

    /** A deep link's tab and period (#470); null fields leave the defaults. */
    private val link = parseFriendLink(savedStateHandle["tab"], savedStateHandle["period"])

    /** Pager page to open on: Recent, Top Songs, Top Albums, Top Artists. */
    val initialPage: Int = when (link.tab) {
        HistoryLinkTab.TOP_TRACKS -> 1
        HistoryLinkTab.TOP_ALBUMS -> 2
        HistoryLinkTab.TOP_ARTISTS -> 3
        HistoryLinkTab.RECENT, null -> 0
    }

    private val _friend = MutableStateFlow<FriendEntity?>(null)
    val friend: StateFlow<FriendEntity?> = _friend

    val selectedPeriod = MutableStateFlow(link.period ?: "1month")

    private val _recentTracks = MutableStateFlow<Resource<List<RecentTrack>>>(Resource.Loading)
    val recentTracks: StateFlow<Resource<List<RecentTrack>>> = _recentTracks

    private val _topTracks = MutableStateFlow<Resource<List<HistoryTrack>>>(Resource.Loading)
    val topTracks: StateFlow<Resource<List<HistoryTrack>>> = _topTracks

    private val _topAlbums = MutableStateFlow<Resource<List<HistoryAlbum>>>(Resource.Loading)
    val topAlbums: StateFlow<Resource<List<HistoryAlbum>>> = _topAlbums

    private val _topArtists = MutableStateFlow<Resource<List<HistoryArtist>>>(Resource.Loading)
    val topArtists: StateFlow<Resource<List<HistoryArtist>>> = _topArtists

    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving

    /** Cached resolved sources for tracks, keyed by "title|artist" */
    private val _trackSources = MutableStateFlow<Map<String, List<ResolvedSource>>>(emptyMap())

    /** Resolver badge names for UI display from shared cache */
    val trackResolvers: StateFlow<Map<String, List<String>>> = trackResolverCache.trackResolvers
    val trackResolverConfidences: StateFlow<Map<String, Map<String, Float>>> = trackResolverCache.trackResolverConfidences

    init {
        loadFriend()
    }

    private fun loadFriend() {
        viewModelScope.launch {
            val friend = friendsRepository.getFriendById(friendId) ?: return@launch
            _friend.value = friend
            loadRecentTracks(friend)
            loadTopCharts(friend)
        }
    }

    fun setPeriod(period: String) {
        selectedPeriod.value = period
        val friend = _friend.value ?: return
        loadTopCharts(friend)
    }

    fun refresh() {
        val friend = _friend.value ?: return
        loadRecentTracks(friend)
    }

    fun togglePin() {
        val current = _friend.value ?: return
        viewModelScope.launch {
            val newPinned = !current.pinnedToSidebar
            friendsRepository.pinFriend(current.id, newPinned)
            _friend.value = current.copy(pinnedToSidebar = newPinned, autoPinned = false)
        }
    }

    /**
     * Resolve and play a top track at the given index.
     */
    fun playTopTrack(index: Int) {
        val resource = _topTracks.value
        if (resource !is Resource.Success) return
        val tracks = resource.data
        if (index !in tracks.indices) return

        viewModelScope.launch {
            _isResolving.value = true
            try {
                val track = tracks[index]
                val entity = resolveTrack(track.title, track.artist, track.artworkUrl)
                if (entity == null) {
                    Log.w(TAG, "No playable source for '${track.artist} - ${track.title}'")
                    return@launch
                }

                playbackController.playTrack(entity)
                _isResolving.value = false

                // Queue remaining
                val remaining = tracks.subList(index + 1, tracks.size)
                if (remaining.isNotEmpty()) {
                    val friendName = _friend.value?.displayName ?: "Friend"
                    val entities = remaining.mapNotNull { t ->
                        resolveTrack(t.title, t.artist, t.artworkUrl)
                    }
                    if (entities.isNotEmpty()) {
                        playbackController.addToQueue(entities)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed", e)
            } finally {
                _isResolving.value = false
            }
        }
    }

    /**
     * Resolve and play a recent track at the given index.
     */
    fun playRecentTrack(index: Int) {
        val resource = _recentTracks.value
        if (resource !is Resource.Success) return
        val tracks = resource.data
        if (index !in tracks.indices) return

        viewModelScope.launch {
            _isResolving.value = true
            try {
                val track = tracks[index]
                val entity = resolveTrack(track.title, track.artist, track.artworkUrl, track.album)
                if (entity == null) {
                    Log.w(TAG, "No playable source for '${track.artist} - ${track.title}'")
                    return@launch
                }

                playbackController.playTrack(entity)
                _isResolving.value = false

                val remaining = tracks.subList(index + 1, tracks.size)
                if (remaining.isNotEmpty()) {
                    val entities = remaining.mapNotNull { t ->
                        resolveTrack(t.title, t.artist, t.artworkUrl, t.album)
                    }
                    if (entities.isNotEmpty()) {
                        playbackController.addToQueue(entities)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed", e)
            } finally {
                _isResolving.value = false
            }
        }
    }

    private suspend fun resolveTrack(
        title: String,
        artist: String,
        artworkUrl: String?,
        album: String? = null,
    ): TrackEntity? {
        val key = trackKey(title, artist)
        val sources = _trackSources.value[key]
            ?: resolverManager.resolveWithHints(query = "$artist - $title", targetTitle = title, targetArtist = artist)
        val best = resolverScoring.selectBest(sources) ?: return null

        return TrackEntity(
            id = "friend-${title.hashCode()}-${artist.hashCode()}",
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            sourceType = best.sourceType,
            sourceUrl = best.url,
            resolver = best.resolver,
            spotifyUri = best.spotifyUri,
            soundcloudId = best.soundcloudId,
            appleMusicId = best.appleMusicId,
        )
    }

    private fun resolveTracksInBackground(tracks: List<HistoryTrack>) {
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

    private fun resolveRecentTracksInBackground(tracks: List<RecentTrack>) {
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

    private fun loadRecentTracks(friend: FriendEntity) {
        viewModelScope.launch {
            friendsRepository.getFriendRecentTracks(friend.username, friend.service)
                .collect {
                    _recentTracks.value = it
                    if (it is Resource.Success) resolveRecentTracksInBackground(it.data)
                }
            // The fetch records whether the profile hides its recent listening
            // (desktop parachord#1140); re-read so the Recent tab can say so.
            friendsRepository.getFriendById(friend.id)?.let { _friend.value = it }
        }
    }

    private fun loadTopCharts(friend: FriendEntity) {
        val period = selectedPeriod.value
        viewModelScope.launch {
            friendsRepository.getFriendTopTracks(friend.username, friend.service, period)
                .collect {
                    _topTracks.value = it
                    if (it is Resource.Success) resolveTracksInBackground(it.data)
                }
        }
        viewModelScope.launch {
            friendsRepository.getFriendTopAlbums(friend.username, friend.service, period)
                .collect { _topAlbums.value = it }
        }
        viewModelScope.launch {
            friendsRepository.getFriendTopArtists(friend.username, friend.service, period)
                .collect { _topArtists.value = it }
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
                Log.e(TAG, "Failed to play top songs for '$artistName'", e)
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
                Log.e(TAG, "Failed to queue top songs for '$artistName'", e)
            }
        }
    }

    fun toggleArtistCollection(artistName: String, imageUrl: String?, isInCollection: Boolean) {
        viewModelScope.launch {
            if (isInCollection) {
                libraryRepository.deleteArtistByName(artistName)
            } else {
                libraryRepository.addArtist(
                    com.parachord.android.data.db.entity.ArtistEntity(
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
            libraryRepository.addAlbum(AlbumEntity(
                id = "album-${title.hashCode()}-${artist.hashCode()}",
                title = title,
                artist = artist,
                artworkUrl = artworkUrl,
            ))
        }
    }

    fun queueAlbumByName(albumTitle: String, albumArtist: String) {
        viewModelScope.launch {
            try {
                val detail = metadataService.getAlbumTracks(albumTitle, albumArtist)
                if (detail == null || detail.tracks.isEmpty()) return@launch
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
                        title = track.title, artist = track.artist, album = albumTitle,
                        duration = track.duration,
                        artworkUrl = track.artworkUrl ?: detail.artworkUrl,
                        sourceType = best.sourceType, sourceUrl = best.url, resolver = best.resolver,
                        spotifyUri = best.spotifyUri, soundcloudId = best.soundcloudId, appleMusicId = best.appleMusicId,
                    )
                }
                if (entities.isNotEmpty()) playbackController.addToQueue(entities)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue album '$albumTitle'", e)
            }
        }
    }

}
