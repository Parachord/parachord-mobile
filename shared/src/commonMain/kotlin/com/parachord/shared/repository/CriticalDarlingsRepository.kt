package com.parachord.shared.repository

import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.metadata.ImageEnrichmentService
import com.parachord.shared.metadata.MusicBrainzProvider
import com.parachord.shared.model.Resource
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository for Critical Darlings — top-rated albums from leading music publications.
 *
 * Mirrors the desktop app's `loadCriticsPicks()` / `parseCriticsPicksRSS()` implementation:
 * 1. Fetch RSS feed from rssground.com/p/uncoveries
 * 2. Parse each item as "Album Title by Artist Name"
 * 3. Progressively fetch album art via Cover Art Archive (MusicBrainz release search → MBID → CAA)
 *
 * Migrated from OkHttp + `XmlPullParser` + `java.util.Date` to:
 *  - Shared Ktor `HttpClient` for the RSS fetch
 *  - Regex-based RSS item extraction (RSS structure is simple enough that
 *    pulling in a KMP XML library — `xmlutil` — for one feed isn't worth
 *    the dep weight; CDATA sections are handled inline)
 *  - `pubDate: Date?` dropped entirely — the field was never displayed
 *    by the UI. The custom `KSerializer` surrogate that handled
 *    `Date → pubDateMs` round-tripping went with it; existing on-disk
 *    caches with the `pubDateMs` key are tolerated via
 *    `ignoreUnknownKeys = true` (worst case is one stale-cache miss).
 *
 * File I/O for the disk cache flows through the same suspend-lambda
 * pattern established by `ConcertsRepository` and `FreshDropsRepository`.
 */
class CriticalDarlingsRepository(
    private val httpClient: HttpClient,
    private val musicBrainzClient: MusicBrainzClient,
    /**
     * Cascade fallback when the strict MusicBrainz search returns no
     * release for a given album/artist (or returns one whose CAA URL is
     * empty). Routes through `metadataService.getAlbumTracks` which
     * pulls Last.fm + Spotify in addition to MB, so the few albums that
     * MB doesn't index still get art from image-rich providers.
     */
    private val imageEnrichmentService: ImageEnrichmentService,
    /** Read JSON from `critical_darlings_cache.json`; null if missing/fails. */
    private val cacheRead: suspend () -> String?,
    /** Write JSON to `critical_darlings_cache.json`. Failures are swallowed. */
    private val cacheWrite: suspend (String) -> Unit,
) {
    companion object {
        private const val TAG = "CriticalDarlingsRepo"
        private const val RSS_URL = "https://www.rssground.com/p/uncoveries"
        /** Short interval to prevent re-fetching when navigating back and forth quickly. */
        private const val MIN_REFETCH_INTERVAL = 5 * 60 * 1000L // 5 minutes
    }

    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** In-memory cache. */
    private var cachedAlbums: List<CriticsPickAlbum>? = null
    private var lastFetchedAt: Long = 0L
    private var diskCacheLoaded = false

    /**
     * Cached albums available without a coroutine context (for ViewModel
     * `init` blocks). Lazy disk load triggered by next suspend call —
     * same trade-off as `ConcertsRepository.cached`.
     */
    val cached: List<CriticsPickAlbum>? get() = cachedAlbums

    private suspend fun loadDiskCache() {
        diskCacheLoaded = true
        try {
            val body = cacheRead() ?: return
            val wrapper = diskJson.decodeFromString<CriticalDarlingsDiskCache>(body)
            // Heal any stale `&amp;` (and friends) that previous app
            // versions saved into the cache before the parse-time
            // entity-decode fix landed. Without this, "Nine Inch Nails
            // &amp; Boys Noize" rows would persist with the literal
            // `&amp;` in the artist field forever — display-broken AND
            // unable to match the MB query (which would search for the
            // literal `&amp;` and return nothing).
            cachedAlbums = wrapper.albums.map { album ->
                val cleanTitle = decodeHtmlEntities(album.title)
                val cleanArtist = decodeHtmlEntities(album.artist)
                if (cleanTitle != album.title || cleanArtist != album.artist) {
                    album.copy(title = cleanTitle, artist = cleanArtist)
                } else album
            }
            lastFetchedAt = wrapper.fetchedAt
            Log.d(TAG, "Loaded ${wrapper.albums.size} cached critics' picks from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load disk cache", e)
        }
    }

    private suspend fun saveDiskCache(albums: List<CriticsPickAlbum>, fetchedAt: Long) {
        try {
            cacheWrite(diskJson.encodeToString(CriticalDarlingsDiskCache(albums, fetchedAt)))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save disk cache", e)
        }
    }

    /**
     * Get critics' picks albums, with progressive album art loading.
     * Emits an initial list (without art), then re-emits as each album's art is resolved.
     */
    fun getCriticsPicks(forceRefresh: Boolean = false): Flow<Resource<List<CriticsPickAlbum>>> = flow {
        if (!diskCacheLoaded) loadDiskCache()
        try {
            val now = currentTimeMillis()
            val recentlyFetched = now - lastFetchedAt < MIN_REFETCH_INTERVAL

            // Show cache immediately (stale-while-revalidate)
            if (cachedAlbums != null) {
                emit(Resource.Success(cachedAlbums!!))
            } else {
                emit(Resource.Loading)
            }

            // Skip RSS re-fetch if we fetched very recently (prevents
            // hammering on quick nav) — but still try to repair any
            // albums whose art came back null on a previous attempt.
            // Without this loop, an album that failed art lookup once
            // would sit broken until the next 5-minute window expired,
            // even though the cascade fallback could resolve it now.
            if (!forceRefresh && recentlyFetched && cachedAlbums != null) {
                enrichMissingArt()
                return@flow
            }

            val albums = fetchAndParseRSS()

            if (albums.isEmpty()) {
                // Keep showing cached data if available, only error on truly empty
                if (cachedAlbums == null) {
                    emit(Resource.Error("No critics' picks available"))
                }
                return@flow
            }

            // Carry over album art from cached albums so we don't re-fetch everything
            val oldArtByKey = cachedAlbums?.associateBy(
                { "${it.title.lowercase()}|${it.artist.lowercase()}" },
                { it.albumArt },
            ) ?: emptyMap()
            val mergedAlbums = albums.map { album ->
                val cachedArt = oldArtByKey["${album.title.lowercase()}|${album.artist.lowercase()}"]
                if (cachedArt != null) album.copy(albumArt = cachedArt) else album
            }

            cachedAlbums = mergedAlbums
            lastFetchedAt = now
            saveDiskCache(mergedAlbums, now)
            emit(Resource.Success(mergedAlbums))

            enrichMissingArt()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load critics' picks", e)
            emit(Resource.Error("Failed to load critics' picks"))
        }
    }

    /**
     * Progressively fetch album art for any cached album with a null
     * `albumArt`, emitting after each successful resolution. Idempotent
     * — re-running with everything filled in is a no-op (the
     * `albumArt == null` filter short-circuits). Rate-limited at 200ms
     * between requests to honour MusicBrainz's 1-req/sec average.
     *
     * Run from both the post-RSS-fetch path AND the stale-cache path
     * (when the 5-min refetch throttle would otherwise prevent any
     * recovery for albums whose first art lookup failed).
     */
    private suspend fun FlowCollector<Resource<List<CriticsPickAlbum>>>.enrichMissingArt() {
        val current = cachedAlbums ?: return
        val mutableAlbums = current.toMutableList()
        val toEnrich = mutableAlbums.withIndex().filter { it.value.albumArt == null }
        if (toEnrich.isEmpty()) return

        for ((index, album) in toEnrich) {
            try {
                val artUrl = fetchAlbumArt(album.title, album.artist)
                if (artUrl != null) {
                    mutableAlbums[index] = album.copy(albumArt = artUrl)
                    cachedAlbums = mutableAlbums.toList()
                    emit(Resource.Success(mutableAlbums.toList()))
                }
                // Rate limit MusicBrainz requests (1 req/sec policy)
                delay(200)
            } catch (_: Exception) { /* skip art for this album */ }
        }
        // Save final enriched data to disk
        cachedAlbums?.let { saveDiskCache(it, lastFetchedAt) }
    }

    /**
     * Fetch and parse the RSS feed via the shared Ktor client.
     * Each RSS item title is in the format "Album Title by Artist Name".
     * Mirrors desktop's `parseCriticsPicksRSS()`.
     */
    private suspend fun fetchAndParseRSS(): List<CriticsPickAlbum> {
        val response = try {
            httpClient.get(RSS_URL)
        } catch (e: Exception) {
            Log.w(TAG, "RSS fetch failed: ${e.message}")
            return emptyList()
        }
        if (!response.status.isSuccess()) {
            Log.w(TAG, "RSS fetch failed: ${response.status.value}")
            return emptyList()
        }
        return parseRSS(response.bodyAsText())
    }

    /**
     * Regex-based RSS item extraction. The feed structure is simple
     * (`<item><title>...</title><link>...</link><description>...</description>...</item>`)
     * and stable enough that a full XML parser isn't worth the KMP dep
     * weight. CDATA sections are stripped inline.
     */
    private fun parseRSS(xml: String): List<CriticsPickAlbum> {
        val albums = mutableListOf<CriticsPickAlbum>()
        val seen = mutableSetOf<String>()
        try {
            val itemRegex = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            val titleRegex = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            val linkRegex = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
            val descRegex = Regex("<description>(.*?)</description>", RegexOption.DOT_MATCHES_ALL)

            for (itemMatch in itemRegex.findAll(xml)) {
                val itemXml = itemMatch.groupValues[1]
                // Decode HTML entities BEFORE parsing — RSS escapes
                // ampersands (and friends) inside the title element, so
                // a title like "Foo by Nine Inch Nails &amp; Boys
                // Noize" would otherwise carry through to the displayed
                // artist name AND poison the MB search query (which
                // looks up the literal `&amp;` and finds nothing).
                val title = titleRegex.find(itemXml)?.groupValues?.get(1)
                    ?.let(::stripCdata)
                    ?.let(::decodeHtmlEntities)
                    ?.trim() ?: continue
                val link = linkRegex.find(itemXml)?.groupValues?.get(1)?.let(::stripCdata)?.trim()
                val description = descRegex.find(itemXml)?.groupValues?.get(1)?.let(::stripCdata)?.trim() ?: ""

                val parsed = parseTitle(title) ?: continue
                val id = "${parsed.first.lowercase()}|${parsed.second.lowercase()}"
                    .replace(Regex("[^a-z0-9|]"), "-")
                if (!seen.add(id)) continue

                albums.add(
                    CriticsPickAlbum(
                        id = "critics-$id",
                        title = parsed.first,
                        artist = parsed.second,
                        link = link,
                        blurb = cleanHtml(description),
                        spotifyUrl = extractSpotifyUrl(description),
                    ),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "RSS parse error", e)
        }
        Log.d(TAG, "Parsed ${albums.size} critics' picks from RSS")
        return albums
    }

    /** Strip `<![CDATA[ ... ]]>` wrappers from extracted XML text. */
    private fun stripCdata(s: String): String =
        s.replace("<![CDATA[", "").replace("]]>", "")

    /**
     * Parse "Album Title by Artist Name" format from RSS item title.
     * Returns (albumTitle, artistName) or null if format doesn't match.
     */
    private fun parseTitle(raw: String): Pair<String, String>? {
        // Normalise whitespace — the RSS feed uses literal newlines between album and "by Artist"
        val normalised = raw.replace(Regex("\\s+"), " ").trim()
        // Desktop uses: title split on " by " — last occurrence to handle "Stand By Me by Ben E. King"
        val idx = normalised.lastIndexOf(" by ")
        if (idx <= 0) return null
        val album = normalised.substring(0, idx).trim()
        val artist = normalised.substring(idx + 4).trim()
        if (album.isBlank() || artist.isBlank()) return null
        return album to artist
    }

    /** Extract Spotify URL from HTML description (matching desktop). */
    private fun extractSpotifyUrl(html: String): String? {
        val regex = Regex("""https?://open\.spotify\.com/album/[a-zA-Z0-9]+""")
        return regex.find(html)?.value
    }

    /**
     * Decode the small set of HTML entities that the RSS feed actually
     * uses. We decode ampersand last so we don't accidentally double-
     * decode something like `&amp;lt;` (which should become `&lt;`,
     * not `<`).
     */
    internal fun decodeHtmlEntities(s: String): String {
        var out = s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        // Generic numeric entities — decimal (&#39; AND zero-padded &#039;,
        // &#8217; curly apostrophe, &#8212; em-dash …) and hex (&#x27;). The
        // RSS feed uses zero-padded forms the old explicit "&#39;" missed.
        out = Regex("&#(\\d+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 0..0xFFFF }?.toChar()?.toString() ?: m.value
        }
        out = Regex("&#[xX]([0-9a-fA-F]+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.takeIf { it in 0..0xFFFF }?.toChar()?.toString() ?: m.value
        }
        // &amp; last so a literal "&" isn't re-interpreted as an entity prefix.
        return out.replace("&amp;", "&")
    }

    /** Strip HTML tags, decode entities, and remove leftover URLs. */
    private fun cleanHtml(html: String): String {
        return decodeHtmlEntities(html.replace(Regex("<[^>]+>"), ""))
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    /**
     * Fetch album art with a verify-then-cascade strategy:
     *   1. MusicBrainz release search → Cover Art Archive URL (fast,
     *      cheap — one HTTP call, well-indexed albums hit this).
     *   2. [ImageEnrichmentService.resolveAlbumArtUrl] HEAD-checks the
     *      CAA URL; on 404 it falls through to the full
     *      MetadataService cascade (Last.fm + Spotify in addition to
     *      MB), which catches albums MB doesn't index well or release
     *      groups CAA hasn't received a front cover for yet.
     *
     * Mirrors desktop's `getAlbumArt()` for stage 1; the verify +
     * cascade fallback are the "retry on first-attempt failure"
     * extension — without them, every album with a missing CAA cover
     * sat with a placeholder forever.
     */
    private suspend fun fetchAlbumArt(albumTitle: String, artistName: String): String? {
        val caaUrl: String? = try {
            val query = "\"$albumTitle\" AND artist:\"$artistName\""
            val results = musicBrainzClient.searchReleases(query, limit = 1)
            results.releases.firstOrNull()?.let { MusicBrainzProvider.coverArtUrl(it.id) }
        } catch (e: Exception) {
            Log.w(TAG, "MB search failed for '$albumTitle' by '$artistName': ${e.message}")
            null
        }
        return imageEnrichmentService.resolveAlbumArtUrl(albumTitle, artistName, hint = caaUrl)
    }
}

/** Disk cache wrapper for JSON serialization. */
@Serializable
private data class CriticalDarlingsDiskCache(
    val albums: List<CriticsPickAlbum>,
    val fetchedAt: Long,
)

/**
 * A critics' pick album from the RSS feed.
 *
 * The previous `pubDate: Date?` field + custom `KSerializer` surrogate
 * was removed in the move to shared — the field was never read by the
 * UI, only parsed and stored. Existing on-disk caches with the
 * `pubDateMs` key are tolerated via `ignoreUnknownKeys = true`; worst
 * case is one stale-cache miss.
 */
@Serializable
data class CriticsPickAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val link: String? = null,
    // NB: `blurb`, not `description` — a Swift `description` property is shadowed
    // by NSObject.description on iOS (AGENTS.md), so Swift would read back
    // toString() instead of the critic blurb.
    val blurb: String = "",
    val spotifyUrl: String? = null,
    val albumArt: String? = null,
)
