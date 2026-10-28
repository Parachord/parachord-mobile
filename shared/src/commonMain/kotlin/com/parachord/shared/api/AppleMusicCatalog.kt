package com.parachord.shared.api

import com.parachord.shared.platform.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Read-only access to Apple Music's catalog API with the app's developer token,
 * for the plugin host's Apple Music hooks (`appleMusicSearchWithMusicKit`,
 * `appleMusicLookupSong` / `Album` / `Playlist`). `applemusic.axe` calls those
 * hooks for search, resolve, URL / album / playlist lookups; desktop answers
 * them through MusicKit, whose `api.music(path)` is this same catalog GET.
 *
 * Without the hooks the plugin falls back to iTunes Search / Lookup, whose
 * index lags new releases by days or weeks: "I Know!" by Swapmeet (2026-10)
 * was in the catalog but not in iTunes Search, so it never resolved to Apple
 * Music.
 *
 * Only `/v1/catalog/` paths are served (no library, no user data; the
 * developer token itself never reaches JavaScript). Calls go through the
 * shared [MusicKitCatalogLimiter], with one retry on a 429 or a transient
 * failure, like iOS's `IosResolverRuntime.fetchCatalog`.
 */
class AppleMusicCatalog(
    private val httpClient: HttpClient,
    private val developerToken: String,
    private val limiter: MusicKitCatalogLimiter,
) {
    val isAvailable: Boolean get() = developerToken.isNotBlank()

    /**
     * GET `https://api.music.apple.com` + [path] (path and query, starting with
     * `/v1/catalog/`). The response body, or null when there's no token, the
     * path isn't a catalog path, the limiter is cooling down, or the request
     * failed. A null answer sends the plugin to its iTunes fallback.
     */
    suspend fun get(path: String): String? {
        if (!isAvailable || !isCatalogPath(path)) return null
        val url = "https://api.music.apple.com$path"
        repeat(2) { attempt ->
            val outcome = limiter.runThrottled { fetchOnce(url) } ?: return null // cooling down
            when (outcome) {
                is Attempt.Ok -> { limiter.reportSuccess(); return outcome.body }
                Attempt.RateLimited -> limiter.reportRateLimit()
                Attempt.Transient -> {}
                is Attempt.Failed -> {
                    Log.w(TAG, "Catalog GET returned ${outcome.status}: $path")
                    return null
                }
            }
            if (attempt == 0) delay(600)
        }
        return null
    }

    private suspend fun fetchOnce(url: String): Attempt = try {
        val resp = httpClient.get(url) { header("Authorization", "Bearer $developerToken") }
        val status = resp.status.value
        val text = resp.bodyAsText()
        when {
            status in 200..299 && text.isNotBlank() -> Attempt.Ok(text)
            status == 429 -> Attempt.RateLimited
            status >= 500 || text.isBlank() -> Attempt.Transient
            else -> Attempt.Failed(status)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Attempt.Transient
    }

    private sealed interface Attempt {
        data class Ok(val body: String) : Attempt
        data object RateLimited : Attempt
        data object Transient : Attempt
        data class Failed(val status: Int) : Attempt
    }

    companion object {
        private const val TAG = "AppleMusicCatalog"

        /** Catalog reads only: `/v1/catalog/…`, no `..`, no scheme or host. */
        fun isCatalogPath(path: String): Boolean =
            path.startsWith("/v1/catalog/") && ".." !in path && "://" !in path && '\\' !in path
    }
}
