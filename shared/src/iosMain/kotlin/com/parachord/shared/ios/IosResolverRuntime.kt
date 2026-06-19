package com.parachord.shared.ios

import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.plugin.IosJsRuntime
import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.ResolverRuntime
import com.parachord.shared.resolver.selectBestMatch
import com.parachord.shared.settings.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * iOS [ResolverRuntime] (#210, D2). Owns the platform-specific native resolver
 * (Apple Music via the catalog HTTP API) and `.axe` execution via the
 * JavaScriptCore unique-key polling workaround.
 *
 * The shared [com.parachord.shared.resolver.ResolverCoordinator] owns the
 * fan-out, the Spotify branch (injected lambda), gating, re-score and ranking.
 * Spotify is NOT a dependency here — it lives entirely in the coordinator.
 *
 * These bodies were moved VERBATIM from `IosResolverCoordinator` (Task 3). The
 * logic is unchanged (they already pick their best match via [selectBestMatch]).
 *
 * ## Concurrency through one JSC context
 *
 * The shared `PluginManager.resolve` reads a bare `(async()=>…)()` IIFE
 * synchronously, which yields `[object Promise]` on JSC (CLAUDE.md mistake
 * #27). We instead kick off the async resolve storing its outcome under a
 * UNIQUE per-call key in `window.__resolveResults`, then poll. A single
 * shared global (like `__lastPluginResult`) would be clobbered when several
 * resolves run concurrently; unique keys keep each call isolated, so the
 * `async { … }` fan-out can interleave on the JSC run loop (each `delay`
 * yields; each fetch callback fires independently).
 */
class IosResolverRuntime(
    private val jsRuntime: IosJsRuntime,
    private val settingsStore: SettingsStore,
    private val httpClient: HttpClient,
    private val appleMusicDeveloperToken: String,
    private val trackDao: TrackDao,
) : ResolverRuntime {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Main-confined (all coroutines here run on Dispatchers.Main), so a
    // plain counter is race-free — there is no true parallelism.
    private var callCounter = 0

    // localfiles joins the native set (#219) — resolved from the scanned
    // device-library rows in the DB, played via AVPlayer (no .axe / network).
    override val nativeResolverIds = setOf("applemusic", "localfiles")

    override suspend fun resolveNative(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        album: String?,
    ): ResolvedSource? = when (resolverId) {
        // Blank dev token → skip (parity with the pre-#210 fan-out's
        // `amAvailable = … && appleMusicDeveloperToken.isNotBlank()` gate, and
        // with resolveSingle's gate) — avoids a wasted empty-Bearer catalog call.
        "applemusic" -> if (appleMusicDeveloperToken.isNotBlank()) {
            resolveAppleMusicNative(targetArtist ?: "", targetTitle ?: "")
        } else null
        "localfiles" -> resolveLocalFileNative(targetTitle, targetArtist)
        else -> null
    }

    /**
     * Native localfiles resolution (#219) — exact, case-insensitive title+artist
     * match against the scanned device-library rows in the DB; deterministic 1.0
     * confidence (Android parity, AndroidResolverRuntime.resolveLocalFile). The
     * sourceUrl is the MPMediaItem `assetURL` (file://) the scanner persisted, which
     * AVPlayer plays directly (PlaybackRouter already routes "localfiles" → avPlayer).
     */
    private suspend fun resolveLocalFileNative(targetTitle: String?, targetArtist: String?): ResolvedSource? {
        if (targetTitle.isNullOrBlank() || targetArtist.isNullOrBlank()) return null
        val track = trackDao.findLocalFile(targetTitle, targetArtist) ?: return null
        val sourceUrl = track.sourceUrl ?: return null
        return ResolvedSource(
            url = sourceUrl,
            sourceType = "local",
            resolver = "localfiles",
            confidence = 1.0,
            matchedTitle = track.title,
            matchedArtist = track.artist,
            matchedDurationMs = track.duration,
            isrc = com.parachord.shared.resolver.validateIsrc(track.isrc),
            artworkUrl = track.artworkUrl,
        )
    }

    override suspend fun resolveAxe(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        album: String?,
    ): ResolvedSource? = resolveOne(resolverId, targetArtist ?: "", targetTitle ?: "", album)

    /** Resolve a single track via one `.axe` resolver, awaiting its Promise. */
    private suspend fun resolveOne(
        resolverId: String,
        artist: String,
        title: String,
        album: String?,
    ): ResolvedSource? {
        val key = "res_${callCounter++}"
        val a = artist.jsEsc()
        val t = title.jsEsc()
        val alb = if (album != null) "'${album.jsEsc()}'" else "null"

        jsRuntime.evaluate(
            """
            (function() {
                window.__resolveResults = window.__resolveResults || {};
                window.__resolveResults['$key'] = 'pending';
                (async () => {
                    try {
                        var r = window.__resolverLoader.getResolver('$resolverId');
                        if (!r || !r.resolve) { window.__resolveResults['$key'] = 'null'; return; }
                        var result = await r.resolve('$a', '$t', $alb, r.config || {});
                        window.__resolveResults['$key'] = result ? JSON.stringify(result) : 'null';
                    } catch (e) {
                        window.__resolveResults['$key'] = 'error:' + ((e && e.message) ? e.message : String(e));
                    }
                })();
            })();
            """.trimIndent(),
        )

        repeat(POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            val raw = jsRuntime.evaluate("window.__resolveResults['$key']")
            if (raw != null && raw != "pending") {
                // Free the slot so the map doesn't grow unbounded across plays.
                jsRuntime.evaluate("delete window.__resolveResults['$key']; null")
                if (raw == "null" || raw.startsWith("error:")) return null
                val parsed = runCatching { json.decodeFromString<AxeResolveResult>(raw) }.getOrNull()
                    ?: return null

                // ID-based resolvers (Apple Music, Spotify) return NO top-level
                // `url` — they carry a catalog ID (appleMusicId / spotifyUri) and
                // route through MusicKit / Spotify Connect, not AVPlayer. Only
                // URL-based resolvers (soundcloud, bandcamp, direct) carry a
                // playable stream `url`. Accept a source if it has EITHER a
                // playable URL or a routable ID. (Android's AxeResolveResult
                // required `url` because native-first means it only ever runs
                // url-based .axe like bandcamp/youtube — applemusic.axe.resolve
                // never executes there.)
                val playableUrl = parsed.url
                    ?: parsed.previewUrl
                    ?: parsed.soundcloudUrl
                    ?: parsed.appleMusicUrl
                    ?: parsed.spotifyUrl
                val hasRoutableId = parsed.appleMusicId != null ||
                    parsed.spotifyUri != null || parsed.spotifyId != null ||
                    parsed.soundcloudId != null
                if (playableUrl == null && !hasRoutableId) return null

                return ResolvedSource(
                    // For AM/Spotify the router keys off the ID; url is the
                    // best-available stream (e.g. AM 30s previewUrl) or empty.
                    url = playableUrl ?: "",
                    sourceType = parsed.sourceType ?: resolverId,
                    resolver = resolverId,
                    spotifyUri = parsed.spotifyUri,
                    spotifyId = parsed.spotifyId,
                    soundcloudId = parsed.soundcloudId,
                    soundcloudUrl = parsed.soundcloudUrl,
                    appleMusicId = parsed.appleMusicId,
                    // Optional `.axe` result field — a streaming resolver MAY
                    // supply its ISRC for the MBID fallback. Validated/normalized
                    // so a malformed value never enters the source record (#217).
                    isrc = com.parachord.shared.resolver.validateIsrc(parsed.isrc),
                    // Placeholder — overridden by scoreConfidence in resolveSources.
                    confidence = 0.9,
                    matchedTitle = parsed.title,
                    matchedArtist = parsed.artist,
                    matchedDurationMs = parsed.duration,
                    artworkUrl = parsed.albumArt,
                )
            }
        }
        return null
    }

    /**
     * Native Apple Music catalog resolution (Android parity). Searches the
     * catalog `songs` endpoint with the dev-token Bearer — the same catalog
     * MusicKit queries — and returns the best title+artist match as a routable
     * `applemusic` source (appleMusicId + artwork + 30s preview). The placeholder
     * 0.9 confidence is overridden by [com.parachord.shared.resolver.scoreConfidence]
     * in the coordinator's re-score, so a wrong-song catalog hit is still dropped
     * by the 0.60 floor.
     */
    /**
     * GET the Apple Music catalog with ONE spaced retry on a transient failure
     * (429 / 5xx / network blip / blank body). Returns the response body on 2xx,
     * or null when it can't get one. See the call site for why (#232).
     */
    private suspend fun fetchCatalog(url: String): String? {
        repeat(2) { attempt ->
            try {
                val resp = httpClient.get(url) { header("Authorization", "Bearer $appleMusicDeveloperToken") }
                val status = resp.status.value
                val text = resp.bodyAsText()
                if (status in 200..299 && text.isNotBlank()) return text
                val transient = status == 429 || status >= 500 || text.isBlank()
                if (attempt == 0 && transient) {
                    delay(600)
                } else {
                    com.parachord.shared.platform.Log.w(
                        "IosResolverRuntime",
                        "AM catalog returned $status — Apple Music source dropped for this resolve (#232)",
                    )
                    return null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 0) delay(600) else return null
            }
        }
        return null
    }

    private suspend fun resolveAppleMusicNative(artist: String, title: String): ResolvedSource? {
        val storefront = settingsStore.getAppleMusicStorefront()?.ifBlank { null } ?: "us"
        val term = "$artist $title".encodeURLParameter()
        val url = "https://api.music.apple.com/v1/catalog/$storefront/search?types=songs&limit=10&term=$term"
        // Single spaced retry on a TRANSIENT failure (#232). The iOS MusicKit
        // catalog is unthrottled (no per-request limiter yet — see CLAUDE.md), so
        // resolving a list occasionally trips a 429; when this returns null the AM
        // source drops out and the reliable Spotify hint wins over a
        // higher-priority Apple Music — the "occasionally picks Spotify over AM"
        // bug. IosTrackResolverCache already caps concurrency at 3, so one 600ms-
        // spaced retry recovers a transient blip without meaningfully adding load;
        // a sustained throttle still falls through to Spotify (correct).
        val body = fetchCatalog(url) ?: return null

        val data = runCatching {
            json.parseToJsonElement(body).jsonObject["results"]?.jsonObject
                ?.get("songs")?.jsonObject?.get("data")?.jsonArray
        }.getOrNull() ?: return null
        if (data.isEmpty()) return null

        // Best title+artist match via the shared bidirectional matcher (same gate
        // scoreConfidence uses, so the pick scores 0.95 not 0.50), falling back to
        // the first catalog hit. Shared with Android's AM tiers — one matcher, no
        // drift. Bidirectional handles the "THE Tallest Man on Earth" case where
        // the catalog string is a substring of the target.
        fun attr(el: kotlinx.serialization.json.JsonElement, k: String) =
            el.jsonObject["attributes"]?.jsonObject?.get(k)?.jsonPrimitive?.contentOrNull
        val best = selectBestMatch(
            data, title, artist,
            { attr(it, "name") },
            { attr(it, "artistName") },
        ) ?: data.first()

        val attrs = best.jsonObject["attributes"]?.jsonObject ?: return null
        val id = best.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val artwork = attrs["artwork"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?.replace("{w}", "600")?.replace("{h}", "600")?.replace("{f}", "jpg")
        val preview = attrs["previews"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull

        return ResolvedSource(
            url = preview ?: "",
            sourceType = "applemusic",
            resolver = "applemusic",
            appleMusicId = id,
            // Apple Music catalog attributes carry the ISRC — supply it for the
            // MBID fallback.
            isrc = com.parachord.shared.resolver.validateIsrc(attrs["isrc"]?.jsonPrimitive?.contentOrNull),
            confidence = 0.9, // overridden by scoreConfidence in the coordinator
            matchedTitle = attrs["name"]?.jsonPrimitive?.contentOrNull,
            matchedArtist = attrs["artistName"]?.jsonPrimitive?.contentOrNull,
            artworkUrl = artwork,
        )
    }

    /** The plugin's model `setting` object, read SYNCHRONOUSLY from the loaded
     *  `.axe` (a property read, no Promise) — `{type, default, options, fallbackOptions}`. */
    private suspend fun readModelSetting(pluginId: String): JsonObject? {
        val raw = jsRuntime.evaluate(
            """
            (function() {
                var r = window.__resolverLoader.getResolver('$pluginId');
                var m = (r && r.settings && r.settings.configurable && r.settings.configurable.model)
                     || (r && r.configurable && r.configurable.model);
                return m ? JSON.stringify(m) : 'null';
            })()
            """.trimIndent(),
        )
        return if (raw != null && raw != "null") runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() else null
    }

    /** The plugin's default model id — desktop seeds the picker with this
     *  (`metaServiceConfigs[id].model || modelSetting.default`, app.js:59532). */
    suspend fun aiModelDefault(pluginId: String): String =
        readModelSetting(pluginId)?.get("default")?.jsonPrimitive?.contentOrNull ?: ""

    /**
     * Model list for an AI `.axe` plugin, mirroring desktop's render precedence
     * (app.js ~L59519): a `dynamic-select` plugin fetches live via `listModels`,
     * falling back to the plugin's curated `fallbackOptions` when that's empty/keyless;
     * a static `select` plugin shows its curated `options`. All marketplace-driven.
     */
    suspend fun aiModels(pluginId: String, apiKey: String): List<String> {
        val setting = readModelSetting(pluginId)
        fun opts(field: String): List<String> =
            setting?.get(field)?.jsonArray?.mapNotNull {
                runCatching { it.jsonObject["value"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            }.orEmpty()
        return if (setting?.get("type")?.jsonPrimitive?.contentOrNull == "dynamic-select") {
            val dynamic = if (apiKey.isNotBlank()) listModelsDynamic(pluginId, apiKey) else emptyList()
            dynamic.ifEmpty { opts("fallbackOptions") }
        } else {
            opts("options")
        }
    }

    /**
     * Run an AI `.axe` plugin's `listModels(config)` via the SAME JSC unique-key
     * polling as [resolveOne] (a bare `(async()=>)()` returns `[object Promise]` on
     * JavaScriptCore). The plugin owns the provider's model API + filtering — this
     * is just the host glue, so iOS shares the marketplace `.axe` like Android does.
     * Returns the model ids the plugin reports; [] on no key / error.
     */
    private suspend fun listModelsDynamic(pluginId: String, apiKey: String): List<String> {
        val key = "lm_${callCounter++}"
        val k = apiKey.jsEsc()
        jsRuntime.evaluate(
            """
            (function() {
                window.__resolveResults = window.__resolveResults || {};
                window.__resolveResults['$key'] = 'pending';
                (async () => {
                    try {
                        var r = window.__resolverLoader.getResolver('$pluginId');
                        if (!r || !r.listModels) { window.__resolveResults['$key'] = 'null'; return; }
                        var result = await r.listModels({ apiKey: '$k' });
                        window.__resolveResults['$key'] = result ? JSON.stringify(result) : 'null';
                    } catch (e) {
                        window.__resolveResults['$key'] = 'error:' + ((e && e.message) ? e.message : String(e));
                    }
                })();
            })();
            """.trimIndent(),
        )
        repeat(POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            val raw = jsRuntime.evaluate("window.__resolveResults['$key']")
            if (raw != null && raw != "pending") {
                jsRuntime.evaluate("delete window.__resolveResults['$key']; null")
                if (raw == "null" || raw.startsWith("error:")) return emptyList()
                // The .axe returns [{value,label}, …] (or bare strings) — take the value.
                return runCatching {
                    json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                        runCatching { el.jsonObject["value"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                            ?: runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
                    }
                }.getOrElse { emptyList() }
            }
        }
        return emptyList()
    }

    private fun String.jsEsc(): String = replace("\\", "\\\\").replace("'", "\\'")

    private companion object {
        const val POLL_ATTEMPTS = 50
        const val POLL_INTERVAL_MS = 100L
    }
}

/**
 * Wire shape of an `.axe` resolver's `resolve()` JSON result — mirrors
 * Android's `AxeResolveResult`. Mapped to [ResolvedSource] (the `.axe`
 * returns no confidence; that's computed via
 * [com.parachord.shared.resolver.scoreConfidence]).
 */
@Serializable
private data class AxeResolveResult(
    val url: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val duration: Long? = null,
    val sourceType: String? = null,
    val spotifyUri: String? = null,
    val spotifyId: String? = null,
    val spotifyUrl: String? = null,
    val soundcloudId: String? = null,
    val soundcloudUrl: String? = null,
    val appleMusicId: String? = null,
    val appleMusicUrl: String? = null,
    val isrc: String? = null,
    val previewUrl: String? = null,
    val albumArt: String? = null,
)
