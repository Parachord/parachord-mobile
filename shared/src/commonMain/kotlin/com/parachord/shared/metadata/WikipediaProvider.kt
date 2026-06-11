package com.parachord.shared.metadata

import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.platform.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wikipedia metadata provider (KMP / commonMain — Ktor port of the Android
 * app-side provider; one shared implementation for both platforms).
 *
 * Same 3-step chain as desktop:
 *   1. MusicBrainz artist lookup -> Wikidata relation URL
 *   2. Wikidata API -> English Wikipedia article title
 *   3. Wikipedia API -> article extract (bio) + thumbnail image
 *
 * Priority 5 (between MusicBrainz 0 and Last.fm 10). All endpoints are public,
 * no auth. Parses with kotlinx.serialization (no org.json on Native).
 */
class WikipediaProvider(
    private val musicBrainzClient: MusicBrainzClient,
    private val musicBrainzProvider: MusicBrainzProvider,
    private val httpClient: HttpClient,
) : MetadataProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val name = "wikipedia"
    override val priority = 5

    override suspend fun isAvailable(): Boolean = true
    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> = emptyList()
    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> = emptyList()
    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> = emptyList()

    override suspend fun getArtistInfo(artistName: String): ArtistInfo? = withContext(Dispatchers.Default) {
        try {
            // 1. Resolve MBID via the cached MB search (MB rate-limits 1 req/s).
            val mbid = musicBrainzProvider.resolveArtist(artistName)?.id ?: return@withContext null
            // 2. MB artist relations -> Wikidata link.
            val wikidataUrl = musicBrainzClient.getArtist(mbid).relations
                .firstOrNull { it.type == "wikidata" }?.url?.resource ?: return@withContext null
            val wikidataId = wikidataUrl.substringAfterLast("/")
            if (wikidataId.isBlank()) return@withContext null
            // 3. Wikidata -> enwiki title.
            val wikiTitle = getWikiTitleFromWikidata(wikidataId) ?: return@withContext null
            // 4. Wikipedia bio + image.
            val bio = getWikipediaBio(wikiTitle)
            val (imageUrl, pageUrl) = getWikipediaImageAndUrl(wikiTitle)
            if (bio == null && imageUrl == null) return@withContext null
            ArtistInfo(
                name = artistName,
                mbid = mbid,
                imageUrl = imageUrl,
                bio = bio,
                bioSource = "wikipedia",
                bioUrl = pageUrl,
                provider = name,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia lookup failed for '$artistName'", e)
            null
        }
    }

    private suspend fun getJson(url: String): JsonObject? = try {
        val body = httpClient.get(url).bodyAsText()
        if (body.isBlank()) null else json.parseToJsonElement(body).jsonObject
    } catch (e: Exception) {
        Log.w(TAG, "Wikipedia GET failed: $url", e)
        null
    }

    private suspend fun getWikiTitleFromWikidata(wikidataId: String): String? {
        val url = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$wikidataId" +
            "&props=sitelinks&sitefilter=enwiki&format=json"
        val entity = getJson(url)?.get("entities")?.jsonObject?.get(wikidataId)?.jsonObject ?: return null
        val enwiki = entity["sitelinks"]?.jsonObject?.get("enwiki")?.jsonObject ?: return null
        return enwiki["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    }

    private suspend fun getWikipediaBio(wikiTitle: String): String? {
        val url = "https://en.wikipedia.org/w/api.php?action=query&titles=${wikiTitle.encodeURLParameter()}" +
            "&prop=extracts&explaintext=1&format=json"
        val pages = getJson(url)?.get("query")?.jsonObject?.get("pages")?.jsonObject ?: return null
        val firstPage = pages.values.firstOrNull()?.jsonObject ?: return null
        return firstPage["extract"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
    }

    private suspend fun getWikipediaImageAndUrl(wikiTitle: String): Pair<String?, String?> {
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/${wikiTitle.encodeURLPath()}"
        val root = getJson(url) ?: return null to null
        val thumb = root["thumbnail"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val original = root["originalimage"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val pageUrl = root["content_urls"]?.jsonObject?.get("desktop")?.jsonObject
            ?.get("page")?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        return (original ?: thumb) to pageUrl
    }

    companion object { private const val TAG = "WikipediaProvider" }
}
