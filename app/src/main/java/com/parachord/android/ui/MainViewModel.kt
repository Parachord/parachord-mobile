package com.parachord.android.ui

import com.parachord.shared.playback.APPLE_MUSIC_SIGN_IN_MESSAGE
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
import com.parachord.shared.model.Track
import com.parachord.shared.playback.LISTEN_ALONG_POLL_MS
import com.parachord.shared.playback.ListenAlongHost
import com.parachord.shared.playback.ListenAlongPlatform
import com.parachord.shared.playback.ListenAlongSuspendedQueue
import com.parachord.shared.playback.ListenAlongPoll
import com.parachord.shared.playback.listenAlongPollOf
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
    private val musicKitBridge: MusicKitWebBridge,
    private val spotifyPlaybackHandler: SpotifyPlaybackHandler,
    private val settingsStore: SettingsStore,
    private val playlistImportManager: PlaylistImportManager,
    private val concertsRepository: ConcertsRepository,
    private val networkMonitor: NetworkMonitor,
    private val pluginManager: com.parachord.android.plugin.PluginManager,
    private val pluginSyncService: com.parachord.android.plugin.PluginSyncService,
    trackResolverCache: com.parachord.android.resolver.TrackResolverCache,
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        /** Desktop polls every 2 minutes for normal friend activity. */
        private const val FRIEND_POLL_INTERVAL_MS = 2 * 60 * 1_000L
    }

    val playbackState: StateFlow<PlaybackState> = playbackStateHolder.state

    /** Art from each track's resolved sources, for the mini player's cover. */
    val trackArtwork: StateFlow<Map<String, String>> = trackResolverCache.trackArtwork

    /** Playback messages with an action, shown as a snackbar. */
    val playbackNotices = playbackController.notices

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
        // Listen Along drives playback through the controller's hooks.
        playbackController.onOwnTrackEnded = { listenAlong.onOwnTrackEnded() }
        playbackController.onTrackUnplayable = { track -> listenAlong.onTrackUnplayable(track.id) }
        playbackController.drivesTrack = { track -> listenAlong.drivesTrack(track.id) }
        playbackController.onUserAction = { action -> listenAlong.onUserAction(action) }
        playbackController.onTrackStarting = { track -> listenAlong.onTrackStarting(track.id) }

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
                // The heart lights up on a title+artist match (isTrackInCollection),
                // but `effectiveTrack` may be an ephemeral row (weekly playlist,
                // recommendation, DJ chat) whose id isn't the library row's. Deleting
                // it by id matched nothing; removeFromCollection resolves the library
                // row the same way the heart does, then removes it sync-aware (#414).
                libraryRepository.removeFromCollection(track)
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

    // ── Listen Along (the shared ListenAlongHost) ──

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
                _toastEvents.emit(APPLE_MUSIC_SIGN_IN_MESSAGE)
                _navigateToSettings.emit(Unit)
            }
        }
        // The app's own Apple Music key expired — NOT a user sign-in problem.
        // Deliberately does not send the user to Settings: there is nothing
        // for them to do there, and routing them to a Connect button that
        // cannot succeed is exactly the loop this split fixes (Sept 2026).
        viewModelScope.launch {
            musicKitBridge.developerTokenExpired.collect {
                _toastEvents.emit("Apple Music key expired — update Parachord to restore it")
            }
        }
    }

    private val _listenAlongSuspendedQueue = MutableStateFlow<List<TrackEntity>>(emptyList())

    /** Shown dimmed as "YOUR QUEUE" in the queue sheet while listening along. */
    val listenAlongSuspendedQueue: StateFlow<List<TrackEntity>> = _listenAlongSuspendedQueue

    private val _listenAlongFriendIsAhead = MutableStateFlow(false)

    /** While listening along: true when the friend is on a song we haven't
     *  played, so Now Playing's Next is enabled (catch up); false in sync. */
    val listenAlongFriendIsAhead: StateFlow<Boolean> = _listenAlongFriendIsAhead

    /**
     * The shared Listen Along host, over [playbackController]. Its messages
     * are the shared [com.parachord.shared.playback.PlaybackMessages]; its
     * plays are metadata-only tracks that the pipeline resolves on the fly.
     */
    private val listenAlong: ListenAlongHost = ListenAlongHost(object : ListenAlongPlatform {
        override fun play(track: Track, context: PlaybackContext) =
            playbackController.playTrack(track, context = context)

        override fun currentQueue(): ListenAlongSuspendedQueue {
            val state = playbackStateHolder.state.value
            return ListenAlongSuspendedQueue(listOfNotNull(state.currentTrack) + state.upNext, state.playbackContext)
        }

        override fun exitSpinoff() = playbackController.exitSpinoff()

        override fun restoreQueue(queue: ListenAlongSuspendedQueue, startPlayback: Boolean) {
            if (startPlayback) {
                playbackController.playQueue(queue.tracks, 0, queue.context)
            } else {
                playbackController.restoreQueuePaused(queue.tracks, queue.context)
            }
        }

        override fun clearQueue() = playbackController.clearQueue()
        override fun stopAndClear() = playbackController.stopAndClear()

        override fun showMessage(text: String) {
            Log.d(TAG, "Listen along: $text")
            viewModelScope.launch { _toastEvents.emit(text) }
        }

        override fun releaseHold() = playbackController.releaseListenerHold()
        override fun ended() = onListenAlongEnded()

        override fun stateChanged() {
            _listenAlongFriendIsAhead.value = listenAlong.friendIsAhead
            _listenAlongSuspendedQueue.value = listenAlong.suspendedQueue
        }

        override fun isPlaying() = playbackStateHolder.state.value.isPlaying
        override fun isPlayStarting() = playbackController.isPlayStarting
        override fun currentTrackId() = playbackStateHolder.state.value.currentTrack?.id
    })

    /** Catch up to the friend's song (Now Playing's Next while listening along). */
    fun advanceToFriend() {
        listenAlong.catchUp()
    }

    /**
     * Start listening along with [friend]: play what they're playing now, then
     * poll every [LISTEN_ALONG_POLL_MS] and let the session decide. Switching
     * friends replaces the session and keeps the user's suspended queue; a
     * running spinoff ends first.
     */
    fun startListenAlong(friend: FriendEntity) {
        listenAlongJob?.cancel()
        listenAlongJob = null
        _listenAlongFriend.value = friend

        // Keep CPU awake so polling continues with the screen off
        if (!listenAlongWakeLock.isHeld) {
            listenAlongWakeLock.acquire(2 * 60 * 60 * 1000L) // 2h safety timeout
            Log.d(TAG, "Acquired WakeLock for listen-along polling")
        }

        val current = (listenAlongPollOf(friend) as? ListenAlongPoll.Listening)?.track
        listenAlong.start(friend.displayName, current)

        listenAlongJob = viewModelScope.launch {
            while (isActive && listenAlong.isActive) {
                delay(LISTEN_ALONG_POLL_MS)
                // Saved and transient (deep-link) friends alike; a failed
                // request is ListenAlongPoll.Failed, not "not listening".
                val result = friendsRepository.pollListenAlong(friend)
                result.friend?.let { _listenAlongFriend.value = it }
                Log.d(TAG, "Listen along poll: ${result.poll} (playing=${playbackState.value.isPlaying}, starting=${playbackController.isPlayStarting})")
                listenAlong.onPoll(result.poll)
            }
        }
    }

    /** The Listen Along toggle / stop button: the user's queue comes back. */
    fun stopListenAlong() {
        listenAlong.stop()
    }

    /**
     * A `parachord://` link takes over: a radio link [silently] (no message),
     * a play link as the user playing something else (the message shows).
     * Either way the suspended queue is dropped.
     */
    fun listenAlongTakenOver(silently: Boolean) {
        listenAlong.takeOver(silently)
    }

    /** The session ended (any way): stop polling and release the wake lock. */
    private fun onListenAlongEnded() {
        listenAlongJob?.cancel()
        listenAlongJob = null
        _listenAlongFriend.value = null
        if (listenAlongWakeLock.isHeld) {
            listenAlongWakeLock.release()
            Log.d(TAG, "Released WakeLock for listen-along polling")
        }
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
