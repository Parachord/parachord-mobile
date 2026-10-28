package com.parachord.shared.metadata

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Deezer's public artist search, a step in the artist-photo chain
 * ([MetadataService.getArtistImage], desktop parachord#1141). It needs no
 * account, and had photos for artists neither Spotify nor Apple Music could
 * be asked about.
 */
class DeezerArtistSearch(private val httpClient: HttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The photo of the artist named [artistName], or null when Deezer has no
     * such artist or no photo. Throws when Deezer can't be reached, so the
     * chain doesn't remember that as a miss.
     */
    suspend fun artistImage(artistName: String): String? {
        val url = "https://api.deezer.com/search/artist?q=${artistName.encodeURLParameter()}&limit=10"
        val response = httpClient.get(url) { header("Accept", "application/json") }
        if (!response.status.isSuccess()) error("Deezer artist search failed: ${response.status.value}")
        val body = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        // Deezer reports quota and other errors as a 200 with an `error` object.
        if (body == null || body["error"] != null) error("Deezer artist search failed: ${body?.get("error")}")
        return ArtistImageChain.deezerImageFromSearch(body, artistName)
    }
}
