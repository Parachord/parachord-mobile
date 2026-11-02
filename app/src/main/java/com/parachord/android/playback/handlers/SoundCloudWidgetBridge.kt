package com.parachord.android.playback.handlers

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.parachord.shared.platform.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SoundCloud's embeddable widget (`w.soundcloud.com/player`) in a hidden
 * WebView: how SoundCloud tracks play (desktop parachord#1209).
 *
 * The public API no longer gives a signed-in user full tracks: `/stream`
 * redirects to a 30-second preview (or 429s with the token in the query), and
 * the full streams are HLS that answers 403 "An active Artist Pro
 * subscription is required". The widget is SoundCloud's own player, needs no
 * sign-in, and plays the whole track for anything SoundCloud lets it embed.
 *
 * The page (`assets/js/soundcloud-widget.html`) runs desktop's
 * `soundcloud-widget-player.js` unchanged, so both apps speak the widget's
 * protocol through the same code; it relays the player's callbacks here
 * through [WidgetJsInterface]. A track the widget won't play loads its title
 * and art and never starts, so [load] reports [LoadResult.Refused] when the
 * position hasn't moved within the module's 10 s. One SoundCloud only lets it
 * preview (a Go+ release) starts, fades out at 0:30 and reports `finish`; the
 * page asks the widget's `getCurrentSound` and refuses those too (reason
 * `preview`).
 *
 * Background playback rides on the same setup as Apple Music's MusicKit
 * WebView ([MusicKitWebBridge]): never added to a window, `LAYER_TYPE_NONE`
 * (screen-off GPU teardown otherwise disrupts audio), no user gesture needed
 * for media, and [keepAlive] before each track. Chromium manages audio focus
 * for WebView audio; the controller's silent ExoPlayer loop keeps the
 * foreground service and MediaSession alive.
 */
class SoundCloudWidgetBridge constructor(
    private val context: Context,
) {
    companion object {
        private const val TAG = "SoundCloudWidget"

        /** Served via WebViewAssetLoader so the page has an https:// origin. */
        private const val PAGE_URL = "https://appassets.androidplatform.net/assets/js/soundcloud-widget.html"
        private const val PAGE_TIMEOUT_MS = 15_000L

        /**
         * The page's own limit is 10 s ([START_TIMEOUT_MS] in the module);
         * this backstop covers timers Chromium throttles in the background.
         */
        private const val LOAD_TIMEOUT_MS = 20_000L

        private val ALLOWED_HOST_SUFFIXES = listOf("soundcloud.com", "sndcdn.com")

        fun isAllowedHost(host: String): Boolean =
            host == "appassets.androidplatform.net" ||
                ALLOWED_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }

        private val json = Json { ignoreUnknownKeys = true }

        /** One relayed player event; null for anything that isn't one. */
        internal fun parseEvent(raw: String): WidgetEvent? = try {
            val obj: JsonObject = json.parseToJsonElement(raw).jsonObject
            val gen = obj["gen"]?.jsonPrimitive?.intOrNull
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (gen == null || type == null) {
                null
            } else {
                val value = obj["value"] as? JsonPrimitive
                WidgetEvent(gen, type, value?.doubleOrNull, value?.takeIf { it.isString }?.content)
            }
        } catch (e: Exception) {
            null
        }
    }

    internal data class WidgetEvent(val gen: Int, val type: String, val number: Double?, val text: String?)

    sealed class LoadResult {
        data class Started(val durationMs: Long) : LoadResult()
        data class Refused(val reason: String) : LoadResult()
    }

    private var webView: WebView? = null
    private var pageReady = CompletableDeferred<Unit>()

    @Volatile private var generation = 0
    @Volatile private var pendingLoad: CompletableDeferred<LoadResult>? = null

    /** Whether the current load has started playing (the position moved). */
    @Volatile var started: Boolean = false; private set
    @Volatile var isPlaying: Boolean = false; private set
    @Volatile var positionMs: Long = 0L; private set
    @Volatile var durationMs: Long = 0L; private set

    /** Called (on a background thread) when the current track finishes. */
    @Volatile var onFinished: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensurePage(): Boolean {
        withContext(Dispatchers.Main) {
            if (webView != null) return@withContext
            pageReady = CompletableDeferred()
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                setLayerType(View.LAYER_TYPE_NONE, null)
                addJavascriptInterface(WidgetJsInterface(), "SoundCloudBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? =
                        request?.url?.let { assetLoader.shouldInterceptRequest(it) }
                            ?: super.shouldInterceptRequest(view, request)

                    /** Only our page and SoundCloud: keeps other pages away from the bridge. */
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val host = request?.url?.host?.lowercase() ?: return false
                        if (isAllowedHost(host)) return false
                        Log.w(TAG, "Blocked navigation to $host")
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    /**
                     * The widget plays some tracks (label releases) as
                     * Widevine-encrypted streams; a WebView refuses those
                     * unless the app grants protected media. Granted only to
                     * SoundCloud's widget and our page.
                     */
                    override fun onPermissionRequest(request: PermissionRequest?) {
                        val req = request ?: return
                        val host = req.origin?.host?.lowercase()
                        if ((host == "w.soundcloud.com" || host == "appassets.androidplatform.net") &&
                            req.resources.all { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                        ) {
                            req.grant(req.resources)
                        } else {
                            Log.w(TAG, "Denied ${req.resources.joinToString()} to $host")
                            req.deny()
                        }
                    }

                    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                        message?.let { Log.d(TAG, "console: ${it.message()} (${it.sourceId()}:${it.lineNumber()})") }
                        return true
                    }
                }
                loadUrl(PAGE_URL)
            }
        }
        val ready = withTimeoutOrNull(PAGE_TIMEOUT_MS) { pageReady.await() } != null
        if (!ready) {
            Log.w(TAG, "Widget page didn't load")
            release()
        }
        return ready
    }

    /** Keep the WebView running while the app is in the background (see class doc). */
    private suspend fun keepAlive() = withContext(Dispatchers.Main) {
        webView?.let {
            it.onResume()
            it.resumeTimers()
        }
    }

    private suspend fun evaluate(script: String) {
        withContext(Dispatchers.Main) { webView?.evaluateJavascript(script, null) }
    }

    /**
     * Load [link] (a soundcloud.com permalink, or an api.soundcloud.com track
     * URL) and start it at [volume] (0..1). Returns once the position has
     * advanced, or [LoadResult.Refused] when the widget won't play it here.
     */
    suspend fun load(link: String, volume: Float): LoadResult {
        if (!ensurePage()) return LoadResult.Refused("page")
        keepAlive()
        pendingLoad?.complete(LoadResult.Refused("superseded"))
        val gen = ++generation
        started = false
        isPlaying = false
        positionMs = 0L
        durationMs = 0L
        val result = CompletableDeferred<LoadResult>()
        pendingLoad = result
        evaluate("scLoad($gen, ${JsonPrimitive(link)}, ${volume.coerceIn(0f, 1f)})")
        val outcome = withTimeoutOrNull(LOAD_TIMEOUT_MS) { result.await() } ?: LoadResult.Refused("unplayable")
        if (pendingLoad === result) pendingLoad = null
        if (outcome is LoadResult.Refused && gen == generation) stop()
        return outcome
    }

    suspend fun play() = evaluate("scPlay()")
    suspend fun pause() = evaluate("scPause()")
    suspend fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
        evaluate("scSeek(${positionMs / 1000.0})")
    }
    suspend fun setVolume(volume: Float): Unit = evaluate("scVolume(${volume.coerceIn(0f, 1f)})")

    /** Halt the track: the page blanks the widget, so nothing is left paused. */
    suspend fun stop() {
        generation++
        pendingLoad?.complete(LoadResult.Refused("superseded"))
        pendingLoad = null
        started = false
        isPlaying = false
        positionMs = 0L
        if (webView != null) evaluate("scStop()")
    }

    private suspend fun release() = withContext(Dispatchers.Main) {
        webView?.destroy()
        webView = null
    }

    internal fun onEvent(event: WidgetEvent) {
        if (event.type == "pageReady") {
            pageReady.complete(Unit)
            return
        }
        if (event.gen != generation) return
        when (event.type) {
            "started" -> {
                started = true
                isPlaying = true
                event.number?.takeIf { it > 0 }?.let { durationMs = (it * 1000).toLong() }
                pendingLoad?.complete(LoadResult.Started(durationMs))
            }
            "failed" -> pendingLoad?.complete(LoadResult.Refused(event.text ?: "unplayable"))
            "progress" -> event.number?.let { positionMs = (it * 1000).toLong() }
            "duration" -> event.number?.takeIf { it > 0 }?.let { durationMs = (it * 1000).toLong() }
            "play" -> {
                Log.d(TAG, "play at ${positionMs}ms")
                isPlaying = true
            }
            "pause" -> {
                Log.d(TAG, "pause at ${positionMs}ms of ${durationMs}ms")
                isPlaying = false
            }
            "finish" -> {
                Log.d(TAG, "finish at ${positionMs}ms of ${durationMs}ms")
                isPlaying = false
                onFinished?.invoke()
            }
        }
    }

    private inner class WidgetJsInterface {
        @JavascriptInterface
        fun onEvent(raw: String) {
            parseEvent(raw)?.let { this@SoundCloudWidgetBridge.onEvent(it) }
        }
    }
}
