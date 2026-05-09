package com.parachord.android.playback.scrobbler

import com.parachord.shared.platform.Log
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.metadata.MbidEnrichmentService
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.api.ListenBrainzClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 36-char MusicBrainz Identifier shape — required by LB feedback API. */
private val LB_MBID_PATTERN = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\$", RegexOption.IGNORE_CASE)

/**
 * ListenBrainz scrobbler — sends JSON POST requests to api.listenbrainz.org.
 *
 * Mirrors the desktop app's listenbrainz-scrobbler.js.
 * Auth is a simple user token passed as `Authorization: Token {token}`.
 *
 * API docs: https://listenbrainz.readthedocs.io/en/latest/users/api/core.html
 *
 * Uses raw OkHttp for `submit-listens` (legacy path) and the shared
 * Ktor-based [ListenBrainzClient] for the `feedback/recording-feedback`
 * endpoint added for issue #125's loved-tracks push. Mixed clients is
 * intentional minimal scope — converting `submit-listens` to Ktor too
 * would be a separate cleanup.
 */
class ListenBrainzScrobbler constructor(
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
    private val listenBrainzClient: ListenBrainzClient,
    private val mbidEnrichment: MbidEnrichmentService,
) : Scrobbler {
    companion object {
        private const val TAG = "ListenBrainzScrobbler"
        private const val API_URL = "https://api.listenbrainz.org/1/submit-listens"
    }

    override val id = "listenbrainz"
    override val displayName = "ListenBrainz"

    override suspend fun isEnabled(): Boolean {
        return settingsStore.getListenBrainzToken() != null
    }

    override suspend fun sendNowPlaying(track: TrackEntity) {
        val token = settingsStore.getListenBrainzToken() ?: return
        try {
            val payload = buildPayload(
                listenType = "playing_now",
                track = track,
                timestamp = null,
            )

            val success = post(payload, token)
            if (success) {
                Log.d(TAG, "Now playing: ${track.artist} - ${track.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send now playing", e)
        }
    }

    override suspend fun submitScrobble(track: TrackEntity, timestamp: Long) {
        val token = settingsStore.getListenBrainzToken() ?: return
        try {
            val payload = buildPayload(
                listenType = "single",
                track = track,
                timestamp = timestamp,
            )

            val success = post(payload, token)
            if (success) {
                Log.d(TAG, "Scrobbled: ${track.artist} - ${track.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scrobble", e)
        }
    }

    /**
     * Push a love (feedback score=1) to ListenBrainz. Issue #125 — opt-in
     * cross-service love sync. One-way: no unlove (LB has score=0 to clear,
     * but desktop's design doc explicitly excludes that path).
     *
     * Requires a 36-char-UUID `track.recordingMbid`. When the track row
     * lacks one, falls through to the MBID Mapper (via
     * [MbidEnrichmentService.getRecordingMbid]) at the mapper's confidence
     * threshold. Silent skip on no resolution — caller leaves the
     * idempotency key untouched and retries on next sync (matches desktop's
     * `pushLoveToScrobblers` behavior in `app.js`).
     *
     * Throws on hard failures (auth invalid, rate-limited 5xx, network).
     */
    override suspend fun loveTrack(track: TrackEntity) {
        val token = settingsStore.getListenBrainzToken()
            ?: throw IllegalStateException("ListenBrainz not authenticated (no token)")

        // Resolve MBID. Use the track's stored value if it's a valid UUID;
        // else fall through to the mapper. Either return value or null.
        val mbid = track.recordingMbid?.takeIf { LB_MBID_PATTERN.matches(it) }
            ?: try {
                mbidEnrichment.getRecordingMbid(track.artist, track.title)
                    ?.takeIf { LB_MBID_PATTERN.matches(it) }
            } catch (e: Exception) {
                Log.w(TAG, "MBID mapper lookup failed for '${track.artist} - ${track.title}': ${e.message}")
                null
            }

        if (mbid == null) {
            // Per design doc: "If the mapper returns confidence < 0.7 or no
            // result, the LB push is skipped for that track but LFM still
            // gets it." Silent skip; caller leaves cache untouched.
            Log.d(TAG, "Skipping LB love for '${track.artist} - ${track.title}': no MBID resolvable")
            return
        }

        val ok = listenBrainzClient.submitRecordingFeedback(mbid, score = 1, token = token)
        if (!ok) {
            throw RuntimeException("ListenBrainz feedback API rejected love for $mbid")
        }
        Log.d(TAG, "Loved on ListenBrainz: ${track.artist} — ${track.title} ($mbid)")
    }

    /**
     * Build the ListenBrainz JSON payload.
     *
     * Format from desktop's listenbrainz-scrobbler.js:
     * ```json
     * {
     *   "listen_type": "single" | "playing_now",
     *   "payload": [{
     *     "listened_at": 1234567890,  // omitted for playing_now
     *     "track_metadata": {
     *       "artist_name": "...",
     *       "track_name": "...",
     *       "release_name": "...",
     *       "additional_info": {
     *         "media_player": "Parachord"
     *       }
     *     }
     *   }]
     * }
     * ```
     */
    private fun buildPayload(
        listenType: String,
        track: TrackEntity,
        timestamp: Long?,
    ): JSONObject {
        val additionalInfo = JSONObject().apply {
            put("media_player", "Parachord")
            // Include MBIDs when available — improves match accuracy on ListenBrainz
            track.recordingMbid?.let { put("recording_mbid", it) }
            track.artistMbid?.let {
                put("artist_mbids", JSONArray().put(it))
            }
            track.releaseMbid?.let { put("release_mbid", it) }
        }

        val trackMetadata = JSONObject().apply {
            put("artist_name", track.artist)
            put("track_name", track.title)
            track.album?.let { put("release_name", it) }
            // Top-level MBIDs per ListenBrainz API spec
            track.recordingMbid?.let { put("recording_mbid", it) }
            put("additional_info", additionalInfo)
        }

        val listenObj = JSONObject().apply {
            if (timestamp != null) {
                put("listened_at", timestamp)
            }
            put("track_metadata", trackMetadata)
        }

        return JSONObject().apply {
            put("listen_type", listenType)
            put("payload", JSONArray().put(listenObj))
        }
    }

    private fun post(payload: JSONObject, token: String): Boolean {
        val body = payload.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Token $token")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string()

        if (!response.isSuccessful) {
            Log.e(TAG, "API error ${response.code}: $responseBody")
            return false
        }

        return true
    }
}
