package com.parachord.android.playback.handlers

import androidx.media3.common.MediaItem
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import com.parachord.shared.playback.PluginStreamUrl
import com.parachord.shared.playback.checkPluginSettingsMessage
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.platform.Log
import com.parachord.shared.resolver.pluginTrackIdKey
import com.parachord.shared.resolver.parsePluginTrackUrl

/**
 * Host playback for a plugin resolver that declares
 * `capabilities.html5Stream` (Subsonic; plan parachord-mobile#664): at play
 * time the plugin's `getStreamUrl(track, config)` builds the plain stream URL
 * — fresh credentials baked in, because a token+salt pair replays until the
 * password changes — and ExoPlayer plays it like a direct stream. No
 * Subsonic-specific logic here: any plugin declaring the capability routes
 * through this handler, so a future `widevine` capability slots in beside it,
 * not through it.
 *
 * The credentialed URL lives for THIS play only: it reaches ExoPlayer's
 * [MediaItem] and nothing else — never the track, the resolver cache, the
 * saved queue, sync, or an unredacted log line.
 */
class PluginStreamHandler(
    private val pluginManager: PluginManager,
    private val trackResolverCache: TrackResolverCache,
) : SourceHandler {

    companion object {
        private const val TAG = "PluginStream"
    }

    /** Capability-driven, never resolver-id: any `html5Stream` plugin routes here. */
    override fun canHandle(track: TrackEntity): Boolean =
        pluginManager.html5StreamPlugin(track.resolver) != null

    override suspend fun createMediaItem(track: TrackEntity): MediaItem {
        val resolver = track.resolver
            ?: throw PluginStreamStartException("The track has no plugin resolver")
        val plugin = pluginManager.html5StreamPlugin(resolver)
            ?: throw PluginStreamStartException("The track has no plugin resolver")
        val settingsMessage = checkPluginSettingsMessage(resolver)
        val trackId = trackIdFor(track, resolver)
            ?: throw PluginStreamStartException(settingsMessage)

        // The `track` desktop hands the plugin: the plugin's own result shape.
        // The id key is the `<resolverId>Id` convention (`subsonicId`).
        val trackJson = buildString {
            append('{')
            append('"').append(pluginTrackIdKey(resolver)).append("\":")
            append(kotlinx.serialization.json.JsonPrimitive(trackId))
            if (track.title.isNotEmpty()) append(",\"title\":${kotlinx.serialization.json.JsonPrimitive(track.title)}")
            if (track.artist.isNotEmpty()) append(",\"artist\":${kotlinx.serialization.json.JsonPrimitive(track.artist)}")
            if (!track.album.isNullOrEmpty()) append(",\"album\":${kotlinx.serialization.json.JsonPrimitive(track.album)}")
            val durationMs = track.duration ?: 0L
            if (durationMs > 0) append(",\"duration\":${kotlinx.serialization.json.JsonPrimitive(durationMs / 1000)}")
            append('}')
        }

        // A failed getStreamUrl (the plugin threw, timed out) is a resolver
        // that couldn't answer, not a settings problem: the generic
        // start-failed message covers it.
        val url = try {
            pluginManager.getStreamUrl(resolver, trackJson)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getStreamUrl($resolver) failed for '${track.title}': ${e.message}")
            throw PluginStreamStartException(settingsMessage, e)
        } ?: throw PluginStreamStartException(settingsMessage) // not configured

        // HTTPS-only for now (PluginStreamUrl.isPlayable is the single place a
        // plain-http home-server allowance would go). A non-https URL is a
        // settings problem: the server needs a real certificate.
        if (!PluginStreamUrl.isPlayable(url)) {
            Log.w(TAG, "getStreamUrl($resolver) returned a non-https URL: ${PluginStreamUrl.redact(url)}")
            throw PluginStreamStartException(settingsMessage)
        }

        // The credentialed URL, logged redacted only. It lives on the
        // MediaItem, in memory, for this play.
        Log.d(TAG, "Playing '${track.title}' from $resolver (${plugin.version}): ${PluginStreamUrl.redact(url)}")
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(url)
            .build()
    }

    /**
     * A Widevine plugin's plain stream for [track] (desktop's `drmFallback`,
     * parachord#1613): asked when the DRM stream didn't start. A [MediaItem]
     * to play on the same source, or null when the plugin has none (or its
     * call failed), so the DRM failure's own handling and message stand.
     * The URL lives on the item only, logged redacted.
     */
    /** [resolverId]'s plugin plays through Widevine and also implements `getStreamUrl`. */
    fun hasDrmPlainStream(resolverId: String?): Boolean = pluginManager.drmPlainStreamPlugin(resolverId) != null

    suspend fun plainStreamItem(track: TrackEntity, resolverId: String): MediaItem? {
        val plugin = pluginManager.drmPlainStreamPlugin(resolverId) ?: return null
        trackResolverCache.awaitLoaded()
        val source = trackResolverCache.trackSources.value[trackKey(track.title, track.artist)]
            ?.filter { it.resolver == resolverId && !it.noMatch }
            ?.maxByOrNull { if (it.pluginFields != null) 1 else 0 }
        val trackJson = com.parachord.shared.playback.pluginStreamTrackJson(
            trackId = track.id, title = track.title, artist = track.artist, album = track.album,
            durationMs = track.duration, resolverId = resolverId,
            pluginTrackId = source?.pluginTrackId, source = source,
        )
        val reply = try {
            com.parachord.shared.playback.PluginStreamReplies.parse(
                pluginManager.requestStreamUrl(resolverId, trackJson.toString()),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getStreamUrl($resolverId) failed for '${track.title}': ${e.message?.take(120)}")
            return null
        }
        val outcome = com.parachord.shared.playback.pluginStreamOutcome(reply, resolverId, plugin.name, html5Stream = false)
        val url = (outcome as? com.parachord.shared.playback.PluginStreamOutcome.Play)?.url
        if (url == null) {
            Log.w(TAG, "No plain stream from $resolverId for '${track.title}' (${outcome::class.simpleName})")
            return null
        }
        Log.d(TAG, "Plain stream for '${track.title}' from $resolverId: ${PluginStreamUrl.redact(url)}")
        return MediaItem.Builder().setMediaId(track.id).setUri(url).build()
    }

    /**
     * The plugin's track id: the placeholder URL the resolved source carries
     * (`subsonic:track:<id>`), else the cached source's `pluginTrackId`
     * (Bandcamp's `trackIdFor` pattern — the track row has no column for it).
     */
    private suspend fun trackIdFor(track: TrackEntity, resolver: String): String? {
        parsePluginTrackUrl(track.sourceUrl ?: "")?.let { (r, id) -> if (r == resolver) return id }
        trackResolverCache.awaitLoaded()
        return trackResolverCache.trackSources.value[trackKey(track.title, track.artist)]
            ?.firstOrNull { it.resolver == resolver && !it.pluginTrackId.isNullOrEmpty() }
            ?.pluginTrackId
    }
}
