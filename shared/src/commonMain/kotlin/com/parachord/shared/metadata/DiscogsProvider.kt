package com.parachord.shared.metadata

import com.parachord.shared.platform.Log
import com.parachord.shared.settings.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Discogs metadata provider (KMP / commonMain — Ktor port of the Android
 * app-side provider; one shared implementation for both platforms).
 *
 * Artist bios + images from the Discogs community DB. Works without auth;
 * honours an optional personal access token (higher rate limits) from
 * SettingsStore. Priority 15 (between Last.fm 10 and Spotify 20). Mirrors
 * desktop's getDiscogsBio()/getDiscogsArtistImage().
 *
 * The shared HttpClient already injects the Parachord User-Agent (Discogs
 * requires a descriptive UA), so we only add the Authorization header here.
 */
class DiscogsProvider(
    private val httpClient: HttpClient,
    private val settingsStore: SettingsStore,
) : MetadataProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val name = "discogs"
    override val priority = 15

    override suspend fun isAvailable(): Boolean = true
    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> = emptyList()
    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> = emptyList()
    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> = emptyList()

    override suspend fun getArtistInfo(artistName: String): ArtistInfo? = withContext(Dispatchers.Default) {
        try {
            val artistId = searchArtistId(artistName) ?: return@withContext null
            val (bio, imageUrl, discogsUrl) = fetchArtistProfile(artistId)
            if (bio == null && imageUrl == null) return@withContext null
            ArtistInfo(
                name = artistName,
                imageUrl = imageUrl,
                bio = bio,
                bioSource = "discogs",
                bioUrl = discogsUrl,
                provider = name,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Discogs lookup failed for '$artistName'", e)
            null
        }
    }

    /** Best-matching artist id: prefer an exact (case-insensitive) name match,
     *  else the first result. */
    private suspend fun searchArtistId(artistName: String): Long? {
        val url = "$BASE_URL/database/search?q=${artistName.encodeURLParameter()}&type=artist&per_page=5"
        val results = getJson(url)?.get("results")?.jsonArray ?: return null
        if (results.isEmpty()) return null
        for (el in results) {
            val o = el.jsonObject
            if (o["title"]?.jsonPrimitive?.contentOrNull.equals(artistName, ignoreCase = true)) {
                o["id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let { return it }
            }
        }
        return results.first().jsonObject["id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
    }

    /** (bio, imageUrl, profileUrl) from the full artist resource. */
    private suspend fun fetchArtistProfile(artistId: Long): Triple<String?, String?, String?> {
        val root = getJson("$BASE_URL/artists/$artistId") ?: return Triple(null, null, null)
        val rawProfile = root["profile"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val bio = if (rawProfile.isNotBlank()) cleanDiscogsMarkup(rawProfile) else null

        var imageUrl: String? = null
        val images = root["images"]?.jsonArray
        if (images != null && images.isNotEmpty()) {
            for (el in images) {
                val o = el.jsonObject
                if (o["type"]?.jsonPrimitive?.contentOrNull == "primary") {
                    imageUrl = o["uri"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                    if (imageUrl != null) break
                }
            }
            if (imageUrl == null) {
                imageUrl = images.first().jsonObject["uri"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
            }
        }
        val profileUrl = root["uri"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        return Triple(bio, imageUrl, profileUrl)
    }

    private suspend fun getJson(url: String): JsonObject? = try {
        val token = settingsStore.getDiscogsToken()
        val body = httpClient.get(url) {
            if (!token.isNullOrBlank()) header("Authorization", "Discogs token=$token")
        }.bodyAsText()
        if (body.isBlank()) null else json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        Log.w(TAG, "Discogs request failed: $url", e)
        null
    }

    /** Strip Discogs markup: [a=Artist] [l=Label] [m=Master] [r=Release],
     *  [url=...]...[/url] links, and stray [tag]s. */
    private fun cleanDiscogsMarkup(text: String): String =
        text.replace(Regex("""\[([almr])=([^\]]*)\]"""), "$2")
            .replace(Regex("""\[url=[^\]]*](.*?)\[/url]"""), "$1")
            .replace(Regex("""\[/?[a-z]+]"""), "")
            .trim()

    companion object {
        private const val TAG = "DiscogsProvider"
        private const val BASE_URL = "https://api.discogs.com"
    }
}
