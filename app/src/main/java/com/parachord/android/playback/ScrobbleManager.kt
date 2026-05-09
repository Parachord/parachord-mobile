package com.parachord.android.playback

import com.parachord.shared.platform.Log
import com.parachord.android.bridge.JsBridge
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.metadata.MbidEnrichmentService
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.playback.scrobbler.Scrobbler
import com.parachord.android.resolver.ResolvedSource
import com.parachord.android.resolver.TrackResolverCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Dispatches scrobble events to all enabled scrobbler plugins.
 *
 * Mirrors the desktop app's scrobble-manager.js which loads and dispatches
 * to registered scrobbler plugins (Last.fm, ListenBrainz, Libre.fm) AND
 * to JS-registered playback-telemetry plugins (achordion etc.) via the
 * `window.scrobbleManager.registerPlugin(...)` contract documented in
 * desktop CLAUDE.md `## Scrobbling: inline core, plugin contract for the
 * rest` (L712-754).
 *
 * Scrobbling rules (per Last.fm / ListenBrainz spec):
 * - Send "now playing" when a track starts
 * - Send "scrobble" after listening to max(30s, min(duration/2, 240s))
 * - Only scrobble once per track play
 *
 * Native + JS dispatch happen in parallel: native scrobblers (LB / LFM /
 * Libre.fm) get the original `TrackEntity`; JS plugins get a desktop-
 * shaped JSON object that includes the `sources` map (resolverId →
 * `{confidence, spotifyId, appleMusicId, ...}`) so they can run their
 * confidence-gated logic (achordion's tier-1 / tier-2 dispatch reads
 * `track.sources[track._activeResolver].confidence`).
 *
 * Inherited <30s duration filter is enforced JS-side in
 * `__scrobbleManagerDispatch` (see `assets/js/bootstrap.html`).
 */
class ScrobbleManager constructor(
    private val settingsStore: SettingsStore,
    private val stateHolder: PlaybackStateHolder,
    private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
    private val trackDao: TrackDao,
    private val mbidEnrichment: MbidEnrichmentService,
    private val jsBridge: JsBridge,
    private val trackResolverCache: TrackResolverCache,
) {
    companion object {
        private const val TAG = "ScrobbleManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observeJob: Job? = null

    // Track state for scrobble threshold logic
    private var currentTrackId: String? = null
    private var nowPlayingSent = false
    private var scrobbleSubmitted = false
    private var trackStartTimestamp: Long = 0

    /**
     * Start observing playback state and scrobbling when enabled.
     * Called once from PlaybackController.connect().
     */
    fun startObserving() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(
                stateHolder.state,
                settingsStore.scrobblingEnabled,
            ) { state, enabled ->
                Pair(state, enabled)
            }.collectLatest { (state, enabled) ->
                if (!enabled) return@collectLatest
                processPlaybackState(state)
            }
        }
    }

    private suspend fun processPlaybackState(state: PlaybackState) {
        val track = state.currentTrack ?: return

        // New track started
        if (track.id != currentTrackId) {
            currentTrackId = track.id
            nowPlayingSent = false
            scrobbleSubmitted = false
            trackStartTimestamp = System.currentTimeMillis() / 1000

            if (state.isPlaying) {
                dispatchNowPlaying(track)
            }
            return
        }

        // Track is playing — send now playing if not yet sent
        if (state.isPlaying && !nowPlayingSent) {
            dispatchNowPlaying(track)
        }

        // Check scrobble threshold
        if (state.isPlaying && !scrobbleSubmitted && state.duration > 0) {
            val positionSeconds = state.position / 1000
            val durationSeconds = state.duration / 1000
            val threshold = scrobbleThreshold(durationSeconds)

            if (positionSeconds >= threshold) {
                dispatchScrobble(track, trackStartTimestamp)
            }
        }
    }

    /**
     * Per Last.fm / ListenBrainz spec: scrobble after max(30s, min(duration/2, 240s)).
     */
    private fun scrobbleThreshold(durationSeconds: Long): Long {
        val halfDuration = durationSeconds / 2
        val fourMinutes = 240L
        val minListenTime = 30L
        return maxOf(minListenTime, minOf(halfDuration, fourMinutes))
    }

    /**
     * Re-read the track from Room to pick up MBIDs that were enriched in the background,
     * then apply canonical name fallback from the MBID mapper cache.
     * Falls back to the original track if it's ephemeral (not in Room).
     */
    private suspend fun refreshTrackMbids(
        track: com.parachord.android.data.db.entity.TrackEntity,
    ): com.parachord.android.data.db.entity.TrackEntity {
        // Re-read from Room to get backfilled MBIDs
        val dbTrack = if (track.recordingMbid != null) track else {
            try { trackDao.getById(track.id) ?: track } catch (_: Exception) { track }
        }
        // Apply canonical name fallback from the mapper cache (fixes misspelled artist/track names)
        val canonical = mbidEnrichment.getCanonicalNames(dbTrack.artist, dbTrack.title)
        return if (canonical != null) {
            dbTrack.copy(artist = canonical.first, title = canonical.second)
        } else {
            dbTrack
        }
    }

    /** Dispatch "now playing" to all enabled scrobblers and JS plugins. */
    private suspend fun dispatchNowPlaying(track: com.parachord.android.data.db.entity.TrackEntity) {
        nowPlayingSent = true
        // Re-read from Room — MBIDs may have been backfilled since playback started
        val enrichedTrack = refreshTrackMbids(track)
        for (scrobbler in scrobblers) {
            scope.launch {
                try {
                    if (scrobbler.isEnabled()) {
                        scrobbler.sendNowPlaying(enrichedTrack)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "${scrobbler.displayName}: now playing failed", e)
                }
            }
        }
        dispatchToJsPlugins("updateNowPlaying", enrichedTrack)
    }

    /** Dispatch scrobble to all enabled scrobblers and JS plugins. */
    private suspend fun dispatchScrobble(
        track: com.parachord.android.data.db.entity.TrackEntity,
        timestamp: Long,
    ) {
        scrobbleSubmitted = true
        // Re-read from Room — by scrobble time (30s+), MBIDs should be backfilled
        val enrichedTrack = refreshTrackMbids(track)
        for (scrobbler in scrobblers) {
            scope.launch {
                try {
                    if (scrobbler.isEnabled()) {
                        scrobbler.submitScrobble(enrichedTrack, timestamp)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "${scrobbler.displayName}: scrobble failed", e)
                }
            }
        }
        dispatchToJsPlugins("scrobble", enrichedTrack)
    }

    /**
     * Fire-and-forget dispatch into the JS-side `window.scrobbleManager`
     * registry. Builds a desktop-shaped track JSON, then asks the WebView
     * to iterate registered plugins and call the matching hook. Errors are
     * logged via `console.warn` JS-side; any exceptions don't propagate
     * back here. The <30s duration filter and per-plugin `isEnabled()` gate
     * are both enforced JS-side in `__scrobbleManagerDispatch`.
     */
    private fun dispatchToJsPlugins(eventName: String, track: com.parachord.android.data.db.entity.TrackEntity) {
        scope.launch {
            try {
                val trackJson = buildPluginTrackJson(track)
                // Pass the JSON via base64 to dodge the JS string-escaping minefield
                // (CLAUDE.md Common Mistake #26 — backticks + `$` break template
                // strings; #33 — `if` statements aren't valid JS expressions for
                // `evaluate()` wrapping). Decode at the JS boundary.
                val base64 = android.util.Base64.encodeToString(
                    trackJson.toString().toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP,
                )
                val safeEvent = eventName.replace("'", "")
                jsBridge.evaluate(
                    "window.__scrobbleManagerDispatch && window.__scrobbleManagerDispatch('$safeEvent', atob('$base64'))",
                )
            } catch (e: Exception) {
                Log.w(TAG, "dispatchToJsPlugins($eventName) failed: ${e.message}")
            }
        }
    }

    /**
     * Build the desktop-shaped track JSON the achordion plugin (and any
     * future JS-side scrobble-plugin) expects. Required fields:
     *
     * - `id`, `title`, `artist`, `album`, `duration` — basic shape
     * - `mbid` — recording MBID, when known (the plugin's `resolveMbid()`
     *   short-circuits on this; absent → falls back to
     *   `window.resolveMbidForLove`)
     * - `_activeResolver` — id of the resolver actually playing this track
     *   (`spotify` / `applemusic` / `localfiles` / etc). Used by
     *   `playedSourceConfidence(track)` to pick tier-1 vs tier-2.
     * - `sources: { resolverId: { confidence, spotifyId?, appleMusicId?,
     *   appleMusicUrl?, soundcloudUrl?, bandcampUrl?, url?, youtubeId? } }` —
     *   the per-resolver source map. The plugin's `buildLinks(track)`
     *   iterates this to construct the submit payload.
     *
     * Source data comes from [TrackResolverCache.getSources]. If the cache
     * is empty for this track (e.g. user pressed play before background
     * resolution finished), we fall back to a single-entry sources map
     * built from the track's own resolver-side IDs (`spotifyId`,
     * `appleMusicId`, etc.) at confidence `0.95` (matching how
     * [com.parachord.android.resolver.ResolverManager.resolveWithHints]
     * stamps direct-ID hints today; see #128 for the future bump to 1.0).
     */
    private fun buildPluginTrackJson(
        track: com.parachord.android.data.db.entity.TrackEntity,
    ): JsonObject {
        val cachedSources = trackResolverCache.getSources(track.title, track.artist)
        val sources: List<ResolvedSource> = cachedSources ?: buildFallbackSources(track)
        return buildJsonObject {
            put("id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            track.album?.let { put("album", it) }
            // Track.duration is milliseconds; desktop / plugin contract is seconds
            // (achordion AGENTS.md L323: "duration in seconds, not ms — protocol spec").
            track.duration?.let { put("duration", it / 1000.0) }
            track.recordingMbid?.let { put("mbid", it) }
            track.resolver?.let { put("_activeResolver", it) }
            putJsonObject("sources") {
                for (source in sources) {
                    putJsonObject(source.resolver) {
                        source.confidence?.let { put("confidence", it) }
                        if (source.noMatch) put("noMatch", true)
                        source.spotifyId?.let { put("spotifyId", it) }
                        source.spotifyUri?.let { put("spotifyUri", it) }
                        source.appleMusicId?.let { put("appleMusicId", it) }
                        source.soundcloudId?.let { put("soundcloudId", it) }
                        source.soundcloudUrl?.let { put("soundcloudUrl", it) }
                        // The plugin reads `appleMusicUrl` / `bandcampUrl` /
                        // `url` / `youtubeId` for link construction. These
                        // aren't on Android's [ResolvedSource] today (see
                        // `ResolverModels.kt`); when adding new resolver
                        // fields with publicly-shareable URLs, surface them
                        // here so the achordion submit picks them up.
                        put("url", source.url)
                    }
                }
            }
        }
    }

    /**
     * Synthesize a `sources` map from the track's own per-resolver IDs
     * when [TrackResolverCache] hasn't cached anything for this track.
     * Confidence stamps `0.95` to match the direct-ID-hint pattern in
     * [com.parachord.android.resolver.ResolverManager.resolveWithHints].
     * Used as a fallback path so the JS plugin still gets useful source
     * data even on a resolver-cache miss.
     */
    private fun buildFallbackSources(
        track: com.parachord.android.data.db.entity.TrackEntity,
    ): List<ResolvedSource> {
        val out = mutableListOf<ResolvedSource>()
        if (!track.spotifyId.isNullOrBlank() || !track.spotifyUri.isNullOrBlank()) {
            out.add(
                ResolvedSource(
                    url = track.spotifyUri ?: "spotify:track:${track.spotifyId}",
                    sourceType = "spotify",
                    resolver = "spotify",
                    spotifyId = track.spotifyId,
                    spotifyUri = track.spotifyUri,
                    confidence = 0.95,
                ),
            )
        }
        if (!track.appleMusicId.isNullOrBlank()) {
            out.add(
                ResolvedSource(
                    url = "applemusic:song:${track.appleMusicId}",
                    sourceType = "applemusic",
                    resolver = "applemusic",
                    appleMusicId = track.appleMusicId,
                    confidence = 0.95,
                ),
            )
        }
        if (!track.soundcloudId.isNullOrBlank()) {
            out.add(
                ResolvedSource(
                    url = "https://api.soundcloud.com/tracks/${track.soundcloudId}",
                    sourceType = "soundcloud",
                    resolver = "soundcloud",
                    soundcloudId = track.soundcloudId,
                    confidence = 0.95,
                ),
            )
        }
        return out
    }
}
