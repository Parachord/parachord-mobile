package com.parachord.shared.metadata

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Apple Music artist-image provider (KMP / commonMain).
 *
 * PRIMARY artist-image source. Its `priority` is 25 (its slot in the
 * getArtistInfo MERGE, which only affects non-image fields like bio/tags), but
 * MetadataService queries Apple Music FIRST for images — both its image-source
 * preference and the fast getArtistImage() path — because the AM catalog has a
 * real image for virtually every artist via a single call on a separate dev
 * token that does NOT draw on the Spotify client_id shared across
 * desktop/iOS/Android, so bulk image enrichment never bursts Spotify
 * (RateLimitGate / #177).
 *
 * Uses the built-in Apple Music DEVELOPER token (Bearer). Catalog artist data is
 * public, so NO Music-User-Token is required. `isAvailable()` is false when no
 * dev token is configured (e.g. an iOS build without one), so the provider is
 * simply skipped — never an error. Image only (Apple's artist resource has no
 * bio field).
 *
 * The shared HttpClient does not auto-inject auth for api.music.apple.com
 * (only Spotify + ListenBrainz are host-gated), so the manual Bearer header
 * here is authoritative.
 */
class AppleMusicArtistProvider(
    private val httpClient: HttpClient,
    private val developerToken: String,
    private val storefront: String = "us",
) : MetadataProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override val name = "applemusic"
    override val priority = 25

    init {
        // Surface an expiring/expired token at construction. The whole failure
        // mode this guards (#186) is that expiry is otherwise INVISIBLE: every
        // request just 401s and artist images quietly stop appearing.
        val days = DeveloperTokenExpiry.daysRemaining(developerToken, currentTimeMillis() / 1000)
        when {
            days == null -> Unit // no/unparseable exp — nothing useful to say
            days < 0 -> Log.w(
                TAG,
                "Apple Music developer token EXPIRED ${-days}d ago — artist images are disabled. " +
                    "Rotate it (see parachord-mobile#186; builds mint it from the .p8 when configured).",
            )
            days <= DeveloperTokenExpiry.WARN_WITHIN_DAYS -> Log.w(
                TAG,
                "Apple Music developer token expires in ${days}d — rotate before artist images break.",
            )
        }
    }

    /**
     * False when the token is missing OR past its `exp`.
     *
     * The expiry half is the #186 fix: an expired token used to pass this check
     * (it only tested `isNotBlank()`), so the provider stayed "available" and
     * every call 401'd silently. Returning false makes MetadataService skip it
     * cleanly and fall through to the other image sources.
     */
    override suspend fun isAvailable(): Boolean =
        developerToken.isNotBlank() &&
            !DeveloperTokenExpiry.isExpired(developerToken, currentTimeMillis() / 1000)
    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> = emptyList()
    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> = emptyList()
    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> = emptyList()

    override suspend fun getArtistInfo(artistName: String): ArtistInfo? = withContext(Dispatchers.Default) {
        if (developerToken.isBlank()) return@withContext null
        try {
            val url = "https://api.music.apple.com/v1/catalog/$storefront/search" +
                "?types=artists&limit=5&term=${artistName.encodeURLParameter()}"
            val body = httpClient.get(url) { header("Authorization", "Bearer $developerToken") }.bodyAsText()
            if (body.isBlank()) return@withContext null

            val data = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject
                ?.get("artists")?.jsonObject?.get("data")?.jsonArray ?: return@withContext null
            if (data.isEmpty()) return@withContext null

            // Prefer an exact (case-insensitive) name match, else the first hit.
            val artist = data.firstOrNull {
                it.jsonObject["attributes"]?.jsonObject?.get("name")
                    ?.jsonPrimitive?.contentOrNull.equals(artistName, ignoreCase = true)
            }?.jsonObject ?: data.first().jsonObject

            val template = artist["attributes"]?.jsonObject?.get("artwork")?.jsonObject
                ?.get("url")?.jsonPrimitive?.contentOrNull ?: return@withContext null
            // Apple artwork URLs are templates: resolve {w}/{h}/{f} to a 600px square.
            val imageUrl = template
                .replace("{w}", "600").replace("{h}", "600").replace("{f}", "jpg")

            ArtistInfo(name = artistName, imageUrl = imageUrl, provider = name)
        } catch (e: Exception) {
            Log.w(TAG, "Apple Music artist lookup failed for '$artistName'", e)
            null
        }
    }

    companion object { private const val TAG = "AppleMusicArtistProvider" }
}
