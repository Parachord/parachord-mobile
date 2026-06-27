package com.parachord.android.deeplink

import com.parachord.android.playlist.parseXspfForProtocol
import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.MbReleaseDetail
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.deeplink.ProtocolInputResolver
import com.parachord.shared.deeplink.ProtocolTrack
import com.parachord.shared.deeplink.ResolvedProtocolPlay
import com.parachord.shared.deeplink.parseProtocolTracklist
import com.parachord.shared.deeplink.validatePublicHttpsUrl
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.platform.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

private const val TAG = "ProtocolInputResolver"

/**
 * Concrete [ProtocolInputResolver] for Android. Wraps the existing API
 * clients to turn each input slot into playable tracks.
 *
 * **Routing per slot:**
 *
 * | Slot | Backend | Notes |
 * |---|---|---|
 * | mbid | [MusicBrainzClient.getRelease] | Treats the MBID as a release. Release-group MBIDs return null (the resolver falls through to the next slot per #119's priority walk). |
 * | spotify | [SpotifyClient.getAlbumTracks] | Strips `spotify:album:` prefix; bare ID accepted. Requires Spotify connected (token provider). |
 * | applemusic | [AppleMusicClient.lookup] (`entity=song`) | Storefront-aware; the `lookup` endpoint returns the album's tracks alongside the album itself. |
 * | url | Ktor GET → [parseProtocolTracklist] | SSRF-guarded via [validatePublicHttpsUrl]. XSPF dispatches to [parseXspfForProtocol]. 100KB cap on body length. |
 * | artist+title | [MetadataService.getAlbumTracks] | Cascades through MB / Last.fm / Spotify per the standard metadata pipeline. |
 *
 * Inline `tracks=` payloads bypass this resolver entirely — `resolveProtocolPlayInput`
 * wraps them directly without calling any of the methods here.
 *
 * **Error handling:** every method returns null on a "couldn't resolve"
 * outcome (404, no results, malformed payload). Hard errors (network 5xx,
 * SSRF rejection, base64 decode fail) throw — the caller in
 * `ProtocolPlayHandler` translates these to user-visible toasts.
 */
class AndroidProtocolInputResolver constructor(
    private val musicBrainzClient: MusicBrainzClient,
    private val spotifyClient: SpotifyClient,
    private val appleMusicClient: AppleMusicClient,
    private val metadataService: MetadataService,
    private val httpClient: HttpClient,
    appleMusicDeveloperToken: String = "",
    spotifyAccessToken: suspend () -> String? = { null },
) : ProtocolInputResolver {

    /** Body cap on URL-fetched tracklists — guards against very large
     *  publisher errors. 100KB matches desktop's per-payload limit. */
    private val maxTracklistBytes: Int = 100 * 1024

    // play/playlist provider-page resolution (parachord#930) — shared with iOS.
    private val providerPlaylistResolver = com.parachord.shared.deeplink.ProviderPlaylistResolver(
        spotifyClient, appleMusicClient, httpClient, appleMusicDeveloperToken, spotifyAccessToken,
    )

    override suspend fun resolveProviderPlaylist(url: String): ResolvedProtocolPlay? =
        providerPlaylistResolver.resolve(url)

    override suspend fun resolveByMbid(mbid: String): ResolvedProtocolPlay? {
        // Try /release/{mbid} first (cheap, common path). If it 404s — typical
        // for release-group MBIDs that Achordion + most music tools emit —
        // fall back to /release?release-group={mbid} to find a canonical
        // release within the release-group.
        //
        // expectSuccess=false on the shared HttpClient means a 404's error
        // JSON `{"error":"Not Found"}` doesn't trip a status throw, but the
        // body deserializer fires a SerializationException when the payload
        // doesn't match MbReleaseDetail. The catch below handles both that
        // and any genuinely malformed response. We additionally fall through
        // when getRelease returns a usable object that has no media[] — that
        // case is rare in practice but the release-group browse may carry a
        // sibling edition with full tracks.
        val releaseFromDirect: MbReleaseDetail? = try {
            musicBrainzClient.getRelease(mbid, inc = "recordings+artist-credits")
                .takeIf { it.media.isNotEmpty() }
        } catch (e: Exception) {
            Log.d(TAG, "MBID $mbid not a release (${e.message?.take(80)}); trying release-group")
            null
        }
        val releaseFromGroup: MbReleaseDetail? = if (releaseFromDirect != null) null else try {
            musicBrainzClient.browseReleasesByReleaseGroup(
                releaseGroupMbid = mbid,
                inc = "recordings+artist-credits",
                limit = 1,
            ).releases.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "MBID $mbid release-group browse failed: ${e.message}")
            null
        }
        val release: MbReleaseDetail = releaseFromDirect ?: releaseFromGroup ?: return null

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
        return ResolvedProtocolPlay(
            displayName = release.title.ifEmpty { "Album" },
            tracks = tracks,
        )
    }

    override suspend fun resolveBySpotify(spotifyIdOrUri: String): ResolvedProtocolPlay? {
        val albumId = extractSpotifyAlbumId(spotifyIdOrUri) ?: return null
        return try {
            val page = spotifyClient.getAlbumTracks(albumId, limit = 50)
            val items = page.items.filter { !it.name.isNullOrBlank() }
            if (items.isEmpty()) return null
            // Spotify's getAlbumTracks doesn't return the album title — derive
            // displayName from the first track's first artist + a generic
            // "Album" suffix. Phase 2.1 could call SpotifyClient.search to fetch
            // the album metadata, but that's an extra round-trip for cosmetic UI.
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
            // `lookup` with no entity returns the bare collection; with
            // entity=song it returns the album + all its tracks. We always
            // request entity=song since the protocol contract is "play this
            // album", which means we need its tracks.
            val resp = appleMusicClient.lookup(appleMusicId, entity = "song")
            val tracks = resp.results.filter { it.wrapperType == "track" && it.kind == "song" }
            if (tracks.isEmpty()) return null
            val albumName = resp.results.firstOrNull { it.wrapperType == "collection" }?.collectionName
                ?: tracks.firstOrNull()?.collectionName
                ?: "Apple Music Album"
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
        // SSRF guard runs at the entry point — and again on every subsequent
        // fetch if we ever follow redirects. Currently we use the shared Ktor
        // client which respects its `followRedirects` setting; the Location
        // header is not re-validated here. Document that as a known gap.
        validatePublicHttpsUrl(url)
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
            val parsed = parseProtocolTracklist(body, ::parseXspfForProtocol)
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
        // For play/album the "album" name is in `title` (per protocol-schema.md
        // §Play Album: ?artist=&title= where title is the album title). Pass
        // through the standard MetadataService cascade.
        val albumTitle = title ?: album ?: return null
        return try {
            val detail = metadataService.getAlbumTracks(albumTitle, artist) ?: return null
            if (detail.tracks.isEmpty()) return null
            ResolvedProtocolPlay(
                displayName = detail.title,
                tracks = detail.tracks.map { t ->
                    ProtocolTrack(
                        artist = t.artist,
                        title = t.title,
                        album = t.album ?: detail.title,
                        mbid = t.mbid,
                    )
                },
                albumArt = detail.artworkUrl,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Artist+title resolve failed for '$albumTitle' by '$artist': ${e.message}")
            null
        }
    }

    /**
     * Extract a bare Spotify album ID from either `spotify:album:<id>` URI
     * form or a bare ID. Returns null when the input is neither.
     */
    private fun extractSpotifyAlbumId(input: String): String? {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("spotify:album:") -> trimmed.removePrefix("spotify:album:").takeIf { it.isNotBlank() }
            trimmed.startsWith("spotify:") -> null  // wrong type (track / playlist / artist)
            trimmed.contains(':') -> null
            else -> trimmed.takeIf { it.matches(Regex("^[A-Za-z0-9]{20,40}$")) }
        }
    }
}
