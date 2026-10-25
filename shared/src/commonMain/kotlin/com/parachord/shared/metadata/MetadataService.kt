package com.parachord.shared.metadata

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
) {

    private val artistImageMutex = Mutex()
    private var artistImageMap: MutableMap<String, String>? = null
    private val artistImageJson = Json { ignoreUnknownKeys = true }

    private companion object {
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

    /** Search albums across all available providers in parallel, merge and deduplicate. */
    suspend fun searchAlbums(query: String, limit: Int = 10): List<AlbumSearchResult> = coroutineScope {
        val results = availableProviders()
            .map { provider -> async {
                try { provider.searchAlbums(query, limit) } catch (_: Exception) { emptyList() }
            } }
            .awaitAll()
            .flatten()

        deduplicateAlbums(results).take(limit)
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
    suspend fun getArtistInfo(artistName: String): ArtistInfo? = coroutineScope {
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
     * Fast artist-IMAGE lookup, separate from the full [getArtistInfo] cascade.
     * Apple Music FIRST: its catalog has a real image for virtually every artist,
     * it's a single call, and it uses a separate dev token that does NOT draw on
     * the Spotify client_id shared across desktop/iOS/Android — so bulk image
     * enrichment never bursts Spotify (the account-wide abuse window that
     * RateLimitGate / #177 guards against). Spotify is only a fallback, then the
     * encyclopedia sources (Wikipedia/Discogs/Last.fm). SHORT-CIRCUITS at the
     * first image — no full-cascade awaitAll, and the slow Wikipedia MB→Wikidata
     * chain never runs once a streaming image is found.
     */
    suspend fun getArtistImage(artistName: String, exclude: String? = null): String? {
        val key = artistName.lowercase().trim()
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
        val available = availableProviders().associateBy { it.name }
        val order = listOf("applemusic", "spotify", "wikipedia", "discogs", "lastfm", "musicbrainz")
        for (name in order) {
            val provider = available[name] ?: continue
            val img = try {
                withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { provider.getArtistInfo(artistName)?.imageUrl }
            } catch (_: Exception) { null }
            if (!img.isNullOrBlank() && img != exclude) {
                artistImageMutex.withLock {
                    artistImageMap!![key] = img
                    artistImageCacheWrite?.invoke(artistImageJson.encodeToString(artistImageMap!!.toMap()))
                }
                return img
            }
        }
        return null
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
        val perProvider: List<Result<List<AlbumSearchResult>>> = availableProviders()
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
        enrichAlbumArtwork?.invoke(artistName, deduped) ?: deduped
    }

    /**
     * Get album tracklist from all providers and merge.
     *
     * Picks the result with the most tracks as the base, then enriches
     * each track with metadata from other providers (spotifyId, artworkUrl, etc.)
     */
    suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? = coroutineScope {
        val results = availableProviders()
            .map { provider -> async {
                try { provider.getAlbumTracks(albumTitle, artistName) } catch (_: Exception) { null }
            } }
            .awaitAll()
            .filterNotNull()

        if (results.isEmpty()) return@coroutineScope null

        // Use the result with the most tracks as base
        val base = results.maxByOrNull { it.tracks.size } ?: return@coroutineScope null
        val others = results.filter { it !== base }

        if (others.isEmpty()) return@coroutineScope base

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

        base.copy(
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
