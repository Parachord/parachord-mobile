package com.parachord.shared.plugin

import com.parachord.shared.platform.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "PluginManager"

/**
 * Central wrapper for .axe plugin loading and execution.
 *
 * Reads .axe plugins from:
 * 1. Bundled plugins (packaged with the app)
 * 2. Cached plugins (downloaded from marketplace)
 *
 * Deduplicates by semver (higher version wins), loads into the JS
 * ResolverLoader instance, and provides type-safe Kotlin methods for
 * search/resolve/AI/scrobble calls.
 */
class PluginManager constructor(
    private val fileAccess: PluginFileAccess,
    private val jsRuntime: JsRuntime,
) {
    @Serializable
    data class PluginInfo(
        val id: String,
        val name: String,
        val version: String,
        val type: String = "",
        val icon: String = "",
        val color: String = "",
        val capabilities: Map<String, Boolean> = emptyMap(),
    )

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    /** Plugins loaded and active on this platform — filtered by capabilities.mobile. */
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    private val _allLoadedPlugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    /**
     * All plugins parsed from disk including ones filtered out by platform
     * capabilities (e.g. `mobile: false`). Used by [PluginSyncService] for
     * version comparison so it doesn't keep re-downloading filtered plugins.
     */
    val allLoadedPlugins: StateFlow<List<PluginInfo>> = _allLoadedPlugins.asStateFlow()

    private val initMutex = Mutex()
    private var initialized = false

    private val json = Json { ignoreUnknownKeys = true }

    // ── Initialization ───────────────────────────────────────────────

    /** Initialize the plugin system. Idempotent — safe to call multiple times. */
    suspend fun ensureInitialized() {
        initMutex.withLock {
            if (initialized) return@withLock

            // Initialize JS runtime (waits for bootstrap + resolver-loader.js to load)
            jsRuntime.initialize()
            jsRuntime.ready.first { it }

            loadPlugins()
            initialized = true
            Log.d(TAG, "Plugin system initialized with ${_plugins.value.size} plugins")
        }
    }

    /**
     * Hot-reload all plugins. Called after marketplace sync downloads new versions.
     * Unloads all plugins from resolver-loader.js and re-loads from disk.
     */
    suspend fun reloadPlugins() {
        initMutex.withLock {
            if (!initialized) return@withLock
            // Unload all existing plugins
            jsRuntime.evaluate("(async () => { const ids = window.__resolverLoader.getAllResolvers().map(r => r.id); for (const id of ids) await window.__resolverLoader.unloadResolver(id); })()")
            loadPlugins()
            Log.d(TAG, "Plugins reloaded: ${_plugins.value.size} plugins")
        }
    }

    // ── Plugin Loading ───────────────────────────────────────────────

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun loadPlugins() {
        val bundled = readBundledPlugins()
        val cached = readCachedPlugins()

        // Deduplicate by semver — higher version wins
        val all = mutableMapOf<String, Pair<String, String>>() // id → (version, axeJson)
        for ((id, version, axeJson) in bundled + cached) {
            val existing = all[id]
            if (existing == null || compareSemver(version, existing.first) > 0) {
                all[id] = version to axeJson
            }
        }

        Log.d(TAG, "Loading ${all.size} plugins (${bundled.size} bundled, ${cached.size} cached)")

        // Load into resolver-loader.js
        val pluginInfos = mutableListOf<PluginInfo>()
        for ((id, pair) in all) {
            val (_, axeJson) = pair
            try {
                // Base64-encode the .axe JSON to avoid escaping issues with
                // backticks, $, quotes, and newlines in the embedded JS code.
                val b64 = Base64.encode(axeJson.encodeToByteArray())

                jsRuntime.evaluate("""
                    window.__lastPluginResult = 'pending';
                    window.__resolverLoader.loadResolver(JSON.parse(atob('$b64')))
                        .then(function() { window.__lastPluginResult = 'ok'; })
                        .catch(function(e) { window.__lastPluginResult = 'error: ' + e.message; });
                """.trimIndent())

                // Give the promise a moment to resolve (loadResolver is fast — just JSON parsing)
                delay(50)

                val result = jsRuntime.evaluate("window.__lastPluginResult")
                val cleanResult = result?.removeSurrounding("\"")
                if (cleanResult == "ok") {
                    val info = parsePluginInfo(axeJson)
                    if (info != null) pluginInfos.add(info)
                } else {
                    Log.w(TAG, "Failed to load plugin '$id': $cleanResult")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception loading plugin '$id': ${e.message}", e)
            }
        }

        // Publish the full unfiltered set for version-comparison purposes.
        _allLoadedPlugins.value = pluginInfos.sortedBy { it.name }

        // Filter out plugins that declare mobile: false in capabilities for the
        // active runtime set that drives UI and routing.
        val platformFiltered = pluginInfos.filter { it.capabilities["mobile"] != false }
        _plugins.value = platformFiltered.sortedBy { it.name }
        if (pluginInfos.size != platformFiltered.size) {
            val hidden = pluginInfos.filter { it.capabilities["mobile"] == false }.map { it.id }
            Log.d(TAG, "Hidden ${hidden.size} plugins not supported on mobile: $hidden")
        }

        // Invoke init() on plugins that need it. Per desktop CLAUDE.md
        // `## Achordion Pre-resolution Plugin`, plugins with the
        // `playbackTelemetry: true` capability self-register with
        // `window.scrobbleManager` inside their `init()` function — without
        // this call, they're loaded into the resolver-loader's registry but
        // never run, so dispatch to JS plugins from `ScrobbleManager.kt`
        // hits an empty plugin list. Mirrors desktop's capability-filtered
        // initResolver() invocation in `initResolvers()` (the
        // withGenerate/withChat/withConcerts pattern).
        //
        // Resolver-side native-first plugins (spotify, applemusic, etc.)
        // intentionally don't init their .axe form — Android calls them via
        // native Kotlin paths. AI plugin .axe init isn't needed either
        // (native ChatGPT/Claude/Gemini providers handle the calls).
        // PlaybackTelemetry plugins like achordion are the load-bearing
        // case: they MUST init to register their hooks.
        for (info in platformFiltered) {
            if (info.capabilities["playbackTelemetry"] == true) {
                try {
                    jsRuntime.evaluate(
                        "window.__resolverLoader && window.__resolverLoader.initResolver('${info.id}', {})",
                    )
                    Log.d(TAG, "Initialized playback-telemetry plugin: ${info.id}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to init plugin '${info.id}': ${e.message}")
                }
            }
        }
    }

    private data class PluginEntry(val id: String, val version: String, val axeJson: String)

    private fun readBundledPlugins(): List<PluginEntry> {
        val entries = mutableListOf<PluginEntry>()
        try {
            val files = fileAccess.listBundledPlugins()
            for (filename in files) {
                if (!filename.endsWith(".axe")) continue
                try {
                    val axeJson = fileAccess.readBundledPlugin(filename)
                    val info = parsePluginInfo(axeJson)
                    if (info != null) {
                        entries.add(PluginEntry(info.id, info.version, axeJson))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read bundled plugin '$filename': ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list bundled plugins: ${e.message}", e)
        }
        return entries
    }

    private fun readCachedPlugins(): List<PluginEntry> {
        val entries = mutableListOf<PluginEntry>()
        try {
            val files = fileAccess.listCachedPlugins()
            for (filename in files) {
                if (!filename.endsWith(".axe")) continue
                try {
                    val axeJson = fileAccess.readCachedPlugin(filename)
                    val info = parsePluginInfo(axeJson)
                    if (info != null) {
                        entries.add(PluginEntry(info.id, info.version, axeJson))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read cached plugin '$filename': ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list cached plugins: ${e.message}", e)
        }
        return entries
    }

    // ── Raw JS Evaluation ──────────────────────────────────────────────

    /**
     * Evaluate a raw JS script on the plugin runtime.
     * Used by AxeScrobbler and other components that need direct JS access.
     */
    suspend fun evaluateJs(script: String): String? {
        ensureInitialized()
        return jsRuntime.evaluate(script)?.unquote()
    }

    // ── Resolver Calls ───────────────────────────────────────────────

    suspend fun search(resolverId: String, query: String): String? {
        ensureInitialized()
        val escaped = escapeForJs(query)
        return jsRuntime.evaluate("""
            (async () => {
                const r = window.__resolverLoader.getResolver('$resolverId');
                if (!r || !r.search) return JSON.stringify([]);
                const results = await r.search('$escaped', r.config || {});
                return JSON.stringify(results || []);
            })()
        """.trimIndent())?.unquote()
    }

    suspend fun resolve(resolverId: String, artist: String, title: String, album: String?): String? {
        ensureInitialized()
        val artistEsc = escapeForJs(artist)
        val titleEsc = escapeForJs(title)
        val albumEsc = if (album != null) "'${escapeForJs(album)}'" else "null"
        return jsRuntime.evaluate("""
            (async () => {
                const r = window.__resolverLoader.getResolver('$resolverId');
                if (!r || !r.resolve) return null;
                const result = await r.resolve('$artistEsc', '$titleEsc', $albumEsc, r.config || {});
                return result ? JSON.stringify(result) : null;
            })()
        """.trimIndent())?.unquote()
    }

    suspend fun lookupUrl(url: String): String? {
        ensureInitialized()
        val escaped = escapeForJs(url)
        return jsRuntime.evaluate("""
            (async () => {
                const result = await window.__resolverLoader.lookupUrl('$escaped');
                return result ? JSON.stringify(result) : null;
            })()
        """.trimIndent())?.unquote()
    }

    // ── AI Provider Calls ────────────────────────────────────────────

    suspend fun aiGenerate(pluginId: String, prompt: String, configJson: String, contextJson: String?): String? {
        ensureInitialized()
        val promptEsc = escapeForJs(prompt)
        val ctxArg = if (contextJson != null) "JSON.parse(`${escapeForJs(contextJson)}`)" else "null"
        return jsRuntime.evaluate("""
            (async () => {
                const r = window.__resolverLoader.getResolver('$pluginId');
                if (!r || !r.generate) return null;
                const config = JSON.parse(`${escapeForJs(configJson)}`);
                const result = await r.generate('$promptEsc', config, $ctxArg);
                return JSON.stringify(result);
            })()
        """.trimIndent())?.unquote()
    }

    suspend fun aiChat(pluginId: String, messagesJson: String, toolsJson: String, configJson: String): String? {
        ensureInitialized()
        return jsRuntime.evaluate("""
            (async () => {
                const r = window.__resolverLoader.getResolver('$pluginId');
                if (!r || !r.chat) return null;
                const messages = JSON.parse(`${escapeForJs(messagesJson)}`);
                const tools = JSON.parse(`${escapeForJs(toolsJson)}`);
                const config = JSON.parse(`${escapeForJs(configJson)}`);
                const result = await r.chat(messages, tools, config);
                return JSON.stringify(result);
            })()
        """.trimIndent())?.unquote()
    }

    suspend fun listModels(pluginId: String, configJson: String): String? {
        ensureInitialized()
        return jsRuntime.evaluate("""
            (async () => {
                const r = window.__resolverLoader.getResolver('$pluginId');
                if (!r || !r.listModels) return null;
                const config = JSON.parse(`${escapeForJs(configJson)}`);
                const result = await r.listModels(config);
                return JSON.stringify(result);
            })()
        """.trimIndent())?.unquote()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Parse and validate .axe JSON content. Returns null (with a warning log)
     * if the content isn't valid JSON, doesn't have the expected shape, or
     * has a suspicious/missing manifest ID.
     * security: M7
     */
    private fun parsePluginInfo(axeJson: String): PluginInfo? {
        return try {
            val axe = json.decodeFromString<AxeFile>(axeJson)
            val m = axe.manifest
            // Validate required fields (security: M7)
            if (m.id.isBlank()) {
                Log.w(TAG, "Rejecting .axe with blank manifest.id")
                return null
            }
            if (!Regex("^[A-Za-z0-9_-]+$").matches(m.id)) {
                Log.w(TAG, "Rejecting .axe with invalid manifest.id: '${m.id}'")
                return null
            }
            PluginInfo(
                id = m.id,
                name = m.name ?: m.id,
                version = m.version ?: "0.0.0",
                type = m.type ?: "",
                icon = m.icon ?: "",
                color = m.color ?: "",
                capabilities = axe.capabilities ?: emptyMap(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse .axe manifest: ${e.message}", e)
            null
        }
    }

    /** Compare two semver strings. Returns >0 if a > b, <0 if a < b, 0 if equal. */
    fun compareSemver(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va - vb
        }
        return 0
    }

    /** Escape a string for safe embedding in JS template literals (backtick strings). */
    private fun escapeForJs(s: String): String =
        s.replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\$", "\\\$")
            .replace("'", "\\'")

    /** Strip outer quotes from evaluateJavascript results. */
    private fun String.unquote(): String {
        if (startsWith("\"") && endsWith("\"") && length >= 2) {
            return substring(1, length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
        }
        return this
    }

    // ── .axe file parsing models ─────────────────────────────────────

    @Serializable
    private data class AxeFile(
        val manifest: AxeManifest,
        val capabilities: Map<String, Boolean>? = null,
    )

    @Serializable
    private data class AxeManifest(
        val id: String,
        val name: String? = null,
        val version: String? = null,
        val type: String? = null,
        val icon: String? = null,
        val color: String? = null,
    )
}
