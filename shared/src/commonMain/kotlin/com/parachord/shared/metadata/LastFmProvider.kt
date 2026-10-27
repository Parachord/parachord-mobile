package com.parachord.shared.metadata

import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.bestImageUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Last.fm metadata provider.
 *
 * Rich artist bios, images, similar artists, and tags. Requires a
 * Last.fm API key — passed in via constructor (Android sources it from
 * [com.parachord.shared.config.AppConfig.lastFmApiKey], iOS will do the
 * same once the iOS DI module is wired up). When the key is blank,
 * [isAvailable] returns false and the cascading [MetadataService] skips
 * this provider entirely.
 */
class LastFmProvider(
    private val api: LastFmClient,
    private val apiKey: String,
) : MetadataProvider {

    override val name = "lastfm"
    override val priority = 10

    override suspend fun isAvailable(): Boolean = apiKey.isNotBlank()

    override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> =
        try {
            val tracks = api.searchTracks(track = query, apiKey = apiKey, limit = limit)
                .results?.trackmatches?.track ?: emptyList()
            tracks.map { t ->
                TrackSearchResult(
                    title = t.name,
                    artist = t.artist,
                    artworkUrl = t.image.bestImageUrl(),
                    mbid = t.mbid?.takeIf { it.isNotBlank() },
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> =
        try {
            val albums = api.searchAlbums(album = query, apiKey = apiKey, limit = limit)
                .results?.albummatches?.album ?: emptyList()
            albums.map { a ->
                AlbumSearchResult(
                    title = a.name,
                    artist = a.artist,
                    artworkUrl = a.image.bestImageUrl(),
                    mbid = a.mbid?.takeIf { it.isNotBlank() },
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> =
        try {
            val artists = api.searchArtists(artist = query, apiKey = apiKey, limit = limit)
                .results?.artistmatches?.artist ?: emptyList()
            artists.map { a ->
                ArtistInfo(
                    name = a.name,
                    mbid = a.mbid?.takeIf { it.isNotBlank() },
                    imageUrl = a.image.bestImageUrl(),
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? {
        return try {
            val info = api.getAlbumInfo(album = albumTitle, artist = artistName, apiKey = apiKey)
            val detail = info.album ?: return null
            val tracks = detail.tracks?.track?.items ?: emptyList()
            if (tracks.isEmpty()) return null

            AlbumDetail(
                title = detail.name,
                artist = detail.artist,
                artworkUrl = detail.image.bestImageUrl(),
                tracks = tracks.map { t ->
                    TrackSearchResult(
                        title = t.name,
                        artist = t.artist?.name ?: detail.artist,
                        album = detail.name,
                        // Last.fm returns duration in seconds; convert to ms.
                        duration = t.duration?.toLongOrNull()?.let { it * 1000 },
                        artworkUrl = detail.image.bestImageUrl(),
                        provider = name,
                    )
                },
                provider = name,
            )
        } catch (e: Exception) {
            // Rethrow rather than answer null: MetadataService treats a provider
            // that threw as "didn't answer" and won't cache a tracklist built
            // without it (a null here would read as "no such album").
            throw e
        }
    }

    override suspend fun getArtistTopTracks(artistName: String, limit: Int): List<TrackSearchResult> =
        try {
            val tracks = api.getArtistTopTracks(artist = artistName, apiKey = apiKey, limit = limit)
                .toptracks?.track ?: emptyList()
            tracks.map { t ->
                TrackSearchResult(
                    title = t.name,
                    artist = t.artist?.name ?: artistName,
                    artworkUrl = t.image.bestImageUrl(),
                    duration = t.duration?.toLongOrNull()?.let { if (it > 0) it * 1000 else null },
                    mbid = t.mbid?.takeIf { it.isNotBlank() },
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun getArtistAlbums(artistName: String, limit: Int): List<AlbumSearchResult> =
        try {
            val albums = api.getArtistTopAlbums(artist = artistName, apiKey = apiKey, limit = limit)
                .topalbums?.album ?: emptyList()
            albums.map { a ->
                AlbumSearchResult(
                    title = a.name,
                    artist = a.artist?.name ?: artistName,
                    artworkUrl = a.image.bestImageUrl(),
                    mbid = a.mbid?.takeIf { it.isNotBlank() },
                    provider = name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

    override suspend fun getArtistInfo(artistName: String): ArtistInfo? = coroutineScope {
        try {
            // Fire both calls in parallel — saves 1-2s vs sequential
            val detailDeferred = async { api.getArtistInfo(artist = artistName, apiKey = apiKey) }
            val similarDeferred = async {
                try {
                    api.getSimilarArtists(artist = artistName, apiKey = apiKey, limit = 20)
                } catch (_: Exception) { null }
            }

            val detail = detailDeferred.await().artist ?: return@coroutineScope null
            val bioText = (detail.bio?.content ?: detail.bio?.summary)?.stripHtmlTags()?.stripLastFmSuffix()

            val similarArtists = similarDeferred.await()
                ?.similarartists?.artist?.map { a ->
                    SimilarArtist(
                        name = a.name,
                        imageUrl = a.image.bestImageUrl(),
                    )
                } ?: detail.similar?.artist?.map { a ->
                    SimilarArtist(name = a.name, imageUrl = a.image.bestImageUrl())
                } ?: emptyList()

            ArtistInfo(
                name = detail.name,
                mbid = detail.mbid?.takeIf { it.isNotBlank() },
                imageUrl = detail.image.bestImageUrl(),
                bio = bioText,
                bioSource = if (bioText != null) "lastfm" else null,
                bioUrl = detail.url?.takeIf { bioText != null },
                tags = detail.tags?.tag?.map { it.name } ?: emptyList(),
                similarArtists = similarArtists,
                provider = name,
            )
        } catch (_: Exception) {
            null
        }
    }
}

private fun String.stripHtmlTags(): String =
    replace(Regex("<[^>]*>"), "").trim()

private fun String.stripLastFmSuffix(): String =
    replace(Regex("\\s*Read more on Last\\.?\\s*fm\\.?\\s*\\.?\\s*$", RegexOption.IGNORE_CASE), "").trim()
