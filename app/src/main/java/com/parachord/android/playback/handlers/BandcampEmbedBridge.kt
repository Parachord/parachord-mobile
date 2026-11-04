package com.parachord.android.playback.handlers

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.BandcampEmbed
import com.parachord.shared.playback.BandcampEmbedTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume

/**
 * Bandcamp's embedded player in a hidden WebView: how Bandcamp tracks play
 * (#513, desktop parachord#1231).
 *
 * The embed has no protocol and our page can't reach into a cross-origin iframe,
 * so it's the WebView's top-level page, and desktop's
 * `assets/js/bandcamp-embed-control.js` runs in it, driving the page's `<audio>`.
 * Bandcamp's own scripts run at that top level, so this WebView has **no
 * JavascriptInterface**: the poll asks `bcState()` from outside, and what comes
 * back goes through [BandcampEmbedTracker], which trusts none of it. Only fixed
 * scripts run, and only while the page is exactly the URL asked for.
 *
 * Background playback is the Apple Music and SoundCloud setup: never added to a
 * window, `LAYER_TYPE_NONE`, no user gesture needed for media, [keepAlive] before
 * each track, and the controller's silent ExoPlayer loop.
 */
class BandcampEmbedBridge constructor(
    private val context: Context,
) {
    companion object {
        private const val TAG = "BandcampEmbed"
        private const val CONTROL_SCRIPT_ASSET = "js/bandcamp-embed-control.js"

        /** A Main thread deferred with the screen off doesn't hold the poll up. */
        private const val EVALUATE_TIMEOUT_MS = 2_000L

        /** The tracker's limit is 15 s; this backstop covers a poll that stopped. */
        private const val LOAD_TIMEOUT_MS = 20_000L

        private val json = Json { ignoreUnknownKeys = true }

        /** evaluateJavascript hands back JSON: a string result arrives encoded a second time. */
        internal fun unwrap(result: String?): String? = try {
            if (result == null || result == "null") {
                null
            } else {
                (json.parseToJsonElement(result) as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
        } catch (e: Exception) {
            null
        }
    }

    sealed class LoadResult {
        data object Started : LoadResult()
        data class Refused(val reason: String) : LoadResult()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controlScript by lazy { context.assets.open(CONTROL_SCRIPT_ASSET).bufferedReader().use { it.readText() } }

    private var webView: WebView? = null
    private var pollJob: Job? = null

    /**
     * The URL whose load last finished. Until the page asked for has, the old one
     * can still be up: replaying a track is the same URL, and its moved position
     * would read as "started" while the new page never gets its click.
     */
    @Volatile private var loadedUrl: String? = null
    @Volatile private var tracker: BandcampEmbedTracker? = null
    @Volatile private var pendingLoad: CompletableDeferred<LoadResult>? = null

    val isPlaying: Boolean get() = tracker?.isPlaying == true
    val positionMs: Long get() = ((tracker?.positionSeconds ?: 0.0) * 1000).toLong()
    val durationMs: Long get() = ((tracker?.durationSeconds ?: 0.0) * 1000).toLong()

    /** Called (on a background thread) when the current track finishes. */
    @Volatile var onFinished: (() -> Unit)? = null

    /**
     * Load [embedUrl] (from [BandcampEmbed.embedUrl]) and start it at [volume]
     * (0..1). Returns once the position has advanced, or [LoadResult.Refused]
     * when it doesn't.
     */
    suspend fun load(embedUrl: String, volume: Float): LoadResult {
        stop()
        val load = BandcampEmbedTracker(System.currentTimeMillis())
        tracker = load
        val result = CompletableDeferred<LoadResult>()
        pendingLoad = result
        withContext(Dispatchers.Main) {
            val view = ensureWebView()
            // Keep the WebView running while the app is in the background.
            view.onResume()
            view.resumeTimers()
            loadedUrl = null
            view.loadUrl(embedUrl)
        }
        pollJob = scope.launch { pollLoop(load, embedUrl, volume, result) }
        val outcome = withTimeoutOrNull(LOAD_TIMEOUT_MS) { result.await() } ?: LoadResult.Refused("unplayable")
        if (pendingLoad === result) pendingLoad = null
        if (outcome is LoadResult.Refused && tracker === load) stop()
        return outcome
    }

    private suspend fun pollLoop(
        load: BandcampEmbedTracker,
        embedUrl: String,
        volume: Float,
        result: CompletableDeferred<LoadResult>,
    ) {
        var installed = false
        var clicked = false
        var reportedFinish = false
        while (scope.isActive && tracker === load) {
            var raw: String? = null
            // Only once it has loaded, and while it's exactly the URL asked for.
            if (loadedUrl == embedUrl && withContext(Dispatchers.Main) { webView?.url } == embedUrl) {
                if (!installed) {
                    evaluate(controlScript)
                    installed = true
                }
                if (!clicked) {
                    evaluate(BandcampEmbed.volumeScript(volume.toDouble()))
                    val answer = evaluate(BandcampEmbed.START_SCRIPT)
                    clicked = answer == "true"
                    // Neither true nor false: the script landed on the page before this one.
                    if (answer != "true" && answer != "false") installed = false
                }
                if (clicked) {
                    raw = unwrap(evaluate(BandcampEmbed.STATE_SCRIPT))
                    // A reloaded page needs the control script again.
                    if (raw == null) installed = false
                }
            }
            load.update(raw, System.currentTimeMillis())
            if (load.started) result.complete(LoadResult.Started)
            if (load.refused) result.complete(LoadResult.Refused("unplayable"))
            if (load.finished && !reportedFinish) {
                reportedFinish = true
                Log.d(TAG, "finish at ${positionMs}ms of ${durationMs}ms")
                onFinished?.invoke()
            }
            delay(BandcampEmbed.POLL_MS)
        }
    }

    suspend fun play() {
        tracker?.userSetPaused(false, System.currentTimeMillis())
        evaluate(BandcampEmbed.PLAY_SCRIPT)
    }

    suspend fun pause() {
        tracker?.userSetPaused(true, System.currentTimeMillis())
        evaluate(BandcampEmbed.PAUSE_SCRIPT)
    }

    suspend fun seekTo(positionMs: Long) {
        tracker?.seeked(positionMs / 1000.0)
        evaluate(BandcampEmbed.seekScript(positionMs / 1000.0))
    }

    /** Halt the track: leaving the page stops it, nothing is left paused. */
    suspend fun stop() {
        tracker = null
        pollJob?.cancel()
        pollJob = null
        pendingLoad?.complete(LoadResult.Refused("superseded"))
        pendingLoad = null
        withContext(Dispatchers.Main) { webView?.loadUrl("about:blank") }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView = webView ?: WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        setLayerType(View.LAYER_TYPE_NONE, null)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                loadedUrl = url
            }

            /** Only Bandcamp: the page is third party, and nothing here should wander. */
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val host = request?.url?.host ?: return true
                if (BandcampEmbed.isAllowedHost(host)) return false
                Log.w(TAG, "Blocked navigation to $host")
                return true
            }
        }
        webView = this
    }

    /** The script's result as WebView reports it; null when the Main thread didn't get to it in time. */
    private suspend fun evaluate(script: String): String? = withTimeoutOrNull(EVALUATE_TIMEOUT_MS) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val view = webView
                if (view == null) {
                    continuation.resume(null)
                } else {
                    view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value) }
                }
            }
        }
    }
}
