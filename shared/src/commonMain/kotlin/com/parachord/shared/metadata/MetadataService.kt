package com.parachord.shared.metadata

import com.parachord.shared.platform.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Cascading metadata service that aggregates results from multiple providers.
 *
 * Mirrors the desktop app's cascading provider pattern:
 * 1. MusicBrainz (free, always available, MBIDs)
 * 2. Last.fm (images, bios, similar artists)
 * 3. Spotify (album art, preview URLs — only when authenticated)
 *
 * For search: results from all available providers are merged, deduplicated,
 * and sorted by provider priority.
 *
 * For artist lookup: providers cascade — each fills in missing fields from
 * the previous provider's result.
 *
 * @param providers All metadata providers, sorted by priority.
 * @param getDisabledProviders Returns the set of provider names the user has disabled.
 * @param enrichAlbumArtwork Optional callback to enrich Cover Art Archive URLs with iTunes artwork.
 */
/**
 * Thrown by [MetadataService.getArtistAlbums] when the discography came back
 * empty BECAUSE a provider failed (e.g. MusicBrainz 503/429/network), as opposed
 * to the artist genuinely having no releases. Lets the UI show a friendly
 * "couldn't load — try again" message instead of a bare empty state.
 */
class DiscographyUnavailableException(
    message: String = "Couldn't load discography",
) : Exception(message)

class MetadataService constructor(
    private val providers: List<MetadataProvider>,
    private val getDisabledProviders: suspend () -> Set<String>,
    private val enrichAlbumArtwork: (suspend (artistName: String, albums: List<AlbumSearchResult>) -> List<AlbumSearchResult>)? = null,
    // Optional persistent artist-name → image-URL cache (shared so the URL lookup
    // is memoized across sessions on every platform — previously an iOS-only
    // artist-images.json). Read/write the whole JSON-map blob; null = no caching
    // (Android keeps its ImageEnrichmentService/DB path).
    private val artistImageCacheRead: (suspend () -> String?)? = null,
    private val artistImageCacheWrite: (suspend (String) -> Unit)? = null,
    /**
     * Persisted album tracklists (desktop `cache_release_details`: 30 days, 400
     * entries) and artist discographies (desktop `cache_artist_data`: 30 days).
     * Without them every launch refetched both from MusicBrainz at 1 req/s.
     * Null = no caching. Only complete answers are stored — see [getAlbumTracks]
     * and [getArtistAlbums].
     */
    private val albumTracksCache: com.parachord.shared.cache.PersistentJsonCache<AlbumDetail>? = null,
    private val artistAlbumsCache: com.parachord.shared.cache.PersistentJsonCache<List<AlbumSearchResult>>? = null,
    /**
     * The plugins' album catalogs (#424): track lists when the providers can't
     * supply one, and extra album search results when MusicBrainz finds few.
     * Null = no catalog fallback.
     */
    private val albumCatalog: AlbumCatalogLoader? = null,
    /** Artist info found this session, shared by every caller (#429). */
    private val artistInfoCache: ArtistInfoCache = ArtistInfoCache(),
    /**
     * The artist-photo chain's plugin step (desktop parachord#1141): the ids of
     * the active resolvers declaring `capabilities.artistImage`, in priority
     * order, and a call to one's `searchArtistImage`. Null = no plugin step.
     */
    private val artistImageResolvers: (suspend () -> List<String>)? = null,
    private val pluginArtistImage: (suspend (resolverId: String, artistName: String) -> String?)? = null,
    /** Deezer's public artist search (no account), the chain's step after the plugins. */
    private val deezer: DeezerArtistSearch? = null,
    /** "No source has a photo of this artist", remembered [ArtistImageChain.MISS_TTL_MS]. */
    private val artistImageMisses: ArtistImageChain.MissCache = ArtistImageChain.MissCache(),
) {

    private val artistImageMutex = Mutex()
    private var artistImageMap: MutableMap<String, String>? = null
    private val artistImageJson = Json { ignoreUnknownKeys = true }

    private companion object {
        const val TAG = "MetadataService"

        /** Desktop: MusicBrainz finding fewer than this many albums adds catalog results. */
        const val CATALOG_SEARCH_BELOW = 3

        /** Desktop MB_SLOW_FALLBACK_MS (parachord#1132). */
        const val CATALOG_SLOW_AFTER_MS = 5_000L

        /** Max time any single provider may take inside the getArtistInfo merge
         *  before it's dropped (null) — keeps a slow/hung provider from stalling
         *  the whole awaitAll. Fast providers return in <1s; this only cuts hangs. */
        const val PROVIDER_TIMEOUT_MS = 8_000L
    }

    /** Search tracks across all available providers in parallel, merge and deduplicate. */
    suspend fun searchTracks(query: String, limit: Int = 20): List<TrackSearchResult> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async {
                try { provider.searchTracks(query, limit) } catch (_: Exception) { emptyList() }
            } }
            .awaitAll()
            .flatten()

        deduplicateTracks(results).take(limit)
    }

    /**
     * Search albums across all available providers in parallel, merge and
     * deduplicate. When MusicBrainz finds fewer than three albums (or fails), the
     * plugins' album catalogs are searched too (desktop parachord#1129), so a
     * MusicBrainz outage doesn't empty the results.
     */
    suspend fun searchAlbums(query: String, limit: Int = 10): List<AlbumSearchResult> = coroutineScope {
        val active = availableProviders()
        val perProvider = active
            .map { provider -> async {
                provider.name to try { provider.searchAlbums(query, limit) } catch (_: Exception) { null }
            } }
            .awaitAll()
        val results = perProvider.flatMap { it.second.orEmpty() }

        val mbFound = perProvider.firstOrNull { it.first == "musicbrainz" }?.second?.size ?: 0
        val catalogResults = if (albumCatalog != null && mbFound < CATALOG_SEARCH_BELOW) {
            try { albumCatalog.search(query) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList()
            }
        } else emptyList()

        deduplicateAlbums(results + catalogResults).take(limit)
    }

    /**
     * The album page's track list (#424): [getAlbumTracksProgressively], plus the
     * plugins' album catalogs when the providers can't supply one.
     *
     * - The providers' lists always win. Any provider answer with tracks is
     *   shown, and replaces a catalog list already on screen.
     * - If no provider has produced tracks after [slowAfterMs], the catalog's
     *   list is shown meanwhile (desktop parachord#1132: MusicBrainz is 1 req/s
     *   and backs up during an outage).
     * - If every provider finishes with nothing, the catalog is asked right away.
     * - Once a catalog list is showing, an empty provider answer doesn't blank it.
     *
     * A catalog list is never written to the tracklist cache — it carries
     * [AlbumDetail.catalogSource] for the "Track list from …" label instead.
     */
    fun getAlbumTracksWithCatalogFallback(
        albumTitle: String,
        artistName: String,
        slowAfterMs: Long = CATALOG_SLOW_AFTER_MS,
    ): Flow<AlbumDetail> = channelFlow {
        val catalog = albumCatalog
        val lock = Mutex()
        var providerHasTracks = false
        var catalogShown = false

        suspend fun showCatalog() {
            if (catalog == null || lock.withLock { providerHasTracks || catalogShown }) return
            val found = try { catalog.load(artistName, albumTitle) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            } ?: return
            val detail = AlbumCatalog.toAlbumDetail(found.album, found.resolverName, albumTitle, artistName)
            if (detail.tracks.isEmpty()) return
            lock.withLock {
                if (!providerHasTracks && !catalogShown) {
                    catalogShown = true
                    send(detail)
                }
            }
        }

        val slowTimer = launch {
            kotlinx.coroutines.delay(slowAfterMs)
            showCatalog()
        }
        getAlbumTracksProgressively(albumTitle, artistName).collect { detail ->
            lock.withLock {
                if (detail.tracks.isNotEmpty()) {
                    providerHasTracks = true
                    send(detail)
                } else if (!catalogShown) {
                    send(detail)
                }
            }
        }
        if (lock.withLock { providerHasTracks }) {
            slowTimer.cancel()
        } else {
            // Every provider is done and none had the album: don't wait out the timer.
            slowTimer.cancel()
            showCatalog()
        }
    }

    /** Search artists across all available providers in parallel, merge and deduplicate. */
    suspend fun searchArtists(query: String, limit: Int = 10): List<ArtistInfo> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async {
                try { provider.searchArtists(query, limit) } catch (_: Exception) { emptyList() }
            } }
            .awaitAll()
            .flatten()

        deduplicateArtists(results).take(limit)
    }

    /**
     * Get detailed artist info using cascading fallback.
     * Tries each provider in priority order, merging fields so that
     * later providers fill in gaps left by earlier ones.
     */
    suspend fun getArtistInfo(artistName: String): ArtistInfo? =
        artistInfoCache.load(artistName) { fetchArtistInfo(artistName) }

    private suspend fun fetchArtistInfo(artistName: String): ArtistInfo? = coroutineScope {
        // Per-provider timeout so one SLOW provider can't stall the awaitAll
        // merge. The shared HttpClient has a 60s request budget (AI endpoints
        // need it), but a metadata provider that hangs — e.g. Wikipedia's
        // 4-call MB→Wikidata→Wikipedia chain under MusicBrainz-gate contention,
        // or a rate-limited Discogs — must NOT block the artist image from the
        // fast providers (MusicBrainz/Spotify/Apple Music) for that long. A
        // timed-out provider just contributes null.
        val results = availableProviders()
            .map { provider -> async {
                try {
                    withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { provider.getArtistInfo(artistName) }
                } catch (_: Exception) { null }
            } }
            .awaitAll()
            .filterNotNull()

        if (results.isEmpty()) return@coroutineScope null

        // Cascade: start with the highest-priority result, fill gaps from others
        var merged = results.reduce { acc, info -> acc.mergeWith(info) }

        // Bio source preference: Wikipedia > Discogs > Last.fm.
        val bioPreference = listOf("wikipedia", "discogs", "lastfm")
        val bestBio = bioPreference.firstNotNullOfOrNull { preferredSource ->
            results.firstOrNull { it.bioSource == preferredSource && !it.bio.isNullOrBlank() }
        }
        if (bestBio != null && merged.bioSource != bestBio.bioSource) {
            merged = merged.copy(
                bio = bestBio.bio,
                bioSource = bestBio.bioSource,
                bioUrl = bestBio.bioUrl,
            )
        }

        // Image source preference — SEPARATE from the bio order. Apple Music
        // FIRST: it reliably has a real image for every artist via a single call
        // on a separate dev token that doesn't draw on the shared Spotify
        // client_id (desktop/iOS/Android), so it never feeds the abuse window
        // (RateLimitGate / #177). Then Spotify, then the encyclopedia sources.
        // Without this override the priority merge prefers Wikipedia/MusicBrainz
        // — wrong for images (encyclopedia thumbnails / Last.fm's grey-star
        // placeholder beat real streaming art).
        val imagePreference = listOf("applemusic", "spotify", "wikipedia", "discogs", "lastfm", "musicbrainz")
        val bestImage = imagePreference.firstNotNullOfOrNull { src ->
            results.firstOrNull { it.provider == src && !it.imageUrl.isNullOrBlank() }?.imageUrl
        }
        if (bestImage != null && bestImage != merged.imageUrl) {
            merged = merged.copy(imageUrl = bestImage)
        }

        merged
    }

    /**
     * Fast artist-IMAGE lookup, separate from the full [getArtistInfo] cascade:
     * the photo for every artist card outside the artist page (Home's
     * suggestions, recommendations, related and top artists, friends, chat
     * cards). Desktop's #1141 chain, first usable photo wins:
     *
     * 1. **Apple Music** — FIRST on mobile (desktop asks Spotify first): its
     *    catalog has a real image for virtually every artist, it's a single
     *    call, and it uses a separate dev token that does NOT draw on the
     *    Spotify client_id shared across desktop/iOS/Android, so bulk image
     *    enrichment never bursts Spotify (the abuse window RateLimitGate / #177
     *    guards against).
     * 2. **Spotify**, when connected.
     * 3. **The active resolvers declaring `capabilities.artistImage`**, in
     *    priority order, through the resolver registry (`searchArtistImage`),
     *    so a new service's plugin adds photos with no feature code.
     * 4. **Deezer's public artist search** (no account).
     * 5. **Wikipedia** (via Wikidata), **Discogs**, then Last.fm and
     *    MusicBrainz as before.
     *
     * Each step is bounded at [ArtistImageChain.STEP_TIMEOUT_MS]; a URL with
     * credentials or a known placeholder is refused ([ArtistImageChain.isUsableUrl]).
     * A miss is remembered for 6 hours, but only when every step answered: a
     * step that threw or timed out may have had the photo. Found photos go to
     * the persistent cache.
     */
    suspend fun getArtistImage(artistName: String, exclude: String? = null): String? {
        val key = artistName.lowercase().trim()
        if (key.isEmpty()) return null
        // Persistent cache hit (shared, both platforms) — skip the network cascade.
        // [exclude] is an image URL that stopped loading ([BrokenArtRetry]): drop
        // it from the cache and look again, skipping it.
        artistImageMutex.withLock {
            if (artistImageMap == null) {
                artistImageMap = artistImageCacheRead?.invoke()?.let {
                    runCatching { artistImageJson.decodeFromString<Map<String, String>>(it).toMutableMap() }.getOrNull()
                } ?: mutableMapOf()
            }
            val cached = artistImageMap!![key]
            if (cached != null && cached == exclude) {
                artistImageMap!!.remove(key)
                artistImageCacheWrite?.invoke(artistImageJson.encodeToString(artistImageMap!!.toMap()))
            } else if (cached != null) {
                return cached
            }
        }
        if (exclude == null && artistImageMisses.has(artistName)) return null

        var complete = true
        for ((source, step) in artistImageSteps(artistName)) {
            val img = try {
                val answer = withTimeoutOrNull(ArtistImageChain.STEP_TIMEOUT_MS) { Answered(step()) }
                if (answer == null) complete = false
                answer?.url
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                complete = false
                Log.d(TAG, "Artist image for '$artistName': $source failed: ${e.message}")
                null
            }
            if (img != null && img != exclude && ArtistImageChain.isUsableUrl(img)) {
                if (source != "applemusic") Log.d(TAG, "Artist image for '$artistName' from $source")
                artistImageMutex.withLock {
                    artistImageMap!![key] = img
                    artistImageCacheWrite?.invoke(artistImageJson.encodeToString(artistImageMap!!.toMap()))
                }
                return img
            }
        }
        if (complete) artistImageMisses.add(artistName)
        return null
    }

    private class Answered(val url: String?)

    /** The chain's steps for [artistName], in order: `(source, lookup)`. */
    private suspend fun artistImageSteps(artistName: String): List<Pair<String, suspend () -> String?>> {
        val available = availableProviders().associateBy { it.name }
        fun provider(name: String): Pair<String, suspend () -> String?>? =
            available[name]?.let { p -> name to suspend { p.getArtistInfo(artistName)?.imageUrl } }
        return buildList {
            provider("applemusic")?.let(::add)
            provider("spotify")?.let(::add)
            val plugins = pluginArtistImage?.let { search ->
                try { artistImageResolvers?.invoke().orEmpty() } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    emptyList()
                }.map { id -> id to suspend { search(id, artistName) } }
            }.orEmpty()
            addAll(plugins)
            deezer?.let { d -> add("deezer" to suspend { d.artistImage(artistName) }) }
            listOf("wikipedia", "discogs", "lastfm", "musicbrainz").forEach { name -> provider(name)?.let(::add) }
        }
    }

    /** Get an artist's top tracks from all available providers, merged and deduplicated. */
    suspend fun getArtistTopTracks(artistName: String, limit: Int = 10): List<TrackSearchResult> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async {
                try { provider.getArtistTopTracks(artistName, limit) } catch (_: Exception) { emptyList() }
            } }
            .awaitAll()
            .flatten()

        deduplicateTracks(results).take(limit)
    }

    /** Get an artist's discography from all available providers. */
    suspend fun getArtistAlbums(artistName: String, limit: Int = 50): List<AlbumSearchResult> = coroutineScope {
        val active = availableProviders()
        val cacheKey = cacheKey(active, artistName, limit.toString())
        artistAlbumsCache?.get(cacheKey)?.let { return@coroutineScope it }
        val perProvider: List<Result<List<AlbumSearchResult>>> = active
            .map { provider -> async {
                try {
                    Result.success(provider.getArtistAlbums(artistName, limit))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e   // never swallow cancellation (CLAUDE.md)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            } }
            .awaitAll()

        val results = perProvider.mapNotNull { it.getOrNull() }.flatten()
        val deduped = deduplicateAlbums(results).take(limit)

        // Distinguish "this artist genuinely has no discography" (empty, no
        // failures) from "we couldn't reach the discography providers" (empty,
        // but a provider threw — typically a MusicBrainz 503/429/network error).
        // The UI shows a friendly retry message for the latter instead of a bare
        // "No discography" empty state.
        if (deduped.isEmpty() && perProvider.any { it.isFailure }) {
            throw DiscographyUnavailableException()
        }

        // Enrich albums that only have Cover Art Archive URLs (which may 404)
        val enriched = enrichAlbumArtwork?.invoke(artistName, deduped) ?: deduped
        // Cache only a complete answer: every provider responded and found
        // something. A partial discography (one provider failed) or an empty
        // one would otherwise be served as the whole thing for 30 days.
        if (enriched.isNotEmpty() && perProvider.none { it.isFailure }) {
            artistAlbumsCache?.put(cacheKey, enriched)
        }
        enriched
    }

    /** Cache key: the request plus which providers answered it, so enabling a
     *  provider (e.g. connecting Spotify) isn't hidden behind an older answer. */
    private fun cacheKey(active: List<MetadataProvider>, vararg parts: String): String =
        (parts.map { it.lowercase().trim() } + active.map { it.name }.sorted().joinToString(",")).joinToString("|")

    /**
     * Get album tracklist from all providers and merge.
     *
     * Picks the result with the most tracks as the base, then enriches
     * each track with metadata from other providers (spotifyId, artworkUrl, etc.)
     */
    suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? = coroutineScope {
        val active = availableProviders()
        val cacheKey = cacheKey(active, artistName, albumTitle)
        albumTracksCache?.get(cacheKey)?.let { return@coroutineScope it }
        var anyFailed = false
        val results = active
            .map { provider -> async {
                try {
                    provider.getAlbumTracks(albumTitle, artistName)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    anyFailed = true
                    null
                }
            } }
            .awaitAll()
            .filterNotNull()

        if (results.isEmpty()) return@coroutineScope null
        mergeAlbumResults(results).also { merged ->
            // Cache only a complete answer (every provider answered, with tracks).
            if (!anyFailed && merged != null && merged.tracks.isNotEmpty()) {
                albumTracksCache?.put(cacheKey, merged)
            }
        }
    }

    private fun mergeAlbumResults(results: List<AlbumDetail>): AlbumDetail? {

        // Use the result with the most tracks as base
        val base = results.maxByOrNull { it.tracks.size } ?: return null
        val others = results.filter { it !== base }

        if (others.isEmpty()) return base

        // Build a lookup of tracks from other providers by normalized title
        val otherTracksByTitle = others
            .flatMap { it.tracks }
            .groupBy { it.title.lowercase().trim() }

        // Enrich base tracks with data from other providers
        val enrichedTracks = base.tracks.map { track ->
            val matches = otherTracksByTitle[track.title.lowercase().trim()] ?: emptyList()
            matches.fold(track) { acc, other -> acc.mergeWith(other) }
        }

        val mergedArtwork = base.artworkUrl ?: others.firstNotNullOfOrNull { it.artworkUrl }

        // Propagate album artwork to tracks that don't have their own
        val tracksWithArt = if (mergedArtwork != null) {
            enrichedTracks.map { t -> t.copy(artworkUrl = t.artworkUrl ?: mergedArtwork) }
        } else enrichedTracks

        return base.copy(
            tracks = tracksWithArt,
            artworkUrl = mergedArtwork,
            year = base.year ?: others.firstNotNullOfOrNull { it.year },
            releaseType = base.releaseType ?: others.firstNotNullOfOrNull { it.releaseType },
            provider = results.joinToString("+") { it.provider },
        )
    }

    /**
     * Progressive version of [getAlbumTracks] — emits an updated [AlbumDetail] as each
     * provider completes, so the UI can show the tracklist from the fastest provider
     * immediately and enrich it as slower providers respond.
     */
    fun getAlbumTracksProgressively(albumTitle: String, artistName: String): Flow<AlbumDetail> = channelFlow {
        val mutex = Mutex()
        var merged: AlbumDetail? = null
        val active = availableProviders()
        // A cached tracklist is the whole answer: show it, no provider calls.
        albumTracksCache?.get(cacheKey(active, artistName, albumTitle))?.let {
            send(it)
            return@channelFlow
        }
        for (provider in active) {
            launch {
                try {
                    val result = provider.getAlbumTracks(albumTitle, artistName) ?: return@launch
                    mutex.withLock {
                        merged = merged?.let { mergeAlbumDetails(it, result) } ?: result
                        send(merged!!)
                    }
                } catch (_: Exception) { /* skip failed provider */ }
            }
        }
    }

    /**
     * Merge two [AlbumDetail] results: keep the richer tracklist as base,
     * fill missing metadata from the other.
     */
    private fun mergeAlbumDetails(existing: AlbumDetail, incoming: AlbumDetail): AlbumDetail {
        val (base, other) = if (existing.tracks.size >= incoming.tracks.size) {
            existing to incoming
        } else {
            incoming to existing
        }

        val otherTracksByTitle = other.tracks.associateBy { it.title.lowercase().trim() }
        val enrichedTracks = base.tracks.map { track ->
            val match = otherTracksByTitle[track.title.lowercase().trim()]
            if (match != null) track.mergeWith(match) else track
        }

        val mergedArtwork = base.artworkUrl ?: other.artworkUrl
        val tracksWithArt = if (mergedArtwork != null) {
            enrichedTracks.map { t -> t.copy(artworkUrl = t.artworkUrl ?: mergedArtwork) }
        } else enrichedTracks

        return base.copy(
            tracks = tracksWithArt,
            artworkUrl = mergedArtwork,
            year = base.year ?: other.year,
            releaseType = base.releaseType ?: other.releaseType,
            provider = listOf(existing.provider, incoming.provider)
                .filter { it.isNotBlank() }
                .joinToString("+"),
        )
    }

    private suspend fun availableProviders(): List<MetadataProvider> {
        val disabled = getDisabledProviders()
        return providers.filter { it.isAvailable() && it.name !in disabled }
    }

    /** Deduplicate tracks by normalized title+artist, preferring results with more metadata. */
    private fun deduplicateTracks(tracks: List<TrackSearchResult>): List<TrackSearchResult> =
        tracks.groupBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .values
            .map { group -> group.reduce { acc, t -> acc.mergeWith(t) } }

    /** Deduplicate albums by normalized title+artist. */
    private fun deduplicateAlbums(albums: List<AlbumSearchResult>): List<AlbumSearchResult> {
        val byTitleArtist = albums.groupBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
        return byTitleArtist.values.flatMap { group ->
            val typed = group.filter { it.releaseType != null }
            val untyped = group.filter { it.releaseType == null }
            if (typed.isEmpty()) {
                listOf(group.reduce { acc, a -> acc.mergeWith(a) })
            } else {
                val merged = if (untyped.isEmpty()) typed
                else listOf(typed.first().let { base -> untyped.fold(base) { acc, a -> acc.mergeWith(a) } }) +
                    typed.drop(1)
                merged.groupBy { it.releaseType?.lowercase() }
                    .values
                    .map { sub -> sub.reduce { acc, a -> acc.mergeWith(a) } }
            }
        }
    }

    /** Deduplicate artists by normalized name. */
    private fun deduplicateArtists(artists: List<ArtistInfo>): List<ArtistInfo> =
        artists.groupBy { it.name.lowercase() }
            .values
            .map { group -> group.reduce { acc, a -> acc.mergeWith(a) } }
}

/** Merge two ArtistInfo, preferring non-null/non-empty fields from the receiver. */
fun ArtistInfo.mergeWith(other: ArtistInfo) = ArtistInfo(
    name = name,
    mbid = mbid ?: other.mbid,
    imageUrl = imageUrl ?: other.imageUrl,
    bio = bio ?: other.bio,
    bioSource = if (bio != null) bioSource else other.bioSource,
    bioUrl = if (bio != null) bioUrl else other.bioUrl,
    tags = tags.ifEmpty { other.tags },
    similarArtists = if (similarArtists.isNotEmpty()) similarArtists else other.similarArtists,
    provider = "$provider+${other.provider}",
)

fun TrackSearchResult.mergeWith(other: TrackSearchResult) = TrackSearchResult(
    title = title,
    artist = artist,
    album = album ?: other.album,
    duration = duration ?: other.duration,
    artworkUrl = artworkUrl ?: other.artworkUrl,
    previewUrl = previewUrl ?: other.previewUrl,
    spotifyId = spotifyId ?: other.spotifyId,
    mbid = mbid ?: other.mbid,
    provider = "$provider+${other.provider}",
)

fun AlbumSearchResult.mergeWith(other: AlbumSearchResult) = AlbumSearchResult(
    title = title,
    artist = artist,
    artworkUrl = artworkUrl ?: other.artworkUrl,
    year = year ?: other.year,
    trackCount = trackCount ?: other.trackCount,
    mbid = mbid ?: other.mbid,
    spotifyId = spotifyId ?: other.spotifyId,
    releaseType = releaseType ?: other.releaseType,
    secondaryTypes = secondaryTypes.ifEmpty { other.secondaryTypes },
    provider = "$provider+${other.provider}",
)
