package com.parachord.shared.api

import com.parachord.shared.metadata.IosMosaicComposer
import com.parachord.shared.plugin.IosJsRuntime
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phase 2.5 smoke test for the iOS app.
 *
 * Exists to prove three things that have to work before any production
 * shared-module HTTP call can be wired up from Swift:
 *   1. Ktor's Darwin engine actually fires HTTP requests
 *   2. kotlinx.serialization parses real JSON responses on iOS
 *   3. Kotlin `suspend fun` ↔ Swift `async throws` interop works
 *      end-to-end (the value, not the completion-handler dance)
 *
 * Uses MusicBrainz because it's the only first-party API surface in
 * the shared module that needs no auth at all — no API key, no OAuth,
 * no LB token. The 1-req/sec rate limit is fine for a button-tap demo.
 *
 * Intentionally NOT the production HttpClient setup. That one wires
 * `OAuthRefreshPlugin`, `User-Agent` injection, the LB token provider,
 * etc. via `createHttpClient(...)` in [HttpClientFactory.ios.kt], all
 * of which require `AppConfig` + `AuthTokenProvider` + `OAuthTokenRefresher`
 * dependencies that haven't been wired through Swift yet. The minimal
 * client below has just enough plugins (ContentNegotiation + the
 * required `User-Agent` header MB returns 403 without) to talk to the
 * MB API.
 */
class IosSmokeTest {

    private val httpClient = HttpClient(Darwin) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        // MusicBrainz returns 403 to the default Ktor User-Agent. The
        // production HttpClient sets a Parachord-branded UA via the
        // shared `User-Agent` interceptor; for the smoke test we set
        // it inline so the request actually gets a response.
        defaultRequest {
            header("User-Agent", "Parachord-iOS-SmokeTest/0.1 (https://parachord.com)")
        }
    }

    private val musicBrainz = MusicBrainzClient(httpClient)
    private val mosaicComposer = IosMosaicComposer(httpClient)

    /**
     * Exposed publicly so the Swift side can grab the underlying
     * [JSContext][platform.JavaScriptCore.JSContext] via
     * `IosJsRuntime.nativeContext` and attach the
     * console / fetch / storage polyfills that the Kotlin/Native JSC
     * binding can't express. See `IosJsRuntime.kt` for the rationale.
     */
    val jsRuntime = IosJsRuntime()

    /**
     * Search MusicBrainz for artists and return a flattened, Swift-
     * friendly DTO. Plain `data class` so Kotlin/Native exposes it
     * cleanly to Swift without `List<MbArtist>` ObjC bridging weirdness.
     */
    @Throws(Throwable::class)
    suspend fun searchArtists(query: String, limit: Int): List<SmokeTestArtist> {
        val response = musicBrainz.searchArtists(query, limit)
        return response.artists.map { artist ->
            SmokeTestArtist(
                id = artist.id,
                name = artist.name,
                disambiguation = artist.disambiguation,
                topTag = artist.tags.maxByOrNull { it.count }?.name,
            )
        }
    }

    /**
     * Phase 4 smoke test for the iOS-side mosaic composite. Downloads
     * four images via Ktor, composites them into a 2×2 600×600 JPEG
     * via UIGraphicsImageRenderer, writes to Application Support,
     * returns the resulting `file://` URL. Drives the same code path
     * that `ImageEnrichmentService.enrichPlaylistArt` would call via
     * its `composeMosaic` lambda once an iOS Koin module wires it up.
     */
    @Throws(Throwable::class)
    suspend fun composeMosaic(playlistId: String, urls: List<String>): String? =
        mosaicComposer.compose(playlistId, urls)

    /**
     * Phase 4.1 smoke test for the iOS-side JavaScriptCore runtime.
     * Initializes the `IosJsRuntime` (cheap — just allocs a `JSContext`)
     * and evaluates a JS expression, returning the stringified result.
     * Used by `ContentView`'s JSC card to prove the runtime can host
     * the same `(async () => {...})()` evaluation pattern the Android
     * `JsBridge.evaluate(...)` accepts.
     */
    @Throws(Throwable::class)
    suspend fun evaluateJs(script: String): String? {
        if (!jsRuntime.ready.value) jsRuntime.initialize()
        return jsRuntime.evaluate(script)
    }

    /**
     * Initialize the JS runtime without evaluating anything. The Swift
     * smoke-test harness calls this before
     * [evaluateJs] so it can grab `jsRuntime.nativeContext` and inject
     * polyfills BEFORE any script that depends on them
     * (`console.log`, `fetch`, etc.) runs. Idempotent.
     */
    @Throws(Throwable::class)
    suspend fun ensureJsRuntimeReady() {
        if (!jsRuntime.ready.value) jsRuntime.initialize()
    }
}

/**
 * Flat Swift-friendly projection of [MbArtist]. The full upstream
 * type has more fields (tags as a list, sort name, etc.) but the
 * smoke test only needs what the demo view renders.
 */
@Serializable
data class SmokeTestArtist(
    val id: String,
    val name: String,
    val disambiguation: String?,
    val topTag: String?,
)
