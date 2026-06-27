package com.parachord.android.share

import android.net.Uri
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.EntityType
import com.parachord.shared.api.SubmitTrackLinksRequest
import com.parachord.shared.api.TrackLink
import com.parachord.shared.platform.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * Builds shareable URLs for tracks, albums, playlists, and artists.
 *
 * - **Tracks / albums / artists** resolve to Achordion entity pages
 *   (`achordion.xyz/{recording,release-group,artist}/...`) via
 *   [AchordionClient]. Track shares additionally pre-warm Achordion's
 *   match cache via `POST /api/track-links/submit` so recipients land
 *   on a fully-populated entity page on first click. Mirrors desktop's
 *   `publishSmartLink` / `publishAlbumSmartLink` / `publishArtistSmartLink`.
 * - **Playlists** resolve to the Achordion playlist page keyed on the
 *   ListenBrainz MBID anchor. A playlist with no LB link isn't shareable
 *   yet (`sharePlaylist` returns null; the caller shows a "sync to
 *   ListenBrainz" toast) — matches desktop's `publishCollectionSmartLink`.
 *   We no longer mint `go.parachord.com` smart-links for playlists: the
 *   inbound round-trip (#138) can't resolve a device-local playlist id on
 *   the recipient, so a tracklist/Achordion path is the only correct share.
 * - For tracks/albums/artists, when the MBID is missing or the API
 *   fails/times out the path falls through to `achordion.xyz/<type>/lookup?...`,
 *   so a share always produces a usable URL.
 *
 * Network calls are bounded by a 4 s timeout so a slow API can't hold
 * up the share sheet.
 */
class ShareManager constructor(
    private val achordionClient: AchordionClient,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val syncPlaylistLinkDao: com.parachord.shared.db.dao.SyncPlaylistLinkDao,
) {
    companion object {
        private const val TAG = "ShareManager"
        private const val SMART_LINK_TIMEOUT_MS = 4_000L
    }

    data class ShareResult(
        val url: String,
        val subject: String,
        /** True when we got a smart-link URL; false when we fell back to the deeplink. */
        val isSmartLink: Boolean,
    )

    suspend fun shareTrack(track: TrackEntity): ShareResult = coroutineScope {
        val subject = "${track.artist} – ${track.title}"
        val mbid = track.recordingMbid

        // Fire entity-link + submit in parallel. Both AWAITED so the recipient's
        // first click sees a fully-warmed Achordion page. Matches desktop's
        // publishSmartLink behavior (parachord-desktop/app.js:13380+).
        val entityLinkJob = async { tryFetchEntityLink(EntityType.Track, mbid) }
        val submitJob = async { trySubmitForTrack(track, mbid) }
        val entityUrl = entityLinkJob.await()
        submitJob.await()

        val url = entityUrl ?: trackLookupUrl(track.artist, track.title)
        ShareResult(url, subject, isSmartLink = entityUrl != null)
    }

    private suspend fun tryFetchEntityLink(type: EntityType, mbid: String?): String? {
        if (mbid.isNullOrBlank()) return null
        return try {
            withTimeout(SMART_LINK_TIMEOUT_MS) {
                achordionClient.fetchEntityLink(type, mbid)?.url
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "fetchEntityLink timed out for $type mbid=$mbid")
            null
        } catch (e: Exception) {
            Log.w(TAG, "fetchEntityLink failed for $type mbid=$mbid: ${e.message}")
            null
        }
    }

    private suspend fun trySubmitForTrack(track: TrackEntity, mbid: String?) {
        val key = mbid?.takeIf { it.isNotBlank() } ?: track.isrc?.takeIf { it.isNotBlank() }
        // #216: an ISRC is a valid recording key too — submit even without an MBID
        // (the server derives it from the ISRC). Skip only when we have neither.
        if (key == null) return
        val links = buildList {
            track.spotifyId?.takeIf { it.isNotBlank() }?.let {
                add(TrackLink(url = "https://open.spotify.com/track/$it", host = "spotify.com", label = "Spotify"))
            }
            track.appleMusicId?.takeIf { it.isNotBlank() }?.let {
                add(TrackLink(url = "https://music.apple.com/song/$it", host = "music.apple.com", label = "Apple Music"))
            }
            track.soundcloudId?.takeIf { it.isNotBlank() }?.let {
                add(TrackLink(url = "https://soundcloud.com/$it", host = "soundcloud.com", label = "SoundCloud"))
            }
        }
        if (links.isEmpty()) return
        try {
            withTimeout(SMART_LINK_TIMEOUT_MS) {
                achordionClient.submitTrackLinks(
                    SubmitTrackLinksRequest(
                        mbid = mbid?.takeIf { it.isNotBlank() },
                        isrc = track.isrc?.takeIf { it.isNotBlank() },
                        links = links,
                        trackName = track.title,
                        artistName = track.artist,
                        albumName = track.album,
                    )
                )
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "submitTrackLinks timed out for key=$key")
        } catch (e: Exception) {
            Log.w(TAG, "submitTrackLinks failed for key=$key: ${e.message}")
        }
    }

    private fun trackLookupUrl(artist: String, title: String): String =
        "https://achordion.xyz/recording/lookup?artist=${enc(artist)}&title=${enc(title)}"

    suspend fun shareAlbum(
        title: String,
        artist: String,
        @Suppress("UNUSED_PARAMETER") artworkUrl: String?, // kept for caller compat; unused after Achordion migration
        releaseGroupMbid: String?,
    ): ShareResult {
        val subject = "$artist – $title"
        val entityUrl = tryFetchEntityLink(EntityType.ReleaseGroup, releaseGroupMbid)
        val url = entityUrl ?: releaseGroupLookupUrl(artist, title)
        return ShareResult(url, subject, isSmartLink = entityUrl != null)
    }

    private fun releaseGroupLookupUrl(artist: String, title: String): String =
        "https://achordion.xyz/release-group/lookup?artist=${enc(artist)}&title=${enc(title)}"

    /**
     * Convenience for callsites that have a [playlistId] but no in-memory
     * track list — fetches both from the local DB before sharing. Returns
     * null when the playlist row doesn't exist anymore.
     */
    suspend fun sharePlaylist(playlistId: String): ShareResult? {
        val playlist = playlistDao.getById(playlistId) ?: return null
        val tracks = playlistTrackDao.getByPlaylistIdSync(playlistId)
        return sharePlaylist(playlist, tracks)
    }

    /**
     * Achordion-only (matches desktop `publishCollectionSmartLink`): a playlist
     * shares via its ListenBrainz MBID anchor — the LB push-link, or the
     * id-prefix when LB is the source. **Returns null when there's no LB anchor**
     * (caller shows a "sync to ListenBrainz" toast). We no longer mint
     * `go.parachord.com` smart-links for playlists — the inbound round-trip (#138)
     * can't resolve a device-local playlist id on the recipient's device.
     */
    suspend fun sharePlaylist(
        playlist: PlaylistEntity,
        @Suppress("UNUSED_PARAMETER") tracks: List<PlaylistTrackEntity>,
    ): ShareResult? {
        val lbMbid = syncPlaylistLinkDao.selectForLink(playlist.id, "listenbrainz")?.externalId
            ?: playlist.id.takeIf { it.startsWith("listenbrainz-") }?.removePrefix("listenbrainz-")
            ?: return null
        return ShareResult(achordionClient.playlistShareUrl(lbMbid), playlist.name, isSmartLink = true)
    }

    /**
     * Shares an artist via the Achordion artist entity page when we have an
     * MBID, falling back to the `/artist/lookup?name=` route otherwise.
     * Matches desktop's behavior — no smart-link submit since artists don't
     * have track-link payloads.
     */
    suspend fun shareArtist(name: String, artistMbid: String? = null): ShareResult {
        val entityUrl = tryFetchEntityLink(EntityType.Artist, artistMbid)
        val url = entityUrl ?: artistLookupUrl(name)
        return ShareResult(url, name, isSmartLink = entityUrl != null)
    }

    private fun artistLookupUrl(name: String): String =
        "https://achordion.xyz/artist/lookup?name=${enc(name)}"

    private fun enc(value: String): String = Uri.encode(value)
}
