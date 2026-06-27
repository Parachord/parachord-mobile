package com.parachord.shared.deeplink

import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.MbReleaseDetail
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.platform.Log
import com.parachord.shared.playlist.XspfParser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

private const val TAG = "ProtocolInputResolver"

/**
 * KMP-shared [ProtocolInputResolver] — turns each `parachord://play/…` input slot
 * into playable [ProtocolTrack]s. A commonMain port of Android's
 * `AndroidProtocolInputResolver`; the only thing that kept that Android-only was
 * the `XmlPullParser`-based XSPF parse, which the shared [XspfParser] (#254) now
 * replaces — so this runs unchanged on both platforms. (Android still uses its
 * own copy today; converging it onto this is a tracked follow-up.)
 *
 * Routing per slot mirrors the Android impl: mbid → MusicBrainz release (then
 * release-group fallback); spotify → album tracks; applemusic → iTunes lookup
 * (entity=song); url → SSRF-guarded fetch + [parseProtocolTracklist] (XSPF via
 * [XspfParser]); artist+title → [MetadataService.getAlbumTracks] cascade.
 */
class DefaultProtocolInputResolver(
    private val musicBrainzClient: MusicBrainzClient,
    private val spotifyClient: SpotifyClient,
    private val appleMusicClient: AppleMusicClient,
    private val metadataService: MetadataService,
    private val httpClient: HttpClient,
    appleMusicDeveloperToken: String = "",
    spotifyAccessToken: suspend () -> String? = { null },
) : ProtocolInputResolver {

    private val maxTracklistBytes: Int = 100 * 1024  // 100KB body cap

    // play/playlist provider-page resolution (parachord#930).
    private val providerPlaylistResolver = ProviderPlaylistResolver(
        spotifyClient, appleMusicClient, httpClient, appleMusicDeveloperToken, spotifyAccessToken,
    )

    override suspend fun resolveProviderPlaylist(url: String): ResolvedProtocolPlay? =
        providerPlaylistResolver.resolve(url)

    override suspend fun resolveByMbid(mbid: String): ResolvedProtocolPlay? {
        val direct: MbReleaseDetail? = try {
            musicBrainzClient.getRelease(mbid, inc = "recordings+artist-credits")
                .takeIf { it.media.isNotEmpty() }
        } catch (e: Exception) {
            Log.d(TAG, "MBID $mbid not a release (${e.message?.take(80)}); trying release-group")
            null
        }
        val fromGroup: MbReleaseDetail? = if (direct != null) null else try {
            musicBrainzClient.browseReleasesByReleaseGroup(
                releaseGroupMbid = mbid, inc = "recordings+artist-credits", limit = 1,
            ).releases.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "MBID $mbid release-group browse failed: ${e.message}")
            null
        }
        val release: MbReleaseDetail = direct ?: fromGroup ?: return null
        val tracks = release.media.flatMap { medium ->
            medium.tracks.map { track ->
                ProtocolTrack(
                    artist = track.artistName.ifEmpty { release.artistName },
                    title = track.title,
                    album = release.title,
                    mbid = track.id,
                )
            }
        }
        if (tracks.isEmpty()) return null
        return ResolvedProtocolPlay(displayName = release.title.ifEmpty { "Album" }, tracks = tracks)
    }

    override suspend fun resolveBySpotify(spotifyIdOrUri: String): ResolvedProtocolPlay? {
        val albumId = extractSpotifyAlbumId(spotifyIdOrUri) ?: return null
        return try {
            val page = spotifyClient.getAlbumTracks(albumId, limit = 50)
            val items = page.items.filter { !it.name.isNullOrBlank() }
            if (items.isEmpty()) return null
            val firstArtist = items.first().artists.firstOrNull()?.name ?: "Spotify"
            val tracks = items.map { simple ->
                ProtocolTrack(
                    artist = simple.artists.joinToString(", ") { it.name.orEmpty() }.ifBlank { firstArtist },
                    title = simple.name.orEmpty(),
                )
            }
            ResolvedProtocolPlay(displayName = "$firstArtist — Album", tracks = tracks)
        } catch (e: Exception) {
            Log.w(TAG, "Spotify album resolve failed for id=$albumId: ${e.message}")
            null
        }
    }

    override suspend fun resolveByAppleMusic(appleMusicId: String): ResolvedProtocolPlay? {
        return try {
            val resp = appleMusicClient.lookup(appleMusicId, entity = "song")
            val tracks = resp.results.filter { it.wrapperType == "track" && it.kind == "song" }
            if (tracks.isEmpty()) return null
            val albumName = resp.results.firstOrNull { it.wrapperType == "collection" }?.collectionName
                ?: tracks.firstOrNull()?.collectionName ?: "Apple Music Album"
            val protocolTracks = tracks.mapNotNull { item ->
                val title = item.trackName ?: return@mapNotNull null
                val artist = item.artistName ?: return@mapNotNull null
                ProtocolTrack(artist = artist, title = title, album = item.collectionName)
            }
            if (protocolTracks.isEmpty()) return null
            val albumArt = tracks.firstNotNullOfOrNull { it.artworkUrl100?.replace("100x100", "600x600") }
            ResolvedProtocolPlay(displayName = albumName, tracks = protocolTracks, albumArt = albumArt)
        } catch (e: Exception) {
            Log.w(TAG, "AppleMusic resolve failed for id=$appleMusicId: ${e.message}")
            null
        }
    }

    override suspend fun resolveByUrl(url: String): ResolvedProtocolPlay? {
        validatePublicHttpsUrl(url)  // SSRF guard
        val body = try {
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) {
                Log.w(TAG, "URL fetch returned ${response.status} for $url")
                return null
            }
            val text = response.bodyAsText()
            if (text.length > maxTracklistBytes) {
                throw IllegalArgumentException("Tracklist body exceeds ${maxTracklistBytes / 1024} KB cap")
            }
            text
        } catch (e: Exception) {
            Log.w(TAG, "URL fetch failed for $url: ${e.message}")
            return null
        }
        return try {
            val parsed = parseProtocolTracklist(body, ::parseXspfTracklist)
            ResolvedProtocolPlay(
                displayName = parsed.title ?: parsed.creator ?: "Playlist",
                tracks = parsed.tracks,
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Tracklist parse failed for $url: ${e.message}")
            null
        }
    }

    override suspend fun resolveByArtistTitle(artist: String, title: String?, album: String?): ResolvedProtocolPlay? {
        val albumTitle = title ?: album ?: return null
        return try {
            val detail = metadataService.getAlbumTracks(albumTitle, artist) ?: return null
            if (detail.tracks.isEmpty()) return null
            ResolvedProtocolPlay(
                displayName = detail.title,
                tracks = detail.tracks.map { t ->
                    ProtocolTrack(artist = t.artist, title = t.title, album = t.album ?: detail.title, mbid = t.mbid)
                },
                albumArt = detail.artworkUrl,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Artist+title resolve failed for '$albumTitle' by '$artist': ${e.message}")
            null
        }
    }

    /** Shared XSPF → protocol-tracklist adapter (replaces Android's XmlPullParser path). */
    private fun parseXspfTracklist(content: String): ParsedProtocolTracklist {
        val pl = XspfParser.parse(content)
        return ParsedProtocolTracklist(
            title = pl.title,
            creator = pl.creator,
            tracks = pl.tracks.map { ProtocolTrack(artist = it.artist, title = it.title, album = it.album) },
        )
    }

    private fun extractSpotifyAlbumId(input: String): String? {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("spotify:album:") -> trimmed.removePrefix("spotify:album:").takeIf { it.isNotBlank() }
            trimmed.startsWith("spotify:") -> null
            trimmed.contains(':') -> null
            else -> trimmed.takeIf { it.matches(Regex("^[A-Za-z0-9]{20,40}$")) }
        }
    }
}
