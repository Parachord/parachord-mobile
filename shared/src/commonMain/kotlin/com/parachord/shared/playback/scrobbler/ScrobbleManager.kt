package com.parachord.shared.playback.scrobbler

import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.SubmitTrackLinksRequest
import com.parachord.shared.api.TrackLink
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Minimal playback snapshot the scrobble threshold logic needs. Decouples the
 * shared [ScrobbleManager] from each platform's playback-state holder — Android
 * maps `PlaybackStateHolder.state` into this; iOS maps its queue coordinator.
 * `position` / `duration` are milliseconds (the manager converts to seconds).
 */
data class ScrobbleState(
    val currentTrack: Track?,
    val isPlaying: Boolean,
    val position: Long,
    val duration: Long,
)

/**
 * Dispatches scrobble events to all enabled scrobblers (#193, KMP-shared so iOS
 * scrobbles through the same instances as Android). Mirrors the desktop's
 * scrobble-manager.js.
 *
 * Scrobbling rules (Last.fm / ListenBrainz spec):
 * - Send "now playing" when a track starts.
 * - Send "scrobble" after max(30s, min(duration/2, 240s)).
 * - Only scrobble once per track play.
 *
 * Two platform-coupled concerns are forwarded as constructor params:
 *  - [playbackStateFlow] — the platform's playback state mapped to [ScrobbleState].
 *  - [dispatchToPlugins] — fire-and-forget hand-off to the JS-side
 *    `window.scrobbleManager` registry (achordion telemetry). Android wires the
 *    WebView/JsBridge + resolver-cache source-map here; iOS supplies its own
 *    (PluginManager) or a no-op. Kept off the shared path because building the
 *    desktop-shaped `sources` JSON depends on each platform's resolver cache.
 *
 * [achordionClient] is the NATIVE Achordion track-links submit (#215). On scrobble
 * the manager POSTs the track's streaming links to Achordion directly, so the
 * link-cache pre-warm does NOT depend on the achordion `.axe` JS plugin being
 * synced + registered + dispatched (that JS path is fragile + opaque on mobile —
 * see [dispatchToPlugins]). Mirrors ShareManager's submit gates. Nullable so tests
 * and platforms without a configured bearer token can omit it.
 */
class ScrobbleManager(
    private val settingsStore: SettingsStore,
    private val playbackStateFlow: Flow<ScrobbleState>,
    private val scrobblers: Set<Scrobbler>,
    private val trackDao: TrackDao,
    private val mbidEnrichment: MbidEnrichmentService,
    private val dispatchToPlugins: (eventName: String, track: Track) -> Unit = { _, _ -> },
    private val achordionClient: AchordionClient? = null,
) {
    companion object {
        private const val TAG = "ScrobbleManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null

    // Track state for scrobble threshold logic.
    private var currentTrackId: String? = null
    private var nowPlayingSent = false
    private var scrobbleSubmitted = false
    private var trackStartTimestamp: Long = 0

    /**
     * Start observing playback state and scrobbling when enabled. Idempotent —
     * cancels any prior observation. Called once after wiring (Android:
     * PlaybackController.connect; iOS: container init).
     */
    fun startObserving() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(playbackStateFlow, settingsStore.scrobblingEnabled) { state, enabled ->
                Pair(state, enabled)
            }.collectLatest { (state, enabled) ->
                if (!enabled) return@collectLatest
                processPlaybackState(state)
            }
        }
    }

    private suspend fun processPlaybackState(state: ScrobbleState) {
        val track = state.currentTrack ?: return

        // New track started.
        if (track.id != currentTrackId) {
            currentTrackId = track.id
            nowPlayingSent = false
            scrobbleSubmitted = false
            trackStartTimestamp = currentTimeMillis() / 1000

            if (state.isPlaying) dispatchNowPlaying(track)
            return
        }

        // Track is playing — send now playing if not yet sent.
        if (state.isPlaying && !nowPlayingSent) dispatchNowPlaying(track)

        // Check scrobble threshold.
        if (state.isPlaying && !scrobbleSubmitted && state.duration > 0) {
            val positionSeconds = state.position / 1000
            val durationSeconds = state.duration / 1000
            if (positionSeconds >= scrobbleThreshold(durationSeconds)) {
                dispatchScrobble(track, trackStartTimestamp)
            }
        }
    }

    /** Per Last.fm / ListenBrainz spec: scrobble after max(30s, min(duration/2, 240s)). */
    private fun scrobbleThreshold(durationSeconds: Long): Long =
        maxOf(30L, minOf(durationSeconds / 2, 240L))

    /**
     * Re-read the track from the DB to pick up MBIDs enriched in the background,
     * then apply canonical name fallback from the MBID mapper cache. Falls back
     * to the original track if it's ephemeral (not in the DB).
     */
    private suspend fun refreshTrackMbids(track: Track): Track {
        var dbTrack = if (track.recordingMbid != null) track else {
            try { trackDao.getById(track.id) ?: track } catch (_: Exception) { track }
        }
        // Still no recording MBID — resolve it now via the mapper. Android enriches
        // at playback start (PlaybackController.enrichInBackground) so the MBID is
        // usually cached by scrobble time; iOS has no such hook, and ephemeral
        // tracks (recommendations, weekly playlists) are never in the DB. Without an
        // MBID the scrobble payload, achordion's tier-2 submit (it reads track.mbid
        // off the dispatched JSON), and the native Achordion submit (#215) are all
        // recording-keyed and skip. getRecordingMbid hits the cache when already
        // enriched (cheap on Android) and returns null for genuinely unmappable
        // tracks (correctly leaving them un-submitted).
        if (dbTrack.recordingMbid.isNullOrBlank()) {
            val mbid = try {
                mbidEnrichment.getRecordingMbid(dbTrack.artist, dbTrack.title)
            } catch (_: Exception) {
                null
            }
            if (!mbid.isNullOrBlank()) dbTrack = dbTrack.copy(recordingMbid = mbid)
        }
        val canonical = mbidEnrichment.getCanonicalNames(dbTrack.artist, dbTrack.title)
        return if (canonical != null) {
            dbTrack.copy(artist = canonical.first, title = canonical.second)
        } else {
            dbTrack
        }
    }

    /** Dispatch "now playing" to all enabled scrobblers and JS plugins. */
    private suspend fun dispatchNowPlaying(track: Track) {
        nowPlayingSent = true
        val enrichedTrack = refreshTrackMbids(track)
        for (scrobbler in scrobblers) {
            scope.launch {
                try {
                    if (scrobbler.isEnabled()) scrobbler.sendNowPlaying(enrichedTrack)
                } catch (e: Exception) {
                    Log.e(TAG, "${scrobbler.displayName}: now playing failed", e)
                }
            }
        }
        dispatchToPlugins("updateNowPlaying", enrichedTrack)
    }

    /** Dispatch scrobble to all enabled scrobblers and JS plugins. */
    private suspend fun dispatchScrobble(track: Track, timestamp: Long) {
        scrobbleSubmitted = true
        val enrichedTrack = refreshTrackMbids(track)
        for (scrobbler in scrobblers) {
            scope.launch {
                try {
                    if (scrobbler.isEnabled()) scrobbler.submitScrobble(enrichedTrack, timestamp)
                } catch (e: Exception) {
                    Log.e(TAG, "${scrobbler.displayName}: scrobble failed", e)
                }
            }
        }
        dispatchToPlugins("scrobble", enrichedTrack)
        submitToAchordion(enrichedTrack)
    }

    /**
     * Native Achordion track-links submit on scrobble (#215). Pre-warms Achordion's
     * per-service link cache so a later share resolves "Listen on Spotify/Apple
     * Music/SoundCloud" immediately instead of an empty page. Runs natively, so it
     * works on both platforms regardless of whether the achordion `.axe` JS plugin
     * is loaded/registered (the [dispatchToPlugins] route depends on that and is
     * fragile + invisible on mobile). Mirrors ShareManager's link-building + gates.
     *
     * [AchordionClient.submitTrackLinks] internally handles the bearer-token gate,
     * the per-session MBID dedup, the empty-MBID / empty-links gates, and the 401
     * kill-switch — so this only builds the payload and fires it fire-and-forget.
     */
    private fun submitToAchordion(track: Track) {
        val client = achordionClient
        if (client == null) {
            Log.d(TAG, "Achordion: no client configured — skip '${track.title}'")
            return
        }
        val mbid = track.recordingMbid
        val links = buildList {
            track.spotifyId?.takeIf { it.isNotBlank() }?.let {
                add(TrackLink(url = "https://open.spotify.com/track/$it", host = "spotify.com", label = "Spotify"))
            }
            track.appleMusicId?.takeIf { it.isNotBlank() }?.let {
                add(TrackLink(url = "https://music.apple.com/song/$it", host = "music.apple.com", label = "Apple Music"))
            }
            track.soundcloudId?.takeIf { it.isNotBlank() }?.let {
                add(TrackLink(url = "https://soundcloud.com/$it", host = "soundcloud.com", label = "SoundCloud"))
            }
        }
        // Boundary instrumentation (#215): one line per scrobble shows the native
        // path was REACHED + WHY it did/didn't submit, so "wired but no links" is
        // distinguishable from "stale build / not wired". Fires before the gates.
        Log.d(
            TAG,
            "Achordion submit candidate '${track.title}': mbid=${mbid ?: "null"} links=${links.size} " +
                "(spotify=${!track.spotifyId.isNullOrBlank()} am=${!track.appleMusicId.isNullOrBlank()} sc=${!track.soundcloudId.isNullOrBlank()})",
        )
        if (mbid.isNullOrBlank() || links.isEmpty()) return
        scope.launch {
            try {
                val result = client.submitTrackLinks(
                    SubmitTrackLinksRequest(
                        mbid = mbid,
                        links = links,
                        trackName = track.title,
                        artistName = track.artist,
                        albumName = track.album,
                    )
                )
                Log.d(TAG, "Achordion submit for '${track.title}' (mbid=$mbid, ${links.size} links): $result")
            } catch (e: Exception) {
                Log.w(TAG, "Achordion submit failed for '${track.title}': ${e.message}")
            }
        }
    }
}
