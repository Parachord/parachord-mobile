package com.parachord.android.bridge

import com.parachord.shared.platform.Log
import android.webkit.JavascriptInterface
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.parachord.android.data.metadata.MbidEnrichmentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "NativeBridge"

/** 36-char MusicBrainz Identifier (8-4-4-4-12 hex with dashes). Plugins
 *  validate `track.mbid` against this same shape; we mirror the regex
 *  here so [NativeBridge.resolveMbidForLove] can short-circuit when JS
 *  already has a valid MBID. */
private val MBID_PATTERN = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\$", RegexOption.IGNORE_CASE)

/**
 * Native module bindings exposed to JavaScript via @JavascriptInterface.
 *
 * Provides fetch, storage, logging, and MBID resolution to the JS
 * runtime so .axe resolver plugins, AI providers, and the achordion
 * playback-telemetry plugin can call back into native code.
 *
 * These methods are called synchronously from the WebView thread —
 * they must return without suspending (hence [runBlocking] for storage reads).
 * Long-running calls (HTTP fetches, MBID lookups) use callback patterns
 * keyed by a JS-generated id, with the result delivered back via
 * `WebView.evaluateJavascript` on the main thread.
 */
class NativeBridge(
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val dataStore: DataStore<Preferences>,
    private val mbidEnrichment: MbidEnrichmentService,
) {
    /** WebView reference for async fetch callbacks. Set by JsBridge after creation. */
    var webView: android.webkit.WebView? = null
    /** HTTP client with limited redirects and shorter timeouts for plugin fetches.
     *  YouTube's consent pages create redirect loops that block the JS thread. */
    private val pluginHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // ── Fetch ────────────────────────────────────────────────────────

    /**
     * HTTP GET fetch for backward compatibility.
     */
    @JavascriptInterface
    fun fetch(url: String, headersJson: String): String {
        return fetchWithOptions(url, "GET", headersJson, "")
    }

    /**
     * Full HTTP fetch supporting GET, POST, PUT, DELETE with headers and body.
     * Returns a JSON envelope: {"status": 200, "ok": true, "body": "..."}
     */
    @JavascriptInterface
    fun fetchWithOptions(url: String, method: String, headersJson: String, body: String): String {
        return try {
            val requestBuilder = Request.Builder().url(url)

            // Parse and apply headers
            if (headersJson.isNotBlank() && headersJson != "{}") {
                try {
                    val cleaned = headersJson.trim().removePrefix("{").removeSuffix("}")
                    if (cleaned.isNotBlank()) {
                        cleaned.split(",").forEach { pair ->
                            val parts = pair.split(":", limit = 2)
                            if (parts.size == 2) {
                                val key = parts[0].trim().removeSurrounding("\"")
                                val value = parts[1].trim().removeSurrounding("\"")
                                if (key.isNotBlank()) requestBuilder.header(key, value)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse headers: $headersJson", e)
                }
            }

            // Set method and body
            val requestBody = when (method.uppercase()) {
                "POST", "PUT", "PATCH" -> body.toRequestBody("application/json".toMediaType())
                "DELETE" -> if (body.isNotBlank()) body.toRequestBody("application/json".toMediaType()) else null
                else -> null
            }
            requestBuilder.method(method.uppercase(), requestBody)

            val response = pluginHttpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val statusCode = response.code
            val isOk = response.isSuccessful

            """{"status":$statusCode,"ok":$isOk,"body":${escapeJsonString(responseBody)}}"""
        } catch (e: Exception) {
            Log.e(TAG, "fetch error ($method $url): ${e.message}")
            """{"status":0,"ok":false,"body":${escapeJsonString(e.message ?: "Network error")}}"""
        }
    }

    // ── Async Fetch ────────────────────────────────────────────────────

    /**
     * Non-blocking fetch that returns immediately and invokes a JS callback
     * when the HTTP response arrives. This frees the WebView JS thread to
     * process other .axe plugin calls concurrently.
     *
     * Called from bootstrap.html's `window.fetch` polyfill. The JS side
     * creates a Promise with a unique callbackId and registers a resolver
     * in `window.__fetchCallbacks[id]`. When the response arrives, we call
     * `window.__fetchCallbacks[id](envelope)` from the main thread.
     */
    @JavascriptInterface
    fun fetchAsync(callbackId: String, url: String, method: String, headersJson: String, body: String) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val envelope = try {
                val requestBuilder = Request.Builder().url(url)

                // Parse headers
                if (headersJson.isNotBlank() && headersJson != "{}") {
                    try {
                        val cleaned = headersJson.trim().removePrefix("{").removeSuffix("}")
                        if (cleaned.isNotBlank()) {
                            cleaned.split(",").forEach { pair ->
                                val parts = pair.split(":", limit = 2)
                                if (parts.size == 2) {
                                    val key = parts[0].trim().removeSurrounding("\"")
                                    val value = parts[1].trim().removeSurrounding("\"")
                                    if (key.isNotBlank()) requestBuilder.header(key, value)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                val requestBody = when (method.uppercase()) {
                    "POST", "PUT", "PATCH" -> body.toRequestBody("application/json".toMediaType())
                    "DELETE" -> if (body.isNotBlank()) body.toRequestBody("application/json".toMediaType()) else null
                    else -> null
                }
                requestBuilder.method(method.uppercase(), requestBody)

                val response = pluginHttpClient.newCall(requestBuilder.build()).execute()
                val responseBody = response.body?.string() ?: ""
                """{"status":${response.code},"ok":${response.isSuccessful},"body":${escapeJsonString(responseBody)}}"""
            } catch (e: Exception) {
                Log.e(TAG, "fetchAsync error ($method $url): ${e.message}")
                """{"status":0,"ok":false,"body":${escapeJsonString(e.message ?: "Network error")}}"""
            }

            // Deliver result back to JS on the main thread
            val escapedEnvelope = envelope.replace("\\", "\\\\").replace("'", "\\'")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                webView?.evaluateJavascript(
                    "window.__fetchCallbacks && window.__fetchCallbacks['$callbackId'] && window.__fetchCallbacks['$callbackId']('$escapedEnvelope')",
                    null,
                )
            }
        }
    }

    // ── MBID Resolver (for window.resolveMbidForLove) ─────────────────

    /**
     * Async MBID lookup. JS calls this with a `callbackId` (registered
     * in `window.__resolveMbidCallbacks`) and a `trackJson` containing
     * at minimum `{ title, artist, mbid? }`. We dispatch to a coroutine
     * that:
     *
     * 1. If `track.mbid` is already a 36-char UUID, returns it directly.
     * 2. Otherwise, calls [MbidEnrichmentService.getRecordingMbid] which
     *    hits the disk cache first, then ListenBrainz's MBID Mapper at
     *    its confidence threshold.
     * 3. JS-callbacks the result (or null) on the main thread via
     *    `window.__resolveMbidCallbacks[callbackId](mbid)`.
     *
     * Used by the achordion plugin's `resolveMbid(track)` fallback when
     * `track.mbid` isn't present. Without this surface, the plugin
     * silently skips submission for any track lacking an upstream MBID.
     *
     * Pattern mirrors [fetchAsync] — the same callback-id-keyed harness.
     */
    @JavascriptInterface
    fun resolveMbidForLove(callbackId: String, trackJson: String) {
        scope.launch {
            val mbid = try {
                val parsed = Json.parseToJsonElement(trackJson).jsonObject
                val existingMbid = parsed["mbid"]?.jsonPrimitive?.contentOrNull
                if (existingMbid != null && MBID_PATTERN.matches(existingMbid)) {
                    existingMbid
                } else {
                    val title = parsed["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    val artist = parsed["artist"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    if (title != null && artist != null) {
                        mbidEnrichment.getRecordingMbid(artist, title)
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolveMbidForLove($callbackId) failed: ${e.message}")
                null
            }

            // Deliver result back to JS on the main thread. Pass either the
            // MBID string or `null` (literal — JS treats it as a null arg).
            val payload = if (mbid != null) "'${mbid.replace("'", "\\'")}'" else "null"
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                webView?.evaluateJavascript(
                    "window.__resolveMbidCallbacks && window.__resolveMbidCallbacks['$callbackId'] && window.__resolveMbidCallbacks['$callbackId']($payload)",
                    null,
                )
            }
        }
    }

    // ── Logging ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun log(level: String, message: String) {
        when (level) {
            "error" -> Log.e(TAG, message)
            "warn" -> Log.w(TAG, message)
            "info" -> Log.i(TAG, message)
            else -> Log.d(TAG, message)
        }
    }

    // ── Storage ──────────────────────────────────────────────────────

    /**
     * Allowed key prefix for plugin storage. All keys MUST start with "plugin."
     * followed by the plugin ID and a dot. This prevents plugins from reading
     * the app's own DataStore keys (OAuth tokens, AI API keys, session
     * credentials, etc.) which share the same DataStore instance.
     *
     * security: C3 — namespace NativeBridge storage per plugin
     */
    private val PLUGIN_KEY_PREFIX = "plugin."

    private fun isAllowedStorageKey(key: String): Boolean =
        key.startsWith(PLUGIN_KEY_PREFIX) &&
            key.indexOf('.', PLUGIN_KEY_PREFIX.length) > PLUGIN_KEY_PREFIX.length

    /**
     * Read a value from DataStore. Synchronous (required by @JavascriptInterface).
     * Uses [runBlocking] — acceptable since DataStore reads from memory cache.
     *
     * Only keys starting with "plugin.<id>." are allowed. Attempts to read
     * app-level keys (tokens, credentials) return null.
     */
    @JavascriptInterface
    fun storageGet(key: String): String? {
        if (!isAllowedStorageKey(key)) {
            Log.w(TAG, "storageGet blocked: key '$key' is not in the plugin namespace")
            return null
        }
        return try {
            runBlocking {
                val prefs = dataStore.data.first()
                prefs[stringPreferencesKey(key)]
            }
        } catch (e: Exception) {
            Log.w(TAG, "storageGet($key) failed: ${e.message}")
            null
        }
    }

    /**
     * Write a value to DataStore asynchronously.
     *
     * Only keys starting with "plugin.<id>." are allowed. Attempts to write
     * app-level keys are silently rejected.
     */
    @JavascriptInterface
    fun storageSet(key: String, value: String) {
        if (!isAllowedStorageKey(key)) {
            Log.w(TAG, "storageSet blocked: key '$key' is not in the plugin namespace")
            return
        }
        scope.launch {
            try {
                dataStore.edit { it[stringPreferencesKey(key)] = value }
            } catch (e: Exception) {
                Log.w(TAG, "storageSet($key) failed: ${e.message}")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u${c.code.toString(16).padStart(4, '0')}") else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
