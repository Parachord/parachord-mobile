package com.parachord.shared.playback.scrobbler

import com.parachord.shared.api.LastFmClient
import com.parachord.shared.config.AppConfig
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.settings.SettingsStore

/**
 * Last.fm scrobbler — signed POSTs through the shared [LastFmClient] (#193, KMP).
 * MD5 `api_sig` signing + the rate-limit gate live in the client; this class
 * just sources credentials (session key from [SettingsStore]; api key + shared
 * secret from [AppConfig]) and forwards. Mirrors the desktop's lastfm-scrobbler.
 *
 * Track.duration is milliseconds; Last.fm wants seconds → divided here.
 */
class LastFmScrobbler(
    private val settingsStore: SettingsStore,
    private val lastFmClient: LastFmClient,
    private val appConfig: AppConfig,
) : Scrobbler {
    companion object {
        private const val TAG = "LastFmScrobbler"
    }

    override val id = "lastfm"
    override val displayName = "Last.fm"

    private val apiKey get() = appConfig.lastFmApiKey
    private val sharedSecret get() = appConfig.lastFmSharedSecret

    override suspend fun isEnabled(): Boolean = settingsStore.getLastFmSessionKey() != null

    override suspend fun sendNowPlaying(track: Track, durationMs: Long?) {
        val sessionKey = settingsStore.getLastFmSessionKey() ?: return
        val ok = lastFmClient.updateNowPlaying(
            artist = track.artist,
            title = track.title,
            apiKey = apiKey,
            sessionKey = sessionKey,
            sharedSecret = sharedSecret,
            album = track.album,
            recordingMbid = track.recordingMbid,
            // Engine duration in ms → seconds for Last.fm (#347), not the
            // unreliable metadata track.duration.
            durationSec = durationMs?.let { it / 1000 },
        )
        if (ok) Log.d(TAG, "Now playing: ${track.artist} - ${track.title}")
    }

    override suspend fun submitScrobble(track: Track, timestamp: Long, durationMs: Long?) {
        val sessionKey = settingsStore.getLastFmSessionKey() ?: return
        val ok = lastFmClient.scrobble(
            artist = track.artist,
            title = track.title,
            timestamp = timestamp,
            apiKey = apiKey,
            sessionKey = sessionKey,
            sharedSecret = sharedSecret,
            album = track.album,
            recordingMbid = track.recordingMbid,
            // Engine duration in ms → seconds for Last.fm (#347).
            durationSec = durationMs?.let { it / 1000 },
        )
        if (ok) Log.d(TAG, "Scrobbled: ${track.artist} - ${track.title}")
    }

    /**
     * Push a love (track.love) to Last.fm. Issue #125 — opt-in cross-service
     * love sync. One-way (no unlove). Throws on auth/network/API failure so the
     * caller (LovesPushService) can decide whether to write the idempotency key.
     */
    override suspend fun loveTrack(track: Track) {
        val sessionKey = settingsStore.getLastFmSessionKey()
            ?: throw IllegalStateException("Last.fm not authenticated (no session key)")
        val ok = lastFmClient.loveTrack(
            artist = track.artist,
            title = track.title,
            apiKey = apiKey,
            sessionKey = sessionKey,
            sharedSecret = sharedSecret,
            recordingMbid = track.recordingMbid,
        )
        if (!ok) {
            throw RuntimeException("Last.fm track.love returned API error for '${track.artist} - ${track.title}'")
        }
        Log.d(TAG, "Loved on Last.fm: ${track.artist} — ${track.title}")
    }
}
