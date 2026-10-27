package com.parachord.shared.metadata

import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.api.bestImageUrl
import com.parachord.shared.settings.SettingsStore

/**
 * Spotify metadata provider.
 *
 * Best album art and preview URLs. Only available when the user is
 * authenticated (a Spotify access token is stored in
 * [SettingsStore.getSpotifyAccessToken]). Lowest priority since OAuth
 * is required and the API has rate limits.
 *
 * The previous Android-only constructor took an `OAuthManager` parameter
 * but never invoked it — the dead reference was dropped during the
 * Phase 9E shared-metadata move. Token-refresh on 401 is fully handled
 * by the shared `OAuthRefreshPlugin` on `SpotifyClient`'s Ktor pipeline,
 * so this class doesn't need to know about OAuth at all — it just
 * short-circuits when no token is stored to avoid burning a refresh
 * attempt on a not-yet-connected user.
 */
class SpotifyProvider(
    private val api: SpotifyClient,
    private val settingsStore: SettingsStore,
) : MetadataProvider {

    override val name = "spotify"
    override val priority = 20

    override suspend fun isAvailable(): Boolean =
        settingsStore.getSpotifyAccessToken() != null

    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> =
        withAuth {
            val response = api.search(query = query, type = "track", limit = limit)
            response.tracks?.items?.mapNotNull { t ->
                val title = t.name ?: return@mapNotNull null
                TrackSearchResult(
                    title = title,
                    artist = t.artistName,
                    album = t.album?.name,
                    duration = t.durationMs,
                    artworkUrl = t.album?.images?.bestImageUrl(),
                    previewUrl = t.previewUrl,
                    provider = name,
                )
            } ?: emptyList()
        } ?: emptyList()

    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> =
        withAuth {
            val response = api.search(query = query, type = "album", limit = limit)
            response.albums?.items?.mapNotNull { a ->
                AlbumSearchResult(
                    title = a.name ?: return@mapNotNull null,
                    artist = a.artistName,
                    artworkUrl = a.images.bestImageUrl(),
                    year = a.year,
                    trackCount = a.totalTracks,
                    provider = name,
                )
            } ?: emptyList()
        } ?: emptyList()

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> =
        withAuth {
            val response = api.search(query = "artist:\"$query\"", type = "artist", limit = limit)
            response.artists?.items?.mapNotNull { a ->
                ArtistInfo(
                    name = a.name ?: return@mapNotNull null,
                    imageUrl = a.images.bestImageUrl(),
                    tags = a.genres,
                    provider = name,
                )
            } ?: emptyList()
        } ?: emptyList()

    override suspend fun getArtistInfo(artistName: String): ArtistInfo? =
        withAuth {
            // The artist search result already carries images + genres + name
            // (SpArtist), so the old second getArtist() call was redundant.
            // Dropping it HALVES Spotify calls per artist — critical because the
            // Spotify client_id is shared across desktop/iOS/Android and bursts
            // trip the account-wide abuse window (RateLimitGate / #177).
            val response = api.search(query = "artist:\"$artistName\"", type = "artist", limit = 1)
            val artist = response.artists?.items?.firstOrNull() ?: return@withAuth null
            ArtistInfo(
                name = artist.name ?: return@withAuth null,
                imageUrl = artist.images.bestImageUrl(),
                tags = artist.genres,
                provider = name,
            )
        }

    override suspend fun getArtistTopTracks(artistName: String, limit: Int): List<TrackSearchResult> =
        withAuth {
            val response = api.search(query = "artist:\"$artistName\"", type = "artist", limit = 1)
            val artistId = response.artists?.items?.firstOrNull()?.id ?: return@withAuth emptyList()
            val topTracks = api.getArtistTopTracks(artistId = artistId)
            topTracks.tracks.take(limit).mapNotNull { t ->
                val title = t.name ?: return@mapNotNull null
                TrackSearchResult(
                    title = title,
                    artist = t.artistName,
                    album = t.album?.name,
                    duration = t.durationMs,
                    artworkUrl = t.album?.images?.bestImageUrl(),
                    previewUrl = t.previewUrl,
                    spotifyId = t.id,
                    provider = name,
                )
            }
        } ?: emptyList()

    override suspend fun getArtistAlbums(artistName: String, limit: Int): List<AlbumSearchResult> =
        withAuth {
            val response = api.search(query = "artist:\"$artistName\"", type = "artist", limit = 1)
            val artistId = response.artists?.items?.firstOrNull()?.id ?: return@withAuth emptyList()
            val albums = api.getArtistAlbums(artistId = artistId, limit = limit)
            albums.items.mapNotNull { a ->
                AlbumSearchResult(
                    title = a.name ?: return@mapNotNull null,
                    artist = a.artistName,
                    artworkUrl = a.images.bestImageUrl(),
                    year = a.year,
                    trackCount = a.totalTracks,
                    spotifyId = a.id,
                    releaseType = a.albumType,
                    provider = name,
                )
            }
        } ?: emptyList()

    // Not wrapped in withAuth's catch-all: a failure (rate-limit cooldown,
    // network) must reach MetadataService as a failure, or a tracklist built
    // without Spotify's IDs would be cached as complete.
    override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? =
        withAuthOrThrow {
            val response = api.search(
                query = "album:$albumTitle artist:$artistName",
                type = "album",
                limit = 1,
            )
            val album = response.albums?.items?.firstOrNull() ?: return@withAuthOrThrow null
            val albumId = album.id ?: return@withAuthOrThrow null
            val albumName = album.name ?: return@withAuthOrThrow null
            val tracksResponse = api.getAlbumTracks(albumId = albumId)
            AlbumDetail(
                title = albumName,
                artist = album.artistName,
                artworkUrl = album.images.bestImageUrl(),
                year = album.year,
                tracks = tracksResponse.items.mapNotNull { t ->
                    TrackSearchResult(
                        title = t.name ?: return@mapNotNull null,
                        artist = t.artistName,
                        album = albumName,
                        duration = t.durationMs,
                        artworkUrl = album.images.bestImageUrl(),
                        previewUrl = t.previewUrl,
                        spotifyId = t.id,
                        provider = name,
                    )
                },
                provider = name,
            )
        }

    /**
     * Skip-if-unauthed gate. The Ktor `SpotifyClient` resolves the
     * Bearer token from `AuthTokenProvider` per-request, and the global
     * `OAuthRefreshPlugin` handles 401-driven refresh + retry on
     * `api.spotify.com`. This wrapper just short-circuits before the
     * API call when no token is stored (avoids burning a refresh
     * attempt on a not-yet-connected user).
     */
    /** [withAuth] without the catch-all: skips when not connected, otherwise lets errors through. */
    private suspend fun <T> withAuthOrThrow(block: suspend () -> T?): T? {
        if (settingsStore.getSpotifyAccessToken() == null) return null
        return block()
    }

    private suspend fun <T> withAuth(block: suspend () -> T): T? {
        if (settingsStore.getSpotifyAccessToken() == null) return null
        return try {
            block()
        } catch (_: Exception) {
            null
        }
    }
}
