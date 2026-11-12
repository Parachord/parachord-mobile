package com.parachord.android.playback

import com.parachord.shared.playback.LoadOnPlay
import com.parachord.shared.playback.failedLoad
import com.parachord.shared.playback.idleTimeoutLoad
import com.parachord.shared.playback.loadOnPlayTarget
import com.parachord.shared.playback.positionForPrevious
import com.parachord.shared.playback.restartedFromTop
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.AdvanceAction
import com.parachord.shared.playback.AdvanceTrigger
import com.parachord.shared.playback.PLAY_START_FAILED_MESSAGE
import com.parachord.shared.playback.LISTEN_ALONG_CONTEXT
import com.parachord.shared.playback.PlaybackMessages
import com.parachord.shared.playback.PoolRefiller
import com.parachord.shared.playback.shuffleAllowed
import com.parachord.shared.playback.spinoffNeedsRefill
import com.parachord.shared.playback.spinoffTrack
import com.parachord.shared.playback.spinoffTracksToPreresolve
import com.parachord.shared.playback.PreviousAction
import com.parachord.shared.playback.advanceAction
import com.parachord.shared.playback.nextRepeatMode
import com.parachord.shared.playback.previousAction
import com.parachord.shared.playback.QueueRestorePolicy
import com.parachord.shared.playback.noSourceFailure
import com.parachord.shared.playback.UnplayableTrackOutcome
import com.parachord.shared.playback.unplayableTrackOutcome
import com.parachord.shared.playback.ResolverVolume
import com.parachord.android.data.store.SettingsStore
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
import com.parachord.android.data.network.NetworkMonitor
import com.parachord.android.playback.handlers.AppleMusicPlaybackHandler
import com.parachord.android.playback.handlers.EmbedPlaybackRefusedException
import com.parachord.android.playback.handlers.ExternalPlaybackHandler
import com.parachord.android.playback.handlers.EmbedPlaybackHandler
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val settingsStore: SettingsStore,
    private val networkMonitor: NetworkMonitor,
) {
    companion object {
        private const val TAG = "PlaybackController"
        /**
         * Longest a handler's play may take before the track is given up on.
         * Above Spotify's slowest legitimate cold start (broadcast wait, launch,
         * ~18s for the Connect device to register, a second local-device wake).
         */
        internal const val PLAY_START_TIMEOUT_MS = 75_000L

        /** How long [awaitConnected] waits for the session's controller. */
        internal const val CONNECT_WAIT_MS = 5_000L

        /** How long an auto-skip waits before skipping past a track that can't play ([scheduleAutoSkip]). */
        internal const val AUTO_SKIP_DELAY_MS = 600L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var positionUpdateJob: Job? = null
    private var spotifyStateJob: Job? = null
    private var appleMusicPollingJob: Job? = null
    private var embedPollingJob: Job? = null
    /** The embedded player [embedPollingJob] is mirroring. */
    private var activeEmbedHandler: EmbedPlaybackHandler? = null
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
     * An external engine plays (Spotify, Apple Music, an embed), by the
     * controller's own state. The session's player routes play and pause by
     * it as well as its own external mode ([sessionPlayPauseGoesToController]).
     */
    val isExternalPlaybackActive: Boolean
        get() = isExternalPlayback

    /**
     * The track on screen that nothing holds, which Play starts through the
     * full pipeline ([togglePlayPause]) instead of resuming ExoPlayer: a
     * queue restored at launch (#567), a paused external track the idle
     * timeout stopped (#550), or a track no source could play. Cleared by any
     * play ([playTrackInternalUnsafe]).
     */
    @Volatile private var loadOnPlay: LoadOnPlay? = null

    /**
     * Play has to load the current track ([loadOnPlay]). The media session's
     * play (car, headphones, lock screen) must come here then: ExoPlayer holds
     * nothing for it, or a failed item, or only its silent loop.
     */
    val needsLoadOnPlay: Boolean
        get() = loadOnPlayTarget(loadOnPlay, stateHolder.state.value.currentTrack?.id) != null

    /**
     * How the play in progress ended with nothing playing, or null. Set by
     * the failure exits of [playTrackInternalUnsafe]; a fallback play's own
     * result replaces it. [playTrackInternal] turns it into [loadOnPlay].
     */
    private var playFailure: PlayFailure? = null

    /**
     * Whether Android Auto is connected ([CarConnections.current]); reported
     * by [PlaybackService] as controllers connect and disconnect.
     */
    internal val carConnections = CarConnections()

    /**
     * Android Auto's car-connection state (0 no car, 1 native, 2 projection;
     * null when unknown), read from its content provider
     * ([queryCarConnectionState]). Asked only while [carConnections] tracks a
     * car, as the "skip, or stop here?" decision is made; tests replace it.
     */
    internal var carConnectionState: suspend () -> Int? = { queryCarConnectionState(context) }

    /**
     * Tracks in a row the queue skipped past, so Android Auto stops after
     * [com.parachord.shared.car.CAR_UNPLAYABLE_RUN_LIMIT] of them with no
     * Parachord screen showing ([carStopsAutoSkip]), as CarPlay does.
     */
    private val carRun = com.parachord.shared.car.CarUnplayableRun()

    /**
     * The car stopped the play in progress on its track instead of letting
     * the queue skip it ([carStopsAutoSkip]). [playTrackInternal] then leaves
     * it for Play ([failedPlayAwaitsLoadAfterCarStop]).
     */
    private var carStoppedPlay = false

    /**
     * A play was asked for in this run of the app. The saved queue is only
     * restored while this is false ([QueueRestorePolicy]): Android Auto can
     * start the app and a play before the restore has read storage.
     */
    @Volatile private var playRequestedThisRun = false

    /** Completes once [controller] is set; replaced by each [connect] after a [release]. */
    private var connected = kotlinx.coroutines.CompletableDeferred<Unit>()

    /**
     * The service was put in the foreground at the start of the play in
     * progress ([shouldPromoteAtPlayStart]) and no route has taken it over yet.
     */
    private var foregroundHeldForPlay = false

    /**
     * A track started playing since the queue was set, restored or last wrapped.
     * Under repeat all, an unplayable last track wraps only when this is true
     * ([advanceAction]'s `playedSinceWrap`), so a queue where nothing plays
     * stops instead of looping forever.
     */
    @Volatile private var playedSinceWrap = false

    /**
     * Bumped wherever [playedSinceWrap] is reset (a new queue, a restored or
     * given-back queue, a wrap), so a play still in flight from before it
     * doesn't mark the new queue as played ([playMarksQueuePlayed]).
     */
    private var queueGeneration = 0

    /**
     * The track a scheduled auto-skip ([scheduleAutoSkip]) is waiting to skip
     * past, or null. Play and Pause do nothing meanwhile ([autoSkipPendingFor]).
     * Cleared when the skip runs or is dropped, and by any play.
     */
    private var autoSkipPendingTrackId: String? = null
    private var autoSkipJob: Job? = null

    /**
     * Counts plays that began ([playTrackInternalUnsafe] started), so a
     * scheduled auto-skip can tell a play that began from a request that
     * played nothing (Previous restarting the track).
     */
    private var playsBegun = 0

    /**
     * How long to keep the foreground service alive after external playback is paused.
     * After this timeout, the service is fully demoted and the process may be killed.
     */
    private val IDLE_TIMEOUT_MS = 5L * 60 * 1000 // 5 minutes

    /**
     * How long a resumed play ([playThenSeek]) waits for the track to start
     * before giving up on the seek. 10 s was too short when the network was
     * slow or coming back: the song then played from 0:00 (#567).
     */
    private val RESUME_SEEK_WAIT_MS = 60_000L

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

    // ── Hooks (Listen Along drives playback through these; the controller
    //    knows nothing about friends or sessions) ─────────────────────────

    /**
     * Called when the current track ENDED ([AdvanceTrigger.TRACK_ENDED] only,
     * not an unplayable track being skipped). True means the listener handled
     * it: the queue doesn't advance, replay (repeat one) or wrap (repeat all).
     * If the listener started no play, the ended track stays on screen, not
     * playing ([holdEndedTrack]).
     */
    var onOwnTrackEnded: (() -> Boolean)? = null

    /**
     * Called when [track] couldn't play and playback stopped on it (no source,
     * a start that failed or timed out, an embed refused with no fallback).
     * True means the listener handled it: no error message or snackbar. Play
     * still loads the track ([loadOnPlay]) unless the listener plays another.
     */
    var onTrackUnplayable: ((TrackEntity) -> Boolean)? = null

    /**
     * Called for a user's playback action, before the controller acts on it.
     * The return value matters for [UserPlaybackAction.SKIP] and
     * [UserPlaybackAction.RESUME] only: true means the controller mustn't
     * skip or resume as well (the listener started playback itself, stopped
     * and cleared the track, or holds it).
     */
    var onUserAction: ((UserPlaybackAction) -> Boolean)? = null

    /**
     * True when the listener drives [track] (Listen Along's own track): it
     * takes the track's failure through [onTrackUnplayable], so a failed play
     * of it keeps external mode and the foreground ([holdForListener]) instead
     * of leaving them, and the listener's next track can still start with the
     * screen off.
     */
    var drivesTrack: ((TrackEntity) -> Boolean)? = null

    /**
     * Called as a play of [track] starts ([playTrackInternal]), so the listener
     * knows its track is playing again.
     */
    var onTrackStarting: ((TrackEntity) -> Unit)? = null

    // Threading: call the controller on the main thread. The hooks,
    // [playRequestCount], [playLaunchesPending] and [heldForListener] are
    // main-thread only, and every entry point that fires a hook or counts a
    // play ([playTrack], [playQueue], [playFromQueue], [skipNext],
    // [skipPrevious], [togglePlayPause], [stopAndClear]) logs an error when
    // called from another thread ([checkMainThread]). It doesn't re-post
    // itself: re-posting only some entry points reorders a mixed sequence
    // from one thread (clearQueue inline, playTrack later).

    /**
     * Counts requested plays ([playTrack], [playQueue], [playFromQueue], a
     * skip), so [skipNextInternal] can tell whether [onOwnTrackEnded] started
     * one and [holdEndedTrack] can drop its stop when one came meanwhile.
     */
    private var playRequestCount = 0

    /**
     * The track on screen is held for the listener (it ended, or couldn't
     * play, and the listener waits to play its next track): Play does
     * nothing until the listener plays something or lets go
     * ([releaseListenerHold]). Cleared by any play.
     */
    private var heldForListener = false

    /** Logs an error when [entryPoint] runs off the main thread (see the threading note above). */
    private fun checkMainThread(entryPoint: String) {
        val looper = android.os.Looper.myLooper()
        if (looper != android.os.Looper.getMainLooper()) {
            Log.e(TAG, "$entryPoint called off the main thread (${Thread.currentThread().name}); call the controller on the main thread")
        }
    }

    /**
     * True while a play is under way: the play lock is held, an advance is in
     * flight, or a [playTrack] / [playQueue] hasn't reached the lock yet. A
     * track that's resolving or starting counts as playing for Listen Along's
     * polls.
     */
    val isPlayStarting: Boolean
        get() = playMutex.isLocked || advanceJob?.isActive == true || playLaunchesPending > 0

    private var playLaunchesPending = 0

    // ── Spinoff state ──────────────────────────────────────────────────────
    /** Separate pool of resolved similar tracks (NOT in the queue — desktop behavior). */
    private val spinoffPool = mutableListOf<TrackEntity>()
    /** The track that was playing when spinoff was activated. */
    private var spinoffSourceTrack: TrackEntity? = null
    /** Previous playback context to restore on exit (queue itself is never modified). */
    private var preSpinoffContext: PlaybackContext? = null
    private var spinoffJob: Job? = null

    // ── Pool-based spinoff (Mode C) refill state ────────────────────────
    // The actual refill loop (rate-limit, empty counter, dedup, ID
    // stamping) lives in [PoolRefiller] for testability. This holder
    // forwards `refillUrl` and `fetcher` into it on
    // [startPoolBasedSpinoff], triggers it from [skipNextInternal] when
    // the pool is low ([spinoffNeedsRefill]), and resets it on
    // [exitSpinoff].
    //
    // Reset on every [startPoolBasedSpinoff] entry AND on [exitSpinoff]
    // to prevent cross-mode state leak — without the exit reset, a Mode B
    // spinoff that follows a Mode C exit would see leftover refiller
    // state.
    private val poolRefiller = PoolRefiller()

    /**
     * Connect to [PlaybackService]'s session, restore the saved queue and
     * start the observers that follow playback (queue saving, scrobbling,
     * the widget). Idempotent.
     *
     * Called by [PlaybackService.onCreate] as well as by the phone's
     * `MainViewModel`, so all of this runs when Android Auto starts the app
     * with no phone screen. When only the phone called it, an Auto play ran
     * with no controller (and saved, scrobbled nothing), and unlocking the
     * phone then restored the last session's queue over it and handed that
     * to Auto.
     */
    fun connect() {
        if (controllerFuture != null) return

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        val connectedSignal = connected

        future.addListener({
            controller = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "MediaController connection failed: ${e.message}")
                null
            }
            if (controller == null) {
                // Let a later connect() try again; callers waiting go on without one.
                if (controllerFuture === future) controllerFuture = null
                connectedSignal.complete(Unit)
                return@addListener
            }
            setupPlayerListener()
            connectedSignal.complete(Unit)
            // Restore persisted queue (if enabled) and start auto-save
            scope.launch {
                val restoredTrack = queuePersistence.restoreIfEnabled(canApply = {
                    QueueRestorePolicy.shouldApply(
                        hasCurrentTrack = stateHolder.state.value.currentTrack != null,
                        playRequested = playRequestedThisRun,
                    )
                })
                if (restoredTrack != null) {
                    playedSinceWrap = false
                    queueGeneration++
                    val snapshot = queueManager.snapshot.value
                    // Where it was when the app was last running (#567).
                    val resumeAtMs = queuePersistence.savedResumePositionMs(restoredTrack.id)
                    stateHolder.update {
                        copy(
                            currentTrack = restoredTrack,
                            isPlaying = false,
                            position = resumeAtMs,
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
                    loadOnPlay = LoadOnPlay(restoredTrack, resumeAtMs, skipReselect = false, why = LoadOnPlay.Why.RESTORED)
                    // Play is the likely next tap: have Spotify ready if
                    // that's where this track plays.
                    prewarmSpotifyFor(restoredTrack)
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

    /**
     * A play is on its way (an Android Auto tap still loading its tracks):
     * don't restore the saved queue, and drop a track waiting to be loaded on
     * play ([loadOnPlay]), which the media session's play that follows the
     * tap would otherwise start alongside it.
     */
    fun notePlayRequested() {
        playRequestedThisRun = true
        loadOnPlay = null
    }

    /**
     * A `parachord://` play link arrived (#604): it searches before it plays,
     * so mark the run now and the saved queue isn't restored over it. Only the
     * mark: a track waiting to be loaded on play stays, in case the link finds
     * nothing.
     */
    fun noteLinkPlayRequested() {
        playRequestedThisRun = true
    }

    /**
     * [connect] if needed and wait (up to [CONNECT_WAIT_MS]) for the
     * controller, so a play Android Auto asks for right after starting the
     * app has one: local files and the silent keepalive go through it.
     */
    suspend fun awaitConnected() {
        connect()
        kotlinx.coroutines.withTimeoutOrNull(CONNECT_WAIT_MS) { connected.await() }
            ?: Log.w(TAG, "MediaController not connected after ${CONNECT_WAIT_MS}ms; playing without it")
    }

    fun release() {
        positionUpdateJob?.cancel()
        spotifyStateJob?.cancel()
        appleMusicPollingJob?.cancel()
        embedPollingJob?.cancel()
        idleTimeoutJob?.cancel()
        if (isExternalPlayback) sendExternalPlaybackStop()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        connected = kotlinx.coroutines.CompletableDeferred()
        scope.launch { router.stopExternalPlayback() }
    }

    /** Play a single track immediately (clears the queue). */
    fun playTrack(track: TrackEntity, context: PlaybackContext? = null) {
        checkMainThread("playTrack")
        playRequestedThisRun = true
        onUserAction?.invoke(UserPlaybackAction.PLAY_SOMETHING_ELSE)
        playRequestCount++
        clearUserPauseMark()
        // The track it replaces goes to history, so Previous reaches it (#605).
        queueManager.clearQueue(outgoing = stateHolder.state.value.currentTrack, next = track)
        if (context != null) queueManager.setContext(context)
        playedSinceWrap = false
        queueGeneration++
        launchPlay { playTrackInternal(track) }
    }

    /** Launch a requested play, counted in [isPlayStarting] until it ends. */
    private fun launchPlay(block: suspend () -> Unit) {
        playLaunchesPending++
        scope.launch {
            try {
                block()
            } finally {
                playLaunchesPending--
            }
        }
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
        checkMainThread("playQueue")
        playRequestedThisRun = true
        onUserAction?.invoke(UserPlaybackAction.PLAY_SOMETHING_ELSE)
        playRequestCount++
        clearUserPauseMark()
        val track = queueManager.setQueue(
            tracks, startIndex, context, shuffle, outgoing = stateHolder.state.value.currentTrack,
        ) ?: return
        playedSinceWrap = false
        queueGeneration++
        launchPlay { playTrackInternal(track) }
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
        checkMainThread("skipNext")
        skipNextInternal(AdvanceTrigger.NEXT_PRESSED)
    }

    /**
     * The repeat button's cycle (off, all, one, off; shared [nextRepeatMode]).
     * Parachord's own repeat, read by [skipNextInternal]: never ExoPlayer's
     * `repeatMode`, which loops the silent keep-alive during external playback
     * and is set apart from it by [setEngineRepeatMode].
     */
    fun cycleRepeatMode() {
        setRepeatMode(nextRepeatMode(stateHolder.state.value.repeatMode))
    }

    /**
     * ExoPlayer's own repeat (ONE loops the silent keep-alive, OFF lets a local
     * track end), through [EngineRepeatCommand]. Never `ctrl.repeatMode`: the
     * session's repeat is Parachord's, so that would change the user's repeat.
     *
     * The command is granted when the controller connects, and [controller] is
     * only set once that has happened, so it is available whenever there is a
     * controller to send it. If it isn't, or the service refuses it, this logs:
     * the user's repeat must not be touched as a fallback.
     */
    private fun setEngineRepeatMode(ctrl: MediaController, mode: Int) {
        if (!ctrl.isSessionCommandAvailable(EngineRepeatCommand.command)) {
            Log.w(TAG, "Engine repeat $mode not sent: the session didn't grant the command")
            return
        }
        val result = ctrl.sendCustomCommand(EngineRepeatCommand.command, EngineRepeatCommand.args(mode))
        result.addListener({
            val code = try { result.get().resultCode } catch (e: Exception) { null }
            if (code != androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                Log.w(TAG, "Engine repeat $mode refused (result $code)")
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * Set Parachord's repeat mode (the media session's repeat command). See
     * [cycleRepeatMode]. Refused during Listen Along, which follows the friend
     * rather than the queue (the Now Playing button is disabled there too).
     *
     * @return false when it was refused.
     */
    fun setRepeatMode(mode: RepeatMode): Boolean {
        if (queueManager.playbackContext?.type == LISTEN_ALONG_CONTEXT) {
            Log.d(TAG, "Repeat refused: Listen Along")
            return false
        }
        Log.d(TAG, "Repeat mode: $mode")
        stateHolder.update { copy(repeatMode = mode) }
        return true
    }

    /**
     * Move the queue on. [trigger] says why (a track ended, Next, or a track
     * that couldn't play being skipped); the shared [advanceAction] decides
     * what happens, after the spinoff pool, which isn't governed by repeat.
     */
    @androidx.annotation.VisibleForTesting
    internal fun skipNextInternal(trigger: AdvanceTrigger) {
        val userInitiated = trigger == AdvanceTrigger.NEXT_PRESSED
        // If a previous advance is still in-flight (resolver/routing not finished),
        // ignore non-user auto-advance signals to prevent double-skipping.
        if (!userInitiated && advanceJob?.isActive == true) {
            Log.d(TAG, "skipNext: ignoring auto-advance — previous advance still in-flight")
            return
        }

        if (userInitiated) {
            playRequestCount++
            // The listener took over (Listen Along restored the user's queue):
            // that play is the answer, so don't skip it as well.
            if (onUserAction?.invoke(UserPlaybackAction.SKIP) == true) return
        } else if (trigger == AdvanceTrigger.TRACK_ENDED) {
            val requestsBefore = playRequestCount
            if (onOwnTrackEnded?.invoke() == true) {
                if (playRequestCount == requestsBefore) holdEndedTrack()
                return
            }
        }

        // Cancel any in-flight advance — user skip takes priority
        advanceJob?.cancel()

        // Spinoff mode: pull from separate pool, bypass queue entirely (desktop behavior)
        if (stateHolder.state.value.spinoffMode) {
            if (spinoffPool.isNotEmpty()) {
                val next = spinoffPool.removeAt(0)
                Log.d(TAG, "Spinoff: playing next '${next.title}' by ${next.artist} (${spinoffPool.size} remaining)")
                // The shared QueueManager keeps the seed (the track playing
                // when the first pool track starts) in the repeat-all lap and
                // the pool tracks out of it.
                if (!queueManager.currentIsPoolTrack) {
                    Log.d(TAG, "Spinoff: seed '${stateHolder.state.value.currentTrack?.title}' kept in history")
                }
                queueManager.poolTrackStarted(stateHolder.state.value.currentTrack)

                // Mode C refill trigger — fire when the shared rule says the
                // pool is low AND we have a refill URL configured. This is a
                // no-op for Mode B (seed-based) spinoffs since their refillUrl
                // is null. The PoolRefiller handles rate-limit + stop-condition
                // gating internally.
                if (spinoffNeedsRefill(spinoffPool.size) && poolRefiller.refillUrl != null) {
                    triggerPoolRefill()
                }
                // Pool tracks are metadata only: resolve the next few ahead so
                // they start quickly (the track itself is resolved on the fly
                // when it plays if this hasn't finished).
                preresolveSpinoffPool()

                advanceJob = scope.launch {
                    try {
                        playTrackInternal(next, advancing = true, userRequested = userInitiated)
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

        val state = stateHolder.state.value
        val currentTrack = state.currentTrack
        val action = advanceAction(trigger, state.repeatMode, queueManager.hasNext, currentTrack != null, playedSinceWrap)
        // A track outside the repeat-all lap (a spinoff pool track, history
        // from before a cleared queue) stays out of it; the shared
        // QueueManager decides.
        if (!queueManager.currentInLap && action != AdvanceAction.REPLAY_CURRENT) {
            Log.d(TAG, "skipNext: '${currentTrack?.title}' isn't in the repeat-all lap; keeping it out")
        }
        val next: TrackEntity = when (action) {
            AdvanceAction.PLAY_NEXT -> queueManager.skipNext(currentTrack)
            AdvanceAction.REPLAY_CURRENT -> currentTrack
            AdvanceAction.WRAP_TO_START -> queueManager.wrapToStart(currentTrack).also { playedSinceWrap = false; queueGeneration++ }
            AdvanceAction.STOP_AND_CLEAR -> null
        } ?: run {
            stopAtEndOfQueue()
            return
        }
        when (action) {
            AdvanceAction.REPLAY_CURRENT -> Log.d(TAG, "skipNext: repeat one, replaying '${next.title}'")
            AdvanceAction.WRAP_TO_START -> Log.d(TAG, "skipNext: repeat all, back to the start: '${next.title}' by ${next.artist}")
            else -> Log.d(TAG, "skipNext: advancing to '${next.title}' by ${next.artist}")
        }
        if (action != AdvanceAction.PLAY_NEXT) syncQueueState()
        advanceJob = scope.launch {
            try {
                val transitionStart = System.currentTimeMillis()
                // Don't call router.stopExternalPlayback() here — it sends an
                // async stop() to MusicKit/Spotify that races with the new play()
                // call. The stop arrives after the new track starts, pausing it.
                // playTrackInternal() handles the transition: for external→external,
                // the new handler.play() replaces the queue directly. For
                // external→ExoPlayer, the ExoPlayer path sends the stop.
                playTrackInternal(next, advancing = true, userRequested = userInitiated)
                Log.d(TAG, "skipNext: full transition took ${System.currentTimeMillis() - transitionStart}ms")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Advance to '${next.title}' failed: ${e.message}", e)
            }
        }
    }

    /**
     * [onOwnTrackEnded] handled the end of the current track and started no
     * play (Listen Along waits for its next poll): the track stays on screen,
     * not playing, and nothing advances or clears. External playback keeps
     * its mode and foreground (the next track, or Play, picks up from there;
     * without the foreground Android cuts the app's network with the screen
     * off), but its handler is stopped, so Spotify can't run on into the
     * album's next song, and the session shows paused. The stop waits for the
     * play lock and is dropped if a play was requested meanwhile (any entry
     * point that counts in [playRequestCount]) or the track changed, so it
     * can't race the next track's start. Play does nothing meanwhile
     * ([heldForListener]): an ended local player would replay the track from
     * the start, an external one re-play it or resume its finished stream.
     */
    private fun holdEndedTrack() {
        val track = stateHolder.state.value.currentTrack ?: return
        Log.d(TAG, "'${track.title}' ended; holding it on screen")
        heldForListener = true
        stateHolder.update { copy(isPlaying = false, isBuffering = false) }
        if (!isExternalPlayback) {
            // An ended ExoPlayer keeps playWhenReady, so the seek to the start
            // that Media3 sends ahead of a session play (a media key, the lock
            // screen) would replay the track: drop it.
            controller?.pause()
            return
        }
        val requests = playRequestCount
        scope.launch {
            playMutex.withLock {
                if (playRequestCount != requests || stateHolder.state.value.currentTrack?.id != track.id) return@withLock
                stopBeforeFailedTrack(autoSkip = true)
                holdForListener()
            }
        }
    }

    /**
     * Nothing plays while a listener ([drivesTrack], [onOwnTrackEnded]) waits
     * to play its next track: external mode and the foreground stay (the
     * handler is already stopped), nothing polls until the next play takes
     * the locks again, the session shows paused, and the idle timeout cleans
     * up if nothing comes.
     */
    private fun holdForListener() {
        if (!isExternalPlayback) return
        releaseExternalPlaybackWakeLock()
        sendSilencePlayback(pause = true)
        startIdleTimeout()
    }

    /**
     * Stop and take the current track off screen, as at the end of the queue
     * (it goes to history, so Previous reaches it). For a listener whose
     * session ended at a song's end with nothing to go back to.
     */
    fun stopAndClear() {
        checkMainThread("stopAndClear")
        stopAtEndOfQueue()
    }

    /**
     * The listener let go of a held track ([heldForListener]) without playing
     * anything else: Play on it does what it does on any stopped track again
     * (re-plays it, or loads it when its play failed).
     */
    fun releaseListenerHold() {
        if (!heldForListener) return
        Log.d(TAG, "Listener let go of '${stateHolder.state.value.currentTrack?.title}'")
        heldForListener = false
    }

    /**
     * Give a queue back paused: [tracks] become the queue in [context], the
     * first one is on screen, paused, with nothing loaded, and Play starts it
     * ([loadOnPlay], as for the queue restored at launch). For a listener
     * whose session ended while the user had paused.
     */
    fun restoreQueuePaused(tracks: List<TrackEntity>, context: PlaybackContext?) {
        checkMainThread("restoreQueuePaused")
        val track = queueManager.setQueue(
            tracks, 0, context, queueManager.shuffleEnabled, outgoing = stateHolder.state.value.currentTrack,
        ) ?: return
        playedSinceWrap = false
        queueGeneration++
        heldForListener = false
        clearPendingAutoSkip()
        // A foreground kept across an auto-skip has no next track to hand it to.
        releaseForegroundHeldForPlay("queue given back paused")
        Log.d(TAG, "Queue given back paused at '${track.title}'")
        // The paused local item belongs to the track being replaced: drop it,
        // so the session's play can't resume it. A paused external handler is
        // left as it is (keeping external mode and the foreground); the next
        // play replaces it, and the idle timeout cleans up if none comes.
        if (!isExternalPlayback) {
            controller?.stop()
            controller?.clearMediaItems()
        }
        loadOnPlay = LoadOnPlay(track, 0L, skipReselect = false, why = LoadOnPlay.Why.RESTORED)
        stateHolder.update {
            copy(
                currentTrack = track,
                isPlaying = false,
                isBuffering = false,
                position = 0L,
                duration = 0L,
                playbackError = null,
            )
        }
        syncQueueState()
    }

    /**
     * The queue ran out ([AdvanceAction.STOP_AND_CLEAR]): stop and take the
     * track off screen, so the mini player and the notification go away and
     * Play has nothing to resume.
     */
    private fun stopAtEndOfQueue() {
        Log.d(TAG, "skipNext: end of queue, stopping and clearing")
        // Leave external mode first, while the service is still in the
        // foreground: notifyExternalMode is a startService, which Android
        // refuses from the background once sendExternalPlaybackStop has
        // demoted it, leaving the session's player stuck in external mode.
        if (isExternalPlayback) {
            notifyExternalMode(false)
            stopSpotifyStatePolling()
            stopAppleMusicStatePolling()
            stopEmbedStatePolling()
            scope.launch { router.stopExternalPlayback() }
            sendExternalPlaybackStop()
            isExternalPlayback = false
        }
        // A foreground kept across an auto-skip ([scheduleAutoSkip]): nothing
        // plays next, so it goes.
        releaseForegroundHeldForPlay("end of the queue")
        clearPendingAutoSkip()
        // The cleared track goes to history, so Previous goes back to it
        // rather than to the track before it.
        stateHolder.state.value.currentTrack?.let { queueManager.pushToHistory(it) }
        // Nothing plays next, so stopping ExoPlayer here (its item, or the
        // silent keep-alive loop during external playback) can't demote the
        // service between two tracks. stop() before clearMediaItems(): an
        // empty playlist on a player that isn't idle goes to STATE_ENDED.
        controller?.stop()
        controller?.clearMediaItems()
        loadOnPlay = null
        heldForListener = false
        stateHolder.update {
            copy(
                currentTrack = null,
                isPlaying = false,
                isBuffering = false,
                position = 0L,
                duration = 0L,
                playbackError = null,
            )
        }
        syncQueueState()
    }

    fun skipPrevious() {
        checkMainThread("skipPrevious")
        playRequestCount++
        // The listener took over (Listen Along restored the user's queue).
        if (onUserAction?.invoke(UserPlaybackAction.SKIP) == true) return
        // A track nothing holds (restored, idle-stopped, failed): where it is
        // is the slot's position, which Play starts from; the player reads 0.
        val slot = stoppedTrackSlot()
        val playerPosition = when {
            slot != null -> 0L
            isExternalPlayback -> router.activeExternalHandler?.getPosition()
                ?: router.getSpotifyHandler().getPosition()
            else -> (controller ?: return).currentPosition
        }
        // A track the queue is skipping past never played: Previous goes to
        // the track before it (an external handler's stale position would read
        // as "restart", which plays nothing).
        val skipping = autoSkipPendingFor(autoSkipPendingTrackId, stateHolder.state.value.currentTrack?.id)
        val position = if (skipping) 0L else positionForPrevious(playerPosition, slot, stateHolder.state.value.currentTrack?.id)
        if (previousAction(position, queueManager.hasPrevious) == PreviousAction.RESTART_CURRENT) {
            Log.d(TAG, "skipPrevious: restarting the current track (position ${position}ms)")
            if (slot != null) {
                // Nothing plays it: Play starts it from the top. Nothing is
                // sought (the player holds nothing for it) or started.
                moveSlot(slot.restartedFromTop())
            } else if (isExternalPlayback) {
                scope.launch { router.activeExternalHandler?.seekTo(0) }
            } else {
                controller?.seekTo(0)
            }
            return
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
        checkMainThread("playFromQueue")
        playRequestCount++
        val currentTrack = stateHolder.state.value.currentTrack
        val track = queueManager.playFromQueue(index, currentTrack) ?: return
        onUserAction?.invoke(UserPlaybackAction.PLAY_SOMETHING_ELSE)
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
        checkMainThread("togglePlayPause")
        // The queue is about to skip past this track ([scheduleAutoSkip]):
        // re-playing it would only fail again, and the skip would then start
        // the next track without it.
        if (autoSkipPendingFor(autoSkipPendingTrackId, stateHolder.state.value.currentTrack?.id)) {
            Log.d(TAG, "togglePlayPause: '${stateHolder.state.value.currentTrack?.title}' is being skipped; ignoring")
            return
        }
        // A resume (nothing playing or starting): the listener may play
        // something else instead (Listen Along's held friend track). A play
        // command while a play starts is that play's own echo, not the user.
        if (!stateHolder.state.value.isPlaying && !isPlayStarting &&
            onUserAction?.invoke(UserPlaybackAction.RESUME) == true
        ) {
            clearUserPauseMark()
            return
        }
        // Held for the listener: nothing to resume, and the track isn't
        // replayed or reloaded (also while the hold's own stop runs, when a
        // play counts as starting and the listener isn't asked).
        if (!stateHolder.state.value.isPlaying && heldForListener) {
            Log.d(TAG, "togglePlayPause: '${stateHolder.state.value.currentTrack?.title}' is held for the listener; ignoring play")
            return
        }
        // No track on screen (the queue ended and the track was cleared):
        // nothing to start, so don't resume the last engine (shared, #617).
        if (com.parachord.shared.playback.playStartsNothing(
                hasCurrentTrack = stateHolder.state.value.currentTrack != null,
                isPlaying = stateHolder.state.value.isPlaying,
                isStarting = isPlayStarting,
            )
        ) {
            Log.d(TAG, "togglePlayPause: no track; ignoring play")
            return
        }
        // Nothing holds the track on screen ([loadOnPlay]): a restored queue,
        // a paused track the idle timeout stopped, or a track no source could
        // play. Resuming would play ExoPlayer's silence, its failed item or
        // nothing, so start the track through the full pipeline, where it was.
        val load = loadOnPlayTarget(loadOnPlay, stateHolder.state.value.currentTrack?.id)
        if (load != null && isExternalPlayback && router.activeExternalHandler?.isPlaying() == true) {
            // Its start timed out but the service started it after all: the
            // track is playing, so this tap is a pause (the branch below).
            Log.d(TAG, "togglePlayPause: '${load.track.title}' started late; not loading it again")
            loadOnPlay = null
        } else if (load != null) {
            // A play or an advance in flight is the answer (see below): a
            // second start would race it.
            if (playMutex.isLocked || advanceJob?.isActive == true) {
                Log.d(TAG, "togglePlayPause: '${load.track.title}' waits for a load, but a play is starting")
                return
            }
            loadOnPlay = null
            clearUserPauseMark()
            Log.d(TAG, "Play loads '${load.track.title}' (${load.why}) at ${load.positionMs} ms")
            scope.launch { playThenSeek(load.track, load.positionMs, load.skipReselect) }
            return
        }

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
                    val playing = handler?.isPlaying() ?: false
                    if (playing) return@launch
                    // The advance hit a track an embedded player refused: it's
                    // being skipped or reported, and replaying it would only be
                    // refused again.
                    if (embedRefusedTrackId == stateHolder.state.value.currentTrack?.id) return@launch
                    // Advance completed but playback didn't start — fall through to re-play
                }

                // A play still starting (a SoundCloud widget loading, Spotify
                // waking): its own silent ExoPlayer start comes back through
                // the MediaSession as a play command. Answering it would
                // "resume" a track that hasn't started and replay it 1.5 s
                // later; and when that play fails (a track SoundCloud's widget
                // refuses), replaying it again would loop. The play in flight
                // is the answer, whatever its outcome.
                if (playMutex.isLocked) {
                    Log.d(TAG, "togglePlayPause: a play is starting, leaving it to that play")
                    return@launch
                }

                val handler = router.activeExternalHandler
                if (handler == null) {
                    // No active handler — external playback stalled. Re-play the current track.
                    val track = stateHolder.state.value.currentTrack ?: return@launch
                    Log.d(TAG, "togglePlayPause: no active handler, re-playing '${track.title}'")
                    playTrackInternal(track, skipReselect = true)
                    return@launch
                }
                val isCurrentlyPlaying = handler.isPlaying()
                if (isCurrentlyPlaying) {
                    markUserPaused()
                    onUserAction?.invoke(UserPlaybackAction.PAUSE)
                    handler.pause()
                    stateHolder.update { copy(isPlaying = false) }
                    // Stop polling and release WakeLock — no need to keep the CPU
                    // awake at 500ms intervals while paused.
                    stopSpotifyStatePolling()
                    stopAppleMusicStatePolling()
                    stopEmbedStatePolling()
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
                    startStatePolling(handler)
                    // If resume didn't actually start playback (stale session),
                    // re-play the track from scratch — but only once per user
                    // interaction. The flag prevents the MediaSession/notification
                    // from re-triggering togglePlayPause in a loop after replay.
                    delay(1500)
                    if (resumeReplayInFlight) return@launch
                    val stillNotPlaying = !handler.isPlaying()
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

        val ctrl = controller ?: return
        if (ctrl.isPlaying) {
            markUserPaused()
            onUserAction?.invoke(UserPlaybackAction.PAUSE)
            ctrl.pause()
        } else {
            clearUserPauseMark()
            ctrl.play()
        }
    }

    /**
     * The media session's play or pause on the local player, which reaches
     * ExoPlayer without [togglePlayPause] (lock screen, headphones, the car):
     * tells [onUserAction] as [togglePlayPause] does
     * ([sessionPlayPauseAction]). @return true when the caller mustn't resume:
     * the listener started something else instead, or holds the track.
     */
    fun onSessionPlayPause(play: Boolean): Boolean {
        // Being skipped ([scheduleAutoSkip]): the local player was cleared for
        // it, and nothing should start or stop until the next track plays.
        if (autoSkipPendingFor(autoSkipPendingTrackId, stateHolder.state.value.currentTrack?.id)) {
            Log.d(TAG, "Session ${if (play) "play" else "pause"}: '${stateHolder.state.value.currentTrack?.title}' is being skipped; ignoring")
            return true
        }
        // Held for the listener ([heldForListener]): an ended ExoPlayer would
        // replay the track from the start.
        if (play && heldForListener && !stateHolder.state.value.isPlaying) {
            Log.d(TAG, "Session play: '${stateHolder.state.value.currentTrack?.title}' is held for the listener; ignoring")
            return true
        }
        val action = sessionPlayPauseAction(play, stateHolder.state.value.isPlaying, isPlayStarting) ?: return false
        val handled = onUserAction?.invoke(action) == true
        if (handled) clearUserPauseMark()
        return handled && action == UserPlaybackAction.RESUME
    }

    /**
     * Turn shuffle on or off. Refused (nothing changes) during a spinoff or
     * Listen Along: the shared [shuffleAllowed] rule. Every shuffle entry
     * point (Now Playing, the `parachord://shuffle` link, the DJ tool, the
     * media session) comes through here or [setShuffle].
     *
     * Parachord shuffles its own queue; ExoPlayer's shuffle is never used
     * (the media session's shuffle command lands in [setShuffle] through the
     * service's forwarding player, which doesn't pass it to ExoPlayer).
     *
     * @return false when shuffle isn't allowed right now.
     */
    fun toggleShuffle(): Boolean {
        val state = stateHolder.state.value
        if (!shuffleAllowed(state.spinoffMode, queueManager.playbackContext?.type)) {
            Log.d(TAG, "Shuffle refused: spinoff or Listen Along")
            return false
        }
        val newState = queueManager.toggleShuffle()
        stateHolder.update { copy(shuffleEnabled = newState) }
        syncQueueState()
        return true
    }

    /**
     * Set shuffle to [enabled]. @return false when it was refused (spinoff or
     * Listen Along, see [toggleShuffle]); true when shuffle is now [enabled].
     */
    fun setShuffle(enabled: Boolean): Boolean {
        if (queueManager.shuffleEnabled == enabled) return true
        return toggleShuffle()
    }

    /**
     * The media session's seek comes here rather than to ExoPlayer
     * ([sessionSeekGoesToController]): an external engine plays, or the
     * track waits for Play to load it.
     */
    val ownsSessionSeek: Boolean
        get() = sessionSeekGoesToController(isExternalPlayback, needsLoadOnPlay)

    /**
     * Set while [seekTo] seeks the session's player. A backstop only: the fix
     * for the #607 loop is the session player's routing by [ownsSessionSeek],
     * which never sends this path's seek back here. The guard drops a seek
     * that comes back synchronously anyway.
     */
    private var seekingSessionPlayer = false

    fun seekTo(positionMs: Long) {
        // A track nothing holds (restored, idle-stopped, failed): no player
        // has it to seek. Move where Play starts it, and the shown position.
        val slot = stoppedTrackSlot()
        if (slot != null) {
            val duration = stateHolder.state.value.duration
            val clamped = if (duration > 0) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
            moveSlot(slot.copy(positionMs = clamped))
            return
        }
        if (isExternalPlayback) {
            scope.launch {
                router.activeExternalHandler?.seekTo(positionMs)
                stateHolder.update { copy(position = positionMs) }
            }
            return
        }
        // The session's player sends a seek back here only when [ownsSessionSeek],
        // which is false on this path; the guard covers a synchronous echo anyway.
        if (seekingSessionPlayer) {
            Log.w(TAG, "seekTo($positionMs) came back from the session's player; dropping it")
            return
        }
        seekingSessionPlayer = true
        try {
            controller?.seekTo(positionMs)
        } finally {
            seekingSessionPlayer = false
        }
    }

    /**
     * The slot ([loadOnPlay]) for the current track, unless its external
     * handler started late after all and plays it (then it's an ordinary
     * playing track, as [togglePlayPause] treats it).
     */
    private fun stoppedTrackSlot(): LoadOnPlay? {
        val slot = loadOnPlayTarget(loadOnPlay, stateHolder.state.value.currentTrack?.id) ?: return null
        if (isExternalPlayback && router.activeExternalHandler?.isPlaying() == true) return null
        return slot
    }

    /**
     * Put [slot] (the current track's) in place: Play starts there, the
     * position shown follows, and so does the saved resume position, so the
     * next launch doesn't bring the old one back.
     */
    private fun moveSlot(slot: LoadOnPlay) {
        loadOnPlay = slot
        stateHolder.update { copy(position = slot.positionMs) }
        queuePersistence.savePositionNow(slot.track.id, slot.positionMs, stateHolder.state.value.duration)
    }

    /**
     * ExoPlayer's item for the current track failed at [positionMs]: Play
     * loads the track again, where it stopped, keeping a source the user
     * picked ([manualPickTrackId]), as iOS does.
     */
    @androidx.annotation.VisibleForTesting
    internal fun onLocalPlayerFailed(positionMs: Long, durationMs: Long) {
        val track = stateHolder.state.value.currentTrack ?: return
        loadOnPlay = failedLoad(
            track,
            skipReselect = manualPickTrackId == track.id,
            failedAtMs = positionMs,
            durationMs = durationMs,
        )
        stateHolder.update { copy(isPlaying = false, isBuffering = false) }
    }

    /**
     * The track whose play kept its source (`skipReselect`: a source the user
     * picked, a fallback after a refused one); null after any other play. A
     * failure mid-play keeps that source for the retry ([onLocalPlayerFailed]).
     */
    private var manualPickTrackId: String? = null

    /** The slot Play would load, for tests. */
    @androidx.annotation.VisibleForTesting
    internal val pendingLoad: LoadOnPlay?
        get() = loadOnPlayTarget(loadOnPlay, stateHolder.state.value.currentTrack?.id)

    /** Whether the play-start foreground is held, for tests. */
    @androidx.annotation.VisibleForTesting
    internal val holdsForegroundForPlay: Boolean
        get() = foregroundHeldForPlay

    /** [playedSinceWrap], for tests. */
    @androidx.annotation.VisibleForTesting
    internal val queuePlayedSinceWrap: Boolean
        get() = playedSinceWrap

    /**
     * @param advancing the play is the queue (or a spinoff) moving on, so a
     *   track that can't play is skipped rather than reported (desktop's
     *   `autoSkipIfAdvancing`).
     * @param userRequested someone asked for this play (a tap, a car or
     *   Bluetooth button, Next / Previous), so the service is put in the
     *   foreground as it starts ([shouldPromoteAtPlayStart]). A skip is
     *   `advancing` and still user-requested.
     */
    private suspend fun playTrackInternal(
        track: TrackEntity,
        skipReselect: Boolean = false,
        advancing: Boolean = false,
        userRequested: Boolean = !advancing,
    ) = playMutex.withLock {
        var completed = false
        val queueAtStart = queueGeneration
        // Someone picked this (a tap, a car browse tap, Play on a stopped
        // track, a link): earlier failures don't count towards the car's run.
        if (!advancing) carRun.reset()
        try {
            playTrackInternalUnsafe(track, skipReselect, advancing, userRequested)
            completed = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Don't swallow cancellation
        } catch (e: Exception) {
            Log.e(TAG, "playTrackInternal failed for '${track.title}': ${e.message}", e)
            // Leave the app in a usable state — show the track in the mini player
            // even though playback failed, so the user can tap play to retry.
            stateHolder.update { copy(currentTrack = track, isPlaying = false, isBuffering = false) }
            playFailure = PlayFailure.ERROR
        } finally {
            // Nothing plays and the queue isn't moving on: Play loads the
            // track again (the network may be back), as it was asked for.
            val failure = playFailure
            playFailure = null
            val stoppedByCar = carStoppedPlay
            carStoppedPlay = false
            if (completed && failure == null) carRun.started()
            // It (or a fallback source) started: repeat all may wrap past an
            // unplayable last track again.
            // Only for the queue this play belongs to: a new queue set while
            // it was in flight hasn't played anything yet.
            if (playMarksQueuePlayed(completed, failure, queueAtStart, queueGeneration)) playedSinceWrap = true
            if (failure != null &&
                failedPlayAwaitsLoadAfterCarStop(failure, advancing, stateHolder.state.value.spinoffMode, stoppedByCar) &&
                stateHolder.state.value.currentTrack?.id == track.id
            ) {
                Log.d(TAG, "'${track.title}' didn't play ($failure); Play will load it")
                loadOnPlay = failedLoad(track, skipReselect, failedAtMs = 0, durationMs = 0)
                // The listener takes it (Listen Along): no message about it,
                // and unless it played another track, Play does nothing while
                // it waits ([heldForListener]) instead of loading this one.
                val requestsBefore = playRequestCount
                if (onTrackUnplayable?.invoke(track) == true) {
                    Log.d(TAG, "'${track.title}': the listener handles the failed play")
                    if (playRequestCount == requestsBefore) heldForListener = true
                    failureNotice = null
                    if (stateHolder.state.value.currentTrack?.id == track.id) {
                        stateHolder.update { copy(playbackError = null) }
                    }
                }
            }
            failureNotice?.let { _notices.tryEmit(it) }
            failureNotice = null
            // Promoted at the start, and no route took it over: nothing is
            // playing. Kept when the queue is skipping past this track, for the
            // next one ([keepsForegroundForAutoSkip]): the skip's play isn't
            // user-requested and can't promote from the background. The next
            // track's route takes it over, or the end of the queue (or this
            // play's finally, for the next track) releases it.
            val skipping = autoSkipPendingFor(autoSkipPendingTrackId, track.id)
            if (foregroundHeldForPlay && keepsForegroundForAutoSkip(skipping, isExternalPlayback)) {
                Log.d(TAG, "'${track.title}' is being skipped; keeping the foreground for the next track")
            } else {
                releaseForegroundHeldForPlay("play of '${track.title}' didn't start")
            }
        }
    }

    private suspend fun playTrackInternalUnsafe(
        track: TrackEntity,
        skipReselect: Boolean = false,
        advancing: Boolean = false,
        userRequested: Boolean = !advancing,
    ) {
        playRequestedThisRun = true
        // Cancel any idle timeout — we're actively playing now
        cancelIdleTimeout()
        // A track waiting for Play to load it (restored, idle-stopped, failed)
        // is superseded by any play: left set, a later Play would start that
        // track instead. A fallback play (playFromOtherSource) comes through
        // here too, so its outcome replaces the refused source's.
        loadOnPlay = null
        playFailure = null
        carStoppedPlay = false
        failureNotice = null
        heldForListener = false
        manualPickTrackId = if (skipReselect) track.id else null
        playsBegun++
        // Overtakes a skip waiting on the previous track (the skip's own play
        // comes through here too, after the skip has run).
        clearPendingAutoSkip()
        onTrackStarting?.invoke(track)
        // Take the foreground now, while the request's window to start a
        // foreground service is open: the lookup below can outlast it, and
        // without one Android cuts the app's network when it isn't on screen.
        // The route takes it over: kept for external playback, handed to Media3
        // for ExoPlayer, released by playTrackInternal if nothing plays.
        if (shouldPromoteAtPlayStart(userRequested, isAppVisible(), isExternalPlayback)) {
            Log.d(TAG, "Promoting to foreground at play start for '${track.title}'")
            sendExternalPlaybackStart(track)
            foregroundHeldForPlay = true
        }
        if (stateHolder.state.value.playbackError != null) {
            stateHolder.update { copy(playbackError = null) }
        }

        // Re-select the best resolver from cached sources before routing.
        // Stored tracks may have a stale `resolver` field from when they were first
        // added (e.g. "spotify"), but the user may now prioritize a different resolver
        // (e.g. "applemusic"). The TrackResolverCache has live resolution results
        // sorted by the user's current priority order, so we pick the best one.
        // Skipped when the user manually picks a source via the resolver dropdown.
        var routedTrack = if (skipReselect) track else reselectBestSource(track)
        // Set by the on-the-fly resolve below: no source because the resolvers
        // couldn't answer (#566), not because they found nothing.
        var resolversCouldNotAnswer = false

        // If the track has no resolver and no source URL, try resolving on-the-fly.
        // This handles ephemeral tracks (e.g. weekly playlists, recommendations)
        // that haven't been through the resolver pipeline yet.
        if (routedTrack.resolver == null && routedTrack.sourceUrl == null &&
            routedTrack.spotifyUri == null && routedTrack.spotifyId == null &&
            routedTrack.soundcloudId == null && routedTrack.appleMusicId == null
        ) {
            Log.d(TAG, "Track '${routedTrack.title}' has no source, resolving on-the-fly")
            val resolved = resolveOnTheFly(routedTrack)
            routedTrack = resolved.track
            resolversCouldNotAnswer = resolved.resolversCouldNotAnswer
        }

        // Spotify already couldn't be woken from the background this session:
        // skip the ~3s wake attempt per track until a Parachord screen shows.
        if (routedTrack.resolver == "spotify" && spotifyBlockedInBackground) {
            if (isAppVisible()) {
                spotifyBlockedInBackground = false
            } else {
                playWithoutSpotify(routedTrack, advancing)
                return
            }
        }

        val action = router.route(routedTrack)
        val snapshot = queueManager.snapshot.value

        if (action == null) {
            // No source to play it from. Online, with the resolvers answering,
            // that's the track (skipped when the queue is moving on, like a
            // refused embed); offline, or with resolvers that couldn't answer
            // (a rate-limit cooldown, a plugin timeout, #566), the lookup didn't
            // really run, so playback stops on it and Play tries it again.
            val online = networkMonitor.isOnline.value
            val failure = noSourceFailure(online, resolversCouldNotAnswer)
            Log.w(TAG, "No playable source for '${routedTrack.title}' (${if (online) "online" else "offline"}, resolvers couldn't answer: $resolversCouldNotAnswer)")
            playFailure = failure
            // Skip it, stop for Play, or (the car's third in a row) stop here,
            // and what to say (the shared rule, #616): decided before the
            // track is published, so the car gets one message, not the
            // phone's and then its own.
            val carConnection = carConnectionForStop()
            val outcome = unplayableTrackOutcome(failure, advancing, stateHolder.state.value.spinoffMode) {
                carStopsAutoSkip(routedTrack, carConnection)
            }
            val autoSkip = outcome is UnplayableTrackOutcome.Skip
            stateHolder.update {
                copy(
                    currentTrack = routedTrack,
                    isPlaying = false,
                    isBuffering = false,
                    position = 0L,
                    duration = routedTrack.duration ?: 0L,
                    upNext = snapshot.upNext,
                    playbackContext = snapshot.playbackContext,
                    shuffleEnabled = snapshot.shuffleEnabled,
                    streamingMetadata = null,
                    playbackError = (outcome as? UnplayableTrackOutcome.Stop)?.message,
                )
            }
            // Stop what was playing before it: left playing, its own end of
            // track would advance the queue as well as the skip below (two
            // advances, the second past the next track).
            val followed = outcome is UnplayableTrackOutcome.Stop && !outcome.byCar &&
                drivesTrack?.invoke(routedTrack) == true
            if (autoSkip) holdForegroundForAutoSkip(routedTrack)
            stopBeforeFailedTrack(autoSkip, followed)
            if (followed) holdForListener()
            if (autoSkip) {
                Log.d(TAG, "Auto-skip '${routedTrack.title}': no playable source")
                scheduleAutoSkip(routedTrack)
            }
            return
        }

        when (action) {
            is PlaybackAction.ExoPlayerItem -> {
                if (isExternalPlayback || foregroundHeldForPlay) {
                    if (localPlayKeepsHeldForeground(isAppVisible())) {
                        // No screen showing: dropped here, the foreground
                        // couldn't be started again for this track (#614),
                        // whoever asked for the play (Listen Along's plays
                        // count as user-requested). PlaybackService keeps it
                        // until ExoPlayer plays, then hands it to Media3.
                        Log.d(TAG, "Keeping the foreground until '${routedTrack.title}' plays locally")
                    } else {
                        sendExternalPlaybackStop()
                    }
                }
                foregroundHeldForPlay = false
                isExternalPlayback = false
                notifyExternalMode(false)
                stopSpotifyStatePolling()
                stopAppleMusicStatePolling()
                stopEmbedStatePolling()

                val ctrl = controller ?: run { playFailure = PlayFailure.ERROR; return }
                // Restore normal ExoPlayer settings after external playback,
                // at this resolver's balanced level rather than a flat 1f — a
                // hot local file or SoundCloud stream otherwise jumps against
                // whatever source preceded it (desktop parity).
                ctrl.volume = resolverVolumeFraction(routedTrack.resolver)
                setEngineRepeatMode(ctrl, Player.REPEAT_MODE_OFF)
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
                // External playback owns the foreground from here on.
                foregroundHeldForPlay = false
                stopPositionUpdates()
                // Cancel old polling jobs WITHOUT releasing the wake lock — we need
                // continuous CPU wakefulness across the track transition. The new
                // startPolling() call below will take over wake lock ownership.
                // Releasing here would create a gap during handler.play() (a suspend
                // call) where Android can suspend the process.
                spotifyStateJob?.cancel()
                appleMusicPollingJob?.cancel()
                embedPollingJob?.cancel()
                router.getAppleMusicHandler().musicKitBridge.onTrackEnded = null
                activeEmbedHandler?.onFinished = null

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
                                .setSubtitle(routedTrack.artist)
                                .setArtist(routedTrack.artist)
                                .setAlbumTitle(routedTrack.album)
                                .setArtworkUri(artworkUri)
                                .build()
                        )
                        .build()
                    ctrl.setMediaItems(listOf(silenceItem))
                    setEngineRepeatMode(ctrl, Player.REPEAT_MODE_ONE)
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

                (action.handler as? EmbedPlaybackHandler)?.volumeFraction =
                    resolverVolumeFraction(routedTrack.resolver)

                try {
                    // Every play and skip waits on playMutex, so a handler
                    // that never returns freezes playback for the session: a
                    // spinner, and no track, prev or next will start (#449,
                    // MusicKit after the network dropped mid-start). Each
                    // handler bounds its own waits; this is the backstop.
                    kotlinx.coroutines.withTimeoutOrNull(PLAY_START_TIMEOUT_MS) {
                        action.handler.play(routedTrack)
                        true
                    } ?: throw com.parachord.android.playback.handlers.PlaybackStartTimeoutException(
                        "${action.handler::class.simpleName} didn't start within ${PLAY_START_TIMEOUT_MS}ms",
                    )
                } catch (e: com.parachord.android.playback.handlers.SpotifyWakeBlockedException) {
                    spotifyBlockedInBackground = true
                    playWithoutSpotify(routedTrack, advancing)
                    return
                } catch (e: com.parachord.android.playback.handlers.SpotifyPlaybackNotStartedException) {
                    // Spotify accepted the play but never started (#452). In
                    // the background that keeps happening, so later Spotify
                    // tracks skip straight to the fallback too.
                    if (!isAppVisible()) spotifyBlockedInBackground = true
                    playWithoutSpotify(routedTrack, advancing)
                    return
                } catch (e: EmbedPlaybackRefusedException) {
                    playAfterEmbedRefused(routedTrack, e, advancing)
                    return
                } catch (e: com.parachord.android.playback.handlers.PlaybackStartTimeoutException) {
                    Log.w(TAG, "'${routedTrack.title}': ${e.message}")
                    playFailure = PlayFailure.START_TIMED_OUT
                    stateHolder.update {
                        copy(
                            isPlaying = false,
                            isBuffering = false,
                            playbackError = PLAY_START_FAILED_MESSAGE,
                        )
                    }
                    return
                } catch (e: com.parachord.android.playback.handlers.PlaybackStartFailedException) {
                    // The handler gave up without starting it (Apple Music
                    // offline). Play loads it again once the network is back.
                    Log.w(TAG, "'${routedTrack.title}': ${e.message}")
                    playFailure = PlayFailure.START_FAILED
                    stateHolder.update {
                        copy(
                            isPlaying = false,
                            isBuffering = false,
                            playbackError = if (e.alreadyReported) {
                                playbackError
                            } else {
                                PLAY_START_FAILED_MESSAGE
                            },
                        )
                    }
                    return
                }

                // Start the appropriate state polling based on the handler type
                startStatePolling(action.handler)
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
        val upcoming = com.parachord.shared.playback.NextTrackPreparation.tracksToPreresolve(snapshot.upNext)
        if (upcoming.isNotEmpty()) {
            trackResolverCache.resolveInBackground(upcoming, priority = false)
        }

        // Check spinoff availability for the new track (unless in spinoff mode)
        if (!stateHolder.state.value.spinoffMode) {
            checkSpinoffAvailability()
        }
    }

    /**
     * ExoPlayer gain (0f–1f) for [resolverId]'s configured dB offset.
     *
     * Base is 100 because Android, like iOS, has no in-app volume slider — the
     * offsets attenuate from full. A null/unknown resolver gets 1f, same as a
     * 0 dB offset.
     *
     * Deliberately NOT applied to the silence keepalive (`ctrl.volume = 0f`),
     * which must stay silent whatever the offsets say.
     */
    private suspend fun resolverVolumeFraction(resolverId: String?): Float {
        val id = resolverId ?: return 1f
        return try {
            ResolverVolume.effectiveVolumeFraction(
                100,
                settingsStore.getResolverVolumeOffsets()[id] ?: 0,
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            1f
        }
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

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // ExoPlayer's item failed (a local file, a stream; offline, or
                // the stream dropped). It sits in its error state, where
                // play() does nothing: Play loads the track again, where it
                // stopped. A REMOTE_ERROR is PlaybackService reporting our own
                // message (a refused track) for the car, not ExoPlayer's.
                if (isExternalPlayback) return
                if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_REMOTE_ERROR) return
                Log.w(TAG, "ExoPlayer failed (${error.errorCodeName}); Play will load the track")
                onLocalPlayerFailed(ctrl.currentPosition.coerceAtLeast(0L), ctrl.duration.coerceAtLeast(0L))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isExternalPlayback) {
                    syncState()
                    stateHolder.update {
                        copy(isBuffering = playbackState == Player.STATE_BUFFERING)
                    }
                    // No track on screen (the queue ended and was cleared, and
                    // a session Play prepared the empty player): nothing ended.
                    if (playbackState == Player.STATE_ENDED && stateHolder.state.value.currentTrack != null) {
                        skipNextInternal(AdvanceTrigger.TRACK_ENDED)
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
                    val position = ctrl.currentPosition.coerceAtLeast(0L)
                    val duration = ctrl.duration.coerceAtLeast(0L)
                    stateHolder.update { copy(position = position, duration = duration) }
                    if (com.parachord.shared.playback.NextTrackPreparation.shouldPrepareNext(position, duration)) {
                        prewarmSpotifyFor(queueManager.snapshot.value.upNext.firstOrNull())
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
                        skipNextInternal(AdvanceTrigger.TRACK_ENDED)
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
                scope.launch(Dispatchers.Main) { skipNextInternal(AdvanceTrigger.TRACK_ENDED) }
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
                    if (com.parachord.shared.playback.NextTrackPreparation.shouldPrepareNext(position, duration)) {
                        prewarmSpotifyFor(queueManager.snapshot.value.upNext.firstOrNull())
                    }
                    if (!nextTrackPreloaded && com.parachord.shared.playback.NextTrackPreparation.shouldPrepareNext(position, duration)) {
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
                            skipNextInternal(AdvanceTrigger.TRACK_ENDED)
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

    /** Start the state loop for [handler]'s service. */
    private fun startStatePolling(handler: ExternalPlaybackHandler) {
        when (handler) {
            is AppleMusicPlaybackHandler -> startAppleMusicStatePolling()
            is EmbedPlaybackHandler -> startEmbedStatePolling(handler)
            else -> startSpotifyStatePolling()
        }
    }

    /**
     * Mirror an embedded player (SoundCloud's widget, Bandcamp's embed) into the
     * playback state. The handler keeps its own state up to date (the widget
     * pushes events, the embed is polled), so this only copies it every 500 ms;
     * nothing waits on the WebView, so a Main thread deferred with the screen
     * off can't stall it.
     */
    private fun startEmbedStatePolling(handler: EmbedPlaybackHandler) {
        embedPollingJob?.cancel()
        activeEmbedHandler?.takeIf { it !== handler }?.onFinished = null
        activeEmbedHandler = handler
        acquireExternalPlaybackWakeLock()
        val trackEndHandled = java.util.concurrent.atomic.AtomicBoolean(false)
        val advance = {
            if (trackEndHandled.compareAndSet(false, true)) {
                scope.launch(Dispatchers.Main) { skipNextInternal(AdvanceTrigger.TRACK_ENDED) }
            }
        }
        handler.onFinished = {
            Log.d(TAG, "${handler::class.simpleName}: track finished")
            advance()
        }
        embedPollingJob = scope.launch(Dispatchers.Default) {
            // The player's finish is the signal; this catches one that never
            // arrived (the position stopped at the end, not playing).
            var stoppedAtEnd = 0
            while (isActive && isExternalPlayback) {
                val position = handler.getPosition()
                val duration = handler.getDuration()
                val playing = handler.isPlaying()
                stateHolder.update {
                    copy(
                        isPlaying = playing,
                        isBuffering = false,
                        position = position,
                        duration = if (duration > 0) duration else this.duration,
                    )
                }
                if (com.parachord.shared.playback.NextTrackPreparation.shouldPrepareNext(position, duration)) {
                    prewarmSpotifyFor(queueManager.snapshot.value.upNext.firstOrNull())
                }
                stoppedAtEnd = if (!playing && duration > 0 && duration - position < 1500) stoppedAtEnd + 1 else 0
                if (stoppedAtEnd >= 4) {
                    Log.d(TAG, "${handler::class.simpleName}: track done (safety net, pos=$position dur=$duration)")
                    advance()
                    break
                }
                delay(500)
            }
        }
    }

    private fun stopEmbedStatePolling() {
        embedPollingJob?.cancel()
        releaseExternalPlaybackWakeLock()
        activeEmbedHandler?.onFinished = null
        activeEmbedHandler = null
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
            val state = stateHolder.state.value
            // A slot already set for this track says how to load it (a failed
            // play re-chooses its source; a queue given back paused starts
            // from the top): keep it.
            loadOnPlay = idleTimeoutLoad(loadOnPlay, state.currentTrack, state.position)
            isExternalPlayback = false
            router.stopExternalPlayback()
        }
    }

    /**
     * Start [track] and, once it plays, seek to [positionMs]: Play on a
     * track nothing holds ([loadOnPlay]): after the idle timeout (#550),
     * whose stopped handler can't be resumed, on a queue restored at launch
     * (#567), and after a play that failed. A track that takes a while to
     * start (a slow or returning network) is waited for, so it still resumes
     * where it was instead of from 0:00; another track playing meanwhile
     * cancels the seek. When this play fails too, the next Play aims for the
     * same position.
     */
    private suspend fun playThenSeek(track: TrackEntity, positionMs: Long, skipReselect: Boolean) {
        playTrackInternal(track, skipReselect = skipReselect)
        if (positionMs <= 0) return
        val deadline = System.currentTimeMillis() + RESUME_SEEK_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (stateHolder.state.value.currentTrack?.id != track.id) return
            // It failed (now, or ExoPlayer's item a moment later): the next
            // Play aims where this one did.
            val failed = loadOnPlay?.takeIf { it.why == LoadOnPlay.Why.FAILED && it.track.id == track.id }
            if (failed != null) {
                loadOnPlay = failedLoad(
                    track, failed.skipReselect, failedAtMs = failed.positionMs, durationMs = 0, aimedAtMs = positionMs,
                )
                stateHolder.update { copy(position = positionMs) }
                return
            }
            val playing = if (isExternalPlayback) {
                router.activeExternalHandler?.isPlaying() == true
            } else {
                controller?.isPlaying == true
            }
            if (playing) {
                seekTo(positionMs)
                return
            }
            delay(250)
        }
        Log.d(TAG, "'${track.title}' didn't start within $RESUME_SEEK_WAIT_MS ms; not seeking to $positionMs ms")
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
     * Last.fm (resolved as they play, not up front) and begin playing from the pool.
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
            displayName = PlaybackMessages.spinoffFrom(track.title, track.artist),
        )
    }

    /**
     * In-app spinoff entry point (right-click → Spinoff, via [startSpinoff]).
     * Fire-and-forget: resolves the seed, builds the pool, then begins spinoff.
     *
     * Accepts an artist-only seed ([seedTitle] null/blank) — it becomes an
     * artist+title seed via the shared [com.parachord.shared.playback.resolveSpinoffSeed]
     * (the artist's top Last.fm track), exactly as desktop's `startSpinoff` does
     * (parachord#1005), so every caller behaves the same.
     *
     * The `parachord://play/radio` deep link does NOT come through here: it calls
     * [buildSpinoffPool] + [startSpinoffFromPool] itself so it can build the WHOLE
     * pool before tearing down the user's queue (#397).
     *
     * [displayName], when non-null, sets the [PlaybackContext.name] directly;
     * null falls back to "Spinoff from <title> by <artist>" of the resolved seed
     * ([PlaybackMessages.spinoffFrom]).
     *
     * [kickStartFirstTrack] controls whether the first pool track plays now
     * (`true`) or the current track keeps playing and `skipNextInternal` pulls
     * from the pool when it ends (`false`, the in-app default). Don't infer this
     * from runtime state — `stateHolder.currentTrack` survives a teardown's
     * `clearQueue()`, so a "currentTrack == null" guard silently failed to kick.
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

        spinoffJob = scope.launch(Dispatchers.IO) {
            try {
                val seed = com.parachord.shared.playback.resolveSpinoffSeed(
                    seedArtist, seedTitle, ::artistTopTrackTitle,
                )
                if (seed == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            com.parachord.shared.playback.noSpinoffSeedMessage(seedArtist),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    stateHolder.update { copy(spinoffLoading = false) }
                    return@launch
                }

                val pool = buildSpinoffPool(seed.artist, seed.title)
                if (pool.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, PlaybackMessages.noSimilarTracks(seed.title), Toast.LENGTH_SHORT).show()
                    }
                    stateHolder.update { copy(spinoffLoading = false, spinoffAvailable = false) }
                    return@launch
                }

                startSpinoffFromPool(
                    pool = pool,
                    displayName = displayName ?: PlaybackMessages.spinoffFrom(seed.title, seed.artist),
                    seedArtist = seed.artist,
                    seedTitle = seed.title,
                    kickStartFirstTrack = kickStartFirstTrack,
                )
            } catch (e: Exception) {
                // Cancellation must propagate (CLAUDE.md Android rule #29) — a
                // cancelled spinoffJob is not a "failed to fetch" for the user.
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Spinoff: failed to start", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, PlaybackMessages.SPINOFF_FETCH_FAILED, Toast.LENGTH_SHORT).show()
                }
                stateHolder.update { copy(spinoffLoading = false) }
            }
        }
    }

    /** The artist's top Last.fm track title, or null — the artist-only spinoff seed (#397). */
    suspend fun artistTopTrackTitle(artist: String): String? =
        lastFmClient.getArtistTopTracks(artist = artist, apiKey = BuildConfig.LASTFM_API_KEY, limit = 1)
            .toptracks?.track?.firstOrNull()?.name

    /** Last.fm as the shared spinoff pool sees it (#534). */
    private val spinoffLastFm by lazy {
        com.parachord.shared.playback.LastFmSpinoffSource(lastFmClient, BuildConfig.LASTFM_API_KEY)
    }

    /**
     * Build the seed's pool with the shared builder (Last.fm similar tracks,
     * topped up from similar artists' top tracks when there are fewer than 5,
     * desktop parachord#1281) as metadata-only tracks ([spinoffTrack]).
     *
     * Nothing is resolved here: a pool track is resolved when it plays
     * (`playTrackInternalUnsafe` → [resolveOnTheFly]), the next few are
     * pre-resolved in the background ([preresolveSpinoffPool]), and one that
     * has no source is skipped (`PlayFailure.NO_SOURCE` during a spinoff).
     * Resolving every candidate up front took 20 resolver fan-outs before the
     * spinoff could start.
     *
     * Touches NO playback state, so a caller can run it BEFORE deciding whether
     * to tear down the user's queue (#397). Returns the pool in the builder's
     * order (it already shuffled, and its fallback tracks are dealt so no two in
     * a row share an artist, so don't shuffle again), possibly empty. A failed
     * similar-tracks request throws
     * [com.parachord.shared.playback.SpinoffFetchFailedException] so the caller
     * reports it as a failure, not as "no similar tracks".
     */
    suspend fun buildSpinoffPool(seedArtist: String, seedTitle: String): List<TrackEntity> =
        withContext(Dispatchers.IO) {
            val built = com.parachord.shared.playback.buildSpinoffPool(spinoffLastFm, seedArtist, seedTitle)
            if (built.failed) throw com.parachord.shared.playback.SpinoffFetchFailedException()
            val pool = built.tracks.map { spinoffTrack(it.title, it.artist) }
            if (pool.isEmpty()) Log.d(TAG, "Spinoff: no similar tracks found for '$seedTitle'")
            else Log.d(TAG, "Spinoff: ${pool.size} pool tracks for '$seedTitle'" +
                if (built.usedFallback) " (similar artists' top tracks)" else "")
            pool
        }

    /**
     * True when [track] resolves to a source that can play now (one that
     * passes [ResolverScoring.selectBest]). The radio link's probe
     * ([firstPlayableIndex]) uses it so a station whose first tracks can't
     * play isn't started (#397). It reads the resolver cache first
     * ([TrackResolverCache.resolveTrack]), so a track resolved earlier in the
     * session isn't resolved again, and a fresh resolve is cached, so the
     * track the station starts with doesn't resolve again either.
     */
    suspend fun isPlayableNow(track: TrackEntity): Boolean = withContext(Dispatchers.IO) {
        val sources = trackResolverCache.resolveTrack(
            title = track.title,
            artist = track.artist,
            spotifyId = track.spotifyId,
            appleMusicId = track.appleMusicId,
            soundcloudId = track.soundcloudId,
            recordingMbid = track.recordingMbid,
        )
        resolverScoring.selectBest(sources) != null
    }

    /**
     * Resolve the spinoff pool's next few tracks ([spinoffTracksToPreresolve])
     * in the background so they're ready to play. Off the priority lane: the
     * user isn't looking at the pool. Tracks already resolved are skipped by
     * the cache.
     */
    private fun preresolveSpinoffPool() {
        val ahead = spinoffTracksToPreresolve(spinoffPool)
        if (ahead.isEmpty()) return
        trackResolverCache.resolveInBackground(ahead, backfillDb = false, priority = false)
    }

    /**
     * Begin spinoff from a non-empty [pool] of metadata-only tracks (see
     * [buildSpinoffPool]); the next few are pre-resolved here.
     *
     * The user's queue is NOT modified here — only the playback context is saved
     * (desktop behavior). For the deep link, the caller has already torn the
     * queue down; for in-app spinoff it stays intact and resumes on exit.
     */
    suspend fun startSpinoffFromPool(
        pool: List<TrackEntity>,
        displayName: String,
        seedArtist: String,
        seedTitle: String,
        kickStartFirstTrack: Boolean,
    ) {
        val spinoffContext = PlaybackContext(type = "spinoff", name = displayName)

        // Save the previous playback context (the queue is NOT modified —
        // desktop behavior) and fill the pool on Main: skipNextInternal reads
        // the pool and the (not thread-safe) QueueManager there, and this
        // function runs on spinoffJob's IO dispatcher.
        withContext(Dispatchers.Main) {
            // A spinoff ends Listen Along (silently; the suspended queue is
            // dropped), so its context isn't one to come back to.
            onUserAction?.invoke(UserPlaybackAction.SPINOFF_STARTED)
            preSpinoffContext = queueManager.playbackContext?.takeUnless { it.type == LISTEN_ALONG_CONTEXT }
            spinoffSourceTrack = stateHolder.state.value.currentTrack
            spinoffPool.clear()
            spinoffPool.addAll(pool)
        }

        stateHolder.update {
            copy(
                spinoffMode = true,
                spinoffLoading = false,
                spinoffAvailable = true,
            )
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                PlaybackMessages.spinningOff(seedTitle, seedArtist),
                Toast.LENGTH_SHORT,
            ).show()
        }

        // Kick-start path (deep link): pull the first pool track and play it
        // now. playTrack() will clearQueue() and then re-apply the spinoff
        // context atomically — that's why the context is passed through here
        // instead of being pre-set; a bare setContext() before playTrack()
        // would get clobbered by playTrack's internal clearQueue().
        //
        // Non-kick path (in-app spinoff): the current track keeps playing;
        // set the context now so the banner updates immediately, and let
        // skipNextInternal() pull from the pool when the current song ends.
        if (kickStartFirstTrack) {
            withContext(Dispatchers.Main) {
                if (spinoffPool.isEmpty()) return@withContext
                val first = spinoffPool.removeAt(0)
                Log.d(TAG, "Spinoff: kicking off with '${first.title}' by ${first.artist}")
                preresolveSpinoffPool()
                playTrack(first, context = spinoffContext)
                // playTrack cleared the queue; its first track is a pool
                // track, so the end of the station doesn't loop it (repeat all).
                queueManager.poolTrackStarted(null)
            }
        } else {
            withContext(Dispatchers.Main) {
                preresolveSpinoffPool()
                queueManager.setContext(spinoffContext)
                // Publish the context now so the banner ("Spinoff from … by …")
                // shows at once, not only when the first pool track plays.
                syncQueueState()
            }
        }
    }

    /**
     * Mode C refill trigger — fire-and-forget fetch + dedup + append.
     *
     * Called from [skipNextInternal] when the spinoff pool is low
     * ([spinoffNeedsRefill]) and a refill URL is configured. The
     * actual rate-limit / stop-condition / dedup logic lives in
     * [PoolRefiller.tryRefill] — this method is just the
     * coroutine-launch shim.
     *
     * Pool-level [PlaybackContext] is inherited (refilled tracks just
     * join the existing spinoff context — no per-track tagging needed
     * on Android since context is queue-level, not per-track).
     */
    private fun triggerPoolRefill() {
        // Cheap fast-path so we don't pay for a snapshot copy or coroutine
        // launch when the refiller is rate-limited / stopped / unconfigured.
        if (!poolRefiller.canRefill()) return
        // Snapshot on the caller's thread (controller scope = Main, same
        // thread as skipNextInternal's removeAt(0)) so we don't race a
        // concurrent modification of [spinoffPool] (a plain mutableListOf,
        // not thread-safe) during toList().
        val snapshot = spinoffPool.toList()
        val refillUrl = poolRefiller.refillUrl
        scope.launch(Dispatchers.IO) {
            try {
                val fresh = poolRefiller.tryRefill(snapshot) ?: return@launch
                // addAll back on Main — same dispatcher as skipNextInternal's
                // removeAt(0). Pool mutations stay on a single thread. A refill
                // that finishes after its spinoff ended (or after another
                // station replaced it) is dropped: it belongs to no pool.
                val added = withContext(Dispatchers.Main) {
                    if (!stateHolder.state.value.spinoffMode || poolRefiller.refillUrl != refillUrl) {
                        Log.d(TAG, "Pool refill: dropped ${fresh.size} tracks, the spinoff it was for has ended")
                        false
                    } else {
                        spinoffPool.addAll(fresh)
                        Log.d(TAG, "Pool refill: appended ${fresh.size} tracks (pool now ${spinoffPool.size})")
                        true
                    }
                }
                if (!added) return@launch
                // No bulk pre-warm: refilled tracks resolve when they play.
                // Pre-resolve only the next few (the pool may have been
                // nearly empty when the refill landed).
                withContext(Dispatchers.Main) { preresolveSpinoffPool() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pool refill launch failed: ${e.message}")
            }
        }
    }

    /**
     * Set (or clear) the fetcher used by the Mode C refill loop to
     * re-fetch tracks from a `?refill=…` URL when `spinoffPool.size < 3`.
     *
     * The fetcher is a closure over the protocol resolver (wired by
     * [com.parachord.android.deeplink.PlayRadioDispatcher] at construction
     * time), so the same SSRF guard, JSPF/XSPF parser, and
     * LB-token-auto-attach logic that the initial pool fetch goes through
     * also applies to refills — for free.
     *
     * Stored as a process-lifetime reference. Not cleared on
     * [exitSpinoff] (only the per-pool refillUrl + counters are), so a
     * subsequent pool-based spinoff doesn't need to re-wire it.
     */
    fun setPoolFetcher(fetcher: (suspend (String) -> List<com.parachord.shared.deeplink.ProtocolTrack>)?) {
        poolRefiller.fetcher = fetcher
    }

    /**
     * Toggle the playbar loading flag, used by the deeplink dispatcher
     * to show a spinner in the mini-player while a Mode C radio pool
     * is being fetched (multi-second URL fetch + parse + resolve).
     * The flag clears automatically once a track lands in
     * [PlaybackState.currentTrack], but the dispatcher also clears it
     * via a finally-block so failure paths don't leave it stuck.
     */
    fun setPlaybarLoading(loading: Boolean) {
        stateHolder.update { copy(isPlaybarLoading = loading) }
    }

    /**
     * Pool-based spinoff (Mode C of `parachord://play/radio`).
     *
     * No Last.fm seed step — the caller supplies an already-resolved pool,
     * pre-built from inline `?tracks=` JSON or a fetched JSPF/XSPF/M3U
     * tracklist. Mirrors desktop's "externally curated pool" path.
     *
     * [refillUrl], when non-null, is forwarded to [poolRefiller] so the
     * refill loop fires when the pool is low ([spinoffNeedsRefill]).
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
        // Callers must pass a non-empty pool — empty pools are filtered
        // upstream by ProtocolPlayHandler.handle(PlayRadio).
        spinoffJob?.cancel()
        spinoffPool.clear()
        // Pool-based has no source track — Task 6's banner branch keys off
        // `spinoffSourceTrack == null` to render "$displayName" rather
        // than "Spun off from $title by $artist".
        spinoffSourceTrack = null

        // Save previous playback context (queue is NOT modified — desktop behavior).
        // Never a Listen Along one: the session has ended by now.
        preSpinoffContext = queueManager.playbackContext?.takeUnless { it.type == LISTEN_ALONG_CONTEXT }

        spinoffPool.addAll(initialPool)

        // Wire refill URL + reset per-pool counters. The fetcher is
        // process-lifetime (set once by PlayRadioDispatcher at app start
        // / first dispatch) so we don't touch it here — preserves it
        // across exit + re-enter cycles. Counters reset to prevent the
        // previous pool's empty-count or last-fetch timestamp leaking
        // into this one.
        poolRefiller.refillUrl = refillUrl
        poolRefiller.resetCounters()

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
        // Pool tracks resolve when they play; only the next few are
        // pre-resolved, as in the in-app spinoff (#177: never the whole pool).
        preresolveSpinoffPool()
        playTrack(first, context = spinoffContext)
        // playTrack cleared the queue; its first track is a pool track, so
        // the end of the station doesn't loop it (repeat all).
        queueManager.poolTrackStarted(null)
    }

    /**
     * Exit spinoff mode and restore the previous queue context.
     * Matches the desktop's exitSpinoff() logic.
     */
    fun exitSpinoff() {
        val state = stateHolder.state.value
        when (com.parachord.shared.playback.spinoffExit(state.spinoffMode, fetching = state.spinoffLoading)) {
            com.parachord.shared.playback.SpinoffExit.NOTHING -> return
            com.parachord.shared.playback.SpinoffExit.CANCEL_FETCH -> {
                // Its tracks would start it later and end what was chosen since (#612).
                spinoffJob?.cancel()
                spinoffJob = null
                stateHolder.update { copy(spinoffLoading = false) }
                Log.d(TAG, "Spinoff: cancelled the fetch still running")
                return
            }
            com.parachord.shared.playback.SpinoffExit.END_SPINOFF -> {}
        }

        spinoffJob?.cancel()
        spinoffPool.clear()
        spinoffSourceTrack = null
        // Full reset — clear refillUrl + counters. fetcher stays wired
        // (it's process-lifetime), so the next pool-based spinoff
        // doesn't need to re-set it.
        poolRefiller.refillUrl = null
        poolRefiller.resetCounters()

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
     * Check whether spinoff is available for the current track — called on
     * track change. The shared check (desktop parachord#1281) asks
     * `track.getsimilar&limit=1`, and only when that's empty
     * `artist.getsimilar&limit=1`, so songs Last.fm has no similar tracks for
     * ("Shape of You") still get a Spinoff from similar artists. A failed
     * request answers false.
     */
    fun checkSpinoffAvailability() {
        val track = stateHolder.state.value.currentTrack ?: return
        // Don't check during spinoff mode
        if (stateHolder.state.value.spinoffMode) return

        scope.launch(Dispatchers.IO) {
            try {
                val available = com.parachord.shared.playback.checkSpinoffAvailable(
                    spinoffLastFm, track.artist, track.title,
                )
                // Up to two requests: don't let a slow answer for the previous
                // track land on the one now playing.
                if (stateHolder.state.value.currentTrack?.id != track.id) return@launch
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
        // A cold start (Android Auto) routes by the sources saved last session.
        trackResolverCache.awaitLoaded()
        val cachedSources = trackResolverCache.trackSources.value[key]
        if (cachedSources.isNullOrEmpty()) {
            // Nothing resolved yet (a cold start — Android Auto, a widget, a
            // restored queue). Resolve in the background so the source icons
            // show and later plays have every source, and meanwhile route by
            // the IDs the track already carries, in the user's priority order.
            trackResolverCache.resolveInBackground(listOf(track))
            return routeByStoredIds(track)
        }

        val best = resolverScoring.selectBest(cachedSources) ?: return track

        // Attach the played source's ISRC for the MBID fallback even when the
        // resolver is unchanged — ISRC is never persisted on the track row, so
        // it must be re-merged from the resolved source on each play. Walk +
        // validate with desktop-parity precedence (#217): carried top-level first,
        // then sources (skipping noMatch), canonical-or-null.
        val isrc = com.parachord.shared.resolver.pickTrackIsrc(track.isrc, cachedSources)

        // Only re-route if the best resolver actually changed (but still carry isrc).
        if (best.resolver == track.resolver) return track.copy(isrc = isrc)

        Log.d(TAG, "Re-routed '${track.title}' from ${track.resolver} → ${best.resolver} (user priority)")
        return track.copy(
            resolver = best.resolver,
            sourceType = best.sourceType,
            sourceUrl = best.url,
            spotifyUri = best.spotifyUri ?: track.spotifyUri,
            spotifyId = best.spotifyId ?: track.spotifyId,
            soundcloudId = best.soundcloudId ?: track.soundcloudId,
            appleMusicId = best.appleMusicId ?: track.appleMusicId,
            isrc = isrc,
        )
    }

    /**
     * Route a track with nothing resolved yet by the streaming IDs it already
     * carries, in the user's resolver order, among services that are connected.
     * Its stored `resolver` is just whichever service first played or synced it:
     * a Daily Brew track pulled from Spotify keeps `resolver = "spotify"` even
     * with an Apple Music ID stored and Apple Music first in the order, and an
     * Android Auto cold start then went to Spotify, which couldn't start from
     * the background (#452). Same trust as [ResolverManager.resolveWithHints]:
     * a stored ID was validated when it was written.
     */
    private suspend fun routeByStoredIds(track: TrackEntity): TrackEntity {
        val candidates = com.parachord.shared.resolver.SourceReselection.storedIdSources(
            track,
            spotifyConnected = !settingsStore.getSpotifyAccessToken().isNullOrBlank(),
            appleMusicConnected = !settingsStore.getAppleMusicUserToken().isNullOrBlank(),
        )
        if (candidates.isEmpty()) return track
        val best = resolverScoring.selectBest(candidates) ?: return track
        if (!com.parachord.shared.resolver.SourceReselection.shouldRerouteToStoredId(track.resolver, best.resolver, settingsStore.getResolverOrder())) return track
        Log.d(TAG, "Routed '${track.title}' by stored IDs: ${track.resolver} → ${best.resolver} (user priority, nothing resolved yet)")
        return track.copy(
            resolver = best.resolver,
            sourceType = best.sourceType,
            sourceUrl = best.url,
            spotifyUri = best.spotifyUri ?: track.spotifyUri,
            spotifyId = best.spotifyId ?: track.spotifyId,
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

            // Its best source that can play: a noMatch or below-floor source isn't offered (#587).
            val source = com.parachord.shared.resolver.SourceReselection.sourceForSwitch(cachedSources, resolver)
                ?: return@launch

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
     * Set when Spotify couldn't be woken because no Parachord screen was
     * visible (Android Auto, a locked phone; #446). While it's set and the app
     * stays in the background, Spotify tracks go straight to [playWithoutSpotify].
     */
    private var spotifyBlockedInBackground = false

    private fun isAppVisible(): Boolean = com.parachord.android.app.CurrentActivityHolder.current != null

    /** The track [prewarmSpotifyFor] last checked, so each is checked once. */
    @Volatile private var spotifyPrewarmCheckedId: String? = null

    /**
     * If [track] will play from Spotify, get Spotify ready for it now
     * ([SpotifyPlaybackHandler.prewarm]) so its play starts on the warm path
     * instead of waiting 5–8 s for Spotify to register. Called while the
     * current song is in its last 30 s, with the next track, and for a
     * restored queue. Skipped while Spotify is already playing (it's warm) and
     * while it's known not to start in the background.
     */
    private fun prewarmSpotifyFor(track: TrackEntity?) {
        if (track == null || track.id == spotifyPrewarmCheckedId) return
        spotifyPrewarmCheckedId = track.id
        if (isExternalPlayback && router.activeExternalHandler is com.parachord.android.playback.handlers.SpotifyPlaybackHandler) return
        scope.launch(Dispatchers.Main) {
            if (spotifyBlockedInBackground && !isAppVisible()) return@launch
            val routed = reselectBestSource(track)
            if (routed.resolver == "spotify" && routed.spotifyUri != null) {
                Log.d(TAG, "'${track.title}' will play from Spotify — prewarming")
                router.getSpotifyHandler().prewarm(routed.spotifyUri)
            }
        }
    }

    /**
     * Play [track] from its best non-Spotify source: Spotify isn't running and
     * Android won't let us open it from the background. Uses the cached sources,
     * or resolves on the spot without Spotify (an Android Auto cold start has
     * none cached). With no other source, the track stops with a message the
     * car shows: open Spotify on the phone.
     */
    private suspend fun playWithoutSpotify(track: TrackEntity, advancing: Boolean) {
        if (playFromOtherSource(track, "spotify", advancing) != null) return
        Log.w(TAG, "'${track.title}': Spotify can't start in the background and there's no other source")
        playFailure = PlayFailure.NO_SOURCE_WITHOUT_SPOTIFY
        stateHolder.update {
            copy(
                currentTrack = track,
                isPlaying = false,
                isBuffering = false,
                playbackError = com.parachord.shared.car.CarMessages.OPEN_SPOTIFY_ON_PHONE,
            )
        }
    }

    /**
     * Play [track] from its best source other than [excluded]: the cached
     * sources, or a resolve without it when none of those will do. Returns
     * the resolver it played from, or null when there's no other source.
     */
    private suspend fun playFromOtherSource(track: TrackEntity, excluded: String, advancing: Boolean): String? {
        val key = trackKey(track.title, track.artist)
        trackResolverCache.awaitLoaded()
        var sources = trackResolverCache.trackSources.value[key].orEmpty().filter { it.resolver != excluded }
        if (resolverScoring.selectBest(sources) == null) {
            sources = try {
                resolverManager.resolve(
                    "${track.title} ${track.artist}",
                    targetTitle = track.title,
                    targetArtist = track.artist,
                    excludeResolvers = setOf(excluded),
                    targetIsrc = track.isrc,
                    targetMbid = track.recordingMbid,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Resolving '${track.title}' without $excluded failed: ${e.message}")
                emptyList()
            }
        }
        val best = resolverScoring.selectBest(sources) ?: return null
        Log.d(TAG, "'${track.title}': can't play from $excluded, playing from ${best.resolver}")
        playTrackInternalUnsafe(
            track.copy(
                resolver = best.resolver,
                sourceType = best.sourceType,
                sourceUrl = best.url,
                // The chosen source's ids, as reselectBestSource; never the
                // excluded service's, which the router would pick again.
                spotifyUri = best.spotifyUri ?: track.spotifyUri.takeIf { excluded != "spotify" },
                spotifyId = best.spotifyId ?: track.spotifyId.takeIf { excluded != "spotify" },
                soundcloudId = best.soundcloudId ?: track.soundcloudId.takeIf { excluded != "soundcloud" },
                appleMusicId = best.appleMusicId ?: track.appleMusicId.takeIf { excluded != "applemusic" },
                isrc = com.parachord.shared.resolver.pickTrackIsrc(track.isrc, sources),
            ),
            skipReselect = true,
            advancing = advancing,
        )
        return best.resolver
    }

    /**
     * A service's embedded player refused [track] (SoundCloud's widget can't
     * play a track SoundCloud won't let it embed). Desktop parachord#1209:
     * another source plays; otherwise the queue moves on when it was
     * advancing; otherwise the user is told, with the track's page on the
     * service to open. The browser never opens by itself.
     */
    private suspend fun playAfterEmbedRefused(
        track: TrackEntity,
        e: EmbedPlaybackRefusedException,
        advancing: Boolean,
    ) {
        Log.w(TAG, "'${track.title}': ${e.message}")
        embedRefusedTrackId = track.id
        val excluded = track.resolver ?: "soundcloud"
        val fallback = playFromOtherSource(track, excluded, advancing)
        if (fallback != null) {
            Toast.makeText(context, PlaybackMessages.playingViaInstead(e.serviceName, fallback), Toast.LENGTH_SHORT).show()
            return
        }
        // Nothing else can play it. Skipped when the queue is moving on,
        // otherwise Play loads it again (the shared [unplayableTrackOutcome]).
        playFailure = PlayFailure.EMBED_REFUSED
        val carConnection = carConnectionForStop()
        val outcome = unplayableTrackOutcome(
            PlayFailure.EMBED_REFUSED, advancing, stateHolder.state.value.spinoffMode,
            refusedMessage = com.parachord.shared.playback.SoundCloudWidget.refusedMessage(e.serviceName, track.title, e.reason),
        ) { carStopsAutoSkip(track, carConnection) }
        val autoSkip = outcome is UnplayableTrackOutcome.Skip
        val followed = outcome is UnplayableTrackOutcome.Stop && !outcome.byCar && drivesTrack?.invoke(track) == true
        if (autoSkip) holdForegroundForAutoSkip(track)
        stopBeforeFailedTrack(autoSkip, followed)
        if (followed) holdForListener()
        if (outcome !is UnplayableTrackOutcome.Stop) {
            Log.d(TAG, "Auto-skip '${track.title}': ${e.serviceName} won't play it")
            stateHolder.update { copy(isPlaying = false, isBuffering = false) }
            scheduleAutoSkip(track)
            return
        }
        stateHolder.update { copy(isPlaying = false, isBuffering = false, playbackError = outcome.message) }
        // The car shows why; no "Open in …" snackbar on the phone.
        if (outcome.byCar) return
        val message = outcome.message
        // Shown by playTrackInternal unless onTrackUnplayable takes the track.
        failureNotice = PlaybackNotice(
            message = message,
            actionLabel = e.pageUrl?.let { PlaybackMessages.openIn(e.serviceName) },
            actionUrl = e.pageUrl,
        )
    }

    /**
     * The queue is about to skip past [track], which can't play: whether the
     * car stops here instead (the shared [com.parachord.shared.car.CarUnplayableRun]).
     * Counts the failure; true on the run's third in a row while Android Auto
     * is connected and no Parachord screen is showing, when nobody can act on
     * the phone ([CarConnections.confirmed]). Not in the car, or with a screen showing, it only counts and
     * the queue skips on. A track a listener drives (Listen Along) isn't counted.
     * True marks the play [carStoppedPlay], so Play loads the track again.
     */
    private fun carStopsAutoSkip(track: TrackEntity, connection: Int?): Boolean {
        if (drivesTrack?.invoke(track) == true) return false
        val stop = if (connection == null) {
            carRun.failed(carWithoutPhone = false)
        } else {
            carRun.failed(carWithoutPhone = !isAppVisible(), connection = connection)
        }
        if (stop) {
            Log.w(TAG, "'${track.title}': ${com.parachord.shared.car.CAR_UNPLAYABLE_RUN_LIMIT} unplayable tracks in a row in the car; stopping here")
            carStoppedPlay = true
        }
        return stop
    }

    /**
     * The car connection [carStopsAutoSkip] counts against, or null with no
     * car. Read before the shared [unplayableTrackOutcome] asks, since that
     * asks without suspending. A tracked controller can outlive the car
     * (Media3 reports no disconnect for a legacy browser): Android Auto's own
     * state may veto it.
     */
    private suspend fun carConnectionForStop(): Int? = carConnections.confirmed(carConnectionState)

    /** A failed play's snackbar, held until [playTrackInternal] knows whether to show it. */
    private var failureNotice: PlaybackNotice? = null

    /**
     * Skip past [track], which can't play, while the queue moves on (a track
     * with no source, a refused embed): after [AUTO_SKIP_DELAY_MS], once this
     * advance's job has ended (skipNext ignores an auto-advance while one is in
     * flight), and only while [track] is still the current one and no play was
     * asked for meanwhile (a play or Next has moved on already). Until then
     * Play and Pause do nothing ([autoSkipPendingTrackId]) and a foreground
     * taken at play start stays for the next track ([keepsForegroundForAutoSkip]).
     * The one place an auto-skip is scheduled, so a "skip, or stop here?"
     * decision can go in front of it.
     */
    private fun scheduleAutoSkip(track: TrackEntity) {
        autoSkipJob?.cancel()
        autoSkipPendingTrackId = track.id
        val begun = playsBegun
        autoSkipJob = scope.launch {
            delay(AUTO_SKIP_DELAY_MS)
            autoSkipPendingTrackId = null
            autoSkipJob = null
            // Dropped only when another play began or is on its way (a tapped
            // track still resolving), or the track changed. A request that
            // played nothing (Previous restarting this track) doesn't count:
            // dropping the skip then would strand the queue on a track that
            // can't play.
            if (stateHolder.state.value.currentTrack?.id != track.id || playsBegun != begun || isPlayStarting) {
                Log.d(TAG, "Auto-skip of '${track.title}' dropped: something else was played")
                // A play in flight takes the kept foreground over; with none,
                // nothing will.
                if (!isPlayStarting) releaseForegroundHeldForPlay("auto-skip dropped")
                return@launch
            }
            skipNextInternal(AdvanceTrigger.SKIPPED_UNPLAYABLE)
            // The skip did nothing (another advance owned it, or a hook took
            // it) and nothing is starting: the foreground kept for it goes.
            if (stateHolder.state.value.currentTrack?.id == track.id && !isPlayStarting) {
                releaseForegroundHeldForPlay("auto-skip did nothing")
            }
        }
    }

    /**
     * Ask for the foreground for a skip past [track] when the track before it
     * was local and no Parachord screen is showing ([shouldHoldForegroundForAutoSkip]).
     * Kept like the play-start hold ([foregroundHeldForPlay]): the next track's
     * route takes it over, and the end of the queue, a dropped skip or a skip
     * that did nothing releases it ([scheduleAutoSkip]).
     *
     * After a local track that ended by itself with the screen off, the
     * service already kept the foreground at the end
     * ([keepForegroundAtLocalTrackEnd]), so this finds it held and the skip
     * carries it to the next track. Asked for here instead, it's only granted
     * while Android still allows a start (Media3 has let it go once ExoPlayer
     * ended, #614).
     */
    private fun holdForegroundForAutoSkip(track: TrackEntity) {
        if (!shouldHoldForegroundForAutoSkip(isAppVisible(), isExternalPlayback, foregroundHeldForPlay)) return
        Log.d(TAG, "Holding the foreground across the skip past '${track.title}'")
        sendExternalPlaybackStart(track)
        foregroundHeldForPlay = true
    }

    /** Drop a scheduled auto-skip ([scheduleAutoSkip]). */
    private fun clearPendingAutoSkip() {
        autoSkipJob?.cancel()
        autoSkipJob = null
        autoSkipPendingTrackId = null
    }

    /**
     * Give up the foreground a play took as it started ([foregroundHeldForPlay])
     * when nothing took it over. External playback owns its own foreground,
     * so it's left alone then.
     */
    private fun releaseForegroundHeldForPlay(why: String) {
        if (!foregroundHeldForPlay) return
        foregroundHeldForPlay = false
        if (isExternalPlayback) return
        Log.d(TAG, "Releasing the play-start foreground: $why")
        sendExternalPlaybackStop()
    }

    /**
     * ExoPlayer has just ended the current track by itself
     * ([Player.STATE_ENDED]), before Media3 lets the foreground go: whether
     * [PlaybackService] keeps it for what plays next
     * ([shouldKeepForegroundAtLocalTrackEnd]). Without it the next track,
     * started by the queue from the background, can't take the foreground
     * back (#614). True marks it held like the play-start hold
     * ([foregroundHeldForPlay]): the next track's route takes it over (an
     * external route keeps it, a local one hands it to Media3 once it plays),
     * and the end of the queue, a queue given back paused or a skip that's
     * dropped releases it. Called by the service on the main thread, from its
     * own listener on ExoPlayer, which runs ahead of this controller's.
     */
    internal fun keepForegroundAtLocalTrackEnd(): Boolean {
        val current = stateHolder.state.value.currentTrack ?: return false
        if (!shouldKeepForegroundAtLocalTrackEnd(isAppVisible(), isExternalPlayback, somethingFollows(current))) return false
        Log.d(TAG, "'${current.title}' ended with no Parachord screen showing; keeping the foreground for what plays next")
        foregroundHeldForPlay = true
        return true
    }

    /**
     * Whether something plays after [current] ends by itself: Listen Along
     * drives it (the session decides, and its end releases the foreground),
     * a spinoff pool has a track, or the queue does ([advanceAction] for a
     * track end: a next track, repeat one, or repeat all wrapping).
     */
    private fun somethingFollows(current: TrackEntity): Boolean {
        if (drivesTrack?.invoke(current) == true) return true
        val state = stateHolder.state.value
        if (state.spinoffMode && spinoffPool.isNotEmpty()) return true
        return advanceAction(AdvanceTrigger.TRACK_ENDED, state.repeatMode, queueManager.hasNext, true, playedSinceWrap) !=
            AdvanceAction.STOP_AND_CLEAR
    }

    /**
     * A play, an advance or a skip is under way, which a foreground the
     * service holds itself waits for ([nextForegroundHoldIdleMs]).
     */
    internal val foregroundTransitionInProgress: Boolean
        get() = isPlayStarting || autoSkipPendingTrackId != null

    /**
     * Listen Along waits for its next track with the ended one on screen
     * ([heldForListener]): a foreground the service holds is kept for it up
     * to [FOREGROUND_HOLD_LISTENER_LIMIT_MS] ([foregroundHoldExpired]).
     */
    internal val foregroundHeldForListener: Boolean
        get() = heldForListener

    /**
     * The service let go of a foreground it held with nothing playing or
     * starting for [FOREGROUND_HOLD_IDLE_LIMIT_MS]: nothing holds it now.
     */
    internal fun foregroundHoldExpired() {
        if (!foregroundHeldForPlay) return
        Log.d(TAG, "The held foreground went unused; released")
        foregroundHeldForPlay = false
    }

    /**
     * Stop what was playing before a track that can't play ([failedTrackStop]).
     * When the queue skips on ([autoSkip]) external playback keeps its mode,
     * silent loop, wake lock and foreground: dropping the foreground here left
     * the next track unable to take it back with the screen off (refused
     * from the background), and Android then stopped the service and cut the
     * app's network mid-song.
     */
    private suspend fun stopBeforeFailedTrack(autoSkip: Boolean, followed: Boolean = false) {
        when (failedTrackStop(isExternalPlayback, autoSkip, followed)) {
            FailedTrackStop.KEEP_EXTERNAL_FOREGROUND -> {
                // As a track change does: cancel the polling without releasing
                // the wake lock, and drop the end-of-track callbacks.
                spotifyStateJob?.cancel()
                appleMusicPollingJob?.cancel()
                embedPollingJob?.cancel()
                router.getAppleMusicHandler().musicKitBridge.onTrackEnded = null
                activeEmbedHandler?.onFinished = null
                activeEmbedHandler = null
                router.stopExternalPlayback()
            }
            FailedTrackStop.LEAVE_EXTERNAL -> {
                stopSpotifyStatePolling()
                stopAppleMusicStatePolling()
                stopEmbedStatePolling()
                router.stopExternalPlayback()
                sendExternalPlaybackStop()
                isExternalPlayback = false
            }
            FailedTrackStop.CLEAR_LOCAL_PLAYER -> {
                // Cleared too, or the player would report the previous track's
                // position for this one. stop() first: emptying a player that
                // isn't idle ends it, and STATE_ENDED would advance the queue.
                controller?.stop()
                controller?.clearMediaItems()
            }
        }
    }

    /** The last track an embedded player refused; see [togglePlayPause]. */
    @Volatile private var embedRefusedTrackId: String? = null

    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<PlaybackNotice>(extraBufferCapacity = 4)

    /** Playback messages for the UI that carry an action (a snackbar). */
    val notices: kotlinx.coroutines.flow.SharedFlow<PlaybackNotice> = _notices

    /**
     * Resolve an unresolved track on-the-fly at play time.
     * Used for ephemeral tracks (weekly playlists, recommendations) that haven't
     * been through the resolver pipeline yet. Caches the result in TrackResolverCache
     * so subsequent plays and queue items benefit.
     */
    private class OnTheFlyResolve(
        val track: TrackEntity,
        /** Nothing usable came back and a resolver couldn't answer, or the resolve threw (#566). */
        val resolversCouldNotAnswer: Boolean = false,
    )

    private suspend fun resolveOnTheFly(track: TrackEntity): OnTheFlyResolve {
        return try {
            val query = "${track.title} ${track.artist}"
            val report = resolverManager.resolveReport(
                query,
                targetTitle = track.title,
                targetArtist = track.artist,
                // Recording identity → exact-ID matches score 1.0 (#402).
                targetIsrc = track.isrc,
                targetMbid = track.recordingMbid,
            )
            val sources = report.sources
            if (sources.isEmpty()) {
                Log.w(TAG, "No resolver results for '${track.title}' by ${track.artist}" +
                    if (report.failed.isNotEmpty()) " (couldn't answer: ${report.failed})" else "")
                return OnTheFlyResolve(track, resolversCouldNotAnswer = report.failed.isNotEmpty())
            }

            // Cache the results so reselectBestSource works for queue tracks too;
            // resolvers that couldn't answer are asked again later (#566).
            trackResolverCache.putSources(track.title, track.artist, sources, report.failed)

            val best = resolverScoring.selectBest(sources)
                ?: return OnTheFlyResolve(track, resolversCouldNotAnswer = report.failed.isNotEmpty())
            Log.d(TAG, "On-the-fly resolved '${track.title}' → ${best.resolver}")
            OnTheFlyResolve(track.copy(
                resolver = best.resolver,
                sourceType = best.sourceType,
                sourceUrl = best.url,
                spotifyUri = best.spotifyUri,
                spotifyId = best.spotifyId,
                soundcloudId = best.soundcloudId,
                appleMusicId = best.appleMusicId,
                // Walk + validate, desktop-parity precedence (#217).
                isrc = com.parachord.shared.resolver.pickTrackIsrc(track.isrc, sources),
            ))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "On-the-fly resolution failed for '${track.title}'", e)
            OnTheFlyResolve(track, resolversCouldNotAnswer = true)
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
