package com.parachord.shared.playback

import com.parachord.shared.playback.drm.PluginDrmContract
import com.parachord.shared.playback.drm.PluginDrmError
import com.parachord.shared.playback.drm.PluginDrmMessages
import com.parachord.shared.playback.drm.PluginDrmOutcome
import com.parachord.shared.playback.drm.PluginDrmReplies
import com.parachord.shared.playback.drm.pluginDrmOutcome
import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.pluginTrackIdKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A plugin's `getStreamUrl(track, config)` played through the host's plain
 * player (Android's ExoPlayer, iOS's AVPlayer): an `html5Stream` plugin
 * (Subsonic, plan parachord-mobile#664) and a Widevine plugin's plain stream
 * (Amazon Music's purchases, desktop parachord#1613). The URL is a credential
 * for one play: never on the track, a cache, the saved queue or a log line.
 */

/** `getStreamUrl`'s answer, with the plugin's error kept ([com.parachord.shared.plugin.PluginManager.requestStreamUrl]). */
sealed class PluginStreamReply {
    /** The plugin answered; [url] is null when it has no stream for this track. */
    data class Answered(val url: String?) : PluginStreamReply()

    /** The plugin threw (its `reason` and message), or didn't answer at all. */
    data class Failed(val error: PluginDrmError) : PluginStreamReply()
}

object PluginStreamReplies {
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads the poll's raw JSON. No answer at all is a failed play. Never logs it. */
    fun parse(raw: String?): PluginStreamReply {
        val obj = raw?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return PluginStreamReply.Failed(PluginDrmError(message = "no answer"))
        val ok = (obj["ok"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        if (!ok) return PluginStreamReply.Failed(PluginDrmReplies.error(obj["error"]))
        val url = (obj["url"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        return PluginStreamReply.Answered(url)
    }
}

/** What the engine does with a [PluginStreamReply] ([pluginStreamOutcome]). */
sealed class PluginStreamOutcome {
    /** Play [url] (https, checked by [PluginStreamUrl.isPlayable]). */
    data class Play(val url: String) : PluginStreamOutcome()

    /**
     * An `html5Stream` plugin couldn't build a stream for a settings reason
     * (not configured, a wrong password, a plain-http server): `START_FAILED`
     * with [message] ([checkPluginSettingsMessage]); Play loads it again.
     */
    data class Settings(val message: String) : PluginStreamOutcome()

    /**
     * The plugin won't play this track (not a purchase, signed out, a plan):
     * a refused source, so another source plays, else the queue moves on when
     * it was advancing, else [message]. [outcome] says why, as the DRM path's.
     */
    data class Refused(val outcome: PluginDrmOutcome, val message: String) : PluginStreamOutcome()

    /** The stream couldn't be had right now (a failed request, a timeout): `START_FAILED`. */
    data class StartFailed(val message: String = PLAY_START_FAILED_MESSAGE) : PluginStreamOutcome()

    /** A newer play took over: never a failure. */
    data object Superseded : PluginStreamOutcome()
}

/**
 * What to do with [reply] from [resolverId]'s plugin, named [serviceName].
 * [html5Stream]: the plugin plays only through `getStreamUrl` (Subsonic), so
 * every failure is its settings, desktop's "check its settings". Otherwise it's
 * a Widevine plugin's plain stream, whose failures map as the DRM path's do
 * ([pluginDrmOutcome]); a `null` means it has no stream for this track (not a
 * purchase), a refused source.
 */
fun pluginStreamOutcome(
    reply: PluginStreamReply,
    resolverId: String,
    serviceName: String,
    html5Stream: Boolean,
): PluginStreamOutcome {
    if (html5Stream) {
        val url = (reply as? PluginStreamReply.Answered)?.url
        if (url != null && PluginStreamUrl.isPlayable(url)) return PluginStreamOutcome.Play(url)
        if (reply is PluginStreamReply.Failed && reply.error.reason == PluginDrmContract.REASON_SUPERSEDED) {
            return PluginStreamOutcome.Superseded
        }
        return PluginStreamOutcome.Settings(checkPluginSettingsMessage(resolverId))
    }
    return when (reply) {
        is PluginStreamReply.Answered -> when {
            reply.url == null -> PluginStreamOutcome.Refused(PluginDrmOutcome.LICENSE_DENIED, noPlainStreamMessage(serviceName))
            PluginStreamUrl.isPlayable(reply.url) -> PluginStreamOutcome.Play(reply.url)
            else -> PluginStreamOutcome.StartFailed()
        }
        is PluginStreamReply.Failed -> when (val outcome = pluginDrmOutcome(reply.error)) {
            PluginDrmOutcome.SUPERSEDED -> PluginStreamOutcome.Superseded
            PluginDrmOutcome.PLAN_REFUSED, PluginDrmOutcome.NOT_SIGNED_IN, PluginDrmOutcome.LICENSE_DENIED ->
                PluginStreamOutcome.Refused(
                    outcome,
                    PluginDrmMessages.refused(
                        outcome, serviceName,
                        planMessage = if (outcome == PluginDrmOutcome.PLAN_REFUSED) reply.error.message else null,
                    ),
                )
            else -> PluginStreamOutcome.StartFailed()
        }
    }
}

/** A Widevine plugin's plain stream had nothing for the track (Amazon: not one of the account's purchases). */
fun noPlainStreamMessage(serviceName: String): String =
    "$serviceName only plays your purchases on this device."

/**
 * Whether a plugin DRM play that failed with [outcome] plays the plugin's
 * plain stream instead (desktop's `drmFallback`, parachord#1613): only when
 * the plugin implements `getStreamUrl` ([hasStreamUrl]) and the DRM stream
 * never started ([started]). A refused licence, a manifest past its budget
 * (after the one retry), a failed load and a start that timed out all fall
 * back. Never after a plan refusal (the track isn't the account's), a
 * sign-in refusal, or a newer play.
 */
fun drmPlainStreamFallbackAllowed(outcome: PluginDrmOutcome, started: Boolean, hasStreamUrl: Boolean): Boolean {
    if (!hasStreamUrl || started) return false
    return when (outcome) {
        PluginDrmOutcome.START_FAILED, PluginDrmOutcome.LICENSE_DENIED, PluginDrmOutcome.FAMILY_REFUSED -> true
        PluginDrmOutcome.PLAN_REFUSED, PluginDrmOutcome.NOT_SIGNED_IN, PluginDrmOutcome.SUPERSEDED -> false
    }
}

/**
 * The track the host hands `getStreamUrl` (desktop's `sourceToPlay`): the
 * track's names, its length in seconds, the plugin's own id under
 * `<resolverId>Id` (Subsonic's `subsonicId`) and the `sources` map whose
 * record for [resolverId] is the plugin's own result ([ResolvedSource.pluginFields]:
 * Amazon reads `sources.amazonmusic.amazonAsin`). Never carries a stream URL.
 */
fun pluginStreamTrackJson(
    trackId: String,
    title: String,
    artist: String,
    album: String?,
    durationMs: Long?,
    resolverId: String,
    pluginTrackId: String?,
    source: ResolvedSource?,
): JsonObject = buildJsonObject {
    put("id", trackId)
    put("title", title)
    put("artist", artist)
    album?.takeIf { it.isNotEmpty() }?.let { put("album", it) }
    durationMs?.takeIf { it > 0 }?.let { put("duration", it / 1000) }
    pluginTrackId?.takeIf { it.isNotEmpty() }?.let { put(pluginTrackIdKey(resolverId), it) }
    val record = buildJsonObject {
        source?.pluginFields?.forEach { (k, v) -> put(k, v) }
        pluginTrackId?.takeIf { it.isNotEmpty() }?.let { put(pluginTrackIdKey(resolverId), it) }
        put("resolver", resolverId)
        source?.url?.takeIf { it.isNotEmpty() }?.let { put("url", it) }
    }
    put("sources", buildJsonObject { put(resolverId, record) })
}
