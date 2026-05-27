package com.parachord.android.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.parachord.shared.platform.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player.Commands
import androidx.media3.common.Timeline
import com.parachord.android.data.db.entity.TrackEntity
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.parachord.android.R
import com.parachord.android.playback.handlers.SpotifyPlaybackHandler
import com.parachord.shared.playback.QueueManager
import org.koin.android.ext.android.inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Foreground service that manages audio playback via ExoPlayer and exposes a
 * MediaLibrarySession for lock-screen controls, Bluetooth, and Android Auto.
 *
 * Extends [MediaLibraryService] (rather than plain MediaSessionService) so the
 * app registers as a browsable media app with Android Auto. Auto requires a
 * library callback even for "remote control only" integrations — we expose a
 * minimal browsable root with no children, which is enough for Auto to surface
 * the Now Playing screen while the user's actual audio routes through their
 * preferred channel (Bluetooth, Auto audio). See [LibraryCallback].
 *
 * Uses a unified notification style for both ExoPlayer (local/SoundCloud) and
 * external (Spotify/Apple Music) playback so the notification tray always looks
 * the same regardless of playback source.
 *
 * During external playback, ExoPlayer isn't actively playing so
 * MediaSessionService won't automatically keep the foreground notification.
 * We handle this by explicitly calling [startForeground] with a persistent
 * notification, preventing Android from killing the process.
 */
class PlaybackService : MediaLibraryService() {

    private val spotifyHandler: SpotifyPlaybackHandler by inject()
    private val playbackController: PlaybackController by inject()
    private val stateHolder: PlaybackStateHolder by inject()
    private val queueManager: QueueManager by inject()
    private val playlistDao: com.parachord.shared.db.dao.PlaylistDao by inject()
    private val playlistTrackDao: com.parachord.shared.db.dao.PlaylistTrackDao by inject()
    private val trackDao: com.parachord.shared.db.dao.TrackDao by inject()
    private val libraryRepository: com.parachord.shared.repository.LibraryRepository by inject()
    private val metadataService: com.parachord.shared.metadata.MetadataService by inject()

    private var mediaSession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var forwardingPlayer: ExternalPlaybackForwardingPlayer? = null
    private var isExternalForeground = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null
    private var queueSnapshotJob: Job? = null

    /**
     * Pause playback when an audio output device disconnects (Bluetooth,
     * wired headset). Standard music player behavior — prevents audio from
     * blasting through the phone speaker unexpectedly.
     *
     * ExoPlayer handles this automatically when playing directly (via
     * handleAudioFocus), but during external playback ExoPlayer plays
     * silence with handleAudioFocus=false, so we need an explicit receiver.
     */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                // Use pause() — never togglePlayPause() — because the noisy
                // broadcast fires on every output-route change (BT off, wired
                // unplug), even when the user already paused. A toggle here
                // would resume playback through the phone speaker as soon as
                // the Bluetooth speaker disconnected. pause() no-ops when
                // already paused, which is the correct behavior.
                Log.d(TAG, "Audio becoming noisy (device disconnected) — pausing playback")
                playbackController.pause()
            }
        }
    }
    private var noisyReceiverRegistered = false

    /** Cached artwork bitmap for the current notification. */
    private var currentArtworkUrl: String? = null
    private var currentArtworkBitmap: Bitmap? = null

    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "parachord_playback"
        private const val ARTWORK_SIZE = 256
        private const val LIBRARY_ROOT_ID = "parachord_root"

        // Browse-tree IDs surfaced in Android Auto. The "browse:" prefix is a
        // navigable folder (Auto calls onGetChildren on it); the "action:"
        // prefix is a tap-and-go playable item that we intercept in
        // onAddMediaItems to dispatch into PlaybackController without going
        // through ExoPlayer's media-item loading path. Playlist items use
        // the "playlist:" prefix.
        private const val BROWSE_PLAYLISTS_ID = "browse:playlists"
        private const val ACTION_COLLECTION_RADIO_ID = "action:collection-radio"
        private const val PLAYLIST_ITEM_PREFIX = "playlist:"

        /**
         * MediaId prefix for tracks returned by Auto search. The integer
         * suffix is an index into the in-memory [searchResultsCache] kept on
         * the LibraryCallback. On tap, the LibraryCallback looks up the
         * cached [TrackSearchResult] and dispatches playback through the
         * resolver pipeline (PlaybackController handles on-the-fly resolve).
         */
        private const val SEARCH_TRACK_PREFIX = "search-track:"

        /** Intent actions sent by PlaybackController to manage foreground state. */
        const val ACTION_EXTERNAL_PLAYBACK_START = "com.parachord.android.EXTERNAL_PLAYBACK_START"
        const val ACTION_EXTERNAL_PLAYBACK_STOP = "com.parachord.android.EXTERNAL_PLAYBACK_STOP"
        const val ACTION_EXTERNAL_MODE_ON = "com.parachord.android.EXTERNAL_MODE_ON"
        const val ACTION_EXTERNAL_MODE_OFF = "com.parachord.android.EXTERNAL_MODE_OFF"
        /** Pause/resume the underlying ExoPlayer directly (bypasses ForwardingPlayer). */
        const val ACTION_SILENCE_PAUSE = "com.parachord.android.SILENCE_PAUSE"
        const val ACTION_SILENCE_RESUME = "com.parachord.android.SILENCE_RESUME"
        const val EXTRA_TRACK_TITLE = "track_title"
        const val EXTRA_TRACK_ARTIST = "track_artist"
        const val EXTRA_TRACK_ARTWORK_URL = "track_artwork_url"

        /** Notification action intents for playback controls. */
        const val ACTION_PLAY_PAUSE = "com.parachord.android.ACTION_PLAY_PAUSE"
        const val ACTION_SKIP_NEXT = "com.parachord.android.ACTION_SKIP_NEXT"
        const val ACTION_SKIP_PREVIOUS = "com.parachord.android.ACTION_SKIP_PREVIOUS"
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = exoPlayer

        // Wrap ExoPlayer in a ForwardingPlayer that reports external playback
        // state (position, duration, artwork) to the MediaSession. During
        // external playback, the system media controls (notification shade,
        // lock screen) show correct progress and artwork instead of the
        // internal silence loop state.
        val wrapper = ExternalPlaybackForwardingPlayer(exoPlayer, playbackController, stateHolder)
        forwardingPlayer = wrapper

        mediaSession = MediaLibrarySession.Builder(this, wrapper, LibraryCallback())
            .build()

        // Unified notification provider — same look for ExoPlayer and external playback.
        setMediaNotificationProvider(UnifiedMediaNotificationProvider())

        // Pause on audio device disconnect (Bluetooth, wired headset)
        val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(becomingNoisyReceiver, noisyFilter)
        noisyReceiverRegistered = true

        startStateObserver()

        // Feed QueueManager snapshots + current-track changes into the
        // wrapper's synthetic timeline. Combines two flows so a change in
        // either side triggers a re-emit. Runs on Dispatchers.Main per
        // Media3 main-thread invariant — wrapper.updateQueueSnapshot fires
        // listeners synchronously.
        queueSnapshotJob = serviceScope.launch {
            combine(
                queueManager.snapshot,
                stateHolder.state.map { it.currentTrack }.distinctUntilChanged(),
            ) { qs, current -> Pair(qs, current) }.collect { (qs, current) ->
                wrapper.updateQueueSnapshot(currentTrack = current, upNext = qs.upNext)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXTERNAL_PLAYBACK_START -> {
                val title = intent.getStringExtra(EXTRA_TRACK_TITLE) ?: "Playing"
                val artist = intent.getStringExtra(EXTRA_TRACK_ARTIST) ?: ""
                val artworkUrl = intent.getStringExtra(EXTRA_TRACK_ARTWORK_URL)
                promoteToForeground(title, artist, artworkUrl)
            }
            ACTION_EXTERNAL_PLAYBACK_STOP -> {
                demoteFromForeground()
            }
            ACTION_EXTERNAL_MODE_ON -> {
                forwardingPlayer?.externalMode = true
            }
            ACTION_EXTERNAL_MODE_OFF -> {
                forwardingPlayer?.externalMode = false
            }
            ACTION_SILENCE_PAUSE -> {
                player?.pause()
            }
            ACTION_SILENCE_RESUME -> {
                player?.play()
            }
            ACTION_PLAY_PAUSE -> {
                playbackController.togglePlayPause()
            }
            ACTION_SKIP_NEXT -> {
                playbackController.skipNext()
            }
            ACTION_SKIP_PREVIOUS -> {
                playbackController.skipPrevious()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Gate which apps can connect as media controllers.
     * Allow system UI (notifications, lockscreen, Bluetooth, Android Auto) and
     * our own app. Block unknown third-party apps from issuing playback commands.
     * security: M11
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        val pkg = controllerInfo.packageName
        // Allow system packages (notifications, lockscreen, BT, Android Auto)
        if (controllerInfo.isTrusted) return mediaSession
        // Allow our own app
        if (pkg == packageName) return mediaSession
        // Allow known system packages that may not have isTrusted set
        val systemPrefixes = listOf(
            "com.android.",
            "com.google.android.",
            "android",
        )
        if (systemPrefixes.any { pkg.startsWith(it) }) return mediaSession
        Log.w(TAG, "Rejected MediaSession connection from untrusted package: $pkg")
        return null
    }

    /**
     * Minimal [MediaLibrarySession.Callback] that exposes a browsable root
     * with no children. Android Auto requires a library callback for the app
     * to register as a media app, but we don't implement a real browse tree
     * today — Auto's use case is limited to displaying the Now Playing screen
     * and forwarding transport controls (play/pause/skip/seek) back to our
     * existing [ExternalPlaybackForwardingPlayer], which already routes them
     * to the correct handler (Spotify Web API, MusicKit JS, ExoPlayer).
     *
     * Audio routing is independent: whether via Bluetooth A2DP or Auto's own
     * audio channel, the car speakers receive audio from whichever app owns
     * the audio stream (Spotify app, MusicKit WebView, ExoPlayer). Parachord's
     * role in Auto is purely the remote-control UI.
     */
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return when (parentId) {
                LIBRARY_ROOT_ID -> {
                    val children = ImmutableList.of(
                        browsableFolder(BROWSE_PLAYLISTS_ID, "Playlists"),
                        playableAction(ACTION_COLLECTION_RADIO_ID, "Collection Radio", subtitle = "Shuffle all loved tracks"),
                    )
                    Futures.immediateFuture(LibraryResult.ofItemList(children, params))
                }
                BROWSE_PLAYLISTS_ID -> loadPlaylistChildrenAsync(params)
                else -> Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = when {
            mediaId == LIBRARY_ROOT_ID ->
                Futures.immediateFuture(LibraryResult.ofItem(rootItem(), null))
            mediaId == BROWSE_PLAYLISTS_ID ->
                Futures.immediateFuture(LibraryResult.ofItem(browsableFolder(BROWSE_PLAYLISTS_ID, "Playlists"), null))
            mediaId == ACTION_COLLECTION_RADIO_ID ->
                Futures.immediateFuture(LibraryResult.ofItem(playableAction(ACTION_COLLECTION_RADIO_ID, "Collection Radio", "Shuffle all loved tracks"), null))
            mediaId.startsWith(PLAYLIST_ITEM_PREFIX) -> {
                val future = SettableFuture.create<LibraryResult<MediaItem>>()
                serviceScope.launch {
                    val id = mediaId.removePrefix(PLAYLIST_ITEM_PREFIX)
                    val p = try { playlistDao.getById(id) } catch (e: Exception) { null }
                    if (p == null) future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    else future.set(LibraryResult.ofItem(playlistToMediaItem(p), null))
                }
                future
            }
            else -> Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val isInternal = controller.packageName == packageName
            if (isInternal) return Futures.immediateFuture(mediaItems)

            val first = mediaItems.firstOrNull()
            val firstId = first?.mediaId
            if (firstId != null && isBrowseTreeMediaId(firstId)) {
                Log.d(TAG, "onAddMediaItems: dispatching browse-tree action mediaId=$firstId from ${controller.packageName}")
                serviceScope.launch { dispatchBrowseTreeAction(firstId) }
                // Return a silence-loop placeholder so Media3 can complete
                // its setMediaItems → play() sequence without erroring out.
                // PlaybackController.playQueue (kicked off above) will replace
                // these items via its own setMediaItems call once playback
                // routes through to an external handler.
                return Futures.immediateFuture(listOf(silencePlaceholderFor(first)))
            }
            Log.d(TAG, "onAddMediaItems: ${mediaItems.size} items from ${controller.packageName} — ignoring (v1)")
            return Futures.immediateFuture(emptyList())
        }

        /**
         * Auto's "tap to play" from the browse tree fires
         * [MediaController.setMediaItem] which routes here. Without this
         * override, Media3 only sees [onAddMediaItems] (no start index /
         * position) and Auto displays "Parachord doesn't seem to be working
         * right now" while the future never resolves to a playable form.
         *
         * The dispatch is the same as [onAddMediaItems]: detect browse-tree
         * mediaIds, kick off the real playback via [dispatchBrowseTreeAction],
         * return a silence placeholder so Media3's add-then-play sequence
         * completes.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val isInternal = controller.packageName == packageName
            if (isInternal) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                )
            }
            val first = mediaItems.firstOrNull()
            val firstId = first?.mediaId
            if (firstId != null && isBrowseTreeMediaId(firstId)) {
                Log.d(TAG, "onSetMediaItems: dispatching browse-tree action mediaId=$firstId from ${controller.packageName}")
                serviceScope.launch { dispatchBrowseTreeAction(firstId) }
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        listOf(silencePlaceholderFor(first)),
                        /* startIndex = */ 0,
                        /* startPositionMs = */ 0L,
                    )
                )
            }
            Log.d(TAG, "onSetMediaItems: ${mediaItems.size} items from ${controller.packageName} — ignoring (v1)")
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            )
        }

        /**
         * Build a silence-URI placeholder MediaItem keyed to the original
         * browse-tree item's mediaId. ExoPlayer can load + play this without
         * error; we then rely on [PlaybackController.playQueue]'s own
         * `setMediaItems` (fired async via [dispatchBrowseTreeAction]) to
         * replace it with the routed track's silence loop + external mode.
         */
        private fun silencePlaceholderFor(source: MediaItem): MediaItem {
            val silenceUri = Uri.parse(
                "android.resource://${packageName}/${com.parachord.android.R.raw.silence}"
            )
            return MediaItem.Builder()
                .setMediaId(source.mediaId)
                .setUri(silenceUri)
                .setMediaMetadata(source.mediaMetadata)
                .build()
        }

        // ── Browse-tree helpers ─────────────────────────────────────────

        /** Async loader for the "Playlists" folder's children. */
        private fun loadPlaylistChildrenAsync(
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    val playlists = playlistDao.getAllSync().sortedByDescending { it.updatedAt }
                    val items = ImmutableList.copyOf(playlists.map { playlistToMediaItem(it) })
                    future.set(LibraryResult.ofItemList(items, params))
                } catch (e: Exception) {
                    Log.e(TAG, "Browse: failed to load playlist children", e)
                    future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }
            return future
        }

        /** Tap-target dispatch for browse-tree entries (called off the binder thread). */
        private suspend fun dispatchBrowseTreeAction(mediaId: String) {
            try {
                when {
                    mediaId == ACTION_COLLECTION_RADIO_ID -> {
                        val collection = trackDao.getAll().first()
                        if (collection.isEmpty()) {
                            Log.w(TAG, "Collection Radio: no loved tracks available")
                            return
                        }
                        Log.d(TAG, "Collection Radio: starting shuffled queue of ${collection.size} tracks")
                        playbackController.playQueue(collection, startIndex = 0, shuffle = true)
                    }
                    mediaId.startsWith(PLAYLIST_ITEM_PREFIX) -> {
                        val playlistId = mediaId.removePrefix(PLAYLIST_ITEM_PREFIX)
                        val rows = playlistTrackDao.getByPlaylistIdSync(playlistId)
                        if (rows.isEmpty()) {
                            Log.w(TAG, "Browse: playlist $playlistId has no tracks")
                            return
                        }
                        val tracks = rows.map { libraryRepository.playlistTrackToTrackEntity(it) }
                        Log.d(TAG, "Browse: starting playlist $playlistId (${tracks.size} tracks)")
                        playbackController.playQueue(tracks, startIndex = 0)
                    }
                    mediaId.startsWith(SEARCH_TRACK_PREFIX) -> {
                        val idx = mediaId.removePrefix(SEARCH_TRACK_PREFIX).toIntOrNull()
                        val cached = searchMutex.withLock { searchResultsCache.toList() }
                        if (idx == null || idx < 0 || idx >= cached.size) {
                            Log.w(TAG, "Search: stale or out-of-range mediaId=$mediaId (cache size=${cached.size})")
                            return
                        }
                        // Queue all cached search results from the tapped index. Tracks
                        // before the index become history; from idx onward becomes the
                        // play order. Matches the Spotify-Auto / YT-Music-Auto tap
                        // semantics where the rest of the search results form upNext.
                        val tracks = cached.map { searchResultToTrackEntity(it) }
                        Log.d(TAG, "Search: starting tap at index $idx of ${tracks.size} cached results")
                        playbackController.playQueue(tracks, startIndex = idx)
                    }
                    else -> Log.w(TAG, "dispatchBrowseTreeAction: unknown mediaId=$mediaId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "dispatchBrowseTreeAction failed for $mediaId", e)
            }
        }

        private fun isBrowseTreeMediaId(mediaId: String): Boolean =
            mediaId == ACTION_COLLECTION_RADIO_ID ||
                mediaId.startsWith(PLAYLIST_ITEM_PREFIX) ||
                mediaId.startsWith(SEARCH_TRACK_PREFIX)

        // ── Auto search ─────────────────────────────────────────────────

        /**
         * In-memory cache of the most recent Auto search results. Auto's
         * search flow is split across two callbacks ([onSearch] +
         * [onGetSearchResult]) and the playback dispatch (via
         * [onSetMediaItems]) is a third, so we need to remember the result
         * list between calls. The list is indexed by position; mediaIds
         * encode the index ("search-track:&lt;n&gt;").
         */
        @Volatile
        private var searchResultsCache: List<com.parachord.shared.metadata.TrackSearchResult> = emptyList()

        /** Guards writes to [searchResultsCache] across the search callbacks. */
        private val searchMutex = kotlinx.coroutines.sync.Mutex()

        /**
         * The first leg of Auto search. Auto calls this once with the user's
         * query; we run the resolver-cascade search through [MetadataService]
         * asynchronously, cache the results, and tell Auto how many we have
         * via `notifySearchResultChanged`. Auto then calls
         * [onGetSearchResult] to fetch a page of items.
         *
         * Returning `LibraryResult.ofVoid` immediately is the required
         * acknowledgement — Auto doesn't block on it; the real signal is
         * `notifySearchResultChanged`.
         */
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            Log.d(TAG, "Auto search requested: query='$query' from ${browser.packageName}")
            serviceScope.launch {
                val count = try {
                    // Fetch wider than we'll surface (40) so ranking has room
                    // to pull the strongest matches forward; we serve the top
                    // 20 after scoring.
                    val raw = metadataService.searchTracks(query, limit = 40)
                    val ranked = rankSearchResults(query, raw).take(20)
                    searchMutex.withLock { searchResultsCache = ranked }
                    ranked.size
                } catch (e: Exception) {
                    Log.e(TAG, "Auto search failed for query='$query'", e)
                    searchMutex.withLock { searchResultsCache = emptyList() }
                    0
                }
                try {
                    session.notifySearchResultChanged(browser, query, count, params)
                } catch (e: Exception) {
                    Log.w(TAG, "notifySearchResultChanged threw: ${e.message}")
                }
            }
            return Futures.immediateFuture(LibraryResult.ofVoid(params))
        }

        /**
         * Re-rank raw [MetadataService] hits using the desktop's combined
         * score: 60% fuzzy title/artist match + 40% provider-priority weighting
         * (MusicBrainz 0, Last.fm 10, Spotify 20 — lower priority number =
         * heavier "popularity" weight, since MB's canonical recordings are
         * the most-trustworthy matches). Mirrors `SearchViewModel.rankTracks`
         * — keep both in sync if either changes.
         */
        private fun rankSearchResults(
            query: String,
            results: List<com.parachord.shared.metadata.TrackSearchResult>,
        ): List<com.parachord.shared.metadata.TrackSearchResult> =
            results.map { track ->
                val titleScore = com.parachord.android.ui.screens.search.FuzzyMatch.score(query, track.title)
                val artistScore = com.parachord.android.ui.screens.search.FuzzyMatch.score(query, track.artist)
                val fuzzy = maxOf(titleScore, artistScore * 0.9)
                val priority = providerPriority(track.provider)
                track to com.parachord.android.ui.screens.search.FuzzyMatch.combinedScore(fuzzy, priority)
            }
                .filter { it.second > 0.0 }
                .sortedByDescending { it.second }
                .map { it.first }

        /** Provider-priority weights — kept in sync with SearchViewModel. */
        private fun providerPriority(provider: String): Int = when {
            "musicbrainz" in provider -> 0
            "lastfm" in provider -> 10
            "spotify" in provider -> 20
            else -> 10
        }

        /**
         * Returns the cached results from the most-recent [onSearch] call,
         * paginated. Auto calls this after we fire `notifySearchResultChanged`.
         */
        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    val cached = searchMutex.withLock { searchResultsCache.toList() }
                    val start = page * pageSize
                    if (start >= cached.size) {
                        future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                        return@launch
                    }
                    val end = minOf(start + pageSize, cached.size)
                    val items = ImmutableList.copyOf(
                        cached.subList(start, end).mapIndexed { offset, r ->
                            searchResultToMediaItem(start + offset, r)
                        }
                    )
                    future.set(LibraryResult.ofItemList(items, params))
                } catch (e: Exception) {
                    Log.e(TAG, "onGetSearchResult failed", e)
                    future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }
            return future
        }

        /**
         * Build a playable [MediaItem] for an Auto search hit. The mediaId
         * is `search-track:<index>` — on tap, [dispatchBrowseTreeAction]
         * looks up the cached [TrackSearchResult] by index and dispatches
         * the queue (rest of the results from that index onward) through
         * [PlaybackController.playQueue].
         */
        private fun searchResultToMediaItem(
            index: Int,
            r: com.parachord.shared.metadata.TrackSearchResult,
        ): MediaItem {
            val md = MediaMetadata.Builder()
                .setTitle(r.title)
                .setArtist(r.artist)
                .apply { r.album?.let { setAlbumTitle(it) } }
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .apply { r.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
            return MediaItem.Builder()
                .setMediaId("$SEARCH_TRACK_PREFIX$index")
                .setMediaMetadata(md)
                .build()
        }

        /**
         * Convert a metadata search hit into a metadata-only [TrackEntity].
         * `resolver` and `sourceUrl` are deliberately null — PlaybackController
         * runs on-the-fly resolution via the resolver cascade (see CLAUDE.md
         * "On-the-fly Track Resolution"). Hints (spotifyId, mbid) are passed
         * through so the cascade can fast-path the lookup.
         */
        private fun searchResultToTrackEntity(
            r: com.parachord.shared.metadata.TrackSearchResult,
        ): TrackEntity =
            com.parachord.shared.model.Track(
                id = com.parachord.shared.platform.randomUUID(),
                title = r.title,
                artist = r.artist,
                album = r.album,
                duration = r.duration,
                artworkUrl = r.artworkUrl,
                spotifyId = r.spotifyId,
                recordingMbid = r.mbid,
            )

        // ── MediaItem builders ──────────────────────────────────────────

        private fun rootItem(): MediaItem {
            val md = MediaMetadata.Builder()
                .setTitle("Parachord")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
            return MediaItem.Builder().setMediaId(LIBRARY_ROOT_ID).setMediaMetadata(md).build()
        }

        private fun browsableFolder(id: String, title: String): MediaItem {
            val md = MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                .build()
            return MediaItem.Builder().setMediaId(id).setMediaMetadata(md).build()
        }

        private fun playableAction(id: String, title: String, subtitle: String? = null): MediaItem {
            val md = MediaMetadata.Builder()
                .setTitle(title)
                .apply { subtitle?.let { setSubtitle(it) } }
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
            return MediaItem.Builder().setMediaId(id).setMediaMetadata(md).build()
        }

        private fun playlistToMediaItem(p: com.parachord.shared.model.Playlist): MediaItem {
            val md = MediaMetadata.Builder()
                .setTitle(p.name)
                .setArtist(p.ownerName ?: "Parachord")
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .apply { p.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
            return MediaItem.Builder()
                .setMediaId("$PLAYLIST_ITEM_PREFIX${p.id}")
                .setMediaMetadata(md)
                .build()
        }
    }

    /**
     * Override Media3's auto-foreground management. Without this, MediaSessionService
     * calls stopForeground() when ExoPlayer reports "not playing" — which it always
     * does during external playback (Spotify/Apple Music) since ExoPlayer just holds
     * metadata. This caused Android to kill the process with the screen off.
     *
     * When [isExternalForeground] is true, we manage foreground ourselves via
     * [promoteToForeground]/[demoteFromForeground] and skip Media3's auto-management.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (isExternalForeground) return
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onUpdateNotification(session: MediaSession) {
        if (isExternalForeground) return
        super.onUpdateNotification(session)
    }

    /**
     * Prevent Media3 from calling stopSelf() when ExoPlayer is idle.
     * MediaSessionService calls this when it thinks nothing is playing and
     * the service should shut down. During external playback, music IS playing
     * (via Spotify/Apple Music) — ExoPlayer just doesn't know about it.
     */
    override fun pauseAllPlayersAndStopSelf() {
        if (isExternalForeground) {
            Log.d(TAG, "Blocked pauseAllPlayersAndStopSelf — external playback is active")
            return
        }
        super.pauseAllPlayersAndStopSelf()
    }

    /**
     * Don't stop when the user swipes away the app — keep playing.
     * This is critical for external playback (Spotify/Apple Music) where
     * the user expects music to continue in the background.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (isExternalForeground || (p != null && p.playWhenReady)) {
            // External playback active or ExoPlayer playing — keep alive
            return
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (noisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            noisyReceiverRegistered = false
        }
        stateObserverJob?.cancel()
        queueSnapshotJob?.cancel()
        serviceScope.cancel()
        spotifyHandler.disconnect()
        isExternalForeground = false
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    /**
     * Observe playback state changes to keep the external notification
     * in sync with the current track and play/pause state.
     */
    private fun startStateObserver() {
        stateObserverJob = serviceScope.launch {
            stateHolder.state.collectLatest { state ->
                if (!isExternalForeground) return@collectLatest

                val track = state.effectiveTrack ?: return@collectLatest
                val title = track.title
                val artist = track.artist ?: ""
                val artworkUrl = track.artworkUrl

                // Load artwork if URL changed
                if (artworkUrl != currentArtworkUrl) {
                    currentArtworkUrl = artworkUrl
                    currentArtworkBitmap = if (artworkUrl != null) {
                        fetchArtworkBitmap(artworkUrl)
                    } else null
                }

                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(
                    NOTIFICATION_ID,
                    buildNotification(title, artist, state.isPlaying, currentArtworkBitmap),
                )

                // Sync ExoPlayer's position with the real external playback
                // position. ExoPlayer plays a 10-minute silence file, so we
                // seek it to match the actual track position. This makes the
                // system media controls (notification shade, lock screen) show
                // correct progress without needing to override position on
                // the ForwardingPlayer (which MediaSession only reads once).
                val externalPos = state.position
                val p = player
                if (p != null && externalPos > 0 && externalPos < 600_000) {
                    val currentPos = p.currentPosition
                    // Only seek if the positions diverge significantly (avoid
                    // seek spam that could cause progress bar flicker)
                    if (kotlin.math.abs(currentPos - externalPos) > 2000) {
                        p.seekTo(externalPos)
                    }
                }
            }
        }
    }

    /**
     * Explicitly start this service in the foreground during external playback.
     * MediaSessionService only auto-promotes when ExoPlayer is actively playing,
     * which doesn't happen for Spotify/Apple Music external handlers.
     */
    private fun promoteToForeground(title: String, artist: String, artworkUrl: String?) {
        if (isExternalForeground) {
            // Just update the notification — state observer will handle ongoing updates
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(
                NOTIFICATION_ID,
                buildNotification(title, artist, true, currentArtworkBitmap),
            )
            // Kick off artwork load if needed
            if (artworkUrl != currentArtworkUrl) {
                loadArtworkAndUpdateNotification(title, artist, artworkUrl)
            }
            return
        }

        Log.d(TAG, "Promoting to foreground for external playback: $title - $artist")
        val notification = buildNotification(title, artist, true, null)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else 0,
            )
            isExternalForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }

        // Load artwork async and update notification once ready
        if (artworkUrl != null) {
            loadArtworkAndUpdateNotification(title, artist, artworkUrl)
        }
    }

    private fun loadArtworkAndUpdateNotification(title: String, artist: String, artworkUrl: String?) {
        currentArtworkUrl = artworkUrl
        if (artworkUrl == null) {
            currentArtworkBitmap = null
            return
        }
        serviceScope.launch {
            val bitmap = fetchArtworkBitmap(artworkUrl)
            if (currentArtworkUrl == artworkUrl) {
                currentArtworkBitmap = bitmap
                if (isExternalForeground) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(
                        NOTIFICATION_ID,
                        buildNotification(title, artist, stateHolder.state.value.isPlaying, bitmap),
                    )
                }
            }
        }
    }

    private fun demoteFromForeground() {
        if (!isExternalForeground) return
        Log.d(TAG, "Demoting from external playback foreground")
        isExternalForeground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /**
     * Build a media notification with consistent styling used for both
     * ExoPlayer and external (Spotify/Apple Music) playback.
     */
    internal fun buildNotification(
        title: String,
        artist: String,
        isPlaying: Boolean = true,
        artwork: Bitmap? = null,
    ): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        val skipPrevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_SKIP_PREVIOUS },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val playPauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val skipNextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_SKIP_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        val playPauseLabel = if (isPlaying) "Pause" else "Play"

        // NOTE: do NOT call `setSilent(true)` here. The SILENT flag it adds
        // causes Pixel's lockscreen notification filter (Android 14+) to
        // hide the notification entirely — the media card never renders
        // on the lock screen, even though `VISIBILITY_PUBLIC` and the
        // MediaStyle + MediaSession token are set correctly. The channel
        // is already `IMPORTANCE_LOW`, which handles the sound /
        // vibration / heads-up suppression. Setting silent on top of that
        // is redundant and actively harmful.
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(isPlaying)
            .setContentIntent(contentPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_widget_skip_previous, "Previous", skipPrevIntent)
            .addAction(playPauseIcon, playPauseLabel, playPauseIntent)
            .addAction(R.drawable.ic_widget_skip_next, "Next", skipNextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(mediaSession?.sessionCompatToken)
            )

        if (artwork != null) {
            builder.setLargeIcon(artwork)
        }

        return builder.build()
    }

    private suspend fun fetchArtworkBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val stream = connection.getInputStream()
            val original = BitmapFactory.decodeStream(stream)
            stream.close()
            if (original != null && (original.width > ARTWORK_SIZE || original.height > ARTWORK_SIZE)) {
                Bitmap.createScaledBitmap(original, ARTWORK_SIZE, ARTWORK_SIZE, true).also {
                    if (it !== original) original.recycle()
                }
            } else {
                original
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch notification artwork: $url", e)
            null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Now Playing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the current track and playback controls"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(channel)
    }

    /**
     * Custom [MediaNotification.Provider] that builds notifications using our
     * unified [buildNotification] style. This ensures ExoPlayer playback
     * (local files, SoundCloud) and external playback (Spotify, Apple Music)
     * show identical notifications in the tray.
     *
     * Media3's [DefaultMediaNotificationProvider] is replaced by this so
     * there's no visual difference between playback sources.
     */
    private inner class UnifiedMediaNotificationProvider : MediaNotification.Provider {

        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification {
            val player = mediaSession.player
            val metadata = player.mediaMetadata
            val title = metadata.title?.toString() ?: ""
            val artist = metadata.artist?.toString() ?: ""
            val isPlaying = player.isPlaying

            // Load artwork from the MediaMetadata artworkUri if we don't have it cached
            val artworkUri = metadata.artworkUri
            val artworkUrl = artworkUri?.toString()
            if (artworkUrl != null && artworkUrl != currentArtworkUrl) {
                currentArtworkUrl = artworkUrl
                currentArtworkBitmap = null
                // Fetch async and re-post via callback
                serviceScope.launch {
                    val bitmap = fetchArtworkBitmap(artworkUrl)
                    if (currentArtworkUrl == artworkUrl) {
                        currentArtworkBitmap = bitmap
                        val updated = buildNotification(title, artist, isPlaying, bitmap)
                        onNotificationChangedCallback.onNotificationChanged(
                            MediaNotification(NOTIFICATION_ID, updated)
                        )
                    }
                }
            }

            val notification = buildNotification(title, artist, isPlaying, currentArtworkBitmap)
            return MediaNotification(NOTIFICATION_ID, notification)
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle,
        ): Boolean = false
    }

    /**
     * Wraps ExoPlayer to report external playback state to MediaSession.
     *
     * During external playback (Apple Music / Spotify), ExoPlayer plays silence
     * to keep the service alive. Without this wrapper, the system media controls
     * (notification shade, lock screen) would show the silence loop's position/
     * duration instead of the actual track's. The wrapper:
     * - Reports position/duration from [PlaybackStateHolder] (the real values)
     * - Makes next/prev commands available and routes them to [PlaybackController]
     * - Delegates everything else to the real ExoPlayer
     */
    /**
     * Wraps ExoPlayer to support next/prev commands during external playback.
     *
     * During external playback, ExoPlayer plays silence with a single item.
     * Without this wrapper, the system media controls wouldn't show next/prev
     * buttons. The wrapper advertises these commands and routes them to
     * [PlaybackController].
     *
     * Position/duration are handled differently: the state observer periodically
     * seeks ExoPlayer to match the real external position, so MediaSession
     * naturally reports correct progress without any overrides here.
     */
    class ExternalPlaybackForwardingPlayer(
        private val delegate: ExoPlayer,
        private val playbackController: PlaybackController,
        private val stateHolder: PlaybackStateHolder,
    ) : ForwardingPlayer(delegate) {

        /** When true, next/prev commands are available and routed to PlaybackController. */
        var externalMode = false

        @Volatile
        private var queueSnapshot: QueueSnapshotState = QueueSnapshotState.EMPTY

        /** Re-entrancy guard for [updateQueueSnapshot]. See KDoc on that method. */
        private var dispatching = false

        /**
         * Set of external listeners (MediaSession, etc.) that subscribed via
         * [addListener]. We re-emit a curated subset of delegate events to
         * these listeners via [delegateForwarder] — **never** by registering
         * them directly on the delegate. Filtering at this layer is the only
         * way to prevent the underlying ExoPlayer's silence-loop / single-
         * item timeline events from clobbering the synthetic queue we build
         * in [updateQueueSnapshot].
         */
        private val externalListeners =
            java.util.concurrent.CopyOnWriteArraySet<Player.Listener>()

        /**
         * Single internal listener installed on the [delegate] (lazily, on
         * first [addListener]) that re-emits a curated subset of player
         * events to [externalListeners].
         *
         * **What is forwarded** — non-timeline state events that MediaSession
         * and Android Auto need to drive Now Playing UI and trigger
         * `PlaybackController`'s `STATE_ENDED → skipNextInternal` flow during
         * ExoPlayer-native playback: `onIsPlayingChanged`,
         * `onPlaybackStateChanged`, `onPlayWhenReadyChanged`,
         * `onPositionDiscontinuity`, `onPlayerError`, `onIsLoadingChanged`,
         * `onRepeatModeChanged`, `onShuffleModeEnabledChanged`,
         * `onAvailableCommandsChanged`.
         *
         * **What is DELIBERATELY NOT forwarded** — events that would corrupt
         * Auto's queue display by leaking the underlying ExoPlayer's
         * single-item silence-loop / setMediaItems timeline:
         * `onTimelineChanged`, `onMediaItemTransition`, `onTracksChanged`,
         * `onMediaMetadataChanged`. The wrapper synthesizes its own versions
         * of these events in [updateQueueSnapshot] from the real
         * `QueueManager` state, and Auto must only see those.
         *
         * If a regression brings back the silence-loop-shows-in-Auto bug,
         * look here first — adding a forwarded override that touches the
         * timeline surface is almost certainly the cause.
         */
        private val delegateForwarder = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                externalListeners.forEach { it.onIsPlayingChanged(isPlaying) }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                externalListeners.forEach { it.onPlaybackStateChanged(playbackState) }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                externalListeners.forEach { it.onPlayWhenReadyChanged(playWhenReady, reason) }
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                externalListeners.forEach {
                    it.onPositionDiscontinuity(oldPosition, newPosition, reason)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                externalListeners.forEach { it.onPlayerError(error) }
            }
            override fun onIsLoadingChanged(isLoading: Boolean) {
                externalListeners.forEach { it.onIsLoadingChanged(isLoading) }
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                externalListeners.forEach { it.onRepeatModeChanged(repeatMode) }
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                externalListeners.forEach { it.onShuffleModeEnabledChanged(shuffleModeEnabled) }
            }
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                externalListeners.forEach { it.onAvailableCommandsChanged(availableCommands) }
            }
            // Intentionally NOT overriding: onTimelineChanged,
            // onMediaItemTransition, onTracksChanged, onMediaMetadataChanged.
            // See KDoc above.
        }

        /** True once [delegateForwarder] has been installed on [delegate]. */
        private var delegateForwarderInstalled = false

        /** Internal data holder so reads of timeline + index are atomic. */
        data class QueueSnapshotState(
            val items: List<MediaItem>,
            val durationsUs: LongArray,
            val timeline: Timeline,
            val currentMediaId: String?,
        ) {
            companion object {
                val EMPTY = QueueSnapshotState(
                    items = emptyList(),
                    durationsUs = LongArray(0),
                    timeline = QueueTimeline(emptyList(), LongArray(0)),
                    currentMediaId = null,
                )
            }
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (command == COMMAND_GET_TIMELINE ||
                command == COMMAND_GET_CURRENT_MEDIA_ITEM ||
                command == COMMAND_SEEK_TO_MEDIA_ITEM
            ) return true
            if (externalMode && command in EXTERNAL_COMMANDS) return true
            return super.isCommandAvailable(command)
        }

        override fun getAvailableCommands(): Commands {
            val builder = super.getAvailableCommands().buildUpon()
                .addAll(
                    COMMAND_GET_TIMELINE,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_MEDIA_ITEM,
                )
            if (externalMode) {
                builder.addAll(
                    COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS,
                    COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
            }
            return builder.build()
        }

        /**
         * Report the real track duration (from [PlaybackStateHolder]) during
         * external playback instead of the silence-loop file's duration.
         *
         * The silence WAV (`res/raw/silence.wav`) is exactly 10 minutes
         * long. If we fall through to `super.getDuration()` while
         * `stateHolder.duration` hasn't been populated yet (track loading,
         * external state poll lagging behind), Auto receives 600_000 and
         * displays a 10:00 progress bar — sometimes briefly mid-track when
         * the external poll drops, sometimes for the whole track if the
         * race lands wrong. It also causes the bar to stall at 10:00 on
         * any track longer than 10 minutes, since Auto clamps position to
         * the reported duration.
         *
         * Return `C.TIME_UNSET` (indeterminate) instead of leaking the
         * silence WAV's duration. Auto handles the unknown-duration case
         * gracefully (no progress bar / spinner) — much better than wrong
         * data.
         */
        override fun getDuration(): Long {
            if (externalMode) {
                val real = stateHolder.state.value.duration
                return if (real > 0L) real else C.TIME_UNSET
            }
            return super.getDuration()
        }

        override fun getContentDuration(): Long {
            if (externalMode) {
                val real = stateHolder.state.value.duration
                return if (real > 0L) real else C.TIME_UNSET
            }
            return super.getContentDuration()
        }

        // ── External-mode playback-state overrides ──────────────────────
        // During external playback (Spotify Connect, Apple Music) the
        // underlying ExoPlayer plays a silent loop or stays IDLE. Without
        // these overrides, the system MediaSession (and Android Auto)
        // would see "STOPPED" and hide the Now Playing UI even when the
        // external app is actually producing audio. We surface the real
        // playback state from [PlaybackStateHolder], which is kept in sync
        // by the platform-specific handlers (SpotifyPlaybackHandler,
        // MusicKitWebBridge polling).

        override fun isPlaying(): Boolean {
            if (externalMode) return stateHolder.state.value.isPlaying
            return super.isPlaying()
        }

        override fun getPlayWhenReady(): Boolean {
            if (externalMode) return stateHolder.state.value.isPlaying
            return super.getPlayWhenReady()
        }

        override fun getPlaybackState(): Int {
            if (externalMode) {
                // Map external state → Player state. We only ever report
                // READY (whether playing or paused) or IDLE (no track).
                // External handlers don't expose buffering/ended states
                // distinctly enough to map here.
                return if (stateHolder.state.value.currentTrack != null) {
                    Player.STATE_READY
                } else {
                    Player.STATE_IDLE
                }
            }
            return super.getPlaybackState()
        }

        override fun getCurrentPosition(): Long {
            if (externalMode) {
                val real = stateHolder.state.value.position
                if (real >= 0L) return real
            }
            return super.getCurrentPosition()
        }

        override fun getContentPosition(): Long {
            if (externalMode) {
                val real = stateHolder.state.value.position
                if (real >= 0L) return real
            }
            return super.getContentPosition()
        }

        override fun play() {
            // Suppress spurious external-playback resumes that arrive shortly
            // after a user pause. Specifically catches the
            // MusicKit-auto-resume-after-BT-disconnect cascade: when a
            // Bluetooth speaker (e.g. Spark Mini) powers off while Apple Music
            // is paused, the WebView's MusicKit briefly state-flickers
            // paused→playing→loading on audio session interruption recovery,
            // Media3 sees the playing state event and re-issues PLAY through
            // the MediaSession, which lands here. Without the guard, our
            // togglePlayPause's resume branch would re-start playback.
            if (externalMode && playbackController.wasRecentlyUserPaused()) {
                Log.w(TAG, "Suppressing wrapper.play() — recent user pause (sincePauseMs=${playbackController.msSinceLastUserPause()})")
                return
            }
            if (externalMode) { playbackController.togglePlayPause(); return }
            super.play()
        }

        override fun pause() {
            // External-mode togglePlayPause is SYMMETRIC: pause-when-playing,
            // resume-when-paused. So a wrapper.pause() that arrives while
            // we're already paused (e.g. spurious MediaSession command during
            // a BT route change) would flip us to PLAYING. Suppress it.
            if (externalMode && !stateHolder.state.value.isPlaying) {
                Log.w(TAG, "Suppressing wrapper.pause() — already paused (toggle would resume)")
                return
            }
            if (externalMode) { playbackController.togglePlayPause(); return }
            super.pause()
        }

        override fun seekToNext() {
            if (externalMode) { playbackController.skipNext(); return }
            super.seekToNext()
        }

        override fun seekToPrevious() {
            if (externalMode) { playbackController.skipPrevious(); return }
            super.seekToPrevious()
        }

        override fun seekToNextMediaItem() {
            if (externalMode) { playbackController.skipNext(); return }
            super.seekToNextMediaItem()
        }

        override fun seekToPreviousMediaItem() {
            if (externalMode) { playbackController.skipPrevious(); return }
            super.seekToPreviousMediaItem()
        }

        // ── Synthetic timeline overrides ────────────────────────────────

        override fun getCurrentTimeline(): Timeline = queueSnapshot.timeline

        override fun getCurrentMediaItemIndex(): Int =
            if (queueSnapshot.currentMediaId != null) 0 else C.INDEX_UNSET

        /** Always 1 when upNext is non-empty (current is at slot 0). */
        override fun getNextMediaItemIndex(): Int =
            if (queueSnapshot.items.size > 1) 1 else C.INDEX_UNSET

        override fun getPreviousMediaItemIndex(): Int = C.INDEX_UNSET

        override fun getMediaItemCount(): Int = queueSnapshot.items.size

        override fun getCurrentMediaItem(): MediaItem? = queueSnapshot.items.firstOrNull()

        override fun getMediaItemAt(index: Int): MediaItem = queueSnapshot.items[index]

        override fun hasNextMediaItem(): Boolean = queueSnapshot.items.size > 1

        // hasPreviousMediaItem stays false for v1 (no history surface).
        override fun hasPreviousMediaItem(): Boolean = false

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (mediaItemIndex == 0) {
                // No-op for tapping the current track. Don't forward to the
                // delegate because that would seek inside the silence loop.
                return
            }
            val queueIndex = mediaItemIndex - 1
            if (queueIndex >= 0) {
                playbackController.playFromQueue(queueIndex)
            }
        }

        override fun seekToDefaultPosition(mediaItemIndex: Int) = seekTo(mediaItemIndex, 0L)

        // ── Auto reorder / remove ───────────────────────────────────────

        override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
            // Both indices include the current track at slot 0; queue
            // mutations operate on upNext, so subtract 1.
            val from = currentIndex - 1
            val to = newIndex - 1
            if (from >= 0 && to >= 0) {
                playbackController.moveInQueue(from, to)
            }
        }

        override fun removeMediaItem(index: Int) {
            val queueIndex = index - 1
            if (queueIndex >= 0) {
                playbackController.removeFromQueue(queueIndex)
            }
        }

        override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
            Log.w(TAG, "addMediaItems ignored — Auto voice search not yet wired (got ${mediaItems.size} items at index $index)")
            // No-op for v1 — Auto only invokes this for voice search, and we
            // don't have a browse tree resolved from MediaItem to our
            // resolver pipeline yet.
        }

        // ── Listener registry ───────────────────────────────────────────

        /**
         * Register a listener for player events.
         *
         * **Filtered-forwarder strategy.** External listeners are kept in
         * [externalListeners] and never registered on the underlying
         * [delegate]. On first registration we install our single
         * [delegateForwarder] on the delegate; it re-emits a curated set of
         * non-timeline events to [externalListeners]. The wrapper itself
         * synthesizes `onTimelineChanged` / `onMediaItemTransition` from the
         * real `QueueManager` state via [updateQueueSnapshot].
         *
         * **Why not `super.addListener`?** `ForwardingPlayer.addListener`
         * registers the listener on the delegate, which then fires every
         * event the delegate emits — including the single-item silence-loop
         * `onTimelineChanged` that arrives after every `setMediaItems` call
         * (track transitions, external→external switches, prepare). When
         * those events leak through, Android Auto sees a size-1 timeline
         * after a track change and the Up Next list goes empty until the
         * next QueueManager mutation. That regression is exactly what this
         * class structure exists to prevent.
         */
        override fun addListener(listener: Player.Listener) {
            val wasFirst = externalListeners.isEmpty()
            externalListeners.add(listener)
            if (wasFirst && !delegateForwarderInstalled) {
                delegate.addListener(delegateForwarder)
                delegateForwarderInstalled = true
            }
            // DO NOT call super.addListener(listener). Doing so leaks the
            // delegate's silence-loop timeline events to Auto. See KDoc.
        }

        override fun removeListener(listener: Player.Listener) {
            externalListeners.remove(listener)
            if (externalListeners.isEmpty() && delegateForwarderInstalled) {
                delegate.removeListener(delegateForwarder)
                delegateForwarderInstalled = false
            }
            // No super.removeListener — we never called super.addListener.
        }

        /**
         * Public entry point invoked by [PlaybackService] when a new queue
         * snapshot arrives or [PlaybackStateHolder.state.currentTrack]
         * changes. Rebuilds the [QueueTimeline], swaps the volatile snapshot,
         * and dispatches synthesized [Player.Listener] events.
         *
         * **Main-thread invariant.** Caller must invoke on Looper.getMainLooper().
         *
         * **Re-entrancy.** Listener callbacks must NOT synchronously call back
         * into this method. Re-entrant calls are detected and dropped (with a
         * warning log) to avoid clobbering the snapshot mid-dispatch. Auto's
         * typical usage doesn't trigger this; the guard is defensive.
         */
        fun updateQueueSnapshot(currentTrack: TrackEntity?, upNext: List<TrackEntity>) {
            if (dispatching) {
                Log.w(TAG, "updateQueueSnapshot called re-entrantly from a listener callback; ignoring inner call to avoid clobbering snapshot state")
                return
            }
            dispatching = true
            try {
                // INVARIANT: items.isEmpty() iff currentTrack == null. We
                // MUST NOT emit a snapshot with upNext populated but no
                // current track. Reason: our [getCurrentMediaItemIndex]
                // returns C.INDEX_UNSET (-1) when [queueSnapshot.currentMediaId]
                // is null. Media3 IPCs the timeline + currentMediaItemIndex
                // together to the controller (Android Auto), and the
                // controller's PlayerInfo.getCurrentMediaItem calls
                // timeline.getWindow(currentMediaItemIndex, ...) — i.e.
                // timeline.getWindow(-1, ...) when current is null.
                // [QueueTimeline.getWindow] doesn't bounds-check (Media3
                // contractually shouldn't call with -1 if the timeline is
                // non-empty), so an inconsistent snapshot crashes the
                // controller process with IndexOutOfBoundsException.
                //
                // Race window that hits this: user taps Collection Radio →
                // [PlaybackController.playQueue] → [QueueManager.setQueue]
                // emits a new snapshot with upNext = N immediately, but
                // [PlaybackStateHolder.currentTrack] doesn't update until
                // the routed handler starts playback ~30-100ms later. The
                // combine in [queueSnapshotJob] fires on the upNext
                // emission first, with currentTrack still null.
                //
                // Fix: when currentTrack is null, emit an empty timeline.
                // The queue becomes visible to Auto once the current
                // track lands.
                val combined: List<TrackEntity> = if (currentTrack != null) {
                    buildList {
                        add(currentTrack)
                        addAll(upNext)
                    }
                } else {
                    emptyList()
                }
                val items = combined.map { it.toAutoMediaItem() }
                val durationsUs = LongArray(combined.size) { i ->
                    val durMs = combined[i].duration ?: 0L
                    if (durMs > 0L) durMs * 1000L else C.TIME_UNSET
                }
                val newTimeline = QueueTimeline(items, durationsUs)
                val previousMediaId = queueSnapshot.currentMediaId
                val newCurrentId = currentTrack?.id
                queueSnapshot = QueueSnapshotState(
                    items = items,
                    durationsUs = durationsUs,
                    timeline = newTimeline,
                    currentMediaId = newCurrentId,
                )

                // Re-emit timeline change to all external listeners.
                for (listener in externalListeners) {
                    listener.onTimelineChanged(
                        newTimeline,
                        Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
                    )
                }
                // Fire onMediaItemTransition only when the current track actually
                // changed (an upNext-only mutation is a timeline change, not a
                // transition).
                if (newCurrentId != previousMediaId) {
                    val newItem = items.firstOrNull()
                    for (listener in externalListeners) {
                        listener.onMediaItemTransition(
                            newItem,
                            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                        )
                    }
                }
            } finally {
                dispatching = false
            }
        }

        companion object {
            private val EXTERNAL_COMMANDS = setOf(
                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_GET_TIMELINE, COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_MEDIA_ITEM,
            )
        }
    }
}
