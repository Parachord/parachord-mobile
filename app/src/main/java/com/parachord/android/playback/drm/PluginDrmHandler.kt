package com.parachord.android.playback.drm

import androidx.media3.common.MediaItem
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.handlers.SourceHandler
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.drm.PluginDrmContract
import com.parachord.shared.playback.drm.PluginDrmError
import com.parachord.shared.playback.drm.PluginDrmOpenReply
import com.parachord.shared.playback.drm.PluginDrmReplies
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.plugin.PluginStatusStore
import com.parachord.shared.plugin.limitMessage
import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.settings.SettingsStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch
import com.parachord.shared.playback.drm.forDevice

/**
 * The manifest half of a plugin DRM play (`getPlaybackManifest` refused or
 * didn't answer): the contract's fields ride [error].
 */
class PluginDrmPlaybackException(val resolverId: String, val error: PluginDrmError) :
    Exception("Plugin DRM play refused by $resolverId (${error.reason})")

/**
 * Plays a source whose plugin declares `capabilities.widevine` and implements
 * both `getPlaybackManifest` and `relayLicense` (desktop's plugin-supplied
 * Widevine path; capability-driven, never by resolver id). [createMediaItem]
 * asks the plugin for the manifest (10 s budget), opens the session and
 * answers a `parachord-drm://<sessionId>` item, which ExoPlayer plays through
 * [PluginDrmMediaSourceFactory]: an ordinary ExoPlayer track otherwise (seek,
 * the end-of-track advance, the #614 foreground keep, resume at a position).
 */
class PluginDrmHandler(
    private val pluginManager: PluginManager,
    private val sessions: PluginDrmSessions,
    private val js: PluginDrmJs,
    private val trackResolverCache: TrackResolverCache,
    private val settingsStore: SettingsStore,
    private val statusStore: Lazy<PluginStatusStore>,
) : SourceHandler {

    override fun canHandle(track: TrackEntity): Boolean = pluginManager.widevinePlugin(track.resolver) != null

    /** The plugin's name ("Amazon Music"), for messages. */
    fun serviceName(resolverId: String): String = pluginManager.widevinePlugin(resolverId)?.name
        ?: pluginManager.plugins.value.find { it.id == resolverId }?.name
        ?: com.parachord.shared.playback.resolverDisplayName(resolverId)

    /**
     * After a plan or sign-in refusal: the plugin's status is read again, so a
     * plan limit lands in the "not playing" list. The app's wording for the
     * limit, when the status gives one.
     */
    suspend fun noteRefusal(resolverId: String): String? {
        statusStore.value.refresh(resolverId)
        return limitMessage(statusStore.value.limits.value, resolverId)
    }

    /** The plan or sign-in refusal's status read, in the background (it can take seconds). */
    fun noteRefusalLater(resolverId: String, scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            try {
                noteRefusal(resolverId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(DRM_TAG, "Status refresh after a refusal failed for $resolverId: ${e.message?.take(120)}")
            }
        }
    }

    /** What the play's failure was, for [sessionId]: the plugin's licence refusal if one came, else [cause]'s. */
    fun errorFor(sessionId: String?, cause: Throwable?): PluginDrmError {
        generateSequence(cause) { it.cause }
            .filterIsInstance<PluginDrmLicenseException>()
            .firstOrNull()
            ?.let { return it.error }
        sessions.get(sessionId)?.lastLicenseError?.let { return it }
        return PluginDrmError(message = cause?.javaClass?.simpleName)
    }

    /** Licence answers as they land ([PluginDrmSessions.licenceEvents]). */
    val licenceEvents get() = sessions.licenceEvents

    /** A licence for [sessionId] came back. */
    fun isLicensed(sessionId: String?): Boolean = sessions.isLicensed(sessionId)

    /** [sessionId] is the one the player is on. */
    fun isOnPlayer(sessionId: String): Boolean = sessions.activeOnPlayer == sessionId

    /** [sessionId] was opened for the track [trackId]. */
    fun sessionIsFor(sessionId: String, trackId: String): Boolean = sessions.get(sessionId)?.trackId == trackId

    /** The licence refusal recorded for [sessionId], if one came. */
    fun recordedRefusal(sessionId: String): PluginDrmError? = sessions.get(sessionId)?.lastLicenseError

    /** Drops a refused family for [sessionId]'s retry ([PluginDrmSessions.latchRefusedFamily]). */
    fun latchRefusedFamily(sessionId: String): Boolean = sessions.latchRefusedFamily(sessionId)

    /** Closes a session that won't reach the player (a superseded play). */
    fun closeSession(sessionId: String) = sessions.close(sessionId, positionMs = 0)

    override suspend fun createMediaItem(track: TrackEntity): MediaItem {
        val resolverId = track.resolver
            ?: throw PluginDrmPlaybackException("?", PluginDrmError(message = "the track has no resolver"))
        val source = sourceFor(track, resolverId)
        val trackJson = trackJson(track, resolverId, source)
        // No Atmos preference where nothing here can decode E-AC-3, so spatial-on can't strand a play.
        val quality = settingsStore.getPlaybackQualitySettings().forDevice(AudioDecoders.capabilities)
        var retries = 0
        var reply = PluginDrmReplies.open(js.open(resolverId, trackJson.toString(), PluginDrmContract.MANIFEST_BUDGET_MS))
        // The budget ran out while the plugin's call is still running in JS
        // (desktop parachord#1603): ask once more, at once, so this play joins
        // it. A newer play cancels the route (and so this) as before.
        while (reply is PluginDrmOpenReply.Failed &&
            com.parachord.shared.playback.drm.manifestRetryAllowed(reply.error, retries)
        ) {
            retries++
            Log.w(DRM_TAG, "Manifest for '${track.title}' ($resolverId) ran out of its budget; asking again (retry $retries)")
            reply = PluginDrmReplies.open(js.open(resolverId, trackJson.toString(), PluginDrmContract.MANIFEST_BUDGET_MS))
            if (reply is PluginDrmOpenReply.Opened) Log.d(DRM_TAG, "Manifest for '${track.title}' answered on retry $retries")
        }
        when (reply) {
            is PluginDrmOpenReply.Failed -> {
                Log.w(
                    DRM_TAG,
                    "Manifest refused by $resolverId for '${track.title}': reason=${reply.error.reason} " +
                        "planKey=${reply.error.planKey} denialReason=${reply.error.denialReason}",
                )
                throw PluginDrmPlaybackException(resolverId, reply.error)
            }
            is PluginDrmOpenReply.Opened -> {
                sessions.register(
                    PluginDrmSessions.Session(
                        id = reply.sessionId,
                        resolverId = resolverId,
                        trackId = track.id,
                        manifest = reply.manifest,
                        extras = reply.extras,
                        quality = quality,
                        albumAsin = source?.pluginFields?.get("albumAsin"),
                        durationMs = track.duration?.takeIf { it > 0 },
                        openedAtMs = sessions.now(),
                    ),
                )
                Log.d(
                    DRM_TAG,
                    "Session ${reply.sessionId} opened for '${track.title}' ($resolverId), quality " +
                        "${quality.tier.key}${if (quality.adaptive) " adaptive" else " locked"}${if (quality.spatial) " +atmos" else ""}",
                )
                return MediaItem.Builder()
                    .setMediaId(track.id)
                    .setUri(PluginDrmUris.uriFor(reply.sessionId))
                    .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                    .build()
            }
        }
    }

    /** The resolved source for [resolverId], whose fields the plugin reads ([ResolvedSource.pluginFields]). */
    private suspend fun sourceFor(track: TrackEntity, resolverId: String): ResolvedSource? {
        trackResolverCache.awaitLoaded()
        return trackResolverCache.trackSources.value[trackKey(track.title, track.artist)]
            ?.filter { it.resolver == resolverId && !it.noMatch }
            ?.maxByOrNull { if (it.pluginFields != null) 1 else 0 }
    }

    companion object {
        /**
         * The track desktop hands `getPlaybackManifest`: the whole track, its
         * `sources` map included, the plugin's own source record being the
         * plugin's result (its [ResolvedSource.pluginFields]); the plugin digs
         * its own identifier out (Amazon: `sources.amazonmusic.amazonAsin`).
         */
        fun trackJson(track: TrackEntity, resolverId: String, source: ResolvedSource?): JsonObject = buildJsonObject {
            put("id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            track.album?.takeIf { it.isNotEmpty() }?.let { put("album", it) }
            track.duration?.takeIf { it > 0 }?.let { put("duration", it / 1000) }
            val record = buildJsonObject {
                source?.pluginFields?.forEach { (k, v) -> put(k, v) }
                put("resolver", resolverId)
                source?.url?.let { put("url", it) }
            }
            put("sources", buildJsonObject { put(resolverId, record) })
        }
    }
}
