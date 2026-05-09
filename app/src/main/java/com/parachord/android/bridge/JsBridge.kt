package com.parachord.android.bridge

import android.annotation.SuppressLint
import android.content.Context
import com.parachord.shared.platform.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.parachord.android.data.metadata.MbidEnrichmentService
import com.parachord.shared.plugin.JsRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private const val TAG = "JsBridge"

/**
 * Android [JsRuntime] implementation using a headless WebView.
 *
 * Executes .axe resolver plugins and core business logic (resolver-loader,
 * AI providers, scrobblers) unchanged from the desktop Electron app.
 *
 * Implements [JsRuntime] so the plugin system (PluginManager) depends on
 * the interface, not the Android-specific WebView — enabling future KMP
 * migration where iOS would use JavaScriptCore instead.
 */
class JsBridge constructor(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val dataStore: DataStore<Preferences>,
    private val mbidEnrichment: MbidEnrichmentService,
) : JsRuntime {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var webView: WebView? = null

    /**
     * Initialize the JS runtime: create headless WebView, load bootstrap HTML
     * (polyfills + resolver-loader.js), and wait for the page to finish loading.
     *
     * Idempotent — safe to call multiple times.
     */
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun initialize() = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext

        val pageLoaded = CompletableDeferred<Unit>()
        val nativeBridge = NativeBridge(httpClient, scope, dataStore, mbidEnrichment)

        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(nativeBridge, "NativeBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "Bootstrap page loaded: $url")
                    pageLoaded.complete(Unit)
                }
            }
        }
        webView = wv
        nativeBridge.webView = wv // For async fetch callbacks

        // Load bootstrap HTML (polyfills + resolver-loader.js)
        wv.loadUrl("file:///android_asset/js/bootstrap.html")

        // Wait for the page to actually finish loading before marking ready.
        // The old code set _ready = true immediately after loadUrl(), which
        // meant evaluate() calls could fire before resolver-loader.js was available.
        pageLoaded.await()
        _ready.value = true
        Log.d(TAG, "JS runtime ready")
    }

    /**
     * Evaluate a JavaScript expression and return the string result.
     *
     * For async expressions, wrap in an IIFE:
     * ```
     * evaluate("(async () => { ... return JSON.stringify(result); })()")
     * ```
     */
    override suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main) {
        val wv = webView
        if (wv == null) {
            Log.w(TAG, "evaluate() called but WebView not initialized")
            return@withContext null
        }
        val deferred = CompletableDeferred<String?>()
        wv.evaluateJavascript(script) { result ->
            // evaluateJavascript returns "null" (string) for null/undefined results
            deferred.complete(if (result == "null" || result == null) null else result)
        }
        deferred.await()
    }

    override fun teardown() {
        webView?.destroy()
        webView = null
        _ready.value = false
        Log.d(TAG, "JS runtime torn down")
    }
}
