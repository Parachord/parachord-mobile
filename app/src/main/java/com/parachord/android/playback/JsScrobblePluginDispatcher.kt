package com.parachord.android.playback

import com.parachord.shared.platform.Log
import com.parachord.android.bridge.JsBridge
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.resolver.ResolvedSource
import com.parachord.android.resolver.TrackResolverCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Android-side hand-off from [com.parachord.shared.playback.scrobbler.ScrobbleManager]
 * into the JS `window.scrobbleManager` registry (achordion playback telemetry).
 *
 * This is the platform-coupled half of scrobble dispatch that can't live in the
 * shared manager: it builds the desktop-shaped track JSON (including the
 * per-resolver `sources` map from [TrackResolverCache]) and pushes it through the
 * [JsBridge] WebView. Wired into the shared manager as its `dispatchToPlugins`
 * lambda (see AndroidModule). iOS supplies its own equivalent (or a no-op).
 *
 * The <30s duration filter and per-plugin `isEnabled()` gate are enforced
 * JS-side in `__scrobbleManagerDispatch` (see `assets/js/bootstrap.html`).
 */
class JsScrobblePluginDispatcher(
    private val jsBridge: JsBridge,
    private val trackResolverCache: TrackResolverCache,
) {
    companion object {
        private const val TAG = "JsScrobbleDispatch"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget dispatch of a scrobble event into the JS registry. */
    fun dispatch(eventName: String, track: TrackEntity) {
        scope.launch {
            try {
                val trackJson = buildPluginTrackJson(track)
                // Pass JSON via base64 to dodge the JS string-escaping minefield
                // (CLAUDE.md Common Mistake #26 / #33). Decode at the JS boundary.
                val base64 = android.util.Base64.encodeToString(
                    trackJson.toString().toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP,
                )
                val safeEvent = eventName.replace("'", "")
                jsBridge.evaluate(
                    "window.__scrobbleManagerDispatch && window.__scrobbleManagerDispatch('$safeEvent', atob('$base64'))",
                )
            } catch (e: Exception) {
                Log.w(TAG, "dispatch($eventName) failed: ${e.message}")
            }
        }
    }

    /**
     * Build the desktop-shaped track JSON the achordion plugin expects:
     * `id`/`title`/`artist`/`album`/`duration` (seconds), `mbid`, `isrc`,
     * `_activeResolver`, and the per-resolver `sources` map. Source data comes
     * from [TrackResolverCache.getSources]; on a cache miss we synthesize a
     * single-entry map from the track's own IDs at confidence 0.95.
     */
    private fun buildPluginTrackJson(track: TrackEntity): JsonObject {
        val cachedSources = trackResolverCache.getSources(track.title, track.artist)
        val sources: List<ResolvedSource> = cachedSources ?: buildFallbackSources(track)
        return buildJsonObject {
            put("id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            track.album?.let { put("album", it) }
            // Track.duration is milliseconds; plugin contract is seconds.
            track.duration?.let { put("duration", it / 1000.0) }
            track.recordingMbid?.let { put("mbid", it) }
            // Parallel to `mbid` — achordion's tier-2 keys its submit on EITHER,
            // and reports "no MBID or ISRC" when both are absent from this payload.
            // Omitting it blocked ISRC-only submits even with a native ISRC (#216).
            track.isrc?.let { put("isrc", it) }
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
                        put("url", source.url)
                    }
                }
            }
        }
    }

    /** Synthesize a `sources` map from the track's own IDs on a cache miss. */
    private fun buildFallbackSources(track: TrackEntity): List<ResolvedSource> {
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
