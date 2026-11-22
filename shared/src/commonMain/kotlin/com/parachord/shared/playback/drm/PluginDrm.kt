package com.parachord.shared.playback.drm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The plugin-supplied Widevine path (desktop docs/architecture/plugins.md,
 * "The plugin-supplied Widevine path", #1356 M4): a plugin declaring
 * `capabilities.widevine` that implements BOTH `getPlaybackManifest(track,
 * config)` and `relayLicense(challengeB64, context)` supplies the DASH
 * manifest and the licences; the host plays them. The plugin's `context`
 * never leaves the JS runtime (the bootstrap's `__pcDrm` keeps it by session).
 */
object PluginDrmContract {
    /** Desktop's `PLUGIN_MANIFEST_BUDGET_MS`: past it the play fails as `playback-failed`. */
    const val MANIFEST_BUDGET_MS: Long = 10_000L

    /** How long one licence round trip may take before the key request fails. */
    const val LICENSE_BUDGET_MS: Long = 15_000L

    /**
     * Per-content licence refusals that latch the refused codec family
     * (desktop's `HD_CONTENT_DENIAL_STATUSES`, #1262): Amazon refuses its HD
     * key group on a plan that doesn't cover it.
     */
    val CONTENT_DENIAL_STATUSES: Set<String> = setOf("CUSTOMER_NOT_ELIGIBLE")

    const val REASON_PLAN_INELIGIBLE = "plan-ineligible"
    const val REASON_NOT_SIGNED_IN = "not-signed-in"
    const val REASON_LOAD_FAILED = "load-failed"
    const val REASON_PLAYBACK_FAILED = "playback-failed"
    const val REASON_SUPERSEDED = "superseded"
}

/**
 * The fields a plugin's DRM error carries (the contract's load-bearing
 * fields): `reason`, `planKey` (plan-ineligible), `licenseDenied` (the build
 * can't play this service at all) and `denialReason` (a per-content refusal).
 * [message] is the plugin's text, cut short; show it only where the plugin
 * writes words for people (a plan refusal).
 */
data class PluginDrmError(
    val reason: String = PluginDrmContract.REASON_PLAYBACK_FAILED,
    val planKey: String? = null,
    val licenseDenied: Boolean = false,
    val denialReason: String? = null,
    val message: String? = null,
    /**
     * The host's budget ran out ([PluginDrmContract.MANIFEST_BUDGET_MS], the
     * bootstrap's `withBudget`), not the plugin reporting a failure. The
     * plugin's own call keeps running in JS ([manifestRetryAllowed]).
     */
    val budgetExpired: Boolean = false,
) {
    companion object {
        /** A budget that ran out: the play fails as `playback-failed`, as desktop's budget does. */
        fun timedOut(what: String): PluginDrmError =
            PluginDrmError(PluginDrmContract.REASON_PLAYBACK_FAILED, message = "$what timed out", budgetExpired = true)
    }
}

/** How many times a play asks for its manifest again after the budget ran out ([manifestRetryAllowed]). */
const val MANIFEST_BUDGET_RETRIES: Int = 1

/**
 * Whether a play asks the plugin for its manifest again, at once, after
 * [error] on its [retriesSoFar]-th retry. Only when the host's 10 s budget ran
 * out ([PluginDrmError.budgetExpired]): the plugin's call keeps running in JS
 * past the budget (Amazon serializes a purchases read and a session-header
 * mint, desktop parachord#1603), so the second call joins it and usually
 * answers well within its own budget. Once per play. Never for a failure the
 * plugin reported itself (plan-ineligible, not-signed-in, load-failed, a
 * licence refusal) or a superseded play. The budget itself stays desktop's.
 */
fun manifestRetryAllowed(error: PluginDrmError, retriesSoFar: Int): Boolean =
    retriesSoFar < MANIFEST_BUDGET_RETRIES &&
        error.budgetExpired &&
        error.reason == PluginDrmContract.REASON_PLAYBACK_FAILED

/** What the engine does about a [PluginDrmError]. */
enum class PluginDrmOutcome {
    /** The account's plan can't play here: note the plan (refresh the plugin's status), another source plays. */
    PLAN_REFUSED,

    /** Not signed in: refresh the status, another source plays (the sign-in prompt is a follow-up, desktop #1523). */
    NOT_SIGNED_IN,

    /** The licence was refused for this build: another source plays. */
    LICENSE_DENIED,

    /** A per-content refusal for one codec family: drop the family, try the track once more. */
    FAMILY_REFUSED,

    /** The user moved on: never a failure. */
    SUPERSEDED,

    /** Anything else (a budget, `load-failed`, `playback-failed`): the play didn't start. */
    START_FAILED,
}

/**
 * The engine's response to [error] (desktop's use-playback-engine widevine
 * catch): superseded first, then the plan, sign-in, a build refusal and a
 * per-content family refusal; anything else didn't start.
 */
fun pluginDrmOutcome(error: PluginDrmError): PluginDrmOutcome = when {
    error.reason == PluginDrmContract.REASON_SUPERSEDED -> PluginDrmOutcome.SUPERSEDED
    error.reason == PluginDrmContract.REASON_PLAN_INELIGIBLE -> PluginDrmOutcome.PLAN_REFUSED
    error.reason == PluginDrmContract.REASON_NOT_SIGNED_IN -> PluginDrmOutcome.NOT_SIGNED_IN
    error.licenseDenied -> PluginDrmOutcome.LICENSE_DENIED
    error.denialReason != null && error.denialReason in PluginDrmContract.CONTENT_DENIAL_STATUSES -> PluginDrmOutcome.FAMILY_REFUSED
    else -> PluginDrmOutcome.START_FAILED
}

/** The manifest extras a `getPlaybackManifest` reply may carry (non-secret). */
data class PluginDrmExtras(
    val asin: String? = null,
    val substituteAsin: String? = null,
    val cdnType: String? = null,
)

/** `__pcDrm.open`'s answer: a session, or the plugin's error. */
sealed class PluginDrmOpenReply {
    data class Opened(val sessionId: String, val manifest: String, val extras: PluginDrmExtras) : PluginDrmOpenReply()
    data class Failed(val error: PluginDrmError) : PluginDrmOpenReply()
}

/**
 * A plugin's FairPlay HLS stream for one play (`type: 'hls-fairplay'`, desktop
 * #1627): the playlist [url], the FairPlay application [certificateB64], and the
 * header lists the playlist and segment requests carry (Amazon: a per-play
 * `rid` cookie). Per-play credentials: never logged, never saved.
 */
data class PluginFairPlayStream(
    val url: String,
    val certificateB64: String,
    val manifestHeaders: List<Pair<String, String>>,
    val segmentHeaders: List<Pair<String, String>>,
) {
    /** The `Cookie` header values, as `name=value` pairs, from both lists (AVPlayer sends cookies, not other headers). */
    fun cookies(): List<Pair<String, String>> =
        (manifestHeaders + segmentHeaders)
            .filter { it.first.equals("Cookie", ignoreCase = true) }
            .flatMap { (_, v) -> v.split(';') }
            .mapNotNull { c ->
                val name = c.substringBefore('=').trim()
                val value = c.substringAfter('=', "").trim()
                if (name.isEmpty() || !c.contains('=')) null else name to value
            }
            .distinct()

    /** Header names other than `Cookie` (the engine can't send them; logged by name only). */
    fun otherHeaderNames(): List<String> =
        (manifestHeaders + segmentHeaders).map { it.first }.filterNot { it.equals("Cookie", ignoreCase = true) }.distinct()
}

/** `__pcDrm.open(…, 'hls-fairplay')`'s answer. */
sealed class PluginFairPlayOpenReply {
    data class Opened(val sessionId: String, val stream: PluginFairPlayStream, val extras: PluginDrmExtras) : PluginFairPlayOpenReply()
    data class Failed(val error: PluginDrmError) : PluginFairPlayOpenReply()
}

/** `__pcDrm.license`'s answer: the licence (base64), or the plugin's error. */
sealed class PluginDrmLicenseReply {
    data class Licensed(val licenseB64: String) : PluginDrmLicenseReply()
    data class Failed(val error: PluginDrmError) : PluginDrmLicenseReply()
}

/** Reads the bootstrap's `__pcDrm` answers. Never logs them: a manifest or a licence is content. */
object PluginDrmReplies {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

    private fun parse(raw: String?): JsonObject? =
        raw?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    /** The error object `{ reason, planKey, licenseDenied, denialReason, message }`. */
    fun error(obj: JsonElement?): PluginDrmError {
        val e = obj as? JsonObject ?: return PluginDrmError()
        return PluginDrmError(
            reason = e.str("reason") ?: PluginDrmContract.REASON_PLAYBACK_FAILED,
            planKey = e.str("planKey"),
            licenseDenied = e.bool("licenseDenied"),
            denialReason = e.str("denialReason"),
            message = e.str("message")?.take(300),
            budgetExpired = e.bool("budgetExpired"),
        )
    }

    /** No answer at all (the runtime isn't ready, the poll timed out) is a failed open. */
    fun open(raw: String?): PluginDrmOpenReply {
        val obj = parse(raw) ?: return PluginDrmOpenReply.Failed(PluginDrmError(message = "no answer"))
        if (!obj.bool("ok")) return PluginDrmOpenReply.Failed(error(obj["error"]))
        val sessionId = obj.str("sessionId")
        val manifest = obj.str("manifest")
        if (sessionId == null || manifest == null) {
            return PluginDrmOpenReply.Failed(PluginDrmError(message = "the manifest reply carried no manifest"))
        }
        val extras = obj["extras"] as? JsonObject
        return PluginDrmOpenReply.Opened(
            sessionId,
            manifest,
            PluginDrmExtras(
                asin = extras?.str("asin"),
                substituteAsin = extras?.str("substituteAsin"),
                cdnType = extras?.str("cdnType"),
            ),
        )
    }

    /** `__pcDrm.open(…, 'hls-fairplay')`: no answer at all is a failed open. */
    fun openFairPlay(raw: String?): PluginFairPlayOpenReply {
        val obj = parse(raw) ?: return PluginFairPlayOpenReply.Failed(PluginDrmError(message = "no answer"))
        if (!obj.bool("ok")) return PluginFairPlayOpenReply.Failed(error(obj["error"]))
        val sessionId = obj.str("sessionId")
        val fp = obj["fairplay"] as? JsonObject
        val url = fp?.str("url")
        val certificate = fp?.str("certificate")
        if (sessionId == null || url == null || certificate == null || !url.startsWith("https://")) {
            return PluginFairPlayOpenReply.Failed(PluginDrmError(message = "the manifest reply is not hls/fairplay"))
        }
        fun pairs(key: String): List<Pair<String, String>> =
            ((fp[key] as? kotlinx.serialization.json.JsonArray) ?: emptyList()).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val n = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val v = (o["value"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                n to v
            }
        val extras = obj["extras"] as? JsonObject
        return PluginFairPlayOpenReply.Opened(
            sessionId,
            PluginFairPlayStream(url, certificate, pairs("manifestHeaders"), pairs("segmentHeaders")),
            PluginDrmExtras(
                asin = extras?.str("asin"),
                substituteAsin = extras?.str("substituteAsin"),
                cdnType = extras?.str("cdnType"),
            ),
        )
    }

    fun license(raw: String?): PluginDrmLicenseReply {
        val obj = parse(raw) ?: return PluginDrmLicenseReply.Failed(PluginDrmError(message = "no answer"))
        if (!obj.bool("ok")) return PluginDrmLicenseReply.Failed(error(obj["error"]))
        val license = obj.str("license")
            ?: return PluginDrmLicenseReply.Failed(PluginDrmError(message = "the plugin's relayLicense answered empty"))
        return PluginDrmLicenseReply.Licensed(license)
    }
}

/**
 * The codec families this process stopped asking for (desktop's
 * `blockedFamilies`, #1262), for a per-content licence refusal: the family
 * whose key was asked for when the refusal came is dropped, and the variant
 * choice picks the best remaining rung. An account fact: [clearPlanBlocks]
 * on sign-out. Desktop also latches a family whose data the decoder rejected
 * (a build fact); here ExoPlayer leaves a codec it can't decode out of the
 * choice to begin with, so only the licence latch is kept.
 */
class DrmFamilyTable {
    private val blocked = mutableMapOf<String, String>()

    val blockedFamilies: Set<String> get() = blocked.keys.toSet()

    /**
     * Records a licence refusal: true when it latched a new family (worth one
     * retry without it), false when it isn't a content refusal, the family
     * isn't known, or it was already blocked (the retry didn't take).
     */
    fun recordLicenseRefusal(error: PluginDrmError, requestedFamily: String?): Boolean {
        if (requestedFamily == null) return false
        if (pluginDrmOutcome(error) != PluginDrmOutcome.FAMILY_REFUSED) return false
        if (requestedFamily in blocked) return false
        blocked[requestedFamily] = LICENSE_DENIED
        return true
    }

    /** Sign-out: the next account's plan may cover the refused families. */
    fun clearPlanBlocks() {
        blocked.entries.removeAll { it.value == LICENSE_DENIED }
    }

    companion object {
        const val LICENSE_DENIED = "license-denied"
    }
}

/**
 * The plugin's player events (`handlePlayerEvent`), with desktop's names and
 * payloads (widevine-player.js): `play-started`, `seek`, `pause`, `resume`,
 * `ended`, `stopped`. The host adds `sessionId`.
 */
object PluginPlayerEvents {
    const val PLAY_STARTED = "play-started"
    const val SEEK = "seek"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val ENDED = "ended"
    const val STOPPED = "stopped"

    /** What the stream is playing: desktop's `variantFacts` (`bandwidth` in bps). */
    data class VariantFacts(val codec: String?, val sampleRate: Int?, val channels: Int?, val bandwidth: Long?)

    fun playStarted(
        asin: String?,
        albumAsin: String?,
        durationMs: Long?,
        initialDelaySeconds: Double?,
        variantFacts: VariantFacts?,
        cdnType: String?,
    ): JsonObject = buildJsonObject {
        put("type", PLAY_STARTED)
        put("asin", asin?.let { JsonPrimitive(it) } ?: JsonNull)
        put("albumAsin", albumAsin?.let { JsonPrimitive(it) } ?: JsonNull)
        put("durationMs", durationMs?.let { JsonPrimitive(it) } ?: JsonNull)
        // Desktop leaves it undefined (so JSON drops it) when unknown.
        if (initialDelaySeconds != null) put("initialDelaySeconds", (kotlin.math.round(initialDelaySeconds * 1000) / 1000.0))
        put(
            "variantFacts",
            if (variantFacts?.bandwidth != null && variantFacts.bandwidth > 0) {
                buildJsonObject {
                    put("codec", variantFacts.codec?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("sampleRate", variantFacts.sampleRate?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("channels", variantFacts.channels?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("bandwidth", variantFacts.bandwidth)
                }
            } else {
                JsonNull
            },
        )
        put("cdnType", cdnType?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    fun position(type: String, positionMs: Long): JsonObject = buildJsonObject {
        put("type", type)
        put("positionMs", positionMs)
    }

    fun ended(positionMs: Long, durationMs: Long?): JsonObject = buildJsonObject {
        put("type", ENDED)
        put("positionMs", positionMs)
        put("durationMs", durationMs?.let { JsonPrimitive(it) } ?: JsonNull)
    }
}

/**
 * Turns one session's player changes into the plugin's events, as desktop's
 * element listeners do: the first `playing` is `play-started` and later ones
 * `resume`; a pause is `pause` unless the track ended; the end is `ended`;
 * leaving a session that started is `stopped`. Pure: the platform feeds it
 * what its player reports and sends what it returns.
 */
class PluginPlayerEventTracker {
    private var started = false
    private var ended = false
    private var stopped = false

    /** The player is playing. [playStarted] builds the first play's event. */
    fun onPlaying(positionMs: Long, playStarted: () -> JsonObject): JsonObject? {
        if (stopped) return null
        ended = false
        if (started) return PluginPlayerEvents.position(PluginPlayerEvents.RESUME, positionMs)
        started = true
        return playStarted()
    }

    /** The player paused (not at the track's end; that one is [onEnded]). */
    fun onPaused(positionMs: Long): JsonObject? {
        if (!started || ended || stopped) return null
        return PluginPlayerEvents.position(PluginPlayerEvents.PAUSE, positionMs)
    }

    fun onSeek(positionMs: Long): JsonObject? {
        if (!started || stopped) return null
        return PluginPlayerEvents.position(PluginPlayerEvents.SEEK, positionMs)
    }

    fun onEnded(positionMs: Long, durationMs: Long?): JsonObject? {
        if (!started || ended || stopped) return null
        ended = true
        // Desktop clears `started` at the end: a replay of the same session is a new play.
        started = false
        return PluginPlayerEvents.ended(positionMs, durationMs)
    }

    /** The session is being left (another track, a stop). Once; only after it started and before it ended. */
    fun onStopped(positionMs: Long): JsonObject? {
        if (stopped) return null
        stopped = true
        if (!started) return null
        return PluginPlayerEvents.position(PluginPlayerEvents.STOPPED, positionMs)
    }
}

/** What the person is told when a plugin DRM source refuses a track. Plugin text only for a plan (its own words). */
object PluginDrmMessages {
    fun refused(outcome: PluginDrmOutcome, serviceName: String, planMessage: String?): String = when (outcome) {
        PluginDrmOutcome.PLAN_REFUSED ->
            planMessage?.takeIf { it.isNotBlank() } ?: "Your $serviceName plan doesn’t play tracks on demand."
        PluginDrmOutcome.NOT_SIGNED_IN -> "Sign in to $serviceName in Settings to play this track."
        PluginDrmOutcome.LICENSE_DENIED -> "$serviceName won’t play its tracks on this device."
        else -> com.parachord.shared.playback.PLAY_START_FAILED_MESSAGE
    }

    /** Desktop's plan toast when another source plays instead: the reason, then "Playing via X." */
    fun playingVia(refusedMessage: String, fallbackName: String): String =
        "$refusedMessage Playing via $fallbackName."
}
