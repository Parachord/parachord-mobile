package com.parachord.android.ui

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.network.NetworkMonitor
import com.parachord.android.data.repository.FriendsRepository
import com.parachord.android.data.repository.ConcertsRepository
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playlist.PlaylistImportManager
import com.parachord.android.playback.PlaybackContext
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.handlers.MusicKitWebBridge
import com.parachord.android.playback.handlers.SpotifyPlaybackHandler
import com.parachord.android.playback.PlaybackState
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.android.playback.effectiveTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
class MainViewModel constructor(
    private val context: Context,
    private val playbackStateHolder: PlaybackStateHolder,
    private val playbackController: PlaybackController,
    private val libraryRepository: LibraryRepository,
    private val friendsRepository: FriendsRepository,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val musicKitBridge: MusicKitWebBridge,
    private val spotifyPlaybackHandler: SpotifyPlaybackHandler,
    private val settingsStore: SettingsStore,
    private val playlistImportManager: PlaylistImportManager,
    private val concertsRepository: ConcertsRepository,
    private val networkMonitor: NetworkMonitor,
    private val pluginManager: com.parachord.android.plugin.PluginManager,
    private val pluginSyncService: com.parachord.android.plugin.PluginSyncService,
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        /** Desktop polls every 2 minutes for normal friend activity. */
        private const val FRIEND_POLL_INTERVAL_MS = 2 * 60 * 1_000L
        /** Desktop polls every 15 seconds during listen-along. */
        private const val LISTEN_ALONG_POLL_MS = 15 * 1_000L
    }

    val playbackState: StateFlow<PlaybackState> = playbackStateHolder.state

    /** Whether the device has an active internet connection. */
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    /** Whether the currently playing track is in the user's collection.
     *  Uses [effectiveTrack] so it reflects the actual streaming metadata. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isCurrentTrackFavorited: StateFlow<Boolean> = playbackStateHolder.state
        .map { it.effectiveTrack }
        .flatMapLatest { track ->
            if (track != null) {
                libraryRepository.isTrackInCollection(track.title, track.artist)
            } else {
                flowOf(false)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val themeMode: StateFlow<String> = settingsStore.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")

    val resolverOrder: StateFlow<List<String>> = settingsStore.getResolverOrderFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Spotify device picker request — observed by the UI to show the picker dialog. */
    val spotifyDevicePickerRequest: StateFlow<SpotifyPlaybackHandler.DevicePickerRequest?> =
        spotifyPlaybackHandler.devicePickerRequest

    /** Whether the currently playing artist is on tour (for mini player dot). */
    private val _isOnTour = MutableStateFlow(false)
    val isOnTour: StateFlow<Boolean> = _isOnTour.asStateFlow()
    private var lastCheckedArtist: String? = null

    /** Pinned friends for the sidebar drawer (on-air auto-pins + manual pins). */
    val friends: StateFlow<List<FriendEntity>> = friendsRepository.getPinnedFriends()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        playbackController.connect()
        playbackController.onTrackEndedListener = { onTrackEnded() }
        playbackController.onUserPlaybackActionListener = { onUserPlaybackAction() }

        // Initialize .axe plugin system and sync updates from marketplace
        viewModelScope.launch {
            try {
                pluginManager.ensureInitialized()
                Log.d(TAG, "Plugin system ready: ${pluginManager.plugins.value.size} plugins loaded")
                // Check for plugin updates (debounced to once per 24h)
                pluginSyncService.syncIfNeeded()
            } catch (e: Exception) {
                Log.w(TAG, "Plugin init/sync failed (non-fatal): ${e.message}")
            }
        }
        // Periodic friend activity polling (mirrors desktop's 2-minute interval)
        viewModelScope.launch {
            // Initial refresh
            try {
                friendsRepository.refreshAllActivity()
            } catch (e: Exception) {
                Log.w(TAG, "Initial friend activity refresh failed", e)
            }
            // Periodic polling
            while (isActive) {
                delay(FRIEND_POLL_INTERVAL_MS)
                try {
                    friendsRepository.refreshAllActivity()
                } catch (e: Exception) {
                    Log.w(TAG, "Friend activity poll failed", e)
                }
            }
        }
        // Check if current artist is on tour near the user's selected area
        // (for mini player dot). Only shows if a concert location is configured.
        viewModelScope.launch {
            playbackState
                .map { it.currentTrack?.artist }
                .distinctUntilChanged()
                .collect { artist ->
                    if (artist.isNullOrBlank() || artist == lastCheckedArtist) return@collect
                    lastCheckedArtist = artist
                    _isOnTour.value = false
                    try {
                        val loc = settingsStore.getConcertLocation()
                        _isOnTour.value = concertsRepository.checkOnTour(
                            artistName = artist,
                            lat = loc.latitude,
                            lon = loc.longitude,
                            radiusMiles = loc.radiusMiles,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "On-tour check failed for '$artist'", e)
                    }
                }
        }
    }

    fun togglePlayPause() {
        playbackController.togglePlayPause()
    }

    fun skipNext() {
        playbackController.skipNext()
    }

    fun onSpotifyDevicePicked(device: com.parachord.shared.api.SpDevice?) {
        spotifyPlaybackHandler.onDevicePicked(device)
    }

    /** Toggle the current track's collection status (heart/favorite).
     *  Uses [effectiveTrack] so it saves/removes the actual streaming track. */
    fun toggleCurrentTrackFavorite() {
        val track = playbackState.value.effectiveTrack ?: return
        viewModelScope.launch {
            if (isCurrentTrackFavorited.value) {
                libraryRepository.deleteTrackWithSync(track)
            } else {
                libraryRepository.addToCollection(track)
            }
        }
    }

    /** Create a new empty playlist. */
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val playlist = PlaylistEntity(
                id = "local-${UUID.randomUUID()}",
                name = name,
            )
            libraryRepository.addPlaylist(playlist)
        }
    }

    // ── Playlist Import ───────────────────────────────────────────

    private val _importLoading = MutableStateFlow(false)
    val importLoading: StateFlow<Boolean> = _importLoading

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError

    fun importPlaylistFromUrl(url: String, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            _importLoading.value = true
            _importError.value = null
            try {
                val result = playlistImportManager.importFromUrl(url)
                _importLoading.value = false
                onSuccess(result.playlistId)
                _toastEvents.emit("Imported '${result.playlistName}' (${result.trackCount} tracks)")
            } catch (e: Exception) {
                _importLoading.value = false
                _importError.value = e.message ?: "Import failed"
            }
        }
    }

    fun importPlaylistFromFile(content: String, filename: String?, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            _importLoading.value = true
            _importError.value = null
            try {
                val result = playlistImportManager.importFromXspfContent(content, filename)
                _importLoading.value = false
                onSuccess(result.playlistId)
                _toastEvents.emit("Imported '${result.playlistName}' (${result.trackCount} tracks)")
            } catch (e: Exception) {
                _importLoading.value = false
                _importError.value = e.message ?: "Import failed"
            }
        }
    }

    fun clearImportError() {
        _importError.value = null
    }

    // ── Listen Along (mirrors desktop's queue + toast logic) ─────

    private var listenAlongJob: Job? = null
    private val _listenAlongFriend = MutableStateFlow<FriendEntity?>(null)
    val listenAlongFriend: StateFlow<FriendEntity?> = _listenAlongFriend

    /** WakeLock to keep listen-along polling active when the screen is off. */
    private val listenAlongWakeLock: PowerManager.WakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Parachord::ListenAlong")
            .apply { setReferenceCounted(false) }
    }

    /** Toast events for the Activity to observe and show. */
    private val _toastEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    val toastEvents = _toastEvents

    /** Navigation events for the Activity (e.g., open Settings for Apple Music sign-in). */
    private val _navigateToSettings = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
    val navigateToSettings = _navigateToSettings

    init {
        // Observe Apple Music sign-in required events
        viewModelScope.launch {
            musicKitBridge.signInRequired.collect {
                _toastEvents.emit("Sign in to Apple Music to play this track")
                _navigateToSettings.emit(Unit)
            }
        }
    }

    /** Track key of last played track to avoid replaying the same one. */
    private var lastListenAlongTrackKey: String? = null

    /** Pending track to play when current finishes (desktop: listenAlongPendingTrackRef). */
    private var pendingListenAlongTrack: TrackEntity? = null

    /** If non-null, stop listen along after current song finishes (desktop: listenAlongEndAfterSongRef). */
    private var deferredStopFriendName: String? = null

    /** True while listen-along itself is driving playback — suppresses auto-stop. */
    private var isListenAlongPlayback = false

    /**
     * Start listening along with a friend.
     * Resolves their current track and plays it, then polls every 15s
     * to follow track changes (mirrors desktop behavior).
     */
    fun startListenAlong(friend: FriendEntity) {
        stopListenAlong(silent = true)
        _listenAlongFriend.value = friend
        lastListenAlongTrackKey = null
        pendingListenAlongTrack = null
        deferredStopFriendName = null

        // Keep CPU awake so polling continues with the screen off
        if (!listenAlongWakeLock.isHeld) {
            listenAlongWakeLock.acquire(2 * 60 * 60 * 1000L) // 2h safety timeout
            Log.d(TAG, "Acquired WakeLock for listen-along polling")
        }

        listenAlongJob = viewModelScope.launch {
            // Toast: "Listening along with [FriendName]"
            _toastEvents.emit("Listening along with ${friend.displayName}")

            // Play current track immediately
            playFriendCurrentTrack(friend, immediate = true)

            // Poll for track changes (desktop uses 15s interval).
            //
            // Transient-friend branch: B1 from Task 7 review. Saved friends use
            // the DB lookup; transient friends (deeplink-spawned, never persisted)
            // must refetch via fetchTransientFriendNowPlaying or the loop dies on
            // the first poll because friendDao.getFriendById returns null for the
            // synthetic id ("transient:{service}:{user}").
            while (isActive) {
                delay(LISTEN_ALONG_POLL_MS)
                try {
                    val refreshed: FriendEntity? = if (friend.transient) {
                        // Transient friend isn't in Room — refetch now-playing
                        // via the same path used at deeplink-entry. Returns
                        // null when they're not currently listening (or the
                        // API call fails — the repo swallows internally).
                        friendsRepository.fetchTransientFriendNowPlaying(friend.service, friend.username)
                    } else {
                        // Saved friend — refresh activity, then re-read the row.
                        friendsRepository.refreshFriendActivity(
                            friendsRepository.getFriendById(friend.id) ?: break
                        )
                        friendsRepository.getFriendById(friend.id)
                    }

                    if (refreshed == null) {
                        // Saved friend deleted, OR transient friend stopped
                        // listening. Mirror the !isOnAir branch below: defer
                        // if a track is mid-play, otherwise calm exit.
                        val state = playbackState.value
                        if (state.isPlaying && state.currentTrack != null) {
                            deferredStopFriendName = friend.displayName
                            Log.d(TAG, "Friend ${friend.displayName} no longer present, deferring stop")
                            continue
                        }
                        _toastEvents.emit("${friend.displayName} stopped listening")
                        break
                    }

                    _listenAlongFriend.value = refreshed

                    if (!refreshed.isOnAir) {
                        // Friend went offline — defer stop if currently playing
                        val state = playbackState.value
                        if (state.isPlaying && state.currentTrack != null) {
                            // Desktop: defer end until current song finishes
                            deferredStopFriendName = refreshed.displayName
                            Log.d(TAG, "Friend ${refreshed.displayName} offline, deferring stop")
                        } else {
                            _toastEvents.emit("${refreshed.displayName} stopped listening")
                            break
                        }
                    } else {
                        deferredStopFriendName = null
                        playFriendCurrentTrack(refreshed, immediate = false)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Listen along poll failed", e)
                }
            }
            _listenAlongFriend.value = null
        }
    }

    fun stopListenAlong(silent: Boolean = false) {
        val friendName = _listenAlongFriend.value?.displayName
        listenAlongJob?.cancel()
        listenAlongJob = null
        _listenAlongFriend.value = null
        lastListenAlongTrackKey = null
        pendingListenAlongTrack = null
        deferredStopFriendName = null
        isListenAlongPlayback = false
        if (listenAlongWakeLock.isHeld) {
            listenAlongWakeLock.release()
            Log.d(TAG, "Released WakeLock for listen-along polling")
        }
        if (!silent && friendName != null) {
            viewModelScope.launch {
                _toastEvents.emit("Stopped listening along with $friendName")
            }
        }
    }

    /**
     * Called when the user manually changes playback (skip, play track, etc.).
     * Stops listen-along — desktop does the same when user takes control.
     */
    private fun onUserPlaybackAction() {
        if (isListenAlongPlayback) return // Listen-along driving playback, not the user
        if (_listenAlongFriend.value != null) {
            stopListenAlong()
        }
    }

    /**
     * Called when a track naturally finishes (auto-advance). If in listen-along mode:
     * - Play pending track if one is queued (desktop behavior)
     * - Handle deferred stop if friend went offline
     */
    private fun onTrackEnded() {
        // Deferred stop: friend went offline, finish current song, then stop
        deferredStopFriendName?.let { name ->
            viewModelScope.launch {
                _toastEvents.emit("$name stopped listening")
            }
            stopListenAlong(silent = true)
            return
        }

        // Play pending listen-along track
        pendingListenAlongTrack?.let { track ->
            pendingListenAlongTrack = null
            playListenAlongTrack(track)
            Log.d(TAG, "Listen along: playing pending ${track.artist} - ${track.title}")
        }
    }

    private suspend fun playFriendCurrentTrack(friend: FriendEntity, immediate: Boolean) {
        val trackName = friend.cachedTrackName ?: return
        val trackArtist = friend.cachedTrackArtist ?: return
        val trackKey = "${trackName.lowercase()}|${trackArtist.lowercase()}"

        // Don't re-play the same track
        if (trackKey == lastListenAlongTrackKey) return
        lastListenAlongTrackKey = trackKey

        try {
            // Listen-along friend tracks must pass the resolver confidence
            // floor (issue #121 §H, UX addendum). resolverScoring.selectBest
            // filters sources below MIN_CONFIDENCE_THRESHOLD (0.60), so a
            // null return here means no resolver had a real match — drop
            // the track silently rather than play a wrong-song result.
            // Contract anchor: ResolverScoringTest."selectBest filters
            // below-floor confidence" + "selectBest null confidence
            // filtered by floor when above-floor source present".
            val sources = resolverManager.resolveWithHints(query = "$trackArtist - $trackName", targetTitle = trackName, targetArtist = trackArtist)
            val best = resolverScoring.selectBest(sources) ?: return

            val track = TrackEntity(
                id = "listen-along-${trackKey.hashCode()}",
                title = trackName,
                artist = trackArtist,
                album = friend.cachedTrackAlbum,
                artworkUrl = friend.cachedTrackArtworkUrl,
                sourceType = best.sourceType,
                sourceUrl = best.url,
                resolver = best.resolver,
                spotifyUri = best.spotifyUri,
                soundcloudId = best.soundcloudId,
                appleMusicId = best.appleMusicId,
            )

            val state = playbackState.value
            if (immediate || !state.isPlaying) {
                // Play immediately (first track or playback idle)
                playListenAlongTrack(track)
                Log.d(TAG, "Listen along: playing ${track.artist} - ${track.title}")
            } else {
                // Desktop: queue as pending, play when current track finishes
                pendingListenAlongTrack = track
                Log.d(TAG, "Listen along: queued pending ${track.artist} - ${track.title}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve listen-along track: $trackArtist - $trackName", e)
        }
    }

    /**
     * Play a track as part of listen-along. Suppresses the user-action listener
     * and sets a listen-along playback context for the queue banner.
     */
    private fun playListenAlongTrack(track: TrackEntity) {
        val friendName = _listenAlongFriend.value?.displayName ?: "Friend"
        val context = PlaybackContext(type = "listen-along", name = friendName)
        isListenAlongPlayback = true
        playbackController.playTrack(track, context = context)
        isListenAlongPlayback = false
    }

    // ── Unpin Friend ──────────────────────────────────────────────

    fun unpinFriend(friendId: String) {
        viewModelScope.launch {
            friendsRepository.pinFriend(friendId, false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopListenAlong()
        playbackController.release()
    }
}
