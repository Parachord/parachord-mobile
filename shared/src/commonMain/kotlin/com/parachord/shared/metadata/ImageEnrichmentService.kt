package com.parachord.shared.metadata

import com.parachord.shared.db.dao.AlbumDao
import com.parachord.shared.db.dao.ArtistDao
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.TrackDao
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Lazily enriches artist images and album artwork for collection items
 * that were synced without image URLs.
 *
 * Uses the cascading MetadataService to fetch images from available providers
 * (MusicBrainz/Cover Art Archive, Last.fm, Spotify) and persists them to the
 * Room database so they only need to be fetched once.
 *
 * In-flight requests are deduplicated so that multiple UI items requesting the
 * same artist/album image don't trigger redundant network calls.
 *
 * KMP migration notes:
 *  - 2x2 mosaic generation is platform-specific (Coil + Bitmap on Android,
 *    Kingfisher/CoreGraphics on iOS), so the mosaic step is forwarded via a
 *    `composeMosaic` suspend lambda. Shared code handles tracklist gathering,
 *    bounded-concurrency search fallbacks, DAO writes, and cache-busting; the
 *    platform fills in only the bitmap composite + JPEG write + URI return.
 *  - `ConcurrentHashMap<String, Deferred<String?>>` → `Mutex` + `MutableMap`
 *    (JVM-only concurrent collections aren't in commonMain).
 *  - `Dispatchers.IO` → `Dispatchers.Default` (commonMain only has Default + Main).
 */
class ImageEnrichmentService(
    private val metadataService: MetadataService,
    private val artistDao: ArtistDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    /**
     * Used by [resolveAlbumArtUrl] to HEAD-check candidate art URLs. The
     * shared Ktor client follows redirects (CAA → IA CDN) so a 2xx
     * response is sufficient evidence the URL serves an image.
     */
    private val httpClient: HttpClient,
    /**
     * Compose a 2x2 mosaic from the given image URLs (already padded to 4)
     * and persist it locally. Returns a URI/path the UI can render, or null
     * if the composite failed. Runs only when there are at least 2 distinct
     * source images.
     */
    private val composeMosaic: suspend (playlistId: String, urls: List<String>) -> String?,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private companion object {
        /** Bounded concurrency for the per-track search fallback in
         *  [enrichPlaylistArt] — keeps a 200-track XSPF from slamming
         *  Last.fm / MusicBrainz with simultaneous requests. */
        const val MAX_TRACK_SEARCH_CONCURRENCY = 4

        /** Bounded concurrency for [regenerateAllPlaylistMosaics] — at
         *  most this many mosaic builds running in parallel. Each mosaic
         *  fetches up to 4 album-art tiles, so 4 parallel = up to 16
         *  concurrent image fetches. */
        const val MAX_PLAYLIST_REGEN_CONCURRENCY = 4
    }

    // In-flight deduplication maps (Mutex-guarded since commonMain has no
    // ConcurrentHashMap).
    private val artistMutex = Mutex()
    private val artistFetches = mutableMapOf<String, Deferred<String?>>()
    private val albumMutex = Mutex()
    private val albumFetches = mutableMapOf<String, Deferred<String?>>()
    private val trackMutex = Mutex()
    private val trackFetches = mutableMapOf<String, Deferred<String?>>()
    private val playlistMutex = Mutex()
    private val playlistFetches = mutableMapOf<String, Deferred<String?>>()

    /**
     * Fetch and persist an artist's image if missing.
     * Returns the image URL or null if none found.
     */
    suspend fun enrichArtistImage(artistName: String): String? {
        val key = artistName.lowercase().trim()
        val deferred = artistMutex.withLock {
            val cur = artistFetches[key]
            if (cur != null && cur.isActive) return@withLock cur
            val d = scope.async {
                try {
                    // Fast image-only path (streaming-first, short-circuits) — NOT
                    // the full getArtistInfo cascade, which awaitAll's the slow
                    // Wikipedia MB→Wikidata chain just to read an image.
                    val imageUrl = metadataService.getArtistImage(artistName)
                    if (imageUrl != null) {
                        artistDao.updateImageByName(artistName, imageUrl)
                    }
                    imageUrl
                } catch (_: Exception) {
                    null
                } finally {
                    artistMutex.withLock { artistFetches.remove(key) }
                }
            }
            artistFetches[key] = d
            d
        }
        return deferred.await()
    }

    /**
     * HEAD-check an image URL. Returns true on a 2xx response, false on
     * 4xx/5xx or any network/timeout error.
     *
     * Used by [resolveAlbumArtUrl] to verify Cover Art Archive URLs
     * before surfacing them to the UI — CAA returns 404 for release
     * groups whose front cover hasn't been uploaded, and Coil renders
     * those as a placeholder rather than retrying the cascade.
     */
    suspend fun verifyArtUrl(url: String): Boolean {
        return try {
            // Ktor follows redirects by default — a HEAD that resolves
            // to 2xx after the CAA → IA CDN redirect chain means an
            // image exists at the final location.
            httpClient.head(url).status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolve a "best-effort" album art URL with a verify-then-cascade
     * retry strategy. Used by repositories surfacing external albums
     * (Critical Darlings, Fresh Drops) where the first-attempt URL
     * (typically a Cover Art Archive URL constructed from a release
     * group MBID) sometimes 404s — those albums then sat with a broken
     * image and no path to recovery.
     *
     * Strategy:
     *   1. If [hint] is non-null, [verifyArtUrl] it. On 2xx, return
     *      the hint as-is — we trust providers' first answers when
     *      they actually serve an image.
     *   2. Otherwise (or if the hint failed verification), fall through
     *      to [lookupAlbumArt] which cascades the full
     *      MetadataService stack (MusicBrainz → Last.fm → Spotify) to
     *      find an art URL from a richer provider.
     *
     * Returns null only if every stage failed.
     */
    suspend fun resolveAlbumArtUrl(
        albumTitle: String,
        artistName: String,
        hint: String? = null,
    ): String? {
        if (hint != null && verifyArtUrl(hint)) return hint
        return lookupAlbumArt(albumTitle, artistName)
    }

    /**
     * Cascade-lookup album art *without* writing to any DAO. Used by
     * repositories that surface external albums (Critical Darlings,
     * Fresh Drops) where the album isn't in the user's library and an
     * `albumDao.updateArtworkByTitleAndArtist` write would be a no-op
     * at best and stomp the wrong row at worst.
     *
     * Cascade: `getAlbumTracks` first (full tracklist + artwork from
     * MB→Last.fm→Spotify), then `searchAlbums`, then `searchTracks`. Each
     * stage's candidate must pass [usableArt]: MusicBrainz always builds a
     * Cover Art Archive URL whether or not a cover was uploaded, so a CAA
     * candidate is HEAD-checked and a 404 falls through to the next stage
     * instead of being returned (and stored) as the album's art.
     *
     * [exclude] skips a URL already known to be dead ([BrokenArtRetry]).
     * In-flight lookups for the same album are shared.
     *
     * Returns the first usable artwork URL across the cascade, or null.
     */
    suspend fun lookupAlbumArt(albumTitle: String, artistName: String, exclude: String? = null): String? {
        val key = "${albumTitle.lowercase().trim()}|${artistName.lowercase().trim()}|${exclude.orEmpty()}"
        val deferred = albumMutex.withLock {
            val cur = albumFetches[key]
            if (cur != null && cur.isActive) return@withLock cur
            val d = scope.async {
                try {
                    // Detail lookup first (preferred — gets richer providers'
                    // artwork through the full track-list response).
                    val detail = metadataService.getAlbumTracks(albumTitle, artistName)
                    detail?.artworkUrl?.let { if (usableArt(it, exclude)) return@async it }

                    // Fall back to album search across the same cascade.
                    val searchResults = metadataService.searchAlbums(
                        "$albumTitle $artistName",
                        limit = 5,
                    )
                    val albumCandidates = listOfNotNull(
                        searchResults.firstOrNull {
                            it.title.equals(albumTitle, ignoreCase = true) &&
                                it.artist.equals(artistName, ignoreCase = true)
                        }?.artworkUrl,
                        searchResults.firstOrNull()?.artworkUrl,
                    ).distinct()
                    albumCandidates.firstOrNull { usableArt(it, exclude) }?.let { return@async it }

                    // Last resort: track-level search. Some obscure
                    // singles (e.g. one-off bandcamp drops) aren't
                    // indexed as albums on MusicBrainz / Last.fm, and
                    // Spotify's `album.search` may also miss them, but
                    // Spotify's `track.search` finds them and returns
                    // the parent album's `images[]`. This mirrors what
                    // [enrichTrackArt] does on the playback path — Now
                    // Playing reliably gets art for these albums while
                    // album-level callers (Album detail screen, Critical
                    // Darlings, Fresh Drops) used to come up empty.
                    val trackResults = metadataService.searchTracks(
                        "$artistName $albumTitle",
                        limit = 5,
                    )
                    listOfNotNull(
                        trackResults.firstOrNull {
                            it.album?.equals(albumTitle, ignoreCase = true) == true &&
                                it.artist.equals(artistName, ignoreCase = true)
                        }?.artworkUrl,
                        trackResults.firstOrNull()?.artworkUrl,
                    ).distinct().firstOrNull { usableArt(it, exclude) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } finally {
                    albumMutex.withLock { albumFetches.remove(key) }
                }
            }
            albumFetches[key] = d
            d
        }
        return deferred.await()
    }

    /**
     * Can [url] be shown (and stored) as cover art? Not blank, not the known-dead
     * [exclude], and — for Cover Art Archive URLs, which MusicBrainz builds
     * without knowing whether a cover exists — actually serving an image.
     * Other providers' URLs come from a real image listing, so they're trusted
     * without a HEAD.
     */
    private suspend fun usableArt(url: String, exclude: String?): Boolean {
        if (url.isBlank() || url == exclude) return false
        if ("coverartarchive.org" !in url) return true
        return verifyArtUrl(url)
    }

    /**
     * Fetch and persist an album's artwork if missing.
     * Returns the artwork URL or null if none found.
     */
    suspend fun enrichAlbumArt(albumTitle: String, artistName: String): String? {
        val artworkUrl = lookupAlbumArt(albumTitle, artistName) ?: return null
        albumDao.updateArtworkByTitleAndArtist(albumTitle, artistName, artworkUrl)
        return artworkUrl
    }

    /**
     * Fetch artwork for a track that has no artworkUrl.
     * Tries album art lookup first (by album+artist), then falls back to search.
     * Persists the result to the tracks table so it's cached for future plays.
     * Returns the artwork URL or null if none found.
     */
    suspend fun enrichTrackArt(trackId: String, trackTitle: String, artistName: String, albumTitle: String?): String? {
        val deferred = trackMutex.withLock {
            val cur = trackFetches[trackId]
            if (cur != null && cur.isActive) return@withLock cur
            val d = scope.async {
                try {
                    var artworkUrl: String? = null

                    // Try album art first (most likely to have artwork). An
                    // unverified Cover Art Archive URL (MusicBrainz builds one
                    // whether or not a cover exists) isn't stored — it falls
                    // through to track search instead.
                    if (albumTitle != null) {
                        val detail = metadataService.getAlbumTracks(albumTitle, artistName)
                        artworkUrl = detail?.artworkUrl?.takeIf { usableArt(it, null) }
                    }

                    // Fall back to track search
                    if (artworkUrl == null) {
                        val results = metadataService.searchTracks("$artistName $trackTitle", limit = 5)
                        artworkUrl = listOfNotNull(
                            results.firstOrNull {
                                it.title.equals(trackTitle, ignoreCase = true) &&
                                    it.artist.equals(artistName, ignoreCase = true)
                            }?.artworkUrl,
                            results.firstOrNull()?.artworkUrl,
                        ).distinct().firstOrNull { usableArt(it, null) }
                    }

                    if (artworkUrl != null) {
                        trackDao.updateArtworkById(trackId, artworkUrl)
                    }
                    artworkUrl
                } catch (_: Exception) {
                    null
                } finally {
                    trackMutex.withLock { trackFetches.remove(trackId) }
                }
            }
            trackFetches[trackId] = d
            d
        }
        return deferred.await()
    }

    /**
     * Generate and persist a 2x2 mosaic artwork for a playlist that has no cover.
     * Collects up to 4 unique track artwork URLs from the playlist's tracks,
     * delegates the actual bitmap composite to the platform's `composeMosaic`
     * lambda, and updates the playlist's artworkUrl in the database.
     *
     * Returns the composed local URI or null if not enough artwork is available.
     */
    suspend fun enrichPlaylistArt(playlistId: String, cacheBustToken: String? = null): String? {
        val deferred = playlistMutex.withLock {
            val cur = playlistFetches[playlistId]
            if (cur != null && cur.isActive) return@withLock cur
            val d = scope.async {
                try {
                    var tracks = playlistTrackDao.getByPlaylistIdSync(playlistId)

                    // XSPF imports don't carry per-track image data, so most rows
                    // come in with `trackArtworkUrl = null`. Two-pass enrichment:
                    //
                    //   1. Album-art lookup (cheap — one call per unique
                    //      `(album, artist)` pair) for tracks that have an
                    //      `<album>` tag.
                    //   2. Per-track search fallback for the remainder. Some XSPFs
                    //      (e.g. radio-station rewinds) ship without `<album>`
                    //      tags at all; without this pass the mosaic stays empty
                    //      forever and every row shows the placeholder.
                    //
                    // Both passes persist into `playlist_tracks.trackArtworkUrl`
                    // via the COALESCE-style `updateTrackArtwork` query, so
                    // subsequent loads skip the network entirely. Bounded
                    // concurrency (4) keeps us from hammering Last.fm /
                    // MusicBrainz on a 200-track playlist.

                    val needsAnyArt = tracks.filter { it.trackArtworkUrl.isNullOrBlank() }
                    if (needsAnyArt.isNotEmpty()) {
                        // Pass 1: album-based enrichment (deduped by album+artist)
                        val withAlbum = needsAnyArt.filter { !it.trackAlbum.isNullOrBlank() }
                        if (withAlbum.isNotEmpty()) {
                            val albumArtCache = mutableMapOf<Pair<String, String>, String?>()
                            for (key in withAlbum.map { (it.trackAlbum ?: "") to it.trackArtist }.distinct()) {
                                val (album, artist) = key
                                albumArtCache[key] = try {
                                    metadataService.getAlbumTracks(album, artist)?.artworkUrl
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            for (track in withAlbum) {
                                val art = albumArtCache[(track.trackAlbum ?: "") to track.trackArtist]
                                    ?: continue
                                playlistTrackDao.updateTrackArtwork(playlistId, track.position, track.trackTitle, track.trackArtist, art)
                            }
                        }

                        // Pass 2: track-search fallback for rows still missing art.
                        // Re-read first so we don't re-enrich rows the album pass
                        // already filled.
                        val stillMissing = playlistTrackDao.getByPlaylistIdSync(playlistId)
                            .filter { it.trackArtworkUrl.isNullOrBlank() }
                        if (stillMissing.isNotEmpty()) {
                            val sem = Semaphore(MAX_TRACK_SEARCH_CONCURRENCY)
                            coroutineScope {
                                stillMissing.map { track ->
                                    async {
                                        sem.withPermit {
                                            val art = try {
                                                val results = metadataService.searchTracks(
                                                    "${track.trackArtist} ${track.trackTitle}",
                                                    limit = 5,
                                                )
                                                results.firstOrNull {
                                                    it.title.equals(track.trackTitle, ignoreCase = true) &&
                                                        it.artist.equals(track.trackArtist, ignoreCase = true)
                                                }?.artworkUrl ?: results.firstOrNull()?.artworkUrl
                                            } catch (_: Exception) {
                                                null
                                            }
                                            if (art != null) {
                                                playlistTrackDao.updateTrackArtwork(playlistId, track.position, track.trackTitle, track.trackArtist, art)
                                            }
                                        }
                                    }
                                }.awaitAll()
                            }
                        }

                        // Re-read so the mosaic builds from the now-enriched rows.
                        tracks = playlistTrackDao.getByPlaylistIdSync(playlistId)
                    }

                    // Collect up to 4 unique artwork URLs
                    val artworkUrls = tracks
                        .mapNotNull { it.trackArtworkUrl }
                        .distinct()
                        .take(4)

                    if (artworkUrls.isEmpty()) return@async null

                    val resultUrl = if (artworkUrls.size == 1) {
                        // Only one unique artwork — just use it directly
                        artworkUrls.first()
                    } else {
                        // Generate 2x2 mosaic (pad to 4 by repeating if needed).
                        // Mosaic composite is platform-specific — see `composeMosaic`.
                        val paddedUrls = when (artworkUrls.size) {
                            2 -> listOf(artworkUrls[0], artworkUrls[1], artworkUrls[1], artworkUrls[0])
                            3 -> artworkUrls + listOf(artworkUrls[0])
                            else -> artworkUrls
                        }
                        composeMosaic(playlistId, paddedUrls)
                    }

                    // Append cache-buster so Coil's URL-keyed memory + disk
                    // cache treats a rewritten mosaic (same file path, new
                    // content) as a new image. `file://` fetchers ignore
                    // query params when reading the underlying File, so the
                    // path still resolves. Callers that don't care about
                    // invalidation (first-time enrichment) pass null and
                    // get the plain URL.
                    val finalUrl = if (resultUrl != null && cacheBustToken != null) {
                        val sep = if (resultUrl.contains('?')) '&' else '?'
                        "$resultUrl${sep}v=$cacheBustToken"
                    } else resultUrl

                    if (finalUrl != null) {
                        playlistDao.updateArtworkById(playlistId, finalUrl)
                    }
                    finalUrl
                } catch (_: Exception) {
                    null
                } finally {
                    playlistMutex.withLock { playlistFetches.remove(playlistId) }
                }
            }
            playlistFetches[playlistId] = d
            d
        }
        return deferred.await()
    }

    /**
     * Walk every playlist and regenerate the mosaic for any whose artwork
     * is NOT a locally-generated `file://` URL. Treats provider stock art
     * (Spotify's `mosaic.scdn.co/...`, Apple Music's `is1-ssl.mzstatic.com/
     * .../AM.PDCXS11.jpg` patterns) and missing artwork the same way: kick
     * off `enrichPlaylistArt` to generate a 2x2 album-art mosaic from the
     * playlist's tracks.
     *
     * Mosaic = "the sum of what's actually IN the playlist". Even when a
     * playlist is synced from a remote provider, we want the mosaic, not
     * the provider's auto-generated stock cover.
     *
     * Idempotent and bounded:
     * - Skips playlists already showing a `file://` mosaic (canonical state).
     * - Each call dedups against in-flight `enrichPlaylistArt` invocations
     *   via the existing `playlistFetches` mutex map.
     * - Bounded concurrency = [MAX_PLAYLIST_REGEN_CONCURRENCY] so a library
     *   with 100 synced playlists doesn't slam the network.
     *
     * Run from app start (`ParachordApplication.onCreate`) and after every
     * sync completion to keep mosaics fresh as new playlists arrive.
     */
    suspend fun regenerateAllPlaylistMosaics() {
        val playlists = playlistDao.getAllSync()
        val needsMosaic = playlists.filter { p ->
            val art = p.artworkUrl
            art.isNullOrBlank() || !art.startsWith("file://")
        }
        if (needsMosaic.isEmpty()) return

        val sem = Semaphore(MAX_PLAYLIST_REGEN_CONCURRENCY)
        coroutineScope {
            needsMosaic.map { p ->
                async {
                    sem.withPermit {
                        try {
                            // cacheBustToken = null: first-time generation
                            // case. The mosaic file is written under
                            // `filesDir/playlist_mosaics/<playlistId>.jpg`.
                            // Coil keys on URL; subsequent rewrites need a
                            // cache-bust, but a fresh write to a new path
                            // doesn't.
                            enrichPlaylistArt(p.id, cacheBustToken = null)
                        } catch (_: Exception) {
                            // Per-playlist failure is fine — try others.
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
