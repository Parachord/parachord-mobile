package com.parachord.shared.share

import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.EntityType
import com.parachord.shared.api.SmartLinkCreateRequest
import com.parachord.shared.api.SmartLinkTrack
import com.parachord.shared.api.SmartLinksClient
import com.parachord.shared.api.SubmitTrackLinksRequest
import com.parachord.shared.api.TrackLink
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.SyncPlaylistLinkDao
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * KMP-shared share-URL builder — the commonMain port of Android's `ShareManager`
 * (outbound sharing, #222), reusing the already-shared [AchordionClient] /
 * [SmartLinksClient] + DAOs so iOS shares route through the SAME Achordion entity
 * links + cache pre-warm + smart-link / lookup-fallback path as Android, instead
 * of a plain text `ShareLink`.
 *
 * - Track / album / artist → Achordion entity page (`achordion.xyz/{recording,
 *   release-group,artist}/…`); tracks also `POST /track-links/submit` to pre-warm
 *   the recipient's per-service links. MBID-miss / API-fail → `…/lookup?…` URL.
 * - Playlist → Achordion playlist page when on ListenBrainz, else a
 *   `go.parachord.com` smart-link, else the `parachord.com/go?uri=` deeplink.
 *
 * Network calls are bounded by a 4 s timeout so a slow API can't hold the share
 * sheet. Android keeps its own `ShareManager` for now (converging it onto this is
 * a tracked follow-up); this changes no Android behavior.
 */
class ShareLinkResolver(
    private val achordionClient: AchordionClient,
    private val smartLinksClient: SmartLinksClient,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val syncPlaylistLinkDao: SyncPlaylistLinkDao,
) {
    companion object {
        private const val TAG = "ShareLinkResolver"
        private const val TIMEOUT_MS = 4_000L
        private const val GO_REDIRECT = "https://parachord.com/go?uri="
    }

    data class ShareResult(val url: String, val subject: String, val isSmartLink: Boolean)

    suspend fun shareTrack(track: Track): ShareResult = coroutineScope {
        val subject = "${track.artist} – ${track.title}"
        val mbid = track.recordingMbid
        // Entity-link + submit in parallel; both awaited so the recipient's first
        // click sees a warmed Achordion page (desktop publishSmartLink parity).
        val entityLinkJob = async { tryFetchEntityLink(EntityType.Track, mbid) }
        val submitJob = async { trySubmitForTrack(track, mbid) }
        val entityUrl = entityLinkJob.await()
        submitJob.await()
        val url = entityUrl ?: lookupUrl("recording", artist = track.artist, title = track.title)
        ShareResult(url, subject, isSmartLink = entityUrl != null)
    }

    suspend fun shareAlbum(title: String, artist: String, releaseGroupMbid: String?): ShareResult {
        val subject = "$artist – $title"
        val entityUrl = tryFetchEntityLink(EntityType.ReleaseGroup, releaseGroupMbid)
        val url = entityUrl ?: lookupUrl("release-group", artist = artist, title = title)
        return ShareResult(url, subject, isSmartLink = entityUrl != null)
    }

    suspend fun shareArtist(name: String, artistMbid: String? = null): ShareResult {
        val entityUrl = tryFetchEntityLink(EntityType.Artist, artistMbid)
        val url = entityUrl ?: "https://achordion.xyz/artist/lookup?name=${enc(name)}"
        return ShareResult(url, name, isSmartLink = entityUrl != null)
    }

    /** Share by playlist id — fetches the row + tracks + LB link from the DB. */
    suspend fun sharePlaylist(playlistId: String): ShareResult? {
        val playlist = playlistDao.getById(playlistId) ?: return null
        val tracks = playlistTrackDao.getByPlaylistIdSync(playlistId)
        return sharePlaylist(playlist, tracks)
    }

    suspend fun sharePlaylist(playlist: Playlist, tracks: List<PlaylistTrack>): ShareResult {
        val subject = playlist.name
        // Prefer the Achordion playlist page, keyed on the LB MBID (cross-platform
        // anchor): the LB push-link, or the id-prefix when LB is the source.
        val lbMbid = syncPlaylistLinkDao.selectForLink(playlist.id, "listenbrainz")?.externalId
            ?: playlist.id.takeIf { it.startsWith("listenbrainz-") }?.removePrefix("listenbrainz-")
        if (lbMbid != null) {
            return ShareResult(achordionClient.playlistShareUrl(lbMbid), subject, true)
        }
        val playlistUrls = buildMap {
            playlist.spotifyId?.let { put("spotify", "https://open.spotify.com/playlist/$it") }
        }
        val smart = tryCreateSmartLink(
            SmartLinkCreateRequest(
                title = playlist.name,
                creator = playlist.ownerName,
                albumArt = playlist.artworkUrl,
                type = "playlist",
                urls = playlistUrls.takeIf { it.isNotEmpty() },
                tracks = tracks.map { it.toSmartLinkTrack() }.ifEmpty { null },
            ),
        )
        val url = smart ?: (GO_REDIRECT + enc("parachord://playlist/${enc(playlist.id)}"))
        return ShareResult(url, subject, smart != null)
    }

    private suspend fun tryFetchEntityLink(type: EntityType, mbid: String?): String? {
        if (mbid.isNullOrBlank()) return null
        return try {
            withTimeout(TIMEOUT_MS) { achordionClient.fetchEntityLink(type, mbid)?.url }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "fetchEntityLink timed out for $type mbid=$mbid"); null
        } catch (e: Exception) {
            Log.w(TAG, "fetchEntityLink failed for $type mbid=$mbid: ${e.message}"); null
        }
    }

    private suspend fun trySubmitForTrack(track: Track, mbid: String?) {
        val key = mbid?.takeIf { it.isNotBlank() } ?: track.isrc?.takeIf { it.isNotBlank() } ?: return
        val links = buildList {
            track.spotifyId?.takeIf { it.isNotBlank() }?.let {
                add(TrackLink(url = "https://open.spotify.com/track/$it", host = "spotify.com", label = "Spotify"))
            }
            track.appleMusicId?.takeIf { it.isNotBlank() }?.let {
                add(TrackLink(url = "https://music.apple.com/us/song/$it", host = "music.apple.com", label = "Apple Music"))
            }
            track.soundcloudId?.takeIf { it.isNotBlank() }?.let {
                add(TrackLink(url = "https://soundcloud.com/$it", host = "soundcloud.com", label = "SoundCloud"))
            }
        }
        if (links.isEmpty()) return
        try {
            withTimeout(TIMEOUT_MS) {
                achordionClient.submitTrackLinks(
                    SubmitTrackLinksRequest(
                        mbid = mbid?.takeIf { it.isNotBlank() },
                        isrc = track.isrc?.takeIf { it.isNotBlank() },
                        links = links,
                        trackName = track.title,
                        artistName = track.artist,
                        albumName = track.album,
                    ),
                )
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "submitTrackLinks timed out for key=$key")
        } catch (e: Exception) {
            Log.w(TAG, "submitTrackLinks failed for key=$key: ${e.message}")
        }
    }

    private suspend fun tryCreateSmartLink(request: SmartLinkCreateRequest): String? = try {
        withTimeout(TIMEOUT_MS) { smartLinksClient.create(request).url }
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "smart-link create timed out for '${request.title}'"); null
    } catch (e: Exception) {
        Log.w(TAG, "smart-link create failed (${e.message}) for '${request.title}'"); null
    }

    private fun lookupUrl(type: String, artist: String, title: String): String =
        "https://achordion.xyz/$type/lookup?artist=${enc(artist)}&title=${enc(title)}"

    private fun PlaylistTrack.toSmartLinkTrack(): SmartLinkTrack = SmartLinkTrack(
        title = trackTitle,
        artist = trackArtist,
        duration = trackDuration,
        urls = buildMap {
            trackSpotifyId?.let { put("spotify", "https://open.spotify.com/track/$it") }
            trackAppleMusicId?.let { put("applemusic", "https://music.apple.com/song/$it") }
            trackSoundcloudId?.let { put("soundcloud", "https://soundcloud.com/$it") }
        },
        albumArt = trackArtworkUrl,
    )

    private fun enc(value: String): String = buildString {
        for (c in value) {
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~') append(c)
            else for (b in c.toString().encodeToByteArray()) {
                append('%').append(((b.toInt() and 0xFF) or 0x100).toString(16).substring(1).uppercase())
            }
        }
    }
}
