package com.parachord.shared.playback.scrobbler

import com.parachord.shared.model.Track
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the native → JS hand-off for the `window.scrobbleManager` registry
 * (achordion playback telemetry, the canonical consumer). Shared so iOS's JSC
 * runtime gets the same dispatch Android's WebView does (#193 follow-up).
 *
 * Android's [com.parachord.android.playback.JsScrobblePluginDispatcher] builds a
 * richer `sources` map from its resolver cache; this shared builder uses the
 * track's own resolver IDs (sufficient for achordion's `buildLinks`). Both
 * produce the desktop-shaped object documented in `assets/js/bootstrap.html`:
 * `id`/`title`/`artist`/`album`/`duration`(seconds)/`mbid`/`isrc`/`_activeResolver` +
 * a per-resolver `sources` map.
 */
@OptIn(ExperimentalEncodingApi::class)
object ScrobblePluginDispatch {

    /** Desktop-shaped track JSON from the track's own IDs (no resolver cache). */
    fun buildTrackJson(track: Track): JsonObject = buildJsonObject {
        put("id", track.id)
        put("title", track.title)
        put("artist", track.artist)
        track.album?.let { put("album", it) }
        // Track.duration is milliseconds; the plugin contract is seconds.
        track.duration?.let { put("duration", it / 1000.0) }
        track.recordingMbid?.let { put("mbid", it) }
        // Parallel to `mbid` — the achordion `.axe` tier-2 keys its submit on
        // EITHER, and logs "no MBID or ISRC — cannot key submit" when both are
        // absent from THIS payload. Omitting `isrc` here made that path unable to
        // submit ISRC-only tracks (e.g. Apple-Music-streamed, mapper down) even
        // when the native Track carried one. Mirrors the native submit gate (#216).
        track.isrc?.let { put("isrc", it) }
        track.resolver?.let { put("_activeResolver", it) }
        putJsonObject("sources") {
            if (!track.spotifyId.isNullOrBlank() || !track.spotifyUri.isNullOrBlank()) {
                putJsonObject("spotify") {
                    put("confidence", 0.95)
                    track.spotifyId?.let { put("spotifyId", it) }
                    track.spotifyUri?.let { put("spotifyUri", it) }
                    put("url", track.spotifyUri ?: "spotify:track:${track.spotifyId}")
                }
            }
            if (!track.appleMusicId.isNullOrBlank()) {
                putJsonObject("applemusic") {
                    put("confidence", 0.95)
                    put("appleMusicId", track.appleMusicId!!)
                    put("url", "applemusic:song:${track.appleMusicId}")
                }
            }
            if (!track.soundcloudId.isNullOrBlank()) {
                putJsonObject("soundcloud") {
                    put("confidence", 0.95)
                    put("soundcloudId", track.soundcloudId!!)
                    put("url", "https://api.soundcloud.com/tracks/${track.soundcloudId}")
                }
            }
        }
    }

    /**
     * The JS to evaluate to fire [eventName] (`updateNowPlaying` | `scrobble`)
     * for [track] into the registry. JSON is passed base64 to dodge the JS
     * string-escaping minefield (matches Android's dispatcher), decoded with
     * `atob` at the JS boundary. The <30s filter + per-plugin `isEnabled()`
     * gate run JS-side in `__scrobbleManagerDispatch`.
     */
    fun dispatchScript(eventName: String, track: Track): String {
        val json = buildTrackJson(track).toString()
        val b64 = Base64.encode(json.encodeToByteArray())
        val safeEvent = eventName.replace("'", "")
        return "window.__scrobbleManagerDispatch && window.__scrobbleManagerDispatch('$safeEvent', atob('$b64'))"
    }
}
