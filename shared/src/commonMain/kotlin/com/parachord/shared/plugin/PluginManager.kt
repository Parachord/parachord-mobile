package com.parachord.shared.plugin

import com.parachord.shared.platform.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "PluginManager"
private const val LOOKUP_POLL_ATTEMPTS = 60
private const val LOOKUP_POLL_INTERVAL_MS = 100L
/** Album search/lookup polls a little past the catalog loader's 8s step cap, so
 *  the loader's timeout (not the poll) decides when a slow catalog gives up. */
private const val ALBUM_POLL_ATTEMPTS = 85
/** A recommendation provider gets 20 s, desktop's `CACHE_TTL.recommendationProvider`:
 *  nobody waits on it, and ListenBrainz's own retries can take several seconds. */
private const val RECOMMENDATION_POLL_ATTEMPTS = 200

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
    /** This app's version and platform key (`android`/`ios`), for [PluginCompat]. */
    private val appVersion: String = "",
    private val platform: String = "",
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
        /** The plugin's `settings.configurable` fields, in manifest order (plan parachord-mobile#664). */
        val configFields: List<PluginConfigField> = emptyList(),
        /** `settings.requiresAuth`: the plugin needs its config set before it can answer. */
        val requiresAuth: Boolean = false,
    )

    /** One `settings.configurable` field of a plugin manifest, rendered by the settings screen. */
    @Serializable
    data class PluginConfigField(
        val key: String,
        /** `text` or `password`. Passwords go to the secure store, never prefs. */
        val type: String,
        val label: String,
        val description: String? = null,
        val placeholder: String? = null,
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

    /**
     * Per-resolver config the host supplies, keyed by resolver id: the JSON
     * object a plugin receives as `config` (desktop's `getResolverConfig`).
     * `soundcloud.axe` needs `{ token }` for every call. Asked before each
     * plugin call (see [ensureInitialized]), so a refreshed token reaches the
     * plugin; a provider that refreshes should be cheap while the token is good.
     */
    var resolverConfigProvider: (suspend () -> Map<String, String>)? = null

    private val configMutex = Mutex()
    /** Config JSON last written into each loaded resolver; reset when plugins load. */
    private val appliedConfigs = mutableMapOf<String, String>()

    // ── Initialization ───────────────────────────────────────────────

    /**
     * Initialize the plugin system. Idempotent — safe to call multiple times.
     * Every plugin call goes through here first, so it also brings each
     * resolver's `config` up to date ([resolverConfigProvider]).
     */
    suspend fun ensureInitialized() {
        initPlugins()
        if (initialized) applyResolverConfigs()
    }

    private suspend fun initPlugins() {
        initMutex.withLock {
            if (initialized) return@withLock

            // Initialize JS runtime (waits for bootstrap + resolver-loader.js to load).
            // initialize() returns once the runtime is ready or has given up; if
            // it gave up, don't wait on `ready` (that waited forever, holding
            // this lock), leave `initialized` false so a later call retries,
            // and let plugin calls answer empty meanwhile.
            jsRuntime.initialize()
            if (!jsRuntime.ready.value) {
                Log.w(TAG, "JS runtime isn't ready; plugins unavailable for now")
                return@withLock
            }

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
            configuredMutex.withLock { configuredCache.clear() }
            Log.d(TAG, "Plugins reloaded: ${_plugins.value.size} plugins")
        }
        applyResolverConfigs()
    }

    /**
     * Write the host's config into each resolver it covers (`r.config`, which
     * every resolver-loader call falls back to). Skips the JS round-trip when
     * the config hasn't changed since it was last written.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun applyResolverConfigs() {
        val provider = resolverConfigProvider ?: return
        val configs = try {
            provider()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Resolver config provider failed: ${e.message}")
            return
        }
        configMutex.withLock {
            for ((id, configJson) in configs) {
                if (appliedConfigs[id] == configJson) continue
                val b64 = Base64.encode(configJson.encodeToByteArray())
                val result = jsRuntime.evaluate(
                    "(function() { var r = window.__resolverLoader && window.__resolverLoader.getResolver('${escapeForJs(id)}'); " +
                        "if (!r) return 'missing'; r.config = JSON.parse(atob('$b64')); return 'ok'; })()",
                )?.unquote()
                if (result == "ok") appliedConfigs[id] = configJson
            }
        }
    }

    // ── Plugin Loading ───────────────────────────────────────────────

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun loadPlugins() {
        // Freshly loaded resolvers start with an empty config.
        configMutex.withLock { appliedConfigs.clear() }
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
                    // A downloaded copy that needs a newer app is skipped, so the
                    // bundled copy stays in use (#484). It can be on disk after an
                    // app downgrade, or from before sync checked the floor. The
                    // bundled copy is never checked: it was built with this app.
                    val requires = info?.let { requiredAppVersion(axeJson) }
                    if (requires != null) {
                        Log.d(TAG, "Skipping cached ${info.id} v${info.version}: needs Parachord $requires, this is $appVersion")
                    } else if (info != null) {
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

    /**
     * A resolver plugin's `resolve(artist, title, album)`: the raw result JSON,
     * or null when the plugin answered with no match. Uses the unique-key poll:
     * a bare `(async () => …)()` evaluates to the unresolved Promise (`{}` on
     * Android) as soon as the plugin awaits a real fetch, so every plugin
     * resolve that searched came back empty.
     *
     * Throws when the plugin threw or didn't answer in time (#566): that's a
     * resolver that couldn't answer, not one with no match.
     */
    suspend fun resolve(resolverId: String, artist: String, title: String, album: String?): String? {
        ensureInitialized()
        // A plugin whose config isn't complete stays out of resolution (plan
        // parachord-mobile#664): it would only answer empty, recording a miss
        // for every track while the user hasn't set it up.
        if (!isPluginConfigured(resolverId)) {
            Log.d(TAG, "Plugin '$resolverId' isn't configured; skipping resolve")
            return null
        }
        val idEsc = escapeForJs(resolverId)
        val artistEsc = escapeForJs(artist)
        val titleEsc = escapeForJs(title)
        val albumEsc = if (album != null) "'${escapeForJs(album)}'" else "null"
        return pollAsync(
            "res",
            "await (async () => { const r = window.__resolverLoader.getResolver('$idEsc'); " +
                "if (!r || !r.resolve) return null; " +
                "return await r.resolve('$artistEsc', '$titleEsc', $albumEsc, r.config || {}); })()",
            attempts = LOOKUP_POLL_ATTEMPTS,
            throwOnFailure = true,
        )
    }

    /**
     * Registry-driven track URL lookup: the resolver whose `urlPatterns` claim
     * [url] runs its `lookupUrl`. Raw `{track, resolverId}` JSON, or null. Polled
     * like [lookupPlaylist]: the bare async IIFE this used to evaluate returned
     * the unresolved Promise (`[object Promise]` on iOS, `{}` on Android).
     */
    suspend fun lookupUrl(url: String): String? {
        ensureInitialized()
        return pollAsync(
            "lur",
            "await window.__resolverLoader.lookupUrl('${escapeForJs(url)}')",
            attempts = LOOKUP_POLL_ATTEMPTS,
        )
    }

    private var lookupCounter = 0

    /**
     * Registry-driven playlist resolution (#281): the resolver-loader finds the
     * resolver whose `urlPatterns` claim [url] and calls its `lookupPlaylist`.
     * Returns the raw `{playlist, resolverId}` JSON (caller parses), or null if no
     * resolver claims it / it has no `lookupPlaylist` / the lookup fails. This is
     * what makes any resolver "just work" for playlist URLs — no per-provider code.
     *
     * Uses the unique-key POLL (not a bare `(async()=>)()`, which yields the
     * unresolved Promise serialized as `{}` once a real async `fetch` is awaited).
     */
    suspend fun lookupPlaylist(url: String): String? {
        ensureInitialized()
        val key = "lpl_${lookupCounter++}"
        val escaped = escapeForJs(url)
        jsRuntime.evaluate(
            """
            (function() {
                window.__resolveResults = window.__resolveResults || {};
                window.__resolveResults['$key'] = 'pending';
                (async () => {
                    try {
                        var result = await window.__resolverLoader.lookupPlaylist('$escaped');
                        window.__resolveResults['$key'] = result ? JSON.stringify(result) : 'null';
                    } catch (e) {
                        window.__resolveResults['$key'] = 'error:' + ((e && e.message) ? e.message : String(e));
                    }
                })();
            })();
            """.trimIndent(),
        )
        repeat(LOOKUP_POLL_ATTEMPTS) {
            delay(LOOKUP_POLL_INTERVAL_MS)
            val raw = jsRuntime.evaluate("window.__resolveResults['$key']")?.unquote()
            if (raw != null && raw != "pending") {
                jsRuntime.evaluate("delete window.__resolveResults['$key']; null")
                if (raw == "null" || raw.startsWith("error:")) return null
                return raw
            }
        }
        return null
    }

    // ── Album search (capabilities.albumSearch, #424) ──────────────────

    /**
     * Loaded resolvers that declare `capabilities.albumSearch` (and so implement
     * `searchAlbums` + `lookupAlbum`), as (id, name). Read from the manifests,
     * so a bundled plugin that predates the capability simply isn't listed
     * until the marketplace sync brings the newer version.
     */
    fun albumSearchResolvers(): List<Pair<String, String>> =
        plugins.value.filter { it.capabilities["albumSearch"] == true }.map { it.id to it.name }

    /**
     * `searchAlbums` on one resolver. Pass [artist] + [title] for an album
     * page's `{ artist, title }` query, or [text] for a free-text search.
     * Returns the raw JSON array, or null.
     */
    suspend fun searchAlbums(resolverId: String, artist: String?, title: String?, text: String?): String? {
        ensureInitialized()
        val query = if (text != null) {
            "'${escapeForJs(text)}'"
        } else {
            "{ artist: '${escapeForJs(artist.orEmpty())}', title: '${escapeForJs(title.orEmpty())}' }"
        }
        return pollAsync(
            "sal",
            "window.__resolverLoader.searchAlbums ? await window.__resolverLoader.searchAlbums('${escapeForJs(resolverId)}', $query) : []",
        )
    }

    /**
     * `lookupAlbum` on the resolver that found the album (not URL-pattern
     * routing: an album url can be private to its plugin). Raw
     * `{ album, resolverId }` JSON, or null.
     */
    suspend fun lookupAlbumFrom(resolverId: String, url: String): String? {
        ensureInitialized()
        return pollAsync(
            "lal",
            "window.__resolverLoader.lookupAlbumFrom ? await window.__resolverLoader.lookupAlbumFrom('${escapeForJs(resolverId)}', '${escapeForJs(url)}') : null",
        )
    }

    /**
     * The active resolvers that can supply an artist photo
     * (`capabilities.artistImage`, desktop parachord#1141), in priority order:
     * not disabled, in [active] when the user narrowed it (an empty list means
     * all), ordered by [order]. The artist-photo chain asks them in turn.
     */
    suspend fun artistImageResolverIds(active: List<String>, disabled: Set<String>, order: List<String>): List<String> {
        ensureInitialized()
        return plugins.value
            .filter { it.capabilities["artistImage"] == true }
            .map { it.id }
            .filter { it !in disabled && (active.isEmpty() || it in active) }
            .sortedBy { id -> order.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    /** One resolver's `searchArtistImage`: an image URL, or null on a miss or error. */
    suspend fun searchArtistImage(resolverId: String, artistName: String): String? {
        ensureInitialized()
        val raw = pollAsync(
            "sai",
            "window.__resolverLoader.searchArtistImage ? await window.__resolverLoader.searchArtistImage(" +
                "'${escapeForJs(resolverId)}', '${escapeForJs(artistName)}') : null",
        ) ?: return null
        // The poll returns the JSON-stringified answer: a quoted URL.
        return runCatching { Json.parseToJsonElement(raw).jsonPrimitive.contentOrNull }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    // ── Recommendations (capabilities.recommendations, #606) ───────────

    /**
     * Loaded plugins that can recommend music (`capabilities.recommendations`,
     * desktop parachord#1309), as (id, name) in [plugins] order (by name).
     * Content resolvers and meta-services alike.
     */
    suspend fun recommendationResolvers(): List<Pair<String, String>> {
        ensureInitialized()
        return plugins.value.filter { it.capabilities["recommendations"] == true }.map { it.id to it.name }
    }

    /**
     * One plugin's `getRecommendations(config)`, through the resolver-loader:
     * the raw JSON array of sections (`[]` is a miss). The plugin reads the
     * config the host wrote into it ([resolverConfigProvider]).
     *
     * Throws when the plugin threw or didn't answer in time: the contract says
     * a failed request throws and a miss is an empty answer, so a network blip
     * is never read as "this service has nothing for you".
     */
    suspend fun getRecommendations(resolverId: String): String {
        ensureInitialized()
        return pollAsync(
            "rec",
            "await window.__resolverLoader.getRecommendations('${escapeForJs(resolverId)}')",
            attempts = RECOMMENDATION_POLL_ATTEMPTS,
            throwOnFailure = true,
        ) ?: "[]"
    }

    /**
     * Run an async JS [expression] and return its JSON-stringified result via the
     * unique-key poll (a bare async IIFE yields the unresolved Promise on both
     * runtimes; see CLAUDE.md "Extensibility principle"). Null on null/error/timeout,
     * or, with [throwOnFailure], null only for a null answer and a throw for an
     * error or timeout.
     */
    private suspend fun pollAsync(
        prefix: String,
        expression: String,
        attempts: Int = ALBUM_POLL_ATTEMPTS,
        throwOnFailure: Boolean = false,
    ): String? {
        val key = "${prefix}_${lookupCounter++}"
        jsRuntime.evaluate(
            """
            (function() {
                window.__resolveResults = window.__resolveResults || {};
                window.__resolveResults['$key'] = 'pending';
                (async () => {
                    try {
                        var result = $expression;
                        window.__resolveResults['$key'] = result ? JSON.stringify(result) : 'null';
                    } catch (e) {
                        window.__resolveResults['$key'] = 'error:' + ((e && e.message) ? e.message : String(e));
                    }
                })();
            })();
            """.trimIndent(),
        )
        repeat(attempts) {
            delay(LOOKUP_POLL_INTERVAL_MS)
            val raw = jsRuntime.evaluate("window.__resolveResults['$key']")?.unquote()
            if (raw != null && raw != "pending") {
                jsRuntime.evaluate("delete window.__resolveResults['$key']; null")
                if (raw.startsWith("error:") && throwOnFailure) throw IllegalStateException("Plugin call failed: ${raw.removePrefix("error:")}")
                if (raw == "null" || raw.startsWith("error:")) return null
                return raw
            }
        }
        if (throwOnFailure) throw IllegalStateException("Plugin call didn't answer in time")
        return null
    }

    /** Loaded plugins that declare `capabilities.html5Stream`, as (id, name) — the resolvers whose playback the host owns (plan parachord-mobile#664). */
    fun html5StreamPlugins(): List<PluginInfo> = plugins.value.filter { it.capabilities["html5Stream"] == true }

    /** The loaded plugin behind [resolverId] when it declares `capabilities.html5Stream`, else null. */
    fun html5StreamPlugin(resolverId: String?): PluginInfo? =
        resolverId?.let { id -> plugins.value.find { it.id == id && it.capabilities["html5Stream"] == true } }

    /**
     * An `html5Stream` plugin's `getStreamUrl(track, config)` (plan
     * parachord-mobile#664): the plain stream URL the host plays for [trackJson]
     * — a JSON object shaped like the plugin's own results (`{ "subsonicId": … }`) —
     * or null when the plugin can't build one (not configured).
     *
     * The returned URL carries the plugin's credentials in its query (a
     * Subsonic token+salt pair replays until the password changes), so it
     * lives for THIS play only: never persist it on the track, in the resolver
     * cache, the saved queue, sync, or a log line.
     *
     * Throws when the plugin threw or didn't answer in time (#566): that's a
     * resolver that couldn't answer, not one with no URL.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun getStreamUrl(resolverId: String, trackJson: String): String? {
        ensureInitialized()
        val b64 = Base64.encode(trackJson.encodeToByteArray())
        val raw = pollAsync(
            "gsu",
            // One expression — pollAsync splices this into `var result = <expression>`, so a
            // statement list here is a syntax error and the call never answers.
            "await (async () => { const r = window.__resolverLoader.getResolver('${escapeForJs(resolverId)}'); " +
                "if (!r || !r.getStreamUrl) return null; " +
                "return await r.getStreamUrl(JSON.parse(atob('$b64')), r.config || {}); })()",
            attempts = LOOKUP_POLL_ATTEMPTS,
            throwOnFailure = true,
        ) ?: return null
        return runCatching {
            (Json.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * One plugin's `isConfigured(config)`: whether its saved config is complete
     * (desktop's rule — an enabled resolver that isn't configured shows as
     * needing setup and stays out of resolution). A plugin without the function
     * counts as configured. Sync, one evaluate, no request. Answer cached until
     * the config changes ([refreshConfiguredCache]).
     */
    suspend fun isPluginConfigured(resolverId: String): Boolean {
        if (plugins.value.find { it.id == resolverId }?.requiresAuth != true) return true
        configuredMutex.withLock {
            if (configuredCache.containsKey(resolverId)) return configuredCache[resolverId]!!
        }
        ensureInitialized()
        val raw = jsRuntime.evaluate(
            "(function() { const r = window.__resolverLoader.getResolver('${escapeForJs(resolverId)}'); " +
                "if (!r || !r.isConfigured) return true; return !!r.isConfigured(r.config || {}); })()",
        )?.unquote()
        val configured = raw == "true"
        configuredMutex.withLock { configuredCache[resolverId] = configured }
        return configured
    }

    /** Forget the cached `isConfigured` answers (a settings save changed the config). */
    suspend fun refreshConfiguredCache(pluginId: String? = null) {
        configuredMutex.withLock {
            if (pluginId == null) configuredCache.clear() else configuredCache.remove(pluginId)
        }
    }

    private val configuredMutex = kotlinx.coroutines.sync.Mutex()
    private val configuredCache = mutableMapOf<String, Boolean>()

    /**
     * One plugin's `search(query, config)` via the unique-key poll, for the
     * settings screen's Test connection: a plugin whose credentials are broken
     * THROWS with the server's status in the message (Subsonic's "40 Wrong
     * username or password"), a working one answers its results. The raw JSON
     * array, or null when the plugin answered nothing.
     */
    suspend fun searchForConnectionTest(resolverId: String): String? {
        ensureInitialized()
        return pollAsync(
            "tst",
            // One expression (see [getStreamUrl]): a statement list here is a
            // syntax error inside pollAsync's `var result = …` wrapper.
            "await (async () => { const r = window.__resolverLoader.getResolver('${escapeForJs(resolverId)}'); " +
                "if (!r || !r.search) return null; " +
                "return await r.search('test', r.config || {}); })()",
            attempts = LOOKUP_POLL_ATTEMPTS,
            throwOnFailure = true,
        )
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
                configFields = axe.settings?.configurable?.map { (key, field) ->
                    PluginConfigField(
                        key = key,
                        type = field.type,
                        label = field.label ?: key,
                        description = field.description,
                        placeholder = field.placeholder,
                    )
                } ?: emptyList(),
                requiresAuth = axe.settings?.requiresAuth ?: false,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse .axe manifest: ${e.message}", e)
            null
        }
    }

    /**
     * The app version an `.axe`'s `manifest.minAppVersion` asks of this
     * platform when this app is below it, else null ([PluginCompat]).
     */
    fun requiredAppVersion(axeJson: String): String? = try {
        val manifest = json.decodeFromString<AxeFile>(axeJson).manifest
        PluginCompat.requiredAppVersion(manifest.minAppVersion, appVersion, platform)
    } catch (_: Exception) {
        null
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
        val settings: AxeSettings? = null,
    )

    @Serializable
    private data class AxeManifest(
        val id: String,
        val name: String? = null,
        val version: String? = null,
        val type: String? = null,
        val icon: String? = null,
        val color: String? = null,
        val minAppVersion: JsonElement? = null,
    )

    @Serializable
    private data class AxeSettings(
        val requiresAuth: Boolean = false,
        val authType: String? = null,
        val configurable: Map<String, AxeConfigField> = emptyMap(),
    )

    @Serializable
    private data class AxeConfigField(
        val type: String = "text",
        val label: String? = null,
        val description: String? = null,
        val placeholder: String? = null,
    )
}
