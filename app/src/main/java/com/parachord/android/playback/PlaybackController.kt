package com.parachord.android.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.parachord.shared.platform.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.parachord.android.BuildConfig
import com.parachord.shared.api.LastFmClient
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.ImageEnrichmentService
import com.parachord.android.data.metadata.MbidEnrichmentService
import com.parachord.android.playback.handlers.AppleMusicPlaybackHandler
import com.parachord.android.playback.handlers.PlaybackAction
import com.parachord.android.resolver.ResolverManager
import com.parachord.android.resolver.ResolverScoring
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import com.parachord.android.widget.MiniPlayerWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Bridges the UI layer to the PlaybackService via MediaController, and routes
 * playback through the PlaybackRouter for multi-source support.
 *
 * Queue management is delegated to [QueueManager] which mirrors the desktop
 * app's queue logic (current track separate from queue, play history, etc.).
 *
 * Handles two playback modes:
 * - ExoPlayer: local files, direct streams, SoundCloud (all via MediaController)
 * - External: Spotify Connect via Web API (manages its own playback lifecycle)
 */
class PlaybackController constructor(
    private val context: Context,
    private val stateHolder: PlaybackStateHolder,
    private val router: PlaybackRouter,
    private val queueManager: QueueManager,
    private val queuePersistence: QueuePersistence,
    private val scrobbleManager: ScrobbleManager,
    private val imageEnrichment: ImageEnrichmentService,
    private val mbidEnrichment: MbidEnrichmentService,
    private val lastFmClient: LastFmClient,
    private val resolverManager: ResolverManager,
    private val resolverScoring: ResolverScoring,
    private val trackResolverCache: TrackResolverCache,
    private val widgetUpdater: MiniPlayerWidgetUpdater,
) {
    companion object {
        private const val TAG = "PlaybackController"
        private const val SPINOFF_SIMILAR_LIMIT = 20
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var positionUpdateJob: Job? = null
    private var spotifyStateJob: Job? = null
    private var appleMusicPollingJob: Job? = null
    private var idleTimeoutJob: Job? = null
    /** Tracks an in-flight track advance (auto or manual skip).
     *  Prevents concurrent advances from double-skipping and lets
     *  togglePlayPause() know a transition is still in progress. */
    private var advanceJob: Job? = null
    /** Guard to prevent togglePlayPause resume-check from looping. */
    @Volatile private var resumeReplayInFlight = false

    /** Serializes all calls to [playTrackInternal] so only one play request
     *  runs at a time. Without this, concurrent launches (e.g. playQueue +
     *  auto-advance) race through device picker and polling setup. */
    private val playMutex = Mutex()

    /** Whether playback is currently managed externally (e.g. Spotify Connect). */
    private var isExternalPlayback = false

    /**
     * Track restored from persistence that hasn't been routed yet.
     * On first play after restore, we route this track through the full
     * playback pipeline instead of calling ctrl.play() on the empty ExoPlayer.
     */
    private var pendingRestoredTrack: TrackEntity? = null

    /**
     * How long to keep the foreground service alive after external playback is paused.
     * After this timeout, the service is fully demoted and the process may be killed.
     */
    private val IDLE_TIMEOUT_MS = 5L * 60 * 1000 // 5 minutes

    /**
     * Partial WakeLock held during external playback (Spotify/Apple Music) to keep
     * the CPU active for state polling and auto-advance detection when the screen is off.
     * ExoPlayer manages its own WakeLock internally.
     */
    private val wakeLock: PowerManager.WakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Parachord::ExternalPlayback")
            .apply { setReferenceCounted(false) }
    }

    /**
     * WiFi lock held during external playback to prevent the WiFi radio from
     * sleeping when the screen is off. ExoPlayer handles this automatically via
     * WAKE_MODE_NETWORK, but Apple Music streams through a WebView that needs
     * an explicit WiFi lock to maintain its connection.
     */
    private val wifiLock: WifiManager.WifiLock by lazy {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Parachord::ExternalPlayback")
            .apply { setReferenceCounted(false) }
    }

    // NOTE: We intentionally do NOT manage audio focus for external playback.
    // Apple Music (WebView): Chromium's internal AudioFocusDelegate handles it.
    //   Our explicit requestAudioFocus() competed with Chromium's, causing
    //   AUDIOFOCUS_LOSS → we paused MusicKit → stopped playback mid-song.
    // Spotify: Manages its own audio focus as a separate app.
    // ExoPlayer: handleAudioFocus=true handles it when ExoPlayer is playing.

    /** Listener called when a track naturally completes (auto-advance, not user skip). */
    var onTrackEndedListener: (() -> Unit)? = null

    /** Listener called when the user manually changes playback (skip, play different track). */
    var onUserPlaybackActionListener: (() -> Unit)? = null

    // ── Spinoff state ──────────────────────────────────────────────────────
    /** Separate pool of resolved similar tracks (NOT in the queue — desktop behavior). */
    private val spinoffPool = mutableListOf<TrackEntity>()
    /** The track that was playing when spinoff was activated. */
    private var spinoffSourceTrack: TrackEntity? = null
    /** Previous playback context to restore on exit (queue itself is never modified). */
    private var preSpinoffContext: PlaybackContext? = null
    private var spinoffJob: Job? = null

    // ── Pool-based spinoff (Mode C) refill state ────────────────────────
    // Captured by [startPoolBasedSpinoff]; read by Task 5's refill loop
    // (`parachord://play/radio?refill=…`). Only meaningful while the
    // current spinoff is pool-based (id == "pool-based"). For
    // seed-based spinoffs these stay null/0.
    // TODO(#121 Task 5): reset poolRefillUrl / poolRefillEmptyCount /
    //  poolLastRefillTs in `exitSpinoff()` once the refill loop lands.
    private var poolRefillUrl: String? = null
    private var poolRefillEmptyCount: Int = 0
    private var poolLastRefillTs: Long = 0L

    fun connect() {
        if (controllerFuture != null) return

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future

        future.addListener({
            controller = future.get()
            setupPlayerListener()
            // Restore persisted queue (if enabled) and start auto-save
            scope.launch {
                val restoredTrack = queuePersistence.restoreIfEnabled()
                if (restoredTrack != null) {
                    val snapshot = queueManager.snapshot.value
                    stateHolder.update {
                        copy(
                            currentTrack = restoredTrack,
                            isPlaying = false,
                            position = 0L,
                            upNext = snapshot.upNext,
                            playbackContext = snapshot.playbackContext,
                            shuffleEnabled = snapshot.shuffleEnabled,
                        )
                    }
                    // Mark this track as needing full routing when play is
                    // tapped. Don't set metadata on ExoPlayer here — that
                    // would make the system notification show a "playing"
                    // track when nothing is actually playing. Our own mini
                    // player reads from stateHolder, not ExoPlayer.
                    pendingRestoredTrack = restoredTrack
                }
                queuePersistence.startObserving()
                scrobbleManager.startObserving()
                widgetUpdater.startObserving()
                // Eagerly warm the MusicKit WebView + JS bridge so the first
                // Apple Music play doesn't pay the ~500ms initialization cost.
                router.getAppleMusicHandler().warmUp()
                // Watch for unwanted MusicKit auto-resumes during a user-pause
                // window. MusicKit's audio session interruption recovery
                // (e.g. BT speaker powered off while paused) flips to playing
                // independently of any app command — our wrapper.play()
                // suppression catches the cascading MediaSession command,
                // but MusicKit's own state machine sustains audio. Force-pause
                // it back. The push event runs even when our polling loop
                // is stopped (which it is during a user pause).
                router.getAppleMusicHandler().musicKitBridge.onResumedToPlaying = {
                    if (wasRecentlyUserPaused()) {
                        Log.w(TAG, "MusicKit auto-resumed during user-pause window — force-pausing (sincePauseMs=${msSinceLastUserPause()})")
                        scope.launch(Dispatchers.Main) {
                            try { router.getAppleMusicHandler().pause() } catch (e: Exception) {
                                Log.w(TAG, "Force-pause after MusicKit auto-resume failed", e)
                            }
                        }
                    }
                }
            }
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        positionUpdateJob?.cancel()
        spotifyStateJob?.cancel()
        appleMusicPollingJob?.cancel()
        idleTimeoutJob?.cancel()
        if (isExternalPlayback) sendExternalPlaybackStop()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        scope.launch { router.stopExternalPlayback() }
    }

    /** Play a single track immediately (clears the queue). */
    fun playTrack(track: TrackEntity, context: PlaybackContext? = null) {
        onUserPlaybackActionListener?.invoke()
        clearUserPauseMark()
        queueManager.clearQueue()
        if (context != null) queueManager.setContext(context)
        scope.launch { playTrackInternal(track) }
    }

    /**
     * Play a list of tracks starting at the given index.
     * Remaining tracks become the queue, tagged with [context].
     */
    fun playQueue(
        tracks: List<TrackEntity>,
        startIndex: Int = 0,
        context: PlaybackContext? = null,
        shuffle: Boolean = queueManager.shuffleEnabled,
    ) {
        onUserPlaybackActionListener?.invoke()
        clearUserPauseMark()
        val track = queueManager.setQueue(tracks, startIndex, context, shuffle) ?: return
        scope.launch { playTrackInternal(track) }
    }

    /** Append tracks to the end of the queue. */
    fun addToQueue(tracks: List<TrackEntity>) {
        queueManager.addToQueue(tracks)
        syncQueueState()
        // Pre-enrich queued tracks with MBIDs so they're ready for scrobbling
        enrichQueuedTracks(tracks)
    }

    /** Insert tracks at the front of the queue (play next). */
    fun insertNext(tracks: List<TrackEntity>) {
        queueManager.insertNext(tracks)
        syncQueueState()
        // Pre-enrich queued tracks with MBIDs so they're ready for scrobbling
        enrichQueuedTracks(tracks)
    }

    /** Fire MBID mapper lookups for tracks entering the queue. */
    private fun enrichQueuedTracks(tracks: List<TrackEntity>) {
        val requests = tracks
            .filter { it.recordingMbid == null }
            .map { com.parachord.android.data.metadata.TrackEnrichmentRequest(it.id, it.artist, it.title) }
        if (requests.isNotEmpty()) {
            mbidEnrichment.enrichBatchInBackground(requests)
        }
    }

    fun skipNext() {
        skipNextInternal(userInitiated = true)
    }

    private fun skipNextInternal(userInitiated: Boolean) {
        // If a previous advance is still in-flight (resolver/routing not finished),
        // ignore non-user auto-advance signals to prevent double-skipping.
        if (!userInitiated && advanceJob?.isActive == true) {
            Log.d(TAG, "skipNext: ignoring auto-advance — previous advance still in-flight")
            return
        }

        if (userInitiated) {
            onUserPlaybackActionListener?.invoke()
        } else {
            onTrackEndedListener?.invoke()
        }

        // Cancel any in-flight advance — user skip takes priority
        advanceJob?.cancel()

        // Spinoff mode: pull from separate pool, bypass queue entirely (desktop behavior)
        if (stateHolder.state.value.spinoffMode) {
            if (spinoffPool.isNotEmpty()) {
                val next = spinoffPool.removeAt(0)
                Log.d(TAG, "Spinoff: playing next '${next.title}' by ${next.artist} (${spinoffPool.size} remaining)")
                advanceJob = scope.launch {
                    try {
                        playTrackInternal(next)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Spinoff advance to '${next.title}' failed: ${e.message}", e)
                    }
                }
                return
            } else {
                // Pool exhausted — exit spinoff, fall through to regular queue
                Log.d(TAG, "Spinoff: pool exhausted, returning to queue")
                exitSpinoff()
            }
        }

        val currentTrack = stateHolder.state.value.currentTrack
        val next = queueManager.skipNext(currentTrack)
        if (next == null) {
            Log.d(TAG, "skipNext: queue empty, stopping playback")
            if (isExternalPlayback) {
                stopSpotifyStatePolling()
                stopAppleMusicStatePolling()
                scope.launch { router.stopExternalPlayback() }
                sendExternalPlaybackStop()
                isExternalPlayback = false
            }
            stateHolder.update { copy(isPlaying = false) }
            return
        }
        Log.d(TAG, "skipNext: advancing to '${next.title}' by ${next.artist}")
        advanceJob = scope.launch {
            try {
                val transitionStart = System.currentTimeMillis()
                // Don't call router.stopExternalPlayback() here — it sends an
                // async stop() to MusicKit/Spotify that races with the new play()
                // call. The stop arrives after the new track starts, pausing it.
                // playTrackInternal() handles the transition: for external→external,
                // the new handler.play() replaces the queue directly. For
                // external→ExoPlayer, the ExoPlayer path sends the stop.
                playTrackInternal(next)
                Log.d(TAG, "skipNext: full transition took ${System.currentTimeMillis() - transitionStart}ms")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Advance to '${next.title}' failed: ${e.message}", e)
            }
        }
    }

    fun skipPrevious() {
        onUserPlaybackActionListener?.invoke()
        if (isExternalPlayback) {
            val position = when (val handler = router.activeExternalHandler) {
                is AppleMusicPlaybackHandler -> handler.getPosition()
                else -> router.getSpotifyHandler().getPosition()
            }
            if (position > 3000) {
                scope.launch { router.activeExternalHandler?.seekTo(0) }
                return
            }
        } else {
            val ctrl = controller ?: return
            if (ctrl.currentPosition > 3000) {
                ctrl.seekTo(0)
                return
            }
        }

        val currentTrack = stateHolder.state.value.currentTrack
        val prev = queueManager.skipPrevious(currentTrack) ?: return
        advanceJob?.cancel()
        advanceJob = scope.launch {
            try {
                playTrackInternal(prev)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Skip previous to '${prev.title}' failed: ${e.message}", e)
            }
        }
    }

    /** User tapped a track in the queue UI — play from that point. */
    fun playFromQueue(index: Int) {
        val currentTrack = stateHolder.state.value.currentTrack
        val track = queueManager.playFromQueue(index, currentTrack) ?: return
        advanceJob?.cancel()
        advanceJob = scope.launch {
            try {
                playTrackInternal(track)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Play from queue '${track.title}' failed: ${e.message}", e)
            }
        }
    }

    /** Drag-to-reorder in the queue. */
    fun moveInQueue(from: Int, to: Int) {
        queueManager.moveInQueue(from, to)
        syncQueueState()
    }

    /** Remove a single track from the queue. */
    fun removeFromQueue(index: Int) {
        queueManager.removeFromQueue(index)
        syncQueueState()
    }

    /** Clear the queue. */
    fun clearQueue() {
        queueManager.clearQueue()
        syncQueueState()
    }

    /**
     * Pause playback if currently playing; no-op if already paused. Use this
     * (instead of [togglePlayPause]) for system-driven events like
     * `ACTION_AUDIO_BECOMING_NOISY` (Bluetooth / wired-headset disconnect)
     * where a symmetric toggle would unexpectedly RESUME a user-paused
     * track when the audio output went away.
     */
    fun pause() {
        if (stateHolder.state.value.isPlaying) togglePlayPause()
    }

    /**
     * Wall-clock time of the most recent transition INTO a paused state via
     * [togglePlayPause] (either branch — external or local ExoPlayer). Used by
     * [ExternalPlaybackForwardingPlayer.play] to suppress spurious play
     * commands that arrive shortly after a user pause — specifically the
     * MusicKit-auto-resume-after-BT-interrupt cascade where the WebView's
     * MusicKit briefly state-flickers paused→playing→loading on output route
     * change, Media3 sees a state event and re-issues PLAY through the
     * MediaSession, and `togglePlayPause` interprets it as a resume.
     *
     * Set to a positive timestamp on user pause; cleared (set to 0) on user
     * resume / playTrack / playQueue. Read via [wasRecentlyUserPaused].
     */
    @Volatile
    private var lastUserPauseTimeMs: Long = 0

    /** Window after a user pause during which we suppress spurious external
     *  play commands (see [lastUserPauseTimeMs]). 5s comfortably absorbs the
     *  MusicKit interrupt-recovery flicker (~50–500ms in observed traces) and
     *  is short enough that "I paused by mistake, let me tap play again"
     *  still works without an artificial delay. */
    private val pauseSuppressionWindowMs: Long = 5_000

    /** True while [pauseSuppressionWindowMs] hasn't elapsed since the last
     *  user pause. */
    fun wasRecentlyUserPaused(): Boolean {
        val pauseTime = lastUserPauseTimeMs
        return pauseTime > 0 && (System.currentTimeMillis() - pauseTime) < pauseSuppressionWindowMs
    }

    /** Diagnostic: ms since the last user pause, or -1 if none in this
     *  process lifetime. */
    fun msSinceLastUserPause(): Long {
        val pauseTime = lastUserPauseTimeMs
        return if (pauseTime > 0) System.currentTimeMillis() - pauseTime else -1
    }

    private fun markUserPaused() {
        lastUserPauseTimeMs = System.currentTimeMillis()
    }
    private fun clearUserPauseMark() {
        lastUserPauseTimeMs = 0
    }

    fun togglePlayPause() {
        if (isExternalPlayback) {
            scope.launch {
                // If an advance is in-flight (auto-advance resolver/routing still running),
                // wait for it to complete rather than resuming the stale previous handler.
                val pendingAdvance = advanceJob
                if (pendingAdvance?.isActive == true) {
                    Log.d(TAG, "togglePlayPause: advance in-flight, waiting for it to complete")
                    pendingAdvance.join()
                    // After the advance completes, playback should already be started.
                    // If it's not playing (advance failed), fall through to re-play.
                    val handler = router.activeExternalHandler
                    val playing = when (handler) {
                        is AppleMusicPlaybackHandler -> handler.isPlaying()
                        null -> false
                        else -> router.getSpotifyHandler().isPlaying()
                    }
                    if (playing) return@launch
                    // Advance completed but playback didn't start — fall through to re-play
                }

                val handler = router.activeExternalHandler
                if (handler == null) {
                    // No active handler — external playback stalled. Re-play the current track.
                    val track = stateHolder.state.value.currentTrack ?: return@launch
                    Log.d(TAG, "togglePlayPause: no active handler, re-playing '${track.title}'")
                    playTrackInternal(track, skipReselect = true)
                    return@launch
                }
                val isCurrentlyPlaying = when (handler) {
                    is AppleMusicPlaybackHandler -> handler.isPlaying()
                    else -> router.getSpotifyHandler().isPlaying()
                }
                if (isCurrentlyPlaying) {
                    markUserPaused()
                    handler.pause()
                    stateHolder.update { copy(isPlaying = false) }
                    // Stop polling and release WakeLock — no need to keep the CPU
                    // awake at 500ms intervals while paused.
                    stopSpotifyStatePolling()
                    stopAppleMusicStatePolling()
                    // DON'T demote from foreground — keep the notification visible
                    // (showing paused state) like other music players. The state
                    // observer updates the notification with isPlaying=false.
                    // The idle timeout handles cleanup if the user doesn't resume.
                    // Also pause ExoPlayer's silence so the MediaSession reports
                    // "paused" and the system widget shows the play button.
                    sendSilencePlayback(pause = true)
                    startIdleTimeout()
                } else {
                    // Cancel idle timeout — user is resuming
                    cancelIdleTimeout()
                    clearUserPauseMark()
                    handler.resume()
                    stateHolder.update { copy(isPlaying = true) }
                    // Resume ExoPlayer's silence so MediaSession reports "playing"
                    sendSilencePlayback(pause = false)
                    // Re-promote to foreground and restart state polling
                    val track = stateHolder.state.value.currentTrack
                    if (track != null) sendExternalPlaybackStart(track)
                    when (handler) {
                        is AppleMusicPlaybackHandler -> startAppleMusicStatePolling()
                        else -> startSpotifyStatePolling()
                    }
                    // If resume didn't actually start playback (stale session),
                    // re-play the track from scratch — but only once per user
                    // interaction. The flag prevents the MediaSession/notification
                    // from re-triggering togglePlayPause in a loop after replay.
                    delay(1500)
                    if (resumeReplayInFlight) return@launch
                    val stillNotPlaying = when (handler) {
                        is AppleMusicPlaybackHandler -> !handler.isPlaying()
                        else -> !router.getSpotifyHandler().isPlaying()
                    }
                    if (stillNotPlaying) {
                        val replayTrack = stateHolder.state.value.currentTrack ?: return@launch
                        Log.d(TAG, "togglePlayPause: resume failed, re-playing '${replayTrack.title}'")
                        resumeReplayInFlight = true
                        // skipReselect: preserve the current resolver so a manual
                        // source switch (e.g. Spotify→Apple Music) doesn't get
                        // overridden back to the auto-selected best source.
                        playTrackInternal(replayTrack, skipReselect = true)
                        // Keep the flag true for 3s so rapid re-triggers from
                        // MediaSession/notification don't cause another replay
                        delay(3000)
                        resumeReplayInFlight = false
                    }
                }
            }
            return
        }

        // If we have a pending restored track (app restarted, user taps play),
        // route it through the full playback pipeline instead of calling
        // ctrl.play() on ExoPlayer (which has no real media items, causing
        // it to auto-advance to the next track in the queue).
        val restored = pendingRestoredTrack
        if (restored != null) {
            pendingRestoredTrack = null
            scope.launch { playTrackInternal(restored) }
            return
        }

        val ctrl = controller ?: return
        if (ctrl.isPlaying) {
            markUserPaused()
            ctrl.pause()
        } else {
            clearUserPauseMark()
            ctrl.play()
        }
    }

    fun toggleShuffle() {
        // Shuffle is disabled during spinoff mode (desktop behavior)
        if (stateHolder.state.value.spinoffMode) return
        val newState = queueManager.toggleShuffle()
        stateHolder.update { copy(shuffleEnabled = newState) }
        if (!isExternalPlayback) {
            controller?.shuffleModeEnabled = false // We manage shuffle ourselves
        }
        syncQueueState()
    }

    fun seekTo(positionMs: Long) {
        if (isExternalPlayback) {
            scope.launch {
                router.activeExternalHandler?.seekTo(positionMs)
                stateHolder.update { copy(position = positionMs) }
            }
            return
        }
        controller?.seekTo(positionMs)
    }

    private suspend fun playTrackInternal(track: TrackEntity, skipReselect: Boolean = false) = playMutex.withLock {
        try {
            playTrackInternalUnsafe(track, skipReselect)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Don't swallow cancellation
        } catch (e: Exception) {
            Log.e(TAG, "playTrackInternal failed for '${track.title}': ${e.message}", e)
            // Leave the app in a usable state — show the track in the mini player
            // even though playback failed, so the user can tap play to retry.
            stateHolder.update { copy(currentTrack = track, isPlaying = false) }
        }
    }

    private suspend fun playTrackInternalUnsafe(track: TrackEntity, skipReselect: Boolean = false) {
        // Cancel any idle timeout — we're actively playing now
        cancelIdleTimeout()

        // Re-select the best resolver from cached sources before routing.
        // Stored tracks may have a stale `resolver` field from when they were first
        // added (e.g. "spotify"), but the user may now prioritize a different resolver
        // (e.g. "applemusic"). The TrackResolverCache has live resolution results
        // sorted by the user's current priority order, so we pick the best one.
        // Skipped when the user manually picks a source via the resolver dropdown.
        var routedTrack = if (skipReselect) track else reselectBestSource(track)

        // If the track has no resolver and no source URL, try resolving on-the-fly.
        // This handles ephemeral tracks (e.g. weekly playlists, recommendations)
        // that haven't been through the resolver pipeline yet.
        if (routedTrack.resolver == null && routedTrack.sourceUrl == null &&
            routedTrack.spotifyUri == null && routedTrack.spotifyId == null &&
            routedTrack.soundcloudId == null && routedTrack.appleMusicId == null
        ) {
            Log.d(TAG, "Track '${routedTrack.title}' has no source, resolving on-the-fly")
            routedTrack = resolveOnTheFly(routedTrack)
        }

        val action = router.route(routedTrack)
        val snapshot = queueManager.snapshot.value

        if (action == null) {
            Log.w(TAG, "No playback handler for: ${routedTrack.title}")
            playViaExoPlayer(routedTrack)
            return
        }

        when (action) {
            is PlaybackAction.ExoPlayerItem -> {
                if (isExternalPlayback) sendExternalPlaybackStop()
                isExternalPlayback = false
                notifyExternalMode(false)
                stopSpotifyStatePolling()
                stopAppleMusicStatePolling()

                val ctrl = controller ?: return
                // Restore normal ExoPlayer settings after external playback
                ctrl.volume = 1f
                ctrl.repeatMode = Player.REPEAT_MODE_OFF
                ctrl.setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus= */ true,
                )
                ctrl.stop()
                ctrl.setMediaItems(listOf(action.mediaItem), 0, 0L)
                ctrl.prepare()
                ctrl.play()

                stateHolder.update {
                    copy(
                        currentTrack = routedTrack,
                        isPlaying = true,
                        position = 0L,
                        upNext = snapshot.upNext,
                        playbackContext = snapshot.playbackContext,
                        shuffleEnabled = snapshot.shuffleEnabled,
                        streamingMetadata = null, // ExoPlayer = no external source mismatch
                    )
                }
            }

            is PlaybackAction.ExternalPlayback -> {
                val wasAlreadyExternal = isExternalPlayback
                val oldHandler = router.activeExternalHandler
                isExternalPlayback = true
                stopPositionUpdates()
                // Cancel old polling jobs WITHOUT releasing the wake lock — we need
                // continuous CPU wakefulness across the track transition. The new
                // startPolling() call below will take over wake lock ownership.
                // Releasing here would create a gap during handler.play() (a suspend
                // call) where Android can suspend the process.
                spotifyStateJob?.cancel()
                appleMusicPollingJob?.cancel()
                router.getAppleMusicHandler().musicKitBridge.onTrackEnded = null

                // If switching between different external handlers (e.g. Apple Music
                // → Spotify), stop the OLD handler. For same-handler transitions
                // (Apple Music → Apple Music), DON'T stop — handler.play() replaces
                // the queue directly, and an async stop() would race with the new
                // play(), pausing the new track.
                if (wasAlreadyExternal && oldHandler != null && oldHandler != action.handler) {
                    oldHandler.stop()
                }

                // Set ExoPlayer metadata so the MediaSession stays active.
                // CRITICAL: During external→external transitions (e.g. Apple Music
                // track advance while screen is off), do NOT call ctrl.stop() —
                // stopping ExoPlayer triggers MediaSessionService auto-demotion from
                // foreground, and re-promoting via startForegroundService() fails
                // when the app is backgrounded (Android 12+ restriction). Instead,
                // just update the metadata on the already-prepared player.
                // Play a silent audio loop on ExoPlayer so Media3's
                // MediaSessionService sees "player is playing" and keeps the
                // foreground service alive. Without this, Media3 demotes the
                // service after ~11 minutes ("Stopping service due to app idle")
                // because ExoPlayer is idle and the FGS validator sees no active
                // media playback. Audio focus is disabled for the silence to
                // avoid conflicting with Chromium's AudioFocusDelegate (which
                // manages focus for MusicKit WebView audio).
                controller?.let { ctrl ->
                    if (!wasAlreadyExternal) {
                        // Disable audio focus for ExoPlayer during external playback.
                        // Chromium's AudioFocusDelegate handles focus for MusicKit.
                        // If ExoPlayer holds focus, Chromium loses it and pauses audio.
                        ctrl.setAudioAttributes(
                            androidx.media3.common.AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                .build(),
                            /* handleAudioFocus= */ false,
                        )
                    }

                    // Play silent audio to keep the service alive. The
                    // ForwardingPlayer in PlaybackService overrides position/
                    // duration/commands so system media controls show real
                    // track progress, artwork, and next/prev buttons.
                    val silenceUri = android.net.Uri.parse(
                        "android.resource://${context.packageName}/${com.parachord.android.R.raw.silence}"
                    )
                    val artworkUri = routedTrack.artworkUrl?.let { android.net.Uri.parse(it) }
                    val silenceItem = MediaItem.Builder()
                        .setMediaId(routedTrack.id)
                        .setUri(silenceUri)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(routedTrack.title)
                                .setArtist(routedTrack.artist)
                                .setAlbumTitle(routedTrack.album)
                                .setArtworkUri(artworkUri)
                                .build()
                        )
                        .build()
                    ctrl.setMediaItems(listOf(silenceItem))
                    ctrl.repeatMode = Player.REPEAT_MODE_ONE
                    ctrl.volume = 0f
                    ctrl.prepare()
                    ctrl.play()
                }

                // Enable external mode on the ForwardingPlayer so MediaSession
                // reports real position/duration and next/prev commands.
                notifyExternalMode(true)

                // Set UI state optimistically — show the track with a buffering spinner
                // so the user gets instant feedback while the handler connects to
                // the external service. The state polling will clear isBuffering
                // once playback is confirmed.
                stateHolder.update {
                    copy(
                        currentTrack = routedTrack,
                        isPlaying = true,
                        isBuffering = true,
                        position = 0L,
                        duration = routedTrack.duration ?: 0L,
                        upNext = snapshot.upNext,
                        playbackContext = snapshot.playbackContext,
                        shuffleEnabled = snapshot.shuffleEnabled,
                        // Keep the previous artwork URL so the album art image stays
                        // visible (no flash to placeholder) while the new track loads.
                        // But clear title/artist/album so the UI immediately shows the
                        // new track's queued metadata instead of stale streaming info
                        // from the previous song.
                        streamingMetadata = streamingMetadata?.copy(
                            title = null,
                            artist = null,
                            album = null,
                        ),
                    )
                }

                // Promote (or re-promote) the foreground service BEFORE the
                // handler.play() call, which can take 5-15 seconds for Spotify
                // device wake + API calls. Without foreground status during this
                // gap, Android can kill the process between songs.
                sendExternalPlaybackStart(routedTrack)

                action.handler.play(routedTrack)

                // Start the appropriate state polling based on the handler type
                when (action.handler) {
                    is AppleMusicPlaybackHandler ->
                        startAppleMusicStatePolling()
                    else ->
                        startSpotifyStatePolling()
                }
            }

            is PlaybackAction.BrowserPlayback -> {
                // Non-streaming resolver (e.g., Bandcamp) — open in system browser.
                // Show the track in the mini player but don't manage playback state.
                Log.d(TAG, "Opening in browser: ${action.url}")
                stateHolder.update {
                    copy(
                        currentTrack = routedTrack,
                        isPlaying = false,
                        upNext = snapshot.upNext,
                        playbackContext = snapshot.playbackContext,
                    )
                }
                try {
                    val browserIntent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(action.url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(browserIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open browser: ${e.message}")
                }
            }
        }

        // If the track has no artwork, try to fetch it in the background
        enrichArtworkIfMissing(routedTrack)

        // Enrich with MusicBrainz MBIDs in the background
        mbidEnrichment.enrichInBackground(routedTrack.id, routedTrack.artist, routedTrack.title)

        // Pre-resolve the next few queue tracks so their resolver IDs
        // (spotifyId, appleMusicId, etc.) are ready before we need them.
        // This eliminates resolver latency from track transitions.
        // Marked non-priority — these aren't on screen yet (the user
        // is looking at the current track / their current screen), so
        // they shouldn't compete with foreground track-list resolution
        // for the priority lane.
        val upcoming = snapshot.upNext.take(3)
        if (upcoming.isNotEmpty()) {
            trackResolverCache.resolveInBackground(upcoming, priority = false)
        }

        // Check spinoff availability for the new track (unless in spinoff mode)
        if (!stateHolder.state.value.spinoffMode) {
            checkSpinoffAvailability()
        }
    }

    /** Fallback: play directly via ExoPlayer when no handler matches. */
    private fun playViaExoPlayer(track: TrackEntity) {
        val ctrl = controller ?: return
        if (isExternalPlayback) sendExternalPlaybackStop()
        isExternalPlayback = false
        notifyExternalMode(false)
        stopSpotifyStatePolling()
        stopAppleMusicStatePolling()

        // Restore normal ExoPlayer settings after external playback
        ctrl.volume = 1f
        ctrl.repeatMode = Player.REPEAT_MODE_OFF
        ctrl.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus= */ true,
        )

        val mediaItem = track.toAutoMediaItem()
        ctrl.stop()
        ctrl.setMediaItems(listOf(mediaItem), 0, 0L)
        ctrl.prepare()
        ctrl.play()

        val snapshot = queueManager.snapshot.value
        stateHolder.update {
            copy(
                currentTrack = track,
                isPlaying = true,
                position = 0L,
                upNext = snapshot.upNext,
                playbackContext = snapshot.playbackContext,
                shuffleEnabled = snapshot.shuffleEnabled,
                streamingMetadata = null, // ExoPlayer = no external source mismatch
            )
        }
        enrichArtworkIfMissing(track)
    }

    /** Push current queue snapshot to PlaybackState without changing playback fields. */
    private fun syncQueueState() {
        val snapshot = queueManager.snapshot.value
        stateHolder.update {
            copy(
                upNext = snapshot.upNext,
                playbackContext = snapshot.playbackContext,
                shuffleEnabled = snapshot.shuffleEnabled,
            )
        }
    }

    private fun setupPlayerListener() {
        val ctrl = controller ?: return
        ctrl.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isExternalPlayback) {
                    syncState()
                    if (isPlaying) startPositionUpdates() else stopPositionUpdates()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!isExternalPlayback) syncState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isExternalPlayback) {
                    syncState()
                    stateHolder.update {
                        copy(isBuffering = playbackState == Player.STATE_BUFFERING)
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        skipNextInternal(userInitiated = false)
                    }
                }
            }
        })
        syncState()
        if (ctrl.isPlaying) startPositionUpdates()
    }

    private fun syncState() {
        val ctrl = controller ?: return
        val snapshot = queueManager.snapshot.value

        stateHolder.update {
            copy(
                isPlaying = ctrl.isPlaying,
                position = ctrl.currentPosition.coerceAtLeast(0L),
                duration = ctrl.duration.coerceAtLeast(0L),
                upNext = snapshot.upNext,
                playbackContext = snapshot.playbackContext,
                shuffleEnabled = snapshot.shuffleEnabled,
            )
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                val ctrl = controller
                if (ctrl != null && ctrl.isPlaying && !isExternalPlayback) {
                    stateHolder.update {
                        copy(
                            position = ctrl.currentPosition.coerceAtLeast(0L),
                            duration = ctrl.duration.coerceAtLeast(0L),
                        )
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
    }

    /** Poll Spotify Web API for position/state when playing externally. */
    private fun startSpotifyStatePolling() {
        spotifyStateJob?.cancel()
        acquireExternalPlaybackWakeLock()
        // Run on Dispatchers.Default so Doze mode doesn't throttle the polling
        // loop — Dispatchers.Main gets deferred when the screen is off.
        spotifyStateJob = scope.launch(Dispatchers.Default) {
            val spotify = router.getSpotifyHandler()
            // Small initial delay to let the track start
            delay(1000)
            while (isActive && isExternalPlayback) {
                val playing = spotify.isPlaying()
                val position = spotify.getPosition()
                val duration = spotify.getDuration()

                // Build streaming metadata from what Spotify reports is actually playing
                val streamingMeta = buildStreamingMetadata(
                    queuedTrack = stateHolder.state.value.currentTrack,
                    actualTitle = spotify.actualTitle,
                    actualArtist = spotify.actualArtist,
                    actualAlbum = spotify.actualAlbum,
                    actualArtworkUrl = spotify.actualArtworkUrl,
                )

                stateHolder.update {
                    copy(
                        isPlaying = playing,
                        isBuffering = spotify.isConnectionStalled,
                        position = position,
                        duration = duration,
                        streamingMetadata = streamingMeta,
                    )
                }

                // Auto-advance: detect when our track is done via multiple signals
                // (natural end, Spotify autoplay to different track, or item cleared)
                if (spotify.isOurTrackDone()) {
                    withContext(Dispatchers.Main) {
                        skipNextInternal(userInitiated = false)
                    }
                    break
                }

                delay(500)
            }
            // Wake lock is NOT released here — it's managed by stopSpotifyStatePolling()
            // (explicit stop) or by playTrackInternal() when switching to ExoPlayer.
            // Releasing in the polling loop creates a gap during track transitions where
            // Android can suspend the process before the next polling loop starts.
        }
    }

    private fun stopSpotifyStatePolling() {
        spotifyStateJob?.cancel()
        releaseExternalPlaybackWakeLock()
    }

    /** Poll Apple Music (MusicKit JS) for position/state when playing externally. */
    private fun startAppleMusicStatePolling() {
        appleMusicPollingJob?.cancel()
        acquireExternalPlaybackWakeLock()
        val handler = router.getAppleMusicHandler()

        // Guard against multiple end-of-track signals arriving from different
        // detection paths (JS playbackStateDidChange, JS mediaItemDidEndPlaying,
        // or the polling safety net). Without this, concurrent signals would
        // call skipNextInternal() multiple times, skipping tracks in the queue.
        val trackEndHandled = java.util.concurrent.atomic.AtomicBoolean(false)

        // Register track-ended callback from MusicKit JS.
        // On spotty networks MusicKit can fire "ended" when it fails to buffer
        // mid-song. Guard against premature advancement by cross-checking the
        // reported position against the known track duration — only accept the
        // signal if we're genuinely near the end (within 15 seconds) or if we
        // have no duration data to compare against.
        handler.musicKitBridge.onTrackEnded = {
            val position = handler.getPosition()
            val duration = handler.getDuration()
            val playing = handler.isPlaying()
            val knownDuration = stateHolder.state.value.currentTrack?.duration
            val effectiveDuration = when {
                knownDuration != null && knownDuration > 0 -> knownDuration
                duration > 0 -> duration
                else -> null
            }
            // MusicKit resets position to 0 when a track genuinely ends.
            // A position-reset with !isPlaying is a real end, not a mid-song stall
            // (stalls leave position > 0 mid-song).
            val positionReset = position == 0L && !playing
            val nearEnd = positionReset || effectiveDuration == null || effectiveDuration - position < 15_000
            if (nearEnd && trackEndHandled.compareAndSet(false, true)) {
                Log.d(TAG, "Apple Music track ended (JS callback, pos=$position dur=$effectiveDuration playing=$playing)")
                scope.launch(Dispatchers.Main) { skipNextInternal(userInitiated = false) }
            } else if (!nearEnd) {
                Log.w(TAG, "Ignoring spurious track-ended signal (pos=$position dur=$effectiveDuration) — likely network stall")
            }
        }

        // Run on Dispatchers.Default so Doze mode doesn't throttle the polling
        // loop — Dispatchers.Main gets deferred when the screen is off.
        appleMusicPollingJob = scope.launch(Dispatchers.Default) {
            try {
                // Small initial delay to let the track start
                delay(1000)

                // Stall recovery state — tracks consecutive stalled polls and
                // uses exponential backoff between resume attempts.
                var stallCount = 0
                var lastRecoveryAttempt = 0L
                val maxRecoveryBackoffMs = 16_000L
                // Prefetch: preload the next track's catalog data once we're
                // within 30 seconds of the end of the current track.
                var nextTrackPreloaded = false
                // Track the wall-clock time when the polling loop started
                // (shortly after handler.play() returned). Used for auto-resume
                // detection when the WebView pauses the track immediately.
                val pollingStartedAt = System.currentTimeMillis()
                var autoResumeAttempted = false

                // Track consecutive poll timeouts — when the Main thread is
                // deferred (Doze mode, screen off), pollPlaybackState() times out
                // and cached values in MusicKitWebBridge go stale. We must NOT
                // run end-of-track detection on stale data, as a stale
                // isPlaying=false can trigger a false isOurTrackDone().
                var consecutivePollTimeouts = 0

                while (isActive && isExternalPlayback) {
                    // Actively poll JS for fresh position/duration — the
                    // playbackStateDidChange event only fires on state transitions,
                    // not continuously during playback.
                    // Use a timeout because Android defers Dispatchers.Main when
                    // the screen is off — without it, the entire polling loop
                    // hangs waiting for the Main thread, and the process gets killed.
                    val pollResult = kotlinx.coroutines.withTimeoutOrNull(3000) {
                        withContext(Dispatchers.Main) {
                            handler.musicKitBridge.pollPlaybackState()
                        }
                        true // poll succeeded
                    }
                    val pollSucceeded = pollResult != null
                    if (!pollSucceeded) {
                        consecutivePollTimeouts++
                        Log.w(TAG, "Apple Music poll timeout #$consecutivePollTimeouts — Main thread deferred (screen off / Doze)")
                    } else {
                        if (consecutivePollTimeouts > 0) {
                            Log.d(TAG, "Apple Music poll recovered after $consecutivePollTimeouts timeouts")
                        }
                        consecutivePollTimeouts = 0
                    }

                    val position = handler.getPosition()
                    val duration = handler.getDuration()
                    val playing = handler.isPlaying()
                    val stateName = handler.musicKitBridge.playbackStateName

                    // Build streaming metadata from what MusicKit reports is actually playing.
                    // During track transitions, MusicKit fires empty "now playing" events
                    // that clear actualTitle — keep the previous streamingMetadata in that
                    // case to prevent artwork flicker in the UI.
                    val streamingMeta = buildStreamingMetadata(
                        queuedTrack = stateHolder.state.value.currentTrack,
                        actualTitle = handler.musicKitBridge.actualTitle,
                        actualArtist = handler.musicKitBridge.actualArtist,
                        actualAlbum = handler.musicKitBridge.actualAlbum,
                        actualArtworkUrl = handler.musicKitBridge.actualArtworkUrl,
                    )

                    val isStalled = stateName == "stalled" || stateName == "loading"
                    stateHolder.update {
                        copy(
                            isPlaying = playing,
                            isBuffering = isStalled,
                            position = position,
                            duration = duration,
                            // Keep previous streaming metadata during transient states
                            // to prevent artwork resize flicker
                            streamingMetadata = streamingMeta ?: streamingMetadata,
                        )
                    }

                    // ── Stall recovery ──────────────────────────────────────
                    // MusicKit reports "stalled" when the buffer runs dry on
                    // spotty networks. Attempt to resume with exponential
                    // backoff so we pick up where we left off once the network
                    // recovers, rather than sitting silent.
                    if (stateName == "stalled" && position > 0) {
                        stallCount++
                        val now = System.currentTimeMillis()
                        // Backoff: 2s, 4s, 8s, 16s (capped)
                        val backoffMs = (2_000L * (1L shl (stallCount - 1).coerceAtMost(3)))
                            .coerceAtMost(maxRecoveryBackoffMs)
                        if (now - lastRecoveryAttempt >= backoffMs) {
                            lastRecoveryAttempt = now
                            Log.d(TAG, "Apple Music stalled (count=$stallCount), attempting recovery at pos=$position")
                            kotlinx.coroutines.withTimeoutOrNull(3000) {
                                withContext(Dispatchers.Main) {
                                    try {
                                        handler.seekTo(position)
                                        handler.resume()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Stall recovery attempt failed", e)
                                    }
                                }
                            }
                        }
                    } else if (stateName == "playing") {
                        // Reset stall tracking once playback resumes
                        if (stallCount > 0) {
                            Log.d(TAG, "Apple Music recovered from stall after $stallCount polls")
                            stateHolder.update { copy(isBuffering = false) }
                        }
                        stallCount = 0
                        lastRecoveryAttempt = 0L
                    }

                    // ── Auto-resume after background pause ─────────────────
                    // The WebView's Chromium engine sometimes pauses MusicKit
                    // immediately after a track starts when the screen is off
                    // (race condition in Chromium's internal media session
                    // handling during background state). Detect this and resume.
                    if (!autoResumeAttempted && pollSucceeded &&
                        !playing && (stateName == "paused" || stateName == "unknown") &&
                        position <= 1000 &&
                        System.currentTimeMillis() - pollingStartedAt < 10_000
                    ) {
                        autoResumeAttempted = true
                        Log.d(TAG, "Apple Music auto-resume: track paused at pos=$position shortly after start (state=$stateName)")
                        // The system may re-pause immediately after a single resume
                        // (race with Chromium's background media handling). Retry up
                        // to 3 times with increasing delays to outlast the system pause.
                        for (attempt in 1..3) {
                            kotlinx.coroutines.withTimeoutOrNull(3000) {
                                withContext(Dispatchers.Main) {
                                    handler.resume()
                                }
                            }
                            delay(500L * attempt) // 500ms, 1s, 1.5s
                            // Re-poll to check if resume stuck
                            val checkResult = kotlinx.coroutines.withTimeoutOrNull(2000) {
                                withContext(Dispatchers.Main) {
                                    handler.musicKitBridge.pollPlaybackState()
                                }
                                true
                            }
                            if (checkResult != null && handler.isPlaying()) {
                                Log.d(TAG, "Apple Music auto-resume succeeded on attempt $attempt")
                                stateHolder.update { copy(isPlaying = true) }
                                break
                            }
                            Log.d(TAG, "Apple Music auto-resume attempt $attempt: still paused, retrying...")
                        }
                    }

                    // ── Next-track resolver pre-resolution ───────────────────
                    // When within 30s of the end, resolve the next track's Apple
                    // Music ID so it's ready when we need it. We intentionally
                    // do NOT call musicKitBridge.preload() here — the preload()
                    // fires a catalog API request through the same MusicKit JS
                    // instance that's actively streaming, which can disrupt
                    // playback on some Android WebView versions (observed as
                    // state flipping to "unknown"/isPlaying:false mid-song).
                    // The resolver pre-resolution is enough: setQueue() in play()
                    // is fast when the WebView is already warm.
                    if (!nextTrackPreloaded && duration > 0 && duration - position in 1..30_000) {
                        val nextTrack = queueManager.snapshot.value.upNext.firstOrNull()
                        if (nextTrack != null) {
                            nextTrackPreloaded = true
                            scope.launch(Dispatchers.Main) {
                                val nextAmId = reselectBestSource(nextTrack).appleMusicId
                                if (nextAmId != null) {
                                    Log.d(TAG, "Pre-resolved next Apple Music track: $nextAmId (skipping preload to avoid playback disruption)")
                                }
                            }
                        }
                    }

                    // Safety-net track completion detection.
                    // CRITICAL: Skip when poll timed out — cached state is stale and
                    // a stale isPlaying=false would trigger a false track-done signal,
                    // stopping playback mid-song when the screen is off.
                    if (pollSucceeded && handler.isOurTrackDone() && trackEndHandled.compareAndSet(false, true)) {
                        Log.d(TAG, "Apple Music track done (safety net, pos=$position dur=$duration playing=$playing state=$stateName)")
                        withContext(Dispatchers.Main) {
                            skipNextInternal(userInitiated = false)
                        }
                        break
                    } else if (!pollSucceeded && consecutivePollTimeouts >= 6) {
                        // 6 consecutive timeouts = 3+ seconds of no fresh data.
                        // Log but do NOT treat as track-done — the track is likely
                        // still playing on the Apple Music side.
                        Log.w(TAG, "Apple Music: $consecutivePollTimeouts poll timeouts, state is stale (pos=$position dur=$duration playing=$playing) — NOT treating as done")
                    }

                    delay(500)
                }
            } finally {
                // Wake lock is NOT released here — it's managed by
                // stopAppleMusicStatePolling() (explicit stop) or by
                // playTrackInternal() when switching to ExoPlayer.
                // Releasing in the polling loop creates a gap during track
                // transitions where Android can suspend the process.
            }
        }
    }

    private fun stopAppleMusicStatePolling() {
        appleMusicPollingJob?.cancel()
        releaseExternalPlaybackWakeLock()
        // Clear the callback to avoid stale references
        router.getAppleMusicHandler().musicKitBridge.onTrackEnded = null
    }

    /**
     * After pausing external playback, start a timeout that will fully clean up
     * the external playback state if the user doesn't resume within [IDLE_TIMEOUT_MS].
     * This prevents the app from lingering indefinitely in a paused-but-connected state.
     */
    private fun startIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            Log.d(TAG, "Idle timeout reached — cleaning up paused external playback")
            isExternalPlayback = false
            router.stopExternalPlayback()
        }
    }

    private fun cancelIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = null
    }

    private fun acquireExternalPlaybackWakeLock() {
        if (!wakeLock.isHeld) {
            // 1-hour timeout as a safety net — released explicitly when polling stops
            wakeLock.acquire(60 * 60 * 1000L)
            Log.d(TAG, "Acquired WakeLock for external playback polling")
        }
        if (!wifiLock.isHeld) {
            wifiLock.acquire()
            Log.d(TAG, "Acquired WifiLock for external playback streaming")
        }
    }

    private fun releaseExternalPlaybackWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.d(TAG, "Released WakeLock for external playback polling")
        }
        if (wifiLock.isHeld) {
            wifiLock.release()
            Log.d(TAG, "Released WifiLock for external playback streaming")
        }
    }

    /**
     * Tell [PlaybackService] to promote itself to foreground with a persistent
     * notification. This prevents Android from killing the process during
     * external playback (Spotify/Apple Music) when the screen is off.
     */
    private fun sendExternalPlaybackStart(track: TrackEntity) {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_EXTERNAL_PLAYBACK_START
            putExtra(PlaybackService.EXTRA_TRACK_TITLE, track.title)
            putExtra(PlaybackService.EXTRA_TRACK_ARTIST, track.artist)
            putExtra(PlaybackService.EXTRA_TRACK_ARTWORK_URL, track.artworkUrl)
        }
        // Prefer startService() when the service is already running — it works
        // from background (unlike startForegroundService on Android 12+).
        // Only fall back to startForegroundService for the initial promotion
        // when the service might not be running yet.
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.d(TAG, "startService failed, trying startForegroundService: ${e.message}")
            try {
                context.startForegroundService(intent)
            } catch (e2: Exception) {
                Log.w(TAG, "startForegroundService also failed: ${e2.message}")
            }
        }
    }

    /**
     * Tell [PlaybackService] to demote from foreground when external playback ends.
     */
    /**
     * Toggle the ForwardingPlayer's external mode in PlaybackService.
     * When enabled, MediaSession reports real position/duration from
     * the external handler and advertises next/prev commands.
     */
    private fun notifyExternalMode(enabled: Boolean) {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = if (enabled) PlaybackService.ACTION_EXTERNAL_MODE_ON
                     else PlaybackService.ACTION_EXTERNAL_MODE_OFF
        }
        try { context.startService(intent) } catch (_: Exception) {}
    }

    /**
     * Pause or resume ExoPlayer's silent playback directly via intent,
     * bypassing the ForwardingPlayer. This syncs the MediaSession's
     * play/pause state with the external handler so the system widget
     * shows the correct icon.
     */
    private fun sendSilencePlayback(pause: Boolean) {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = if (pause) PlaybackService.ACTION_SILENCE_PAUSE
                     else PlaybackService.ACTION_SILENCE_RESUME
        }
        try { context.startService(intent) } catch (_: Exception) {}
    }

    private fun sendExternalPlaybackStop() {
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_EXTERNAL_PLAYBACK_STOP
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            // On Android 12+, startService() throws when the app is backgrounded.
            // The service will demote itself when it next checks isExternalForeground.
            Log.w(TAG, "sendExternalPlaybackStop: startService failed (app backgrounded): ${e.message}")
        }
    }

    // ── Spinoff public API ────────────────────────────────────────────────

    /**
     * Start spinoff mode: fetch similar tracks for the current track from
     * Last.fm, shuffle them, resolve each one, and begin playing from the pool.
     * Matches the desktop's startSpinoff() logic.
     *
     * Thin delegate over [startSpinoffWithSeed] — kept as the in-app entry
     * point (right-click → Spinoff) so the existing toast / banner copy
     * (`Spinoff from <title>`) stays exactly as it was.
     */
    fun startSpinoff() {
        val track = stateHolder.state.value.currentTrack ?: return
        startSpinoffWithSeed(
            seedArtist = track.artist,
            seedTitle = track.title,
            displayName = "Spinoff from ${track.title}",
        )
    }

    /**
     * Generalized spinoff entry point — used by the in-app right-click →
     * Spinoff action (via [startSpinoff]) and by `parachord://play/radio?artist=…`
     * (Mode B).
     *
     * Seed forms:
     *  - `(artist, title)` → Last.fm `track.getsimilar` cascade (same path
     *    as in-app spinoff has always used).
     *  - `(artist, null)` → Last.fm `artist.getTopTracks` cascade (Mode B
     *    fallback when the deeplink has no track hint). Top tracks isn't
     *    quite "similar tracks", but it's the closest thing Last.fm offers
     *    for an artist-only seed and matches the desktop's Mode B path.
     *
     * [displayName], when non-null, sets the [PlaybackContext.name]
     * directly. When null, falls back to `"Spinoff from $seedTitle"` (or
     * `"Radio: $seedArtist"` when title is also null) — preserves the
     * existing in-app banner copy for the wrapper [startSpinoff].
     *
     * [kickStartFirstTrack] controls whether the first pool track plays
     * immediately once the pool is populated. The deeplink (Mode B) path
     * passes `true` — radio should start playing now, regardless of
     * whatever was previously on. The in-app spinoff path passes the
     * default `false` so the existing track keeps playing and
     * `skipNextInternal` pulls from the pool when it ends. Don't infer
     * this from runtime state — `stateHolder.currentTrack` survives a
     * teardown's `clearQueue()` call (it only clears on track-end /
     * explicit stop), so the previous "currentTrack == null" guard
     * silently failed to kick on Mode B when a song was playing.
     */
    fun startSpinoffWithSeed(
        seedArtist: String,
        seedTitle: String?,
        displayName: String? = null,
        kickStartFirstTrack: Boolean = false,
    ) {
        if (stateHolder.state.value.spinoffMode) return // already active

        spinoffJob?.cancel()
        stateHolder.update { copy(spinoffLoading = true) }

        // Used for toast / no-results copy. Title takes precedence when present.
        val seedDisplay = seedTitle ?: seedArtist
        // Resolved PlaybackContext name (banner). Title-bearing fallback
        // matches the historical in-app `Spinoff from <title>` template.
        val resolvedDisplayName = displayName
            ?: seedTitle?.let { "Spinoff from $it" }
            ?: "Radio: $seedArtist"

        spinoffJob = scope.launch(Dispatchers.IO) {
            try {
                // 1. Fetch similar tracks (or top tracks for artist-only seeds).
                //    Both endpoints return `(name, artist.name)` pairs that we
                //    can map uniformly; the only difference is the wire shape.
                data class PoolSeed(val name: String, val artistName: String)
                val pool: List<PoolSeed> = if (seedTitle != null) {
                    val response = lastFmClient.getSimilarTracks(
                        track = seedTitle,
                        artist = seedArtist,
                        apiKey = BuildConfig.LASTFM_API_KEY,
                        limit = SPINOFF_SIMILAR_LIMIT,
                    )
                    response.similartracks?.track.orEmpty().mapNotNull {
                        val a = it.artist?.name ?: return@mapNotNull null
                        PoolSeed(it.name, a)
                    }
                } else {
                    val response = lastFmClient.getArtistTopTracks(
                        artist = seedArtist,
                        apiKey = BuildConfig.LASTFM_API_KEY,
                        limit = SPINOFF_SIMILAR_LIMIT,
                    )
                    response.toptracks?.track.orEmpty().mapNotNull {
                        // For top-tracks the artist is the seed artist (LfmTopTrackArtist
                        // mirrors what we requested) but fall back gracefully.
                        val a = it.artist?.name ?: seedArtist
                        PoolSeed(it.name, a)
                    }
                }

                if (pool.isEmpty()) {
                    Log.d(TAG, "Spinoff: no similar/top tracks found for '$seedDisplay'")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No similar tracks found for \"$seedDisplay\"", Toast.LENGTH_SHORT).show()
                    }
                    stateHolder.update { copy(spinoffLoading = false, spinoffAvailable = false) }
                    return@launch
                }

                Log.d(TAG, "Spinoff: found ${pool.size} candidate tracks for '$seedDisplay', resolving...")

                // 2. Convert to TrackEntities and resolve each.
                // Skip Last.fm images — they're almost always placeholder/blank.
                // Album art will be enriched via metadata providers on playback.
                val resolvedTracks = mutableListOf<TrackEntity>()
                for (cand in pool.shuffled()) {
                    val query = "${cand.name} ${cand.artistName}"
                    try {
                        val sources = resolverManager.resolve(
                            query,
                            targetTitle = cand.name,
                            targetArtist = cand.artistName,
                        )
                        val best = resolverScoring.selectBest(sources) ?: continue

                        resolvedTracks.add(
                            TrackEntity(
                                id = "spinoff_${cand.name}_${cand.artistName}".hashCode().toString(),
                                title = cand.name,
                                artist = cand.artistName,
                                sourceUrl = best.url,
                                resolver = best.resolver,
                                spotifyUri = best.spotifyUri,
                                spotifyId = best.spotifyId,
                                soundcloudId = best.soundcloudId,
                                appleMusicId = best.appleMusicId,
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Spinoff: failed to resolve '${cand.name}'", e)
                    }
                }

                if (resolvedTracks.isEmpty()) {
                    Log.d(TAG, "Spinoff: no tracks could be resolved")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No similar tracks found for \"$seedDisplay\"", Toast.LENGTH_SHORT).show()
                    }
                    stateHolder.update { copy(spinoffLoading = false, spinoffAvailable = false) }
                    return@launch
                }

                Log.d(TAG, "Spinoff: resolved ${resolvedTracks.size} tracks, ready for playback")

                // 3. Save previous playback context (queue is NOT modified — desktop behavior).
                //    Source-track concept only applies for the in-app
                //    title-bearing seed; Mode B (artist-only) has no
                //    "current track" to spinoff from.
                preSpinoffContext = queueManager.playbackContext
                spinoffSourceTrack = stateHolder.state.value.currentTrack

                // 4. Populate spinoff pool (separate from queue).
                //    Don't interrupt the current song — let it finish, then
                //    skipNextInternal() will pull from the pool automatically.
                //    For Mode B (deeplink) the queue was already cleared by
                //    the protocol teardown, so the pool will start playing
                //    immediately on the next advance.
                spinoffPool.clear()
                spinoffPool.addAll(resolvedTracks)

                val spinoffContext = PlaybackContext(type = "spinoff", name = resolvedDisplayName)

                stateHolder.update {
                    copy(
                        spinoffMode = true,
                        spinoffLoading = false,
                        spinoffAvailable = true,
                    )
                }

                withContext(Dispatchers.Main) {
                    val toast = if (seedTitle != null) {
                        "Spinning off of $seedTitle - $seedArtist"
                    } else {
                        "Building radio from $seedArtist"
                    }
                    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
                }

                // Kick-start path (Mode B / deeplink): pull the first pool
                // track and play it now. playTrack() will clearQueue() and
                // then re-apply the spinoff context atomically — that's
                // why the context is passed through here instead of being
                // pre-set; a bare setContext() before playTrack() would
                // get clobbered by playTrack's internal clearQueue().
                //
                // Non-kick path (in-app spinoff): the current track keeps
                // playing; set the context now so the banner updates
                // immediately, and let skipNextInternal() pull from the
                // pool when the current song ends.
                if (kickStartFirstTrack && spinoffPool.isNotEmpty()) {
                    val first = spinoffPool.removeAt(0)
                    Log.d(TAG, "Spinoff: kicking off with '${first.title}' by ${first.artist}")
                    withContext(Dispatchers.Main) {
                        playTrack(first, context = spinoffContext)
                    }
                } else {
                    queueManager.setContext(spinoffContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Spinoff: failed to start", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to fetch similar tracks", Toast.LENGTH_SHORT).show()
                }
                stateHolder.update { copy(spinoffLoading = false) }
            }
        }
    }

    /**
     * Pool-based spinoff (Mode C of `parachord://play/radio`).
     *
     * No Last.fm seed step — the caller supplies an already-resolved pool,
     * pre-built from inline `?tracks=` JSON or a fetched JSPF/XSPF/M3U
     * tracklist. Mirrors desktop's "externally curated pool" path.
     *
     * [refillUrl], when non-null, is captured for Task 5's refill loop to
     * re-fetch from once `spinoffPool.size < 3`. Stored here; not read in
     * this commit.
     *
     * [displayName] is the station name shown in the banner. Pool-based
     * spinoffs have no source track, so the banner branch (Task 6) renders
     * just this string instead of "Spun off from X by Y".
     *
     * The spinoff [PlaybackContext] uses `id = "pool-based"` as a sentinel
     * so Task 6's banner branch can distinguish pool-based from seed-based.
     */
    fun startPoolBasedSpinoff(
        initialPool: List<TrackEntity>,
        displayName: String,
        refillUrl: String? = null,
    ) {
        if (initialPool.isEmpty()) {
            Log.w(TAG, "startPoolBasedSpinoff: empty pool, ignoring")
            return
        }

        spinoffJob?.cancel()
        spinoffPool.clear()
        // Pool-based has no source track — Task 6's banner branch keys off
        // `spinoffSourceTrack == null` to render "$displayName" rather
        // than "Spun off from $title by $artist".
        spinoffSourceTrack = null

        // Save previous playback context (queue is NOT modified — desktop behavior)
        preSpinoffContext = queueManager.playbackContext

        spinoffPool.addAll(initialPool)

        // Capture refill state for Task 5's refill loop. Reset siblings
        // (every entry resets — harmless if exitSpinoff hasn't yet wired
        // its own reset, since they're re-initialized here on every call).
        poolRefillUrl = refillUrl
        poolRefillEmptyCount = 0
        poolLastRefillTs = 0L

        val spinoffContext = PlaybackContext(
            type = "spinoff",
            name = displayName,
            // Sentinel — Task 6's banner branch uses this to distinguish
            // pool-based ("just show displayName") from seed-based
            // ("Spun off from $title by $artist").
            id = "pool-based",
        )

        stateHolder.update {
            copy(
                spinoffMode = true,
                spinoffLoading = false,
                spinoffAvailable = true,
            )
        }

        // Kick-start: pull the first pool track and play it now. Mirrors
        // the Mode B kick-start path in [startSpinoffWithSeed] — playTrack
        // clearQueue()s atomically and re-applies the spinoff context, so
        // a bare setContext() before playTrack() would get clobbered.
        val first = spinoffPool.removeAt(0)
        Log.d(TAG, "Pool spinoff: kicking off '$displayName' with '${first.title}' by ${first.artist} (${spinoffPool.size} remaining)")
        playTrack(first, context = spinoffContext)
    }

    /**
     * Exit spinoff mode and restore the previous queue context.
     * Matches the desktop's exitSpinoff() logic.
     */
    fun exitSpinoff() {
        if (!stateHolder.state.value.spinoffMode) return

        spinoffJob?.cancel()
        spinoffPool.clear()
        spinoffSourceTrack = null

        // Restore previous playback context (queue was never modified)
        queueManager.setContext(preSpinoffContext)
        preSpinoffContext = null

        stateHolder.update {
            copy(
                spinoffMode = false,
                spinoffLoading = false,
            )
        }
        syncQueueState()

        Log.d(TAG, "Spinoff: exited, restored previous context")
    }

    /** Toggle spinoff on/off. */
    fun toggleSpinoff() {
        if (stateHolder.state.value.spinoffMode) {
            exitSpinoff()
        } else {
            startSpinoff()
        }
    }

    /**
     * Check whether spinoff is available for the current track.
     * Lightweight call with limit=1 — called on track change.
     */
    fun checkSpinoffAvailability() {
        val track = stateHolder.state.value.currentTrack ?: return
        // Don't check during spinoff mode
        if (stateHolder.state.value.spinoffMode) return

        scope.launch(Dispatchers.IO) {
            try {
                val response = lastFmClient.getSimilarTracks(
                    track = track.title,
                    artist = track.artist,
                    apiKey = BuildConfig.LASTFM_API_KEY,
                    limit = 1,
                )
                val available = !response.similartracks?.track.isNullOrEmpty()
                stateHolder.update { copy(spinoffAvailable = available) }
            } catch (e: Exception) {
                Log.w(TAG, "Spinoff availability check failed", e)
                stateHolder.update { copy(spinoffAvailable = null) }
            }
        }
    }

    /**
     * If the currently playing track has no artwork, try to fetch it from
     * metadata providers in the background. Updates both the DB and the
     * live PlaybackState so the UI refreshes without a restart.
     */
    /**
     * Re-select the best resolver for a track using cached resolution results.
     *
     * When a track is stored in the DB or queue, its `resolver` field reflects
     * whichever resolver was "best" at the time it was added. But the user may
     * have since changed their resolver priority order, or background resolution
     * may have discovered additional sources (e.g. Apple Music for a track that
     * was originally stored as Spotify-only).
     *
     * This checks the shared [TrackResolverCache] for live-resolved sources and
     * uses [ResolverScoring.selectBest] to pick the current best, then returns
     * a copy of the track with the updated routing fields. If no cached sources
     * exist, the original track is returned unchanged.
     */
    private suspend fun reselectBestSource(track: TrackEntity): TrackEntity {
        val key = trackKey(track.title, track.artist)
        val cachedSources = trackResolverCache.trackSources.value[key]
        if (cachedSources.isNullOrEmpty()) return track

        val best = resolverScoring.selectBest(cachedSources) ?: return track

        // Only update if the best resolver actually changed
        if (best.resolver == track.resolver) return track

        Log.d(TAG, "Re-routed '${track.title}' from ${track.resolver} → ${best.resolver} (user priority)")
        return track.copy(
            resolver = best.resolver,
            sourceType = best.sourceType,
            sourceUrl = best.url,
            spotifyUri = best.spotifyUri ?: track.spotifyUri,
            spotifyId = best.spotifyId ?: track.spotifyId,
            soundcloudId = best.soundcloudId ?: track.soundcloudId,
            appleMusicId = best.appleMusicId ?: track.appleMusicId,
        )
    }

    /**
     * Switch the currently playing track to a different resolver source.
     * Called when the user manually selects a different source from the Now Playing
     * resolver dropdown (matching the desktop's source switcher behavior).
     * Restarts playback from 0:00 using the chosen resolver.
     */
    fun switchSource(resolver: String) {
        val track = stateHolder.state.value.currentTrack ?: return
        scope.launch(Dispatchers.Main) {
            val key = trackKey(track.title, track.artist)
            val cachedSources = trackResolverCache.trackSources.value[key]
            if (cachedSources.isNullOrEmpty()) return@launch

            val source = cachedSources.firstOrNull { it.resolver == resolver } ?: return@launch

            Log.d(TAG, "Manual source switch for '${track.title}': ${track.resolver} → $resolver")
            // Clear all resolver-specific fields, then set only the chosen resolver's.
            // Without this, leftover spotifyUri/spotifyId from a previous resolver causes
            // the router to match SpotifyPlaybackHandler even when switching to localfiles.
            val switched = track.copy(
                resolver = source.resolver,
                sourceType = source.sourceType,
                sourceUrl = source.url,
                spotifyUri = source.spotifyUri,
                spotifyId = source.spotifyId,
                soundcloudId = source.soundcloudId,
                appleMusicId = source.appleMusicId,
            )
            // skipReselect=true: the user explicitly chose this source — don't let
            // reselectBestSource() override it back to the auto-selected best.
            playTrackInternal(switched, skipReselect = true)
        }
    }

    /**
     * Resolve an unresolved track on-the-fly at play time.
     * Used for ephemeral tracks (weekly playlists, recommendations) that haven't
     * been through the resolver pipeline yet. Caches the result in TrackResolverCache
     * so subsequent plays and queue items benefit.
     */
    private suspend fun resolveOnTheFly(track: TrackEntity): TrackEntity {
        return try {
            val query = "${track.title} ${track.artist}"
            val sources = resolverManager.resolve(
                query,
                targetTitle = track.title,
                targetArtist = track.artist,
            )
            if (sources.isEmpty()) {
                Log.w(TAG, "No resolver results for '${track.title}' by ${track.artist}")
                return track
            }

            // Cache the results so reselectBestSource works for queue tracks too
            trackResolverCache.putSources(track.title, track.artist, sources)

            val best = resolverScoring.selectBest(sources) ?: return track
            Log.d(TAG, "On-the-fly resolved '${track.title}' → ${best.resolver}")
            track.copy(
                resolver = best.resolver,
                sourceType = best.sourceType,
                sourceUrl = best.url,
                spotifyUri = best.spotifyUri,
                spotifyId = best.spotifyId,
                soundcloudId = best.soundcloudId,
                appleMusicId = best.appleMusicId,
            )
        } catch (e: Exception) {
            Log.e(TAG, "On-the-fly resolution failed for '${track.title}'", e)
            track
        }
    }

    /**
     * Build [StreamingMetadata] from what the streaming source reports is actually playing.
     * The Now Playing screen uses this to display actual track info (title, artist, album,
     * artwork) directly from the source — so the user always sees what's really streaming.
     *
     * Returns null only when actual metadata isn't available yet (polling hasn't started).
     */
    private fun buildStreamingMetadata(
        queuedTrack: TrackEntity?,
        actualTitle: String?,
        actualArtist: String?,
        actualAlbum: String?,
        actualArtworkUrl: String?,
    ): StreamingMetadata? {
        if (queuedTrack == null || actualTitle == null) return null

        return StreamingMetadata(
            title = actualTitle,
            artist = actualArtist,
            album = actualAlbum,
            artworkUrl = actualArtworkUrl,
        )
    }

    private fun enrichArtworkIfMissing(track: TrackEntity) {
        // Obviously missing — go straight to enrichment
        val artUrl = track.artworkUrl
        if (artUrl.isNullOrBlank()) {
            fetchAndApplyArtwork(track)
            return
        }
        // Local albumart content URI — might be broken, validate on IO thread
        if (artUrl.startsWith("content://media/external/audio/albumart")) {
            scope.launch(Dispatchers.IO) {
                if (isStaleLocalArtwork(artUrl)) {
                    fetchAndApplyArtwork(track)
                }
            }
            return
        }
        // Has a real URL — nothing to do
    }

    private fun fetchAndApplyArtwork(track: TrackEntity) {
        scope.launch(Dispatchers.IO) {
            val url = imageEnrichment.enrichTrackArt(
                trackId = track.id,
                trackTitle = track.title,
                artistName = track.artist,
                albumTitle = track.album,
            ) ?: return@launch
            // Update live state if this track is still playing
            stateHolder.update {
                if (currentTrack?.id == track.id) {
                    copy(currentTrack = currentTrack?.copy(artworkUrl = url))
                } else this
            }
        }
    }

    /**
     * Check if a local file's album art content URI is broken/empty.
     * MediaStore albumart URIs can exist as strings but point to no actual image data.
     * Returns true if the URI looks like a local albumart URI that doesn't resolve.
     */
    private fun isStaleLocalArtwork(artworkUrl: String?): Boolean {
        if (artworkUrl == null) return false
        if (!artworkUrl.startsWith("content://media/external/audio/albumart")) return false
        return try {
            val uri = android.net.Uri.parse(artworkUrl)
            context.contentResolver.openInputStream(uri)?.use { false } ?: true
        } catch (_: Exception) {
            true
        }
    }
}

