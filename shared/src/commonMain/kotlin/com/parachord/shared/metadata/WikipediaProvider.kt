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
 * Same chain as desktop:
 *   1. The artist's MusicBrainz id: known locally ([knownArtistMbid] — the
 *      MBID mapper cache, library tracks) when possible, else a MusicBrainz
 *      name search.
 *   2. Its Wikidata id: Wikidata's own P434 search first, MusicBrainz url-rels
 *      as the fallback ([WikidataIdLookup], #429). Together with step 1, a
 *      MusicBrainz outage no longer takes the bio down for an artist the user
 *      has played.
 *   3. Wikidata API -> English Wikipedia article title
 *   4. Wikipedia API -> article extract (bio) + thumbnail image
 *
 * Priority 5 (between MusicBrainz 0 and Last.fm 10). All endpoints are public,
 * no auth. Parses with kotlinx.serialization (no org.json on Native).
 */
class WikipediaProvider(
    private val musicBrainzClient: MusicBrainzClient,
    private val musicBrainzProvider: MusicBrainzProvider,
    private val httpClient: HttpClient,
    /**
     * The artist's MusicBrainz id when it's already known on the device, so no
     * MusicBrainz request is needed. Null = not known; the MusicBrainz name
     * search is the last resort.
     */
    private val knownArtistMbid: suspend (artistName: String) -> String? = { null },
) : MetadataProvider {

    private val wikidataIds = WikidataIdLookup(httpClient, musicBrainzWikidataUrl = { mbid ->
        musicBrainzClient.getArtist(mbid).relations
            .firstOrNull { it.type == "wikidata" }?.url?.resource
    })

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val name = "wikipedia"
    override val priority = 5

    override suspend fun isAvailable(): Boolean = true
    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> = emptyList()
    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> = emptyList()
    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> = emptyList()

    override suspend fun getArtistInfo(artistName: String): ArtistInfo? = withContext(Dispatchers.Default) {
        try {
            // 1. The artist's MBID: known on the device, else the cached MB
            //    search (MB rate-limits 1 req/s).
            val mbid = knownArtistMbid(artistName)?.takeIf { WikidataIdLookup.isMbid(it) }
                ?: musicBrainzProvider.resolveArtist(artistName)?.id
                ?: return@withContext null
            // 2. Wikidata id: Wikidata's P434 search, MB url-rels as the fallback.
            val wikidataId = wikidataIds.lookup(mbid) ?: return@withContext null
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
        // The whole article, trimmed to its lead + prose sections (#429,
        // desktop parachord#1134): no lists, reference sections or `== x ==`.
        return WikipediaBio.fromExtract(firstPage["extract"]?.jsonPrimitive?.contentOrNull)
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
