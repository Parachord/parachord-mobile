package com.parachord.shared.playlist

import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.deeplink.HOSTED_PLAYLIST_MAX_BYTES
import com.parachord.shared.deeplink.fetchPublicPlaylistText
import com.parachord.shared.deeplink.validatePublicHttpsUrl
import com.parachord.shared.metadata.ImageEnrichmentService
import com.parachord.shared.metadata.PlaylistCovers
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.platform.randomUUID
import com.parachord.shared.repository.LibraryRepository
import io.ktor.client.HttpClient

/** Outcome of an XSPF import — playlist id, name, and how many tracks landed. */
data class XspfImportResult(
    val playlistId: String,
    val playlistName: String,
    val trackCount: Int,
)

/**
 * KMP-shared (commonMain) XSPF import + hosted-poll service — the cross-platform
 * core behind issue #254 (iOS XSPF support). Mirrors Android's
 * `PlaylistImportManager` (XSPF paths) + `HostedPlaylistPoller`, but built
 * entirely on already-shared pieces (Ktor, `PlaylistDao`/`PlaylistTrackDao`,
 * `LibraryRepository`, `ImageEnrichmentService`, `validatePublicHttpsUrl`,
 * [XspfParser], [sha256Hex]) so it runs unchanged on both platforms.
 *
 * Hosted playlists carry `sourceUrl` + a SHA-256 `sourceContentHash`; [pollAll]
 * re-fetches each, and when the body changed it replaces the tracks and stamps
 * `locallyModified` (via [PlaylistDao.updateHostedSnapshot]) so the next sync
 * pushes the new state. The XSPF is canonical — local edits to an in-lockstep
 * hosted playlist are overwritten on the next tick. SSRF is re-validated on
 * every fetch (a DNS record can change between import and a later poll).
 *
 * Errors are logged and swallowed — the next tick retries (desktop parity).
 *
 * Both apps import hosted XSPF links through [importHostedXspf] (via
 * [PlaylistUrlImporter]). Android still polls with its own `HostedPlaylistPoller`
 * (XmlPullParser + WorkManager); moving its polling here is a follow-up.
 */
class HostedXspfService(
    private val httpClient: HttpClient,
    private val libraryRepository: LibraryRepository,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val imageEnrichmentService: ImageEnrichmentService,
) {
    companion object {
        private const val TAG = "HostedXspfService"
    }

    /** Import a local `.xspf` file's contents (no `sourceUrl` → not polled). */
    suspend fun importLocalXspf(content: String): XspfImportResult {
        val parsed = XspfParser.parse(content)
        return save(parsed.title, "imported-xspf", parsed.tracks, sourceUrl = null, contentHash = null, cover = parsed.imageUrl)
    }

    /** Import an XSPF playlist from an HTTPS URL → a hosted playlist (polled). */
    suspend fun importHostedXspf(url: String): XspfImportResult {
        validatePublicHttpsUrl(url)  // H10: https-only + private/loopback host block
        // An import says why it failed (refused redirect, too large, HTTP status).
        val content = fetchPublicPlaylistText(httpClient, url, HOSTED_PLAYLIST_MAX_BYTES)
            .ifBlank { throw IllegalStateException("Failed to fetch playlist from $url") }
        require(looksLikeXspf(content)) { "URL does not contain a valid XSPF playlist" }
        val parsed = XspfParser.parse(content)
        return save(parsed.title, "hosted-xspf", parsed.tracks, sourceUrl = url, contentHash = sha256Hex(content), cover = parsed.imageUrl)
    }

    /** Poll every hosted playlist in the DB; replace tracks on content change. */
    suspend fun pollAll() {
        val hosted = playlistDao.getHosted()
        if (hosted.isEmpty()) return
        Log.d(TAG, "Polling ${hosted.size} hosted playlist(s)")
        for (p in hosted) runCatching { poll(p) }
    }

    /** Poll one hosted playlist. Returns true iff the content changed + tracks
     *  were replaced; false if unchanged, fetch failed, or not actually hosted. */
    suspend fun poll(playlist: Playlist): Boolean {
        val url = playlist.sourceUrl ?: return false
        try {
            validatePublicHttpsUrl(url)  // re-check: DNS could have changed since import
        } catch (e: Exception) {
            Log.w(TAG, "Skipping hosted '${playlist.name}' — ${e.message}")
            return false
        }
        val content = fetch(url) ?: return false
        val hash = sha256Hex(content)
        if (hash == playlist.sourceContentHash) return false
        if (!looksLikeXspf(content)) {
            Log.w(TAG, "Hosted '${playlist.name}' body is not valid XSPF")
            return false
        }
        val parsed = try {
            XspfParser.parse(content)
        } catch (e: Exception) {
            Log.w(TAG, "Hosted '${playlist.name}' parse failed: ${e.message}")
            return false
        }

        val now = currentTimeMillis()
        val rows = parsed.tracks.mapIndexed { i, t ->
            PlaylistTrack(
                playlistId = playlist.id,
                position = i,
                trackTitle = t.title,
                trackArtist = t.artist,
                trackAlbum = t.album,
                trackDuration = t.duration,
                trackArtworkUrl = t.artworkUrl,
                trackSourceUrl = t.sourceUrl,
                trackResolver = t.resolver,
                trackSpotifyUri = t.spotifyUri,
                trackSoundcloudId = t.soundcloudId,
                trackSpotifyId = t.spotifyId,
                trackAppleMusicId = t.appleMusicId,
                addedAt = now,
            )
        }
        playlistTrackDao.replaceAll(playlist.id, rows)
        // Bumps trackCount + sourceContentHash + lastModified + locallyModified=1
        // → the next SyncEngine push overwrites the mirror with the XSPF content.
        playlistDao.updateHostedSnapshot(
            playlistId = playlist.id,
            contentHash = hash,
            trackCount = rows.size,
            lastModified = now,
        )
        // The document's cover, or the playlist's own it already had, stays
        // (#852); else the tracklist changed, the prior mosaic is stale: clear +
        // rebuild with a cache-bust token so Coil/SwiftUI reload the file.
        val cover = PlaylistCovers.coverAfterHostedUpdate(playlist.artworkUrl, parsed.imageUrl, rows.map { it.trackArtworkUrl })
        if (cover != null) {
            if (cover != playlist.artworkUrl) {
                val old = playlist.artworkUrl
                if (old == null) playlistDao.updateArtworkById(playlist.id, cover)
                else playlistDao.replaceArtworkById(playlist.id, expected = old, artworkUrl = cover)
            }
        } else {
            playlistDao.clearArtworkById(playlist.id)
            runCatching {
                imageEnrichmentService.enrichPlaylistArt(playlist.id, cacheBustToken = hash.take(8))
            }
        }
        Log.d(TAG, "Hosted '${playlist.name}' updated (${rows.size} tracks)")
        return true
    }

    /** The hosted XSPF's text, every redirect hop checked and the body capped
     *  ([fetchPublicPlaylistText], GHSA-gfvj-rh43-vmp9); null when it failed. */
    private suspend fun fetch(url: String): String? = try {
        fetchPublicPlaylistText(httpClient, url, HOSTED_PLAYLIST_MAX_BYTES).ifBlank { null }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Fetch $url error: ${e.message}")
        null
    }

    private fun looksLikeXspf(content: String): Boolean =
        content.contains("<playlist", ignoreCase = true) && content.contains("</playlist>", ignoreCase = true)

    private suspend fun save(
        name: String,
        source: String,
        tracks: List<Track>,
        sourceUrl: String?,
        contentHash: String?,
        cover: String?,
    ): XspfImportResult {
        val playlistId = "$source-${randomUUID()}"
        val playlist = Playlist(
            id = playlistId,
            name = name,
            // Its own cover, or none and the mosaic is built: never a track's (#852).
            artworkUrl = cover,
            trackCount = tracks.size,
            sourceUrl = sourceUrl,
            sourceContentHash = contentHash,
        )
        libraryRepository.createPlaylistWithTracks(playlist, tracks)
        Log.d(TAG, "Imported '$name' (${tracks.size} tracks, id=$playlistId)")
        return XspfImportResult(playlistId, name, tracks.size)
    }
}
