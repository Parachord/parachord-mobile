package com.parachord.shared.sync

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.settings.SettingsStore
import kotlin.concurrent.Volatile

/**
 * Two-way playlist sync between Parachord and ListenBrainz.
 *
 * Mirrors [AppleMusicSyncProvider]'s shape — session-scoped
 * `authFailedForSession` kill-switch on 401, graceful no-op handling
 * for the not-yet-supported surface (tracks / albums / artists fall
 * back to inherited defaults).
 *
 * V1 scope: playlists only (push + pull). Loved tracks stay on
 * [com.parachord.android.playback.LovesPushService]. Library surface
 * (saved tracks/albums, followed artists) inherits the no-op
 * defaults from [SyncProvider].
 *
 * See `docs/plans/2026-05-27-listenbrainz-sync-provider-design.md`.
 */
class ListenBrainzSyncProvider(
    private val client: ListenBrainzClient,
    private val settingsStore: SettingsStore,
    private val mbidEnrichmentService: MbidEnrichmentService,
) : SyncProvider {
    companion object {
        const val PROVIDER_ID = "listenbrainz"
        private const val TAG = "ListenBrainzSyncProvider"
    }

    override val id: String = PROVIDER_ID
    override val displayName: String = "ListenBrainz"

    override val features: ProviderFeatures = ProviderFeatures(
        snapshots = SnapshotKind.DateString,         // LB returns last_modified_at ISO string
        supportsFollow = false,                       // V2
        supportsPlaylistDelete = true,                // POST /1/playlist/{mbid}/delete
        supportsPlaylistRename = true,                // POST /1/playlist/edit/{mbid}
        supportsTrackReplace = true,                  // delete-all + add-all
    )

    /**
     * Session-scoped kill-switch. Tripped by a 401 from any mutation
     * endpoint; remaining LB pushes in the session short-circuit until
     * the user re-authenticates. Mirrors the AM pattern.
     */
    @Volatile
    private var authFailedForSession: Boolean = false

    // ── Playlist surface — all TODO for now, implemented in Tasks 10-13 ─

    override suspend fun fetchPlaylists(
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedPlaylist> {
        // Kill-switch active — short-circuit until session restart so we
        // don't hammer LB with calls we know will 401 again. Mirrors AM's
        // `amPutUnsupportedForSession` / `amPatchUnsupportedForSession`
        // pattern.
        if (authFailedForSession) return emptyList()

        // Provider not configured — token + username are both required.
        // Treat unconfigured as "no playlists" (NOT an error) so SyncEngine
        // can iterate every enabled provider blindly.
        val token = settingsStore.getListenBrainzToken()
        if (token.isNullOrBlank()) return emptyList()
        val username = settingsStore.getListenBrainzUsername()
        if (username.isNullOrBlank()) return emptyList()

        val lbPlaylists = try {
            client.getUserOwnedPlaylists(username)
        } catch (e: ListenBrainzUnauthorizedException) {
            // Defensive — getUserOwnedPlaylists is unauth today (LB public playlists
            // are world-readable), so this catch is mostly for symmetry with the
            // AM provider and future-proofing in case LB starts requiring auth on
            // this endpoint. Real 401s today land in the generic-Exception path
            // below and propagate to SyncEngine. The kill-switch primarily exists
            // to trip from the *mutation* endpoints (createPlaylist, replacePlaylist
            // tracks, etc.) which DO send Token and DO translate 401.
            Log.w(TAG, "LB fetchPlaylists 401 — tripping kill-switch", e)
            authFailedForSession = true
            return emptyList()
        }
        return lbPlaylists.map { it.toSyncedPlaylist() }
    }

    private fun com.parachord.shared.api.LbPlaylist.toSyncedPlaylist(): SyncedPlaylist {
        // SyncedPlaylist's `spotifyId` slot carries the provider's external
        // id (the field name is Spotify-shaped by historical accident; AM
        // does the same). The LB playlist MBID lives here.
        val description = annotation.ifBlank { null }
        val playlistEntity = Playlist(
            id = "listenbrainz-$mbid",
            name = title,
            description = description,
            artworkUrl = null,
            trackCount = 0,
            createdAt = 0L,
            updatedAt = currentTimeMillis(),
            spotifyId = null,
            snapshotId = lastModifiedAt,
            lastModified = 0L,
            locallyModified = false,
            ownerName = null,
            sourceUrl = null,
            sourceContentHash = null,
            localOnly = false,
        )
        return SyncedPlaylist(
            entity = playlistEntity,
            spotifyId = mbid,
            snapshotId = lastModifiedAt,
            trackCount = 0,
            // The `/1/user/{userName}/playlists` endpoint returns only the
            // user's OWN playlists — every entry here is owned + mutable.
            isOwned = true,
        )
    }

    override suspend fun fetchPlaylistTracks(
        externalPlaylistId: String,
    ): List<PlaylistTrack> {
        // playlistId follows the `<provider>-<externalId>` convention used by
        // SpotifySyncProvider + AppleMusicSyncProvider. SyncEngine remaps this
        // to the local id when persisting; this value is a stable, locally
        // recognisable placeholder.
        val localPlaylistId = "listenbrainz-$externalPlaylistId"
        val richTracks = client.getPlaylistTracksRich(externalPlaylistId)
        return richTracks.mapIndexed { index, t ->
            PlaylistTrack(
                playlistId = localPlaylistId,
                position = index,
                trackTitle = t.title,
                trackArtist = t.artist,
                trackAlbum = t.album,
                trackDuration = t.durationMs,
                trackArtworkUrl = t.albumArt,
                trackRecordingMbid = t.mbid,
            )
        }
    }

    override suspend fun getPlaylistSnapshotId(
        externalPlaylistId: String,
    ): String? = client.getPlaylistLastModified(externalPlaylistId)

    override suspend fun createPlaylist(
        name: String,
        description: String?,
    ): RemoteCreated {
        // Mutations require auth — unlike pulls. Throw so SyncEngine knows
        // the push can't proceed; this is a hard config error (not "no playlists").
        val token = settingsStore.getListenBrainzToken()
        if (token.isNullOrBlank()) {
            throw IllegalStateException("ListenBrainz token not configured")
        }
        val mbid = try {
            // Default PRIVATE, matching desktop (sync-providers/listenbrainz.js
            // hardcodes public:false) and the Achordion interop contract. LB
            // treats a MISSING public flag as public, so we must send it
            // explicitly. Pushing a user's whole library as public playlists is
            // a privacy leak — the original isPublic=true here is what put 6,397
            // public duplicates on a real profile (Parachord/parachord-android
            // pagination incident, May 2026).
            client.createPlaylist(name = name, description = description, isPublic = false, token = token)
        } catch (e: ListenBrainzUnauthorizedException) {
            Log.w(TAG, "LB createPlaylist 401 — tripping kill-switch", e)
            authFailedForSession = true
            throw e // propagate so SyncEngine knows the push failed
        }
        // Snapshot is fetched separately via getPlaylistSnapshotId — the create
        // endpoint returns only the MBID, not a last_modified timestamp.
        return RemoteCreated(externalId = mbid, snapshotId = null)
    }

    override suspend fun replacePlaylistTracks(
        externalPlaylistId: String,
        externalTrackIds: List<String>,
    ): String? {
        val token = settingsStore.getListenBrainzToken()
        if (token.isNullOrBlank()) {
            throw IllegalStateException("ListenBrainz token not configured")
        }

        try {
            // Get current track count to know how many to delete.
            val currentTracks = client.getPlaylistTracksRich(externalPlaylistId)
            val currentCount = currentTracks.size

            // Step 1: delete all existing items (if any).
            // NOTE: this is NOT atomic — between step 1 and step 2 the playlist
            // appears empty to other LB clients. Acceptable per the design
            // (mirrors how AM POST-appends after PUT degradation).
            if (currentCount > 0) {
                client.deletePlaylistItems(
                    playlistMbid = externalPlaylistId,
                    index = 0,
                    count = currentCount,
                    token = token,
                )
            }

            // Step 2: add desired items (if any).
            if (externalTrackIds.isNotEmpty()) {
                client.addPlaylistItems(
                    playlistMbid = externalPlaylistId,
                    recordingMbids = externalTrackIds,
                    token = token,
                )
            }

            // Step 3: re-fetch and return the new snapshot.
            return client.getPlaylistLastModified(externalPlaylistId)
        } catch (e: ListenBrainzUnauthorizedException) {
            Log.w(TAG, "LB replacePlaylistTracks 401 — tripping kill-switch", e)
            authFailedForSession = true
            throw e
        }
    }

    override suspend fun updatePlaylistDetails(
        externalPlaylistId: String,
        name: String?,
        description: String?,
    ) {
        // No-op when both are null — nothing to update.
        if (name == null && description == null) return

        val token = settingsStore.getListenBrainzToken()
        if (token.isNullOrBlank()) {
            throw IllegalStateException("ListenBrainz token not configured")
        }

        try {
            client.editPlaylist(
                playlistMbid = externalPlaylistId,
                name = name,
                description = description,
                token = token,
            )
        } catch (e: ListenBrainzUnauthorizedException) {
            Log.w(TAG, "LB updatePlaylistDetails 401 — tripping kill-switch", e)
            authFailedForSession = true
            throw e
        }
    }

    override suspend fun deletePlaylist(
        externalPlaylistId: String,
    ): DeleteResult {
        val token = settingsStore.getListenBrainzToken()
        if (token.isNullOrBlank()) {
            return DeleteResult.Failed(IllegalStateException("ListenBrainz token not configured"))
        }
        return try {
            client.deletePlaylist(externalPlaylistId, token)
            DeleteResult.Success
        } catch (e: ListenBrainzUnauthorizedException) {
            // Per the SyncProvider contract, deletePlaylist must NEVER throw on
            // documented-unsupported responses. For LB the 401 is an auth issue
            // (not "API doesn't support delete"), but the user can't recover
            // without re-auth — surface to UI as Unsupported(401) so they get
            // the "remove manually in {provider}" toast (matches AM pattern).
            Log.w(TAG, "LB deletePlaylist 401 — tripping kill-switch", e)
            authFailedForSession = true
            DeleteResult.Unsupported(401)
        } catch (e: Throwable) {
            // Re-throw cancellation per KMP convention — coroutine cancellation
            // must propagate, never be swallowed.
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "LB deletePlaylist failed", e)
            DeleteResult.Failed(e)
        }
    }

    override suspend fun searchForTrackId(
        title: String,
        artist: String,
        album: String?,
    ): String? {
        // Delegate to the existing MBID mapper. Returns the canonical
        // recording MBID for the title+artist pair, or null if no high-
        // confidence match was found (the LB mapper applies its own internal
        // confidence floor — we don't need to apply MIN_CONFIDENCE_THRESHOLD
        // here).
        //
        // Note arg order: mapperLookup is (artist, recording) — NOT (title, artist).
        val result = try {
            mbidEnrichmentService.mapperLookup(artist, title)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "LB searchForTrackId mapper lookup failed for '$title' / '$artist'", e)
            return null
        }
        return result?.recordingMbid
    }

    // Library surface (saveTracks, saveAlbums, fetchArtists, etc.) intentionally
    // inherits the no-op defaults from SyncProvider.
}
