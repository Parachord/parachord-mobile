package com.parachord.android.playback.scrobbler

import com.parachord.shared.platform.Log
import com.parachord.android.BuildConfig
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

/**
 * Last.fm scrobbler — sends signed POST requests to ws.audioscrobbler.com.
 *
 * Mirrors the desktop app's lastfm-scrobbler behavior. Uses MD5-signed
 * form POST with sorted key/value pairs + shared secret.
 */
class LastFmScrobbler constructor(
    private val settingsStore: SettingsStore,
    private val okHttpClient: OkHttpClient,
) : Scrobbler {
    companion object {
        private const val TAG = "LastFmScrobbler"
        private const val API_URL = "https://ws.audioscrobbler.com/2.0/"
    }

    override val id = "lastfm"
    override val displayName = "Last.fm"

    override suspend fun isEnabled(): Boolean {
        return settingsStore.getLastFmSessionKey() != null
    }

    override suspend fun sendNowPlaying(track: TrackEntity) {
        val sessionKey = settingsStore.getLastFmSessionKey() ?: return
        try {
            val params = buildMap {
                put("method", "track.updateNowPlaying")
                put("artist", track.artist)
                put("track", track.title)
                put("api_key", BuildConfig.LASTFM_API_KEY)
                put("sk", sessionKey)
                track.album?.let { put("album", it) }
                track.duration?.let { put("duration", (it / 1000).toString()) }
                track.recordingMbid?.let { put("mbid", it) }
            }

            val success = postSigned(params, API_URL, BuildConfig.LASTFM_SHARED_SECRET)
            if (success) {
                Log.d(TAG, "Now playing: ${track.artist} - ${track.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send now playing", e)
        }
    }

    override suspend fun submitScrobble(track: TrackEntity, timestamp: Long) {
        val sessionKey = settingsStore.getLastFmSessionKey() ?: return
        try {
            val params = buildMap {
                put("method", "track.scrobble")
                put("artist[0]", track.artist)
                put("track[0]", track.title)
                put("timestamp[0]", timestamp.toString())
                put("api_key", BuildConfig.LASTFM_API_KEY)
                put("sk", sessionKey)
                track.album?.let { put("album[0]", it) }
                track.duration?.let { put("duration[0]", (it / 1000).toString()) }
                track.recordingMbid?.let { put("mbid[0]", it) }
            }

            val success = postSigned(params, API_URL, BuildConfig.LASTFM_SHARED_SECRET)
            if (success) {
                Log.d(TAG, "Scrobbled: ${track.artist} - ${track.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scrobble", e)
        }
    }

    /**
     * Push a love (track.love) to Last.fm. Issue #125 — opt-in cross-service
     * love sync. One-way: no unlove. Throws on auth/network/API failures so
     * the caller (LovesPushService) can decide whether to write the
     * idempotency key.
     *
     * Last.fm `track.love` requires `artist` + `track` + session-key auth.
     * `mbid` is optional and improves match accuracy on Last.fm's side
     * (helps disambiguate covers / same-name tracks).
     */
    override suspend fun loveTrack(track: TrackEntity) {
        val sessionKey = settingsStore.getLastFmSessionKey()
            ?: throw IllegalStateException("Last.fm not authenticated (no session key)")
        val params = buildMap {
            put("method", "track.love")
            put("artist", track.artist)
            put("track", track.title)
            put("api_key", BuildConfig.LASTFM_API_KEY)
            put("sk", sessionKey)
            track.recordingMbid?.let { put("mbid", it) }
        }
        val success = postSigned(params, API_URL, BuildConfig.LASTFM_SHARED_SECRET)
        if (!success) {
            throw RuntimeException("Last.fm track.love returned API error for '${track.artist} - ${track.title}'")
        }
        Log.d(TAG, "Loved on Last.fm: ${track.artist} — ${track.title}")
    }

    /**
     * POST signed request to a Last.fm-compatible API.
     * Signature = MD5 of sorted key+value pairs (excluding format) + shared secret.
     *
     * This is also used by [LibreFmScrobbler] via composition.
     */
    internal fun postSigned(
        params: Map<String, String>,
        apiUrl: String,
        sharedSecret: String,
    ): Boolean {
        val allParams = params.toMutableMap()
        allParams["format"] = "json"

        // Generate api_sig: sort keys (excluding format), concat key+value, append secret, MD5
        val sigString = allParams
            .filterKeys { it != "format" }
            .toSortedMap()
            .entries
            .joinToString("") { "${it.key}${it.value}" } + sharedSecret

        allParams["api_sig"] = md5(sigString)

        val formBody = FormBody.Builder().apply {
            allParams.forEach { (k, v) -> add(k, v) }
        }.build()

        val request = Request.Builder()
            .url(apiUrl)
            .post(formBody)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string()

        if (!response.isSuccessful) {
            Log.e(TAG, "API error ${response.code}: $body")
            return false
        }

        if (body != null && body.contains("\"error\"")) {
            Log.e(TAG, "API returned error: $body")
            return false
        }

        return true
    }

    internal fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
