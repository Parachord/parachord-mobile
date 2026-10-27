package com.parachord.shared.metadata

import com.parachord.shared.platform.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * An artist's Wikidata id from their MusicBrainz id — the start of the
 * Wikipedia bio and image. Port of desktop `src/renderer/lib/artist-bio.js`
 * `createWikidataIdLookup` (parachord#1133, mobile #429).
 *
 * The id used to come only from the artist's MusicBrainz url-rels, so a
 * MusicBrainz outage took the Wikipedia bio down with it (Wild Pink,
 * 2026-10-25). Wikidata records the MusicBrainz artist id itself (property
 * P434), so Wikidata's search is asked first and the url-rels are only the
 * fallback, for an artist Wikidata has no P434 for, or when Wikidata answers
 * non-2xx or can't be reached.
 *
 * Found ids are kept for the session and concurrent lookups for one artist
 * share a request. A miss isn't kept: it may be an outage. A MusicBrainz
 * failure in the fallback propagates to the caller, as on desktop.
 */
class WikidataIdLookup(
    private val httpClient: HttpClient,
    /** The `wikidata` url-rel's resource from MusicBrainz `/artist/{mbid}?inc=url-rels`, or null. */
    private val musicBrainzWikidataUrl: suspend (mbid: String) -> String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()
    private val found = mutableMapOf<String, String>()
    private val inFlight = mutableMapOf<String, Deferred<String?>>()

    suspend fun lookup(mbid: String?): String? {
        if (!isMbid(mbid)) return null
        val key = mbid!!.lowercase()
        val deferred = mutex.withLock {
            found[key]?.let { return it }
            inFlight[key] ?: scope.async {
                try {
                    lookupNow(key).also { id -> if (id != null) mutex.withLock { found[key] = id } }
                } finally {
                    mutex.withLock { inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        return deferred.await()
    }

    private suspend fun lookupNow(mbid: String): String? {
        try {
            val response = httpClient.get(wikidataSearchUrl(mbid)!!)
            if (response.status.isSuccess()) {
                wikidataIdFromSearch(response.bodyAsText())?.let { return it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Wikidata unreachable: MusicBrainz may still answer.
            Log.d(TAG, "Wikidata search failed for $mbid: ${e.message}")
        }
        return wikidataIdFromMbRelation(musicBrainzWikidataUrl(mbid))
    }

    private fun wikidataIdFromSearch(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val first = ((root?.get("query") as? JsonObject)?.get("search") as? JsonArray)?.firstOrNull() as? JsonObject
        val title = (first?.get("title") as? JsonPrimitive)?.contentOrNull
        return title?.takeIf { WIKIDATA_ID.matches(it) }
    }

    companion object {
        private const val TAG = "WikidataIdLookup"
        private val MBID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        private val WIKIDATA_ID = Regex("^Q\\d+$")

        fun isMbid(value: String?): Boolean = value != null && MBID.matches(value)

        /**
         * Wikidata search for the entity whose "MusicBrainz artist ID" (P434) is
         * [mbid]. The mbid is validated first, so nothing else reaches the
         * search expression. Null for an invalid mbid.
         */
        fun wikidataSearchUrl(mbid: String?): String? {
            if (!isMbid(mbid)) return null
            val query = "haswbstatement:P434=${mbid!!.lowercase()}"
            return "https://www.wikidata.org/w/api.php?action=query&list=search" +
                "&srsearch=${query.encodeURLParameter()}&srlimit=1&format=json&origin=*"
        }

        /** "https://www.wikidata.org/wiki/Q42" → "Q42"; anything else → null. */
        fun wikidataIdFromMbRelation(resource: String?): String? =
            resource?.substringAfterLast('/')?.takeIf { WIKIDATA_ID.matches(it) }
    }
}
