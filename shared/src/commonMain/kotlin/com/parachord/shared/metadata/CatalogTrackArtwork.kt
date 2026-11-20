package com.parachord.shared.metadata

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A song's cover from the store catalogs: iTunes Search, then Deezer's
 * public search. The track half of desktop's `album-art-catalog.js`
 * (`getCatalogTrackArtwork`), which desktop's chat cards fall back to when a
 * track has no album. A hit must name the artist and the track asked for
 * ([nameMatch]): both APIs answer with a best-effort top hit, and a wrong
 * cover is worse than none. A failed request is a miss here, as on desktop.
 */
class CatalogTrackArtwork(private val httpClient: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun trackArtwork(artist: String, track: String): String? =
        itunesTrackArtwork(artist, track) ?: deezerTrackArtwork(artist, track)

    suspend fun itunesTrackArtwork(artist: String, track: String): String? {
        if (artist.isBlank() || track.isBlank()) return null
        val term = "$artist $track".trim().encodeURLParameter(spaceToPlus = true)
        val body = readJson("https://itunes.apple.com/search?term=$term&entity=song&limit=5") ?: return null
        val hit = (body["results"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull {
            nameMatch(it.str("artistName"), artist) && nameMatch(it.str("trackName"), track)
        } ?: return null
        return hit.str("artworkUrl100")?.takeIf { it.isNotEmpty() }?.replace("100x100", "500x500")
    }

    suspend fun deezerTrackArtwork(artist: String, track: String): String? {
        if (artist.isBlank() || track.isBlank()) return null
        val q = "artist:\"${esc(artist)}\" track:\"${esc(track)}\"".encodeURLParameter(spaceToPlus = true)
        val body = readJson("https://api.deezer.com/search?q=$q&limit=5") ?: return null
        val hit = (body["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull {
            nameMatch((it["artist"] as? JsonObject)?.str("name"), artist) && nameMatch(it.str("title"), track)
        } ?: return null
        val album = hit["album"] as? JsonObject ?: return null
        return album.str("cover_big")?.takeIf { it.isNotEmpty() } ?: album.str("cover_medium")?.takeIf { it.isNotEmpty() }
    }

    private suspend fun readJson(url: String): JsonObject? = try {
        val response = httpClient.get(url) { header("Accept", "application/json") }
        if (!response.status.isSuccess()) null else json.parseToJsonElement(response.bodyAsText()) as? JsonObject
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun esc(s: String) = s.replace('"', ' ')

    companion object {
        private val NON_ALNUM = Regex("[^a-z0-9]")

        private fun norm(s: String?): String = (s ?: "").lowercase().replace(NON_ALNUM, "").removePrefix("the")

        /**
         * Desktop's `nameMatch`: equal after lower-casing, dropping everything
         * but a–z and 0–9 and a leading "the", or one a prefix of the other
         * ("Black Star" matches "Black Star (Remastered)", not "The Place
         * Where the Black Stars Hang"). Empty never matches.
         */
        fun nameMatch(a: String?, b: String?): Boolean {
            val x = norm(a)
            val y = norm(b)
            return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.startsWith(y) || y.startsWith(x))
        }
    }
}
