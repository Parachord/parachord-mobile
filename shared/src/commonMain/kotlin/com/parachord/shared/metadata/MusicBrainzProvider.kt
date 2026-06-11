package com.parachord.shared.metadata

import com.parachord.shared.api.MbArtist
import com.parachord.shared.api.MbReleaseGroup
import com.parachord.shared.api.MbReleaseGroupEntry
import com.parachord.shared.api.MusicBrainzClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * MusicBrainz metadata provider.
 *
 * Free, no auth needed. Primary source for MBIDs and structured
 * metadata (discography, tracklists). Highest priority — tried first
 * because it's always available.
 *
 * Carries an in-memory artist-search cache with in-flight deduplication.
 * MusicBrainz rate limits at 1 req/s, so when multiple providers
 * (this one, [WikipediaProvider]) resolve the same artist concurrently
 * the deduplication saves seconds on artist-page loads. The cache
 * lives for the JVM-process / app-process lifetime — there's no
 * eviction; the artist-id space is small enough that unbounded growth
 * doesn't matter in practice.
 */
class MusicBrainzProvider(
    private val api: MusicBrainzClient,
) : MetadataProvider {

    /**
     * In-memory artist-search cache with in-flight deduplication.
     * Mutex-guarded `MutableMap` rather than a `ConcurrentHashMap` so
     * the class compiles on KMP common — no `java.util.concurrent` in
     * shared code. Lock scope is tiny (only the put-or-get window),
     * so contention is negligible compared to the network call itself.
     */
    private val cacheMutex = Mutex()
    private val artistSearchCache = mutableMapOf<String, CompletableDeferred<MbArtist?>>()

    /**
     * Resolve an artist name to their MusicBrainz artist record, with caching.
     * Safe to call concurrently — duplicate in-flight requests are coalesced.
     */
    suspend fun resolveArtist(artistName: String): MbArtist? {
        val key = artistName.lowercase().trim()

        // Acquire-or-create the in-flight deferred under the lock; resolve
        // outside the lock so a slow network call never blocks the cache.
        val (deferred, isOwner) = cacheMutex.withLock {
            val existing = artistSearchCache[key]
            if (existing != null) {
                existing to false
            } else {
                val fresh = CompletableDeferred<MbArtist?>()
                artistSearchCache[key] = fresh
                fresh to true
            }
        }

        if (!isOwner) return deferred.await()

        return try {
            val result = api.searchArtists(artistName, limit = 1).artists.firstOrNull()
            deferred.complete(result)
            result
        } catch (_: Exception) {
            deferred.complete(null)
            null
        }
    }

    override val name = "musicbrainz"
    override val priority = 0

    override suspend fun isAvailable(): Boolean = true

    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> =
        try {
            api.searchRecordings(query, limit).recordings.map { rec ->
                TrackSearchResult(
                    title = rec.title,
                    artist = rec.artistName,
                    album = rec.albumTitle,
                    duration = rec.length,
                    mbid = rec.id,
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> =
        try {
            api.searchReleases(query, limit).releases.map { rel ->
                AlbumSearchResult(
                    title = rel.title,
                    artist = rel.artistName,
                    artworkUrl = coverArtUrl(rel.id),
                    year = rel.year,
                    trackCount = rel.trackCount,
                    mbid = rel.id,
                    releaseType = normalizeReleaseType(rel.releaseGroup),
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> =
        try {
            api.searchArtists(query, limit).artists.map { a ->
                ArtistInfo(
                    name = a.name,
                    mbid = a.id,
                    tags = a.tags.sortedByDescending { it.count }.take(5).map { it.name },
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun getArtistInfo(artistName: String): ArtistInfo? =
        try {
            val a = resolveArtist(artistName) ?: return null
            ArtistInfo(
                name = a.name,
                mbid = a.id,
                tags = a.tags.sortedByDescending { it.count }.take(5).map { it.name },
                provider = name,
            )
        } catch (_: Exception) {
            null
        }

    override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? {
        return try {
            // Search for the release to get its MBID
            val query = "release:\"$albumTitle\" AND artist:\"$artistName\""
            val results = api.searchReleases(query, limit = 1)
            val release = results.releases.firstOrNull() ?: return null

            // Look up the release with recordings included
            val detail = api.getRelease(release.id)
            val tracks = detail.media.flatMap { it.tracks }
            if (tracks.isEmpty()) return null

            val artwork = coverArtUrl(detail.id)

            AlbumDetail(
                title = detail.title,
                artist = detail.artistName,
                artworkUrl = artwork,
                year = detail.year,
                releaseType = normalizeReleaseType(release.releaseGroup),
                tracks = tracks.map { t ->
                    TrackSearchResult(
                        title = t.recording?.title ?: t.title,
                        artist = t.artistName.ifBlank { detail.artistName },
                        album = detail.title,
                        duration = t.length ?: t.recording?.length,
                        artworkUrl = artwork,
                        mbid = t.recording?.id ?: t.id,
                        provider = name,
                    )
                },
                provider = name,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get an artist's discography via MusicBrainz release-groups (not releases).
     *
     * Release-groups are deduplicated (one entry per album, not per pressing/country).
     * Steps: 1) search artist to get MBID, 2) browse release-groups by MBID.
     */
    override suspend fun getArtistAlbums(artistName: String, limit: Int): List<AlbumSearchResult> =
        try {
            val artistMbid = resolveArtistMbid(artistName) ?: return emptyList()
            val response = api.browseReleaseGroups(artistId = artistMbid, limit = limit)
            val artistLower = artistName.lowercase()
            response.releaseGroups
                // Filter out releases where the artist credit doesn't match
                // (e.g. "Various Artists" compilations the artist merely appears on)
                .filter { rg ->
                    val creditLower = rg.artistName.lowercase()
                    creditLower == artistLower ||
                        creditLower.contains(artistLower) ||
                        artistLower.contains(creditLower)
                }
                // Filter out DJ-mixes, remixes, etc.
                .filter { rg ->
                    val secondaryLower = rg.secondaryTypes.map { it.lowercase() }
                    "dj-mix" !in secondaryLower &&
                        "remix" !in secondaryLower &&
                        "mixtape/street" !in secondaryLower
                }
                .map { rg ->
                    AlbumSearchResult(
                        title = rg.title,
                        artist = rg.artistName.ifBlank { artistName },
                        artworkUrl = releaseGroupArtUrl(rg.id),
                        year = rg.year,
                        mbid = rg.id,
                        releaseType = normalizeReleaseGroupEntry(rg),
                        secondaryTypes = rg.secondaryTypes,
                        provider = name,
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }

    /** Resolve an artist name to their MusicBrainz MBID (uses cached search). */
    private suspend fun resolveArtistMbid(artistName: String): String? =
        resolveArtist(artistName)?.id

    companion object {
        /** Cover Art Archive front cover URL for a release. */
        fun coverArtUrl(mbid: String): String =
            "https://coverartarchive.org/release/$mbid/front-250"

        /** Cover Art Archive front cover URL for a release-group. */
        fun releaseGroupArtUrl(mbid: String): String =
            "https://coverartarchive.org/release-group/$mbid/front-250"

        /**
         * Normalize MusicBrainz release-group types to our canonical types:
         * "album", "single", "ep", "live", "compilation".
         */
        fun normalizeReleaseType(rg: MbReleaseGroup?): String? {
            if (rg == null) return null
            val secondary = rg.secondaryTypes.firstOrNull()?.lowercase()
            if (secondary == "live") return "live"
            if (secondary == "compilation") return "compilation"
            return when (rg.primaryType?.lowercase()) {
                "album" -> "album"
                "single" -> "single"
                "ep" -> "ep"
                else -> rg.primaryType?.lowercase()
            }
        }

        /** Normalize a release-group entry (from browse API) to canonical type. */
        fun normalizeReleaseGroupEntry(rg: MbReleaseGroupEntry): String? {
            val secondary = rg.secondaryTypes.firstOrNull()?.lowercase()
            if (secondary == "live") return "live"
            if (secondary == "compilation") return "compilation"
            return when (rg.primaryType?.lowercase()) {
                "album" -> "album"
                "single" -> "single"
                "ep" -> "ep"
                else -> rg.primaryType?.lowercase()
            }
        }
    }
}
