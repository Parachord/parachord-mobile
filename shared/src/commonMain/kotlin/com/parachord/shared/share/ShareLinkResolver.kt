package com.parachord.shared.share

import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.EntityType
import com.parachord.shared.api.SubmitTrackLinksRequest
import com.parachord.shared.api.TrackLink
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.SyncPlaylistLinkDao
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * KMP-shared share-URL builder — the commonMain port of Android's `ShareManager`
 * (outbound sharing, #222), reusing the already-shared [AchordionClient] + DAOs so
 * iOS shares route through the SAME Achordion entity links + cache pre-warm +
 * lookup-fallback path as Android, instead of a plain text `ShareLink`.
 *
 * - Track / album / artist → Achordion entity page (`achordion.xyz/{recording,
 *   release-group,artist}/…`); tracks also `POST /track-links/submit` to pre-warm
 *   the recipient's per-service links. MBID-miss / API-fail → `…/lookup?…` URL.
 * - Playlist → Achordion playlist page when on ListenBrainz; **null when there's
 *   no LB anchor** (caller shows a "sync to ListenBrainz" hint). No
 *   `go.parachord.com` smart-link minting — the inbound round-trip (#138) can't
 *   resolve a device-local playlist id on the recipient. Matches desktop's
 *   `publishCollectionSmartLink` (and Android's `ShareManager`).
 *
 * Network calls are bounded by a 4 s timeout so a slow API can't hold the share
 * sheet.
 */
class ShareLinkResolver(
    private val achordionClient: AchordionClient,
    private val playlistDao: PlaylistDao,
    private val syncPlaylistLinkDao: SyncPlaylistLinkDao,
) {
    companion object {
        private const val TAG = "ShareLinkResolver"
        private const val TIMEOUT_MS = 4_000L
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

    /** Share by playlist id — fetches the row + LB link from the DB. Null when the
     *  row is gone OR there's no LB anchor (Achordion-only, see below). */
    suspend fun sharePlaylist(playlistId: String): ShareResult? {
        val playlist = playlistDao.getById(playlistId) ?: return null
        return sharePlaylist(playlist)
    }

    /**
     * Achordion-only (matches desktop + Android `ShareManager`): a playlist shares
     * via its ListenBrainz MBID anchor — the LB push-link, or the id-prefix when LB
     * is the source. **Returns null when there's no LB anchor** (caller shows a
     * "sync to ListenBrainz" hint). No `go.parachord.com` smart-link minting — the
     * inbound round-trip (#138) can't resolve a device-local playlist id on the
     * recipient.
     */
    suspend fun sharePlaylist(playlist: Playlist): ShareResult? {
        val lbMbid = syncPlaylistLinkDao.selectForLink(playlist.id, "listenbrainz")?.externalId
            ?: playlist.id.takeIf { it.startsWith("listenbrainz-") }?.removePrefix("listenbrainz-")
            ?: return null
        return ShareResult(achordionClient.playlistShareUrl(lbMbid), playlist.name, isSmartLink = true)
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

    private fun lookupUrl(type: String, artist: String, title: String): String =
        "https://achordion.xyz/$type/lookup?artist=${enc(artist)}&title=${enc(title)}"

    private fun enc(value: String): String = buildString {
        for (c in value) {
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~') append(c)
            else for (b in c.toString().encodeToByteArray()) {
                append('%').append(((b.toInt() and 0xFF) or 0x100).toString(16).substring(1).uppercase())
            }
        }
    }
}
