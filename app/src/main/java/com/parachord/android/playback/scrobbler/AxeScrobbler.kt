package com.parachord.android.playback.scrobbler

import com.parachord.shared.platform.Log
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.plugin.PluginManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "AxeScrobbler"

/**
 * Scrobbler that delegates to an .axe plugin via [PluginManager].
 *
 * Replaces the native Kotlin scrobbler implementations (LastFmScrobbler,
 * ListenBrainzScrobbler, LibreFmScrobbler) so only the .axe plugins need
 * to be maintained — same code on desktop and mobile.
 *
 * The .axe scrobbler plugins (lastfm.axe, listenbrainz.axe, librefm.axe)
 * define `nowPlaying()` and `scrobble()` functions that make the API calls.
 */
class AxeScrobbler(
    override val id: String,
    override val displayName: String,
    private val pluginManager: PluginManager,
    private val settingsStore: SettingsStore,
) : Scrobbler {

    override suspend fun isEnabled(): Boolean {
        // Check if the plugin is loaded and scrobbling is globally enabled
        val scrobblingEnabled = settingsStore.scrobblingEnabled.first()
        if (!scrobblingEnabled) return false

        val plugin = pluginManager.plugins.value.find { it.id == id } ?: return false
        val hasScrobble = plugin.capabilities["scrobbling"] == true ||
            plugin.capabilities["scrobble"] == true
        if (!hasScrobble) return false

        // Check if the service has credentials stored
        return when (id) {
            "lastfm" -> settingsStore.getLastFmSessionKey() != null
            "listenbrainz" -> settingsStore.getListenBrainzToken() != null
            "librefm" -> settingsStore.getLibreFmSessionKey() != null
            else -> false
        }
    }

    override suspend fun sendNowPlaying(track: TrackEntity, durationMs: Long?) {
        if (!isEnabled()) return
        try {
            val trackJson = buildTrackJson(track)
            val configJson = buildConfigJson()
            pluginManager.evaluateJs("""
                (async () => {
                    const r = window.__resolverLoader.getResolver('$id');
                    if (!r || !r.nowPlaying) return null;
                    const track = JSON.parse('${escapeJs(trackJson)}');
                    const config = JSON.parse('${escapeJs(configJson)}');
                    return await r.nowPlaying(track, config);
                })()
            """.trimIndent())
        } catch (e: Exception) {
            Log.w(TAG, "[$id] nowPlaying failed: ${e.message}")
        }
    }

    override suspend fun submitScrobble(track: TrackEntity, timestamp: Long, durationMs: Long?) {
        if (!isEnabled()) return
        try {
            val trackJson = buildTrackJson(track, timestamp)
            val configJson = buildConfigJson()
            pluginManager.evaluateJs("""
                (async () => {
                    const r = window.__resolverLoader.getResolver('$id');
                    if (!r || !r.scrobble) return null;
                    const track = JSON.parse('${escapeJs(trackJson)}');
                    const config = JSON.parse('${escapeJs(configJson)}');
                    return await r.scrobble(track, config);
                })()
            """.trimIndent())
            Log.d(TAG, "[$id] Scrobbled: ${track.title} by ${track.artist}")
        } catch (e: Exception) {
            Log.w(TAG, "[$id] scrobble failed: ${e.message}")
        }
    }

    private fun buildTrackJson(track: TrackEntity, timestamp: Long? = null): String {
        return buildJsonObject {
            put("title", track.title)
            put("artist", track.artist)
            track.album?.let { put("album", it) }
            track.duration?.let { put("duration", it / 1000) } // seconds
            if (timestamp != null) put("timestamp", timestamp / 1000) // unix seconds
        }.toString()
    }

    private suspend fun buildConfigJson(): String {
        return buildJsonObject {
            when (id) {
                "lastfm" -> {
                    put("sessionKey", settingsStore.getLastFmSessionKey() ?: "")
                    put("apiKey", com.parachord.android.BuildConfig.LASTFM_API_KEY)
                }
                "listenbrainz" -> {
                    put("token", settingsStore.getListenBrainzToken() ?: "")
                }
                "librefm" -> {
                    put("sessionKey", settingsStore.getLibreFmSessionKey() ?: "")
                }
            }
        }.toString()
    }

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
}
