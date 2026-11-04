package com.parachord.shared.playback

import com.parachord.shared.platform.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Bandcamp's own embedded player, how Bandcamp tracks play (#513, desktop
 * parachord#1231). Both apps load it as the top-level page of a hidden web view
 * (Android `BandcampEmbedBridge`, iOS `IosBandcampEmbed`) and run desktop's
 * `bandcamp-embed-control.js`, copied unchanged, in it.
 *
 * Unlike SoundCloud's widget the embed has no protocol, and here it isn't inside
 * a page of ours: Bandcamp's scripts run at the top level. So there's no bridge
 * into the page. The apps poll `bcState()` from outside, and what it answers is
 * untrusted ([parseState]). The URL rules port desktop's
 * `main-process/bandcamp-embed-url.js`, the state rules its
 * `sanitizeEmbedState` and `bandcamp-embed-player.js`.
 */
object BandcampEmbed {
    const val SERVICE_NAME = "Bandcamp"
    const val POLL_MS = 500L

    /** A track whose position hasn't moved by then is refused (desktop's limit). */
    const val START_TIMEOUT_MS = 15_000L

    const val START_SCRIPT = "bcStart()"
    const val STATE_SCRIPT = "bcState()"
    const val PLAY_SCRIPT = "bcPlay()"
    const val PAUSE_SCRIPT = "bcPause()"

    private const val EMBED_ORIGIN = "https://bandcamp.com"
    private const val EMBED_SUFFIX =
        "/size=large/bgcol=333333/linkcol=ffffff/tracklist=false/artwork=small/transparent=true/"

    /** A position or duration past this (a month) isn't a real one. */
    private const val MAX_SECONDS = 2_592_000.0

    private val TRACK_ID = Regex("^[1-9][0-9]*$")
    private val json = Json { ignoreUnknownKeys = true }

    /** Only ever a checked number reaches the page, never text. */
    fun seekScript(seconds: Double): String = "bcSeek(${seconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0})"

    fun volumeScript(volume: Double): String = "bcVolume(${volume.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0})"

    /**
     * The embed for a numeric track id. Always the single-track form:
     * `album=…/track=…` starts on that track but is a player for the album, and
     * runs on into its next track by itself.
     */
    fun embedUrl(trackId: String?): String? =
        trackId?.takeIf { TRACK_ID.matches(it) }?.let { "$EMBED_ORIGIN/EmbeddedPlayer/track=$it$EMBED_SUFFIX" }

    /** What the hidden web view may load: Bandcamp and its media hosts. */
    fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "bandcamp.com" || h.endsWith(".bandcamp.com") || h == "bcbits.com" || h.endsWith(".bcbits.com")
    }

    /** A track page on a bandcamp.com subdomain, the only page we look an id up from. */
    fun isTrackPage(url: String?): Boolean {
        val parsed = parse(url) ?: return false
        return url!!.startsWith("https://") && parsed.user == null && parsed.password == null &&
            Regex("^[a-z0-9-]+\\.bandcamp\\.com$", RegexOption.IGNORE_CASE).matches(parsed.host) &&
            Regex("^/track/[^/]+/?$").matches(parsed.encodedPath)
    }

    /** The track's page, for "Open in Bandcamp". Artists can have their own domains. */
    fun pageLink(url: String?): String? = url?.takeIf { it.startsWith("https://") && parse(it) != null }

    /**
     * What `bcState()` answered, or null when it wasn't a state. The page is
     * third party, so every field is coerced and range-checked.
     */
    fun parseState(raw: String?): BandcampEmbedState? {
        val obj = try {
            json.parseToJsonElement(raw ?: return null) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        fun flag(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        fun seconds(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            ?.takeIf { it.isFinite() && it in 0.0..MAX_SECONDS } ?: 0.0
        return BandcampEmbedState(
            hasSrc = flag("hasSrc") == true,
            paused = flag("paused") != false,
            ended = flag("ended") == true,
            positionSeconds = seconds("t"),
            durationSeconds = seconds("dur"),
            errorCode = (obj["errorCode"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
        )
    }

    private fun parse(url: String?): Url? = try {
        url?.let { Url(it) }
    } catch (e: Exception) {
        null
    }

    /** The embed URL for a source: its id, else one looked up from its track page. */
    internal suspend fun resolveEmbedUrl(
        trackId: String?,
        pageUrl: String?,
        fetch: suspend (String) -> String?,
    ): String? {
        embedUrl(trackId)?.let { return it }
        if (!isTrackPage(pageUrl)) return null
        for (lookup in listOf(::trackIdFromSearch, ::trackIdFromPage)) {
            try {
                embedUrl(lookup(pageUrl!!, fetch))?.let { return it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("BandcampEmbed", "Couldn't find this track's id: ${e.message}")
            }
        }
        return null
    }

    private suspend fun trackIdFromSearch(pageUrl: String, fetch: suspend (String) -> String?): String? {
        val page = Url(pageUrl)
        val slug = page.encodedPath.trimEnd('/').substringAfterLast('/')
        val query = page.host.removeSuffix(".bandcamp.com").replace('-', ' ') + " " +
            slug.replace(Regex("-[0-9]+$"), "").replace('-', ' ')
        val body = fetch("$EMBED_ORIGIN/api/fuzzysearch/1/app_autocomplete?q=${query.encodeURLParameter()}") ?: return null
        val results = (json.parseToJsonElement(body) as? JsonObject)?.get("results") as? JsonArray ?: return null
        return results.firstNotNullOfOrNull { result ->
            val obj = result as? JsonObject ?: return@firstNotNullOfOrNull null
            // app_autocomplete can double the URL ("https://x.bandcamp.comhttps://x.bandcamp.com/track/y").
            val url = (obj["url"] as? JsonPrimitive)?.content?.let { it.substring(maxOf(0, it.lastIndexOf("https://"))) }
            if (url != null && samePage(url, pageUrl)) (obj["id"] as? JsonPrimitive)?.content else null
        }
    }

    private suspend fun trackIdFromPage(pageUrl: String, fetch: suspend (String) -> String?): String? {
        val html = fetch(pageUrl) ?: return null
        val attribute = Regex("data-tralbum=\"([^\"]*)\"").find(html)?.groupValues?.get(1) ?: return null
        val decoded = attribute.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        val current = (json.parseToJsonElement(decoded) as? JsonObject)?.get("current") as? JsonObject ?: return null
        if ((current["type"] as? JsonPrimitive)?.content == "album") return null
        return (current["id"] as? JsonPrimitive)?.content
    }

    private fun samePage(a: String, b: String): Boolean {
        val x = parse(a) ?: return false
        val y = parse(b) ?: return false
        return x.host.equals(y.host, ignoreCase = true) && x.encodedPath.trimEnd('/') == y.encodedPath.trimEnd('/')
    }
}

/** One answer from `bcState()`, after [BandcampEmbed.parseState]. */
data class BandcampEmbedState(
    val hasSrc: Boolean,
    val paused: Boolean,
    val ended: Boolean,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val errorCode: Int?,
)

/**
 * Finds the embed URL for a Bandcamp source. Sources normally carry the track id
 * (`bandcamp.axe`'s search results do); older cached sources, library rows and
 * `lookupUrl` results have only the page. A custom-domain or album page with no
 * id can't be looked up, and the caller treats the track as refused.
 */
class BandcampEmbedResolver(private val httpClient: HttpClient) {
    suspend fun embedUrl(trackId: String?, pageUrl: String?): String? =
        BandcampEmbed.resolveEmbedUrl(trackId, pageUrl) { url ->
            val response = httpClient.get(url)
            if (response.status.isSuccess()) response.bodyAsText() else null
        }
}

/**
 * What one load of the embed has done, from the states polled out of it. Each
 * app's poll loop feeds [update] and reads the result; the rules live here so
 * they can't drift. "Started" means the position advanced. A track that doesn't
 * within [BandcampEmbed.START_TIMEOUT_MS], or errors first, is [refused], a
 * failed source. An error after it started counts as [finished], so the queue
 * moves on rather than stalling.
 */
class BandcampEmbedTracker(private val startedAtMs: Long) {
    var started = false; private set
    var refused = false; private set
    var finished = false; private set
    var paused = true; private set
    var positionSeconds = 0.0; private set
    var durationSeconds = 0.0; private set
    private var holdPausedUntilMs = 0L

    /** `playing` as the app shows it. */
    val isPlaying: Boolean get() = started && !paused && !finished

    /**
     * Take in what `bcState()` answered at [nowMs]; null when the poll got
     * nothing. Returns false when [raw] wasn't a state, which is the page
     * having lost the control script, or not having it yet.
     */
    fun update(raw: String?, nowMs: Long): Boolean {
        val state = BandcampEmbed.parseState(raw)
        if (state != null) apply(state, nowMs)
        if (!started && !refused && nowMs - startedAtMs >= BandcampEmbed.START_TIMEOUT_MS) refused = true
        return state != null
    }

    /** The user seeked: show it now, the next poll confirms. */
    fun seeked(seconds: Double) {
        positionSeconds = seconds.coerceAtLeast(0.0)
    }

    /**
     * The user paused or resumed. Show it now, and trust it over the page for a
     * moment: a poll takes up to 500 ms, and until one answers, anything reading
     * [isPlaying] (the play button, the media session, a second tap) would see
     * the old state.
     */
    fun userSetPaused(paused: Boolean, nowMs: Long) {
        this.paused = paused
        holdPausedUntilMs = nowMs + USER_PAUSE_HOLD_MS
    }

    private fun apply(state: BandcampEmbedState, nowMs: Long) {
        if (state.durationSeconds > 0) durationSeconds = state.durationSeconds
        if (!started) {
            if (state.errorCode != null) {
                refused = true
                return
            }
            if (state.positionSeconds > 0) {
                started = true
            } else {
                if (state.ended) refused = true
                return
            }
        }
        if (state.ended || state.errorCode != null) {
            finished = true
            paused = true
            return
        }
        positionSeconds = state.positionSeconds
        if (nowMs >= holdPausedUntilMs) paused = state.paused
    }

    private companion object {
        const val USER_PAUSE_HOLD_MS = 1_000L
    }
}
