package com.parachord.shared.resolver

import com.parachord.shared.api.AppleMusicCatalog
import com.parachord.shared.platform.Log
import com.parachord.shared.settings.SettingsStore
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.concurrent.Volatile

/** What an Apple Music catalog search came back with. */
sealed interface AppleMusicCatalogLookup {
    /** The catalog had songs; [source] is the best match, re-scored later by the coordinator. */
    data class Found(val source: ResolvedSource) : AppleMusicCatalogLookup

    /** The catalog answered with no songs. A real miss: iTunes isn't asked. */
    data object NoMatch : AppleMusicCatalogLookup

    /** No Music User Token, so Apple Music isn't connected and isn't offered (#327). */
    data object NotConnected : AppleMusicCatalogLookup

    /** No developer token, the limiter is cooling down, or the request failed. */
    data object Unavailable : AppleMusicCatalogLookup
}

/**
 * The native Apple Music resolve on both platforms: one catalog `songs` search
 * with the developer token ([AppleMusicCatalog], shared limiter), and the best
 * title + artist match among up to [SEARCH_LIMIT] hits ([selectBestMatch], an
 * exact ISRC first, #402).
 *
 * Android used to search through the MusicKit JS WebView, which is still
 * configuring when Home resolves at launch and is the same instance that plays
 * audio. The catalog GET needs neither MusicKit nor the user's token, but Apple
 * Music is only offered once it's connected (the Music User Token), as on
 * desktop.
 *
 * A catalog answer is final, as in the plugin host's
 * `appleMusicSearchWithMusicKit`: only [AppleMusicCatalogLookup.Unavailable]
 * should send a caller to iTunes.
 */
class AppleMusicCatalogResolver(
    private val catalog: AppleMusicCatalog,
    private val settingsStore: SettingsStore,
) {
    /** One log per process for the not-connected skip; it runs for every row of every list. */
    @Volatile private var loggedNotConnected = false

    suspend fun lookup(
        query: String,
        targetTitle: String?,
        targetArtist: String?,
        targetIsrc: String? = null,
    ): AppleMusicCatalogLookup {
        if (settingsStore.getAppleMusicUserToken().isNullOrBlank()) {
            if (!loggedNotConnected) {
                loggedNotConnected = true
                Log.w(TAG, "Apple Music resolution skipped: no Music User Token. Connect Apple Music in Settings → Plug-ins.")
            }
            return AppleMusicCatalogLookup.NotConnected
        }
        val term = searchTerm(query, targetTitle, targetArtist) ?: return AppleMusicCatalogLookup.NoMatch
        val storefront = storefrontOrDefault(settingsStore.getAppleMusicStorefront())
        val path = "/v1/catalog/$storefront/search?types=songs&limit=$SEARCH_LIMIT&term=${term.encodeURLParameter()}"
        val body = catalog.get(path) ?: return AppleMusicCatalogLookup.Unavailable
        val source = pickCatalogSong(body, targetTitle, targetArtist, targetIsrc)
            ?: return AppleMusicCatalogLookup.NoMatch
        Log.d(TAG, "Apple Music (catalog) matched '${source.matchedTitle}' by ${source.matchedArtist}")
        return AppleMusicCatalogLookup.Found(source)
    }

    companion object {
        private const val TAG = "AppleMusicCatalogResolver"
        const val SEARCH_LIMIT = 10
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val STOREFRONT = Regex("^[a-z]{2}$")

        /** "artist title" when both are known, else the caller's query; null when blank. */
        fun searchTerm(query: String, targetTitle: String?, targetArtist: String?): String? =
            if (!targetTitle.isNullOrBlank() && !targetArtist.isNullOrBlank()) "$targetArtist $targetTitle"
            else query.trim().ifBlank { null }

        /** A two-letter storefront code, lowercased; anything else is "us". */
        fun storefrontOrDefault(stored: String?): String =
            stored?.trim()?.lowercase()?.takeIf { STOREFRONT.matches(it) } ?: "us"

        /**
         * The source for the best song in a catalog search response, or null
         * when it has none. With both target names the shared matcher picks
         * (falling back to the first hit, which the re-score then floors if
         * wrong); otherwise the first hit.
         */
        fun pickCatalogSong(
            body: String,
            targetTitle: String?,
            targetArtist: String?,
            targetIsrc: String?,
        ): ResolvedSource? {
            val songs = runCatching {
                json.parseToJsonElement(body).jsonObject["results"]?.jsonObject
                    ?.get("songs")?.jsonObject?.get("data")?.jsonArray
            }.getOrNull()?.mapNotNull { it as? JsonObject }
            if (songs.isNullOrEmpty()) return null

            fun attr(song: JsonElement, key: String) =
                song.jsonObject["attributes"]?.jsonObject?.get(key)?.jsonPrimitive?.contentOrNull
            val best = if (targetTitle != null && targetArtist != null) {
                selectBestMatch(
                    songs, targetTitle, targetArtist,
                    { attr(it, "name") },
                    { attr(it, "artistName") },
                    targetIsrc = targetIsrc,
                    isrcOf = { attr(it, "isrc") },
                ) ?: songs.first()
            } else {
                songs.first()
            }

            val id = best["id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: return null
            val attrs = best["attributes"]?.jsonObject ?: return null
            val artwork = attrs["artwork"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                ?.replace("{w}", "600")?.replace("{h}", "600")?.replace("{f}", "jpg")
            return ResolvedSource(
                // The song's music.apple.com page; playback routes by appleMusicId.
                url = attrs["url"]?.jsonPrimitive?.contentOrNull ?: "applemusic:song:$id",
                sourceType = "applemusic",
                resolver = "applemusic",
                appleMusicId = id,
                // Catalog songs carry the ISRC, for the ISRC→MBID fallback (#217).
                isrc = validateIsrc(attrs["isrc"]?.jsonPrimitive?.contentOrNull),
                confidence = 0.9, // overridden by scoreConfidence in the coordinator
                matchedTitle = attrs["name"]?.jsonPrimitive?.contentOrNull,
                matchedArtist = attrs["artistName"]?.jsonPrimitive?.contentOrNull,
                matchedDurationMs = attrs["durationInMillis"]?.jsonPrimitive?.longOrNull,
                // Carried so ImageEnrichmentService saves it instead of asking the metadata cascade.
                artworkUrl = artwork,
            )
        }
    }
}
