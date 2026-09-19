package com.parachord.shared.api

import com.parachord.shared.platform.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GeoIP + Nominatim geocoding client. Cross-platform.
 *
 * Mirrors the desktop's location-detection flow used by the Concerts feature
 * (On Tour indicator, manual concert-area picker):
 *  1. Try GeoIP services (ipapi.co → ip-api.com → ipwho.is) for lat/lng.
 *  2. Reverse-geocode with Nominatim (OpenStreetMap) for a city name.
 *  3. Forward-geocode with Nominatim for user-typed location search.
 *
 * Uses the shared Ktor HttpClient — global User-Agent, timeouts, and
 * sanitized logging are inherited automatically (no per-call UA needed;
 * Nominatim's "be polite" UA policy is satisfied by the global
 * "Parachord/<version> ( Android|iOS; https://parachord.com )" header).
 *
 * Migrated to shared in Phase 9E.1.2.
 */
class GeoLocationClient(private val httpClient: HttpClient) {

    companion object {
        private const val TAG = "GeoLocationClient"
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** Detect user's location via GeoIP services with fallback chain. */
    suspend fun detectLocationByIp(): GeoLocation? {
        val coords = tryIpApiCo()
            ?: tryIpApi()
            ?: tryIpWhoIs()

        if (coords == null) {
            Log.w(TAG, "All GeoIP services failed")
            return null
        }

        val cityName = reverseGeocode(coords.lat, coords.lng)
        return GeoLocation(coords.lat, coords.lng, cityName ?: formatCoords(coords.lat, coords.lng))
    }

    /** Search for locations by query string using Nominatim (OpenStreetMap). */
    suspend fun searchLocations(query: String): List<GeoLocation> {
        if (query.isBlank()) return emptyList()
        return try {
            val response = httpClient.get("https://nominatim.openstreetmap.org/search") {
                parameter("q", query)
                parameter("format", "json")
                parameter("limit", "5")
                parameter("addressdetails", "1")
            }
            if (!response.status.isSuccess()) return emptyList()

            val results = json.decodeFromString<List<NominatimSearchResult>>(response.bodyAsText())
            results.mapNotNull { result ->
                val lat = result.lat.toDoubleOrNull() ?: return@mapNotNull null
                val lon = result.lon.toDoubleOrNull() ?: return@mapNotNull null
                GeoLocation(lat, lon, buildNominatimDisplayName(result))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Nominatim search failed for '$query'", e)
            emptyList()
        }
    }

    /** Reverse geocode coordinates to a display name via Nominatim. */
    suspend fun reverseGeocode(lat: Double, lng: Double): String? {
        return try {
            val response = httpClient.get("https://nominatim.openstreetmap.org/reverse") {
                parameter("lat", lat)
                parameter("lon", lng)
                parameter("format", "json")
                parameter("zoom", "10")
            }
            if (!response.status.isSuccess()) return null

            val result = json.decodeFromString<NominatimReverseResult>(response.bodyAsText())
            val addr = result.address ?: return null
            val city = addr.city ?: addr.town ?: addr.village ?: addr.county
            val state = addr.state
            val country = addr.country
            buildString {
                city?.let { append(it) }
                state?.let { if (isNotEmpty()) append(", "); append(it) }
                if (isEmpty()) country?.let { append(it) }
            }.ifEmpty { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Reverse geocode failed", e)
            null
        }
    }

    // ── GeoIP Providers (matching desktop fallback chain) ────────

    private suspend fun tryIpApiCo(): GeoCoords? = try {
        val response = httpClient.get("https://ipapi.co/json/")
        if (response.status.isSuccess()) {
            val result = json.decodeFromString<IpApiCoResponse>(response.bodyAsText())
            if (result.latitude != null && result.longitude != null) {
                GeoCoords(result.latitude, result.longitude)
            } else null
        } else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.d(TAG, "ipapi.co failed: ${e.message}")
        null
    }

    private suspend fun tryIpApi(): GeoCoords? = try {
        val response = httpClient.get("http://ip-api.com/json/") {
            parameter("fields", "lat,lon,status")
        }
        if (response.status.isSuccess()) {
            val result = json.decodeFromString<IpApiResponse>(response.bodyAsText())
            if (result.status == "success" && result.lat != null && result.lon != null) {
                GeoCoords(result.lat, result.lon)
            } else null
        } else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.d(TAG, "ip-api.com failed: ${e.message}")
        null
    }

    private suspend fun tryIpWhoIs(): GeoCoords? = try {
        val response = httpClient.get("https://ipwho.is/")
        if (response.status.isSuccess()) {
            val result = json.decodeFromString<IpWhoIsResponse>(response.bodyAsText())
            if (result.success == true && result.latitude != null && result.longitude != null) {
                GeoCoords(result.latitude, result.longitude)
            } else null
        } else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.d(TAG, "ipwho.is failed: ${e.message}")
        null
    }

    private fun buildNominatimDisplayName(result: NominatimSearchResult): String {
        val addr = result.address
        if (addr != null) {
            val city = addr.city ?: addr.town ?: addr.village ?: addr.county
            val state = addr.state
            val country = addr.country
            return buildString {
                city?.let { append(it) }
                state?.let { if (isNotEmpty()) append(", "); append(it) }
                if (isEmpty()) country?.let { append(it) }
            }.ifEmpty { result.displayName }
        }
        return result.displayName
    }

    /** KMP-compatible "%.2f, %.2f" replacement (no java.util.Formatter on iOS). */
    private fun formatCoords(lat: Double, lng: Double): String =
        "${roundTo2(lat)}, ${roundTo2(lng)}"

    private fun roundTo2(value: Double): String {
        // Round half-up to 2 decimals, format with leading zero + sign preserved.
        val rounded = kotlin.math.round(value * 100.0) / 100.0
        // Always show two decimal places (e.g. 1.0 → "1.00").
        val absStr = rounded.toString()
        val parts = absStr.split('.')
        val whole = parts[0]
        val frac = (parts.getOrNull(1) ?: "").padEnd(2, '0').take(2)
        return "$whole.$frac"
    }
}

// ── Public Models ──────────────────────────────────────────────

data class GeoCoords(val lat: Double, val lng: Double)
data class GeoLocation(val lat: Double, val lng: Double, val displayName: String)

// ── Wire Models (private to this file) ─────────────────────────

@Serializable
private data class IpApiCoResponse(
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
private data class IpApiResponse(
    val status: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
private data class IpWhoIsResponse(
    val success: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
data class NominatimSearchResult(
    val lat: String = "",
    val lon: String = "",
    @SerialName("display_name")
    val displayName: String = "",
    val address: NominatimAddress? = null,
)

@Serializable
data class NominatimReverseResult(
    val address: NominatimAddress? = null,
)

@Serializable
data class NominatimAddress(
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val county: String? = null,
    val state: String? = null,
    val country: String? = null,
)
