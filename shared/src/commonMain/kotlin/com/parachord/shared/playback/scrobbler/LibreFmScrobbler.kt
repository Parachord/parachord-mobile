package com.parachord.shared.playback.scrobbler

import com.parachord.shared.api.LastFmClient
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.settings.SettingsStore

/**
 * Libre.fm scrobbler — same Last.fm-compatible protocol against libre.fm/2.0/
 * with the placeholder API key/secret. Reuses the shared [LastFmClient]'s signed
 * POST + `auth.getMobileSession` via the `apiUrl` override (#193, KMP). Mirrors
 * the desktop's librefm-scrobbler.js.
 *
 * Libre.fm convention: both api_key and shared secret are the all-zeros
 * placeholder. Auth is username/password → session key (no OAuth).
 */
class LibreFmScrobbler(
    private val settingsStore: SettingsStore,
    private val lastFmClient: LastFmClient,
) : Scrobbler {
    companion object {
        private const val TAG = "LibreFmScrobbler"
        private const val API_URL = "https://libre.fm/2.0/"
        private const val API_KEY = "00000000000000000000000000000000"
        private const val SHARED_SECRET = "00000000000000000000000000000000"
    }

    override val id = "librefm"
    override val displayName = "Libre.fm"

    override suspend fun isEnabled(): Boolean = settingsStore.getLibreFmSessionKey() != null

    override suspend fun sendNowPlaying(track: Track, durationMs: Long?) {
        val sessionKey = settingsStore.getLibreFmSessionKey() ?: return
        val ok = lastFmClient.updateNowPlaying(
            artist = track.artist,
            title = track.title,
            apiKey = API_KEY,
            sessionKey = sessionKey,
            sharedSecret = SHARED_SECRET,
            album = track.album,
            recordingMbid = track.recordingMbid,
            // Engine duration in ms → seconds (#347), not metadata track.duration.
            durationSec = durationMs?.let { it / 1000 },
            apiUrl = API_URL,
        )
        if (ok) Log.d(TAG, "Now playing: ${track.artist} - ${track.title}")
    }

    override suspend fun submitScrobble(track: Track, timestamp: Long, durationMs: Long?) {
        val sessionKey = settingsStore.getLibreFmSessionKey() ?: return
        val ok = lastFmClient.scrobble(
            artist = track.artist,
            title = track.title,
            timestamp = timestamp,
            apiKey = API_KEY,
            sessionKey = sessionKey,
            sharedSecret = SHARED_SECRET,
            album = track.album,
            recordingMbid = track.recordingMbid,
            // Engine duration in ms → seconds (#347).
            durationSec = durationMs?.let { it / 1000 },
            apiUrl = API_URL,
        )
        if (ok) Log.d(TAG, "Scrobbled: ${track.artist} - ${track.title}")
    }

    /**
     * Authenticate with Libre.fm via auth.getMobileSession. Persists + returns
     * the session key on success, null on failure.
     */
    suspend fun authenticate(username: String, password: String): String? {
        val sessionKey = try {
            lastFmClient.getMobileSession(username, password, API_KEY, SHARED_SECRET, API_URL)
        } catch (e: Exception) {
            Log.e(TAG, "Auth error: ${e.message}")
            null
        }
        if (sessionKey != null) {
            settingsStore.setLibreFmSession(sessionKey)
            Log.d(TAG, "Libre.fm auth successful")
        } else {
            Log.e(TAG, "Libre.fm auth failed")
        }
        return sessionKey
    }
}
