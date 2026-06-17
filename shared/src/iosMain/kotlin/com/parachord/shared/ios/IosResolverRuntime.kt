package com.parachord.shared.ios

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
) : ResolverRuntime {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Main-confined (all coroutines here run on Dispatchers.Main), so a
    // plain counter is race-free — there is no true parallelism.
    private var callCounter = 0

    override val nativeResolverIds = setOf("applemusic")

    override suspend fun resolveNative(
        resolverId: String,
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        album: String?,
    ): ResolvedSource? =
        // Blank dev token → skip (parity with the pre-#210 fan-out's
        // `amAvailable = … && appleMusicDeveloperToken.isNotBlank()` gate, and
        // with resolveSingle's gate) — avoids a wasted empty-Bearer catalog call.
        if (resolverId == "applemusic" && appleMusicDeveloperToken.isNotBlank()) {
            resolveAppleMusicNative(targetArtist ?: "", targetTitle ?: "")
        } else {
            null
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
    private suspend fun resolveAppleMusicNative(artist: String, title: String): ResolvedSource? {
        val storefront = settingsStore.getAppleMusicStorefront()?.ifBlank { null } ?: "us"
        val term = "$artist $title".encodeURLParameter()
        val url = "https://api.music.apple.com/v1/catalog/$storefront/search?types=songs&limit=10&term=$term"
        val body = try {
            httpClient.get(url) { header("Authorization", "Bearer $appleMusicDeveloperToken") }.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (body.isBlank()) return null

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
