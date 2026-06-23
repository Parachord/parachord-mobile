package com.parachord.shared.sync

import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.api.AmCreatePlaylistAttributes
import com.parachord.shared.api.AmCreatePlaylistRequest
import com.parachord.shared.api.AmLibraryAlbum
import com.parachord.shared.api.AmLibraryArtist
import com.parachord.shared.api.AmLibrarySong
import com.parachord.shared.api.AmPlaylist
import com.parachord.shared.api.AmTrack
import com.parachord.shared.api.AmTrackReference
import com.parachord.shared.api.AmTracksRequest
import com.parachord.shared.api.AmUpdatePlaylistAttributes
import com.parachord.shared.api.AmUpdatePlaylistRequest
import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.AppleMusicLibraryClient
import com.parachord.shared.api.AppleMusicLibraryClient.AmHttpException
import com.parachord.shared.api.ItunesRateLimitedException
import io.ktor.http.isSuccess
import com.parachord.shared.model.Album
import com.parachord.shared.model.Artist
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.Track
import com.parachord.shared.sync.DeleteResult
import kotlin.concurrent.Volatile
import com.parachord.shared.sync.ProviderFeatures
import com.parachord.shared.sync.RemoteCreated
import com.parachord.shared.sync.SnapshotKind
import com.parachord.shared.sync.SyncProvider
import com.parachord.shared.sync.SyncedAlbum
import com.parachord.shared.sync.SyncedArtist
import com.parachord.shared.sync.SyncedPlaylist
import com.parachord.shared.sync.SyncedTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant

/**
 * Apple Music sync provider.
 *
 * Phase 4 shipped the playlist surface (fetchPlaylists, push, dedup,
 * the kill-switch degradation pattern). The collection-sync surface
 * (fetchTracks / fetchAlbums / fetchArtists + removeTracks /
 * removeAlbums) landed in the Phase 6.5+ collection-sync extension.
 *
 * Limitations:
 * - Apple has no follow/unfollow API for artists. `fetchArtists` is
 *   pull-only; `followArtists` / `unfollowArtists` inherit the no-op
 *   defaults from [SyncProvider].
 * - `saveTracks` / `saveAlbums` use Apple's documented
 *   `POST /me/library?ids[songs]=...` query-string API (NOT a JSON
 *   body). `removeTracks` / `removeAlbums` use per-item
 *   `DELETE /me/library/songs/{id}` etc. — no bulk delete.
 *
 * Endpoint degradation per desktop CLAUDE.md:
 * - PUT /library/playlists/{id}/tracks → 401 → [amPutUnsupportedForSession]
 *   flips, fall back to POST-append. Removals stay on the remote.
 * - PATCH /library/playlists/{id} → 401 → [amPatchUnsupportedForSession]
 *   flips, returns silently. Load-bearing: this runs before the track
 *   push and a throw would abort the track push too.
 * - DELETE /library/playlists/{id} → 401 → returns
 *   [DeleteResult.Unsupported]. Caller surfaces "remove manually in
 *   the Music app" UX.
 *
 * Do NOT retry-on-401 for any documented-unsupported endpoint — the
 * 401 is structural (Apple won't unblock the endpoint by handing you a
 * fresh token) and a defensive retry escalates a benign rejection into
 * a phantom auth crisis (the MusicKit bridge would walk the user
 * through a System Settings revoke for an authorization that was
 * never broken).
 *
 * Track-ID resolution via catalog search is deferred — Phase 4 push
 * paths only push tracks that already have an `appleMusicId` set;
 * tracks without one are silently skipped.
 */
class AppleMusicSyncProvider(
    private val api: AppleMusicLibraryClient,
    /**
     * Catalog-search client (iTunes Search API). Used by [searchForTrackId]
     * to hydrate `appleMusicId` for tracks lacking one before push. Distinct
     * from [api] (the Library API client) because catalog search lives at
     * `itunes.apple.com` and needs no auth, whereas the library API at
     * `api.music.apple.com` needs the dev-token + MUT.
     */
    private val catalogClient: AppleMusicClient,
) : SyncProvider {

    companion object {
        const val PROVIDER_ID = "applemusic"
        private const val TAG = "AppleMusicSyncProvider"
        private const val PAGE_SIZE = 100
        /** Polite pacing — Apple Music rate-limits aggressive callers. */
        private const val INTER_REQUEST_DELAY_MS = 150L
    }

    override val id: String = PROVIDER_ID
    override val displayName: String = "Apple Music"
    override val features = ProviderFeatures(
        snapshots = SnapshotKind.DateString,
        // Apple has no follow/unfollow API for artists.
        supportsFollow = false,
        // PATCH/PUT/DELETE on library playlists all return 401 in
        // practice. Flags advertise the limitation to SyncEngine and
        // the UI layer; the provider degrades gracefully via session
        // kill-switches at runtime.
        supportsPlaylistDelete = false,
        supportsPlaylistRename = false,
        supportsTrackReplace = false,
        // No playlist-track remove endpoint — library playlists are add-only.
        trackRemoveMode = TrackRemoveMode.Unsupported,
    )

    /**
     * Once flipped on a 401/403/405 from PUT, subsequent calls skip
     * straight to POST-append without re-probing PUT. Reset on app
     * restart so we re-probe if Apple's behavior changes.
     */
    @Volatile
    internal var amPutUnsupportedForSession: Boolean = false

    /**
     * Independent kill-switch for PATCH (rename/description). Flipped
     * separately from PUT — they're distinct endpoints with their own
     * rejection behavior. Subsequent calls short-circuit and silently
     * return.
     */
    @Volatile
    internal var amPatchUnsupportedForSession: Boolean = false

    /**
     * iTunes Search rate-limit kill-switch. Flipped on the first HTTP 429
     * response from `itunes.apple.com/search` and never reset within the
     * process — Apple's throttle backoff is not documented, and continued
     * hammering after a 429 just earns a longer cooldown. Subsequent
     * [searchForTrackId] calls short-circuit to `null` so push paths
     * silently skip un-hydrated tracks rather than turning every track
     * into a wasted 429-throwing request.
     *
     * Reset on app restart so a long-lived session can re-probe iTunes
     * Search after the user backgrounds the app.
     */
    @Volatile
    internal var iTunesSearchRateLimited: Boolean = false

    // Cached storefront for catalog ISRC lookups (the catalog API is per-
    // storefront). Fetched once per session under a mutex so concurrent
    // hydration calls don't each hit /me/storefront. null = not-yet-fetched OR
    // fetch failed (re-probed next time).
    private var cachedStorefront: String? = null
    private val storefrontMutex = Mutex()

    private suspend fun ensureStorefront(): String? {
        cachedStorefront?.let { return it }
        return storefrontMutex.withLock {
            cachedStorefront ?: try {
                api.getStorefront().data.firstOrNull()?.id?.also { cachedStorefront = it }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "ensureStorefront: getStorefront failed", e); null
            }
        }
    }

    // ── Read methods ─────────────────────────────────────────────────

    override suspend fun fetchPlaylists(
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedPlaylist> {
        val all = mutableListOf<SyncedPlaylist>()
        var offset = 0
        while (true) {
            delay(INTER_REQUEST_DELAY_MS)
            val resp = api.listPlaylists(limit = PAGE_SIZE, offset = offset)
            for (am in resp.data) {
                all.add(am.toSyncedPlaylist())
            }
            // Total count isn't in the response; report as we go so the
            // UI sees progress.
            onProgress?.invoke(all.size, all.size)
            // `next` is the URL of the next page; `null` (or a short
            // page) means we're done.
            if (resp.next == null || resp.data.size < PAGE_SIZE) break
            offset += resp.data.size
        }
        return all
    }

    override suspend fun fetchPlaylistTracks(
        externalPlaylistId: String,
    ): List<com.parachord.shared.model.PlaylistTrack> {
        val localPlaylistId = "applemusic-$externalPlaylistId"

        // PRIMARY path — matches desktop's sync-providers/applemusic.js
        // (`/me/library/playlists/{id}/tracks?limit=100` with `data.next`
        // pagination). This is the canonical Apple-documented endpoint
        // for library-playlist tracks.
        val all = mutableListOf<com.parachord.shared.model.PlaylistTrack>()
        var offset = 0
        while (true) {
            delay(INTER_REQUEST_DELAY_MS)
            val resp = try {
                api.listPlaylistTracks(
                    playlistId = externalPlaylistId,
                    limit = PAGE_SIZE,
                    offset = offset,
                )
            } catch (e: AmHttpException) {
                if (e.status == 404) {
                    Log.w(TAG, "AM playlist $externalPlaylistId /tracks 404 at offset=$offset; " +
                        "returning ${all.size} tracks collected so far")
                    break
                }
                Log.w(TAG, "AM playlist $externalPlaylistId /tracks failed at offset=$offset", e)
                throw e
            }
            for ((index, am) in resp.data.withIndex()) {
                all.add(am.toPlaylistTrack(playlistId = localPlaylistId, position = offset + index))
            }
            if (resp.next == null || resp.data.size < PAGE_SIZE) break
            offset += resp.data.size
        }
        if (all.isNotEmpty()) return all

        // FALLBACK — primary returned empty. Desktop doesn't have this
        // fallback; we add it because the user reported AM playlists
        // showing 0 tracks in Parachord while the same playlists have
        // tracks visible in the Music app. The `?include=tracks` form
        // on the playlist GET endpoint returns tracks via
        // `relationships.tracks.data` and sometimes succeeds when the
        // dedicated `/tracks` endpoint silently returns empty (Apple
        // Music's library mirror has known inconsistencies for
        // shared / curated / smart playlists).
        delay(INTER_REQUEST_DELAY_MS)
        val viaInclude = try {
            val resp = api.getLibraryPlaylistWithTracks(externalPlaylistId)
            val data = resp.data.firstOrNull()?.relationships?.tracks?.data ?: emptyList()
            data.mapIndexed { i, am ->
                am.toPlaylistTrack(playlistId = localPlaylistId, position = i)
            }
        } catch (e: AppleMusicReauthRequiredException) {
            throw e
        } catch (e: AmHttpException) {
            Log.w(TAG, "AM include=tracks fallback for $externalPlaylistId failed (HTTP ${e.status})")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "AM include=tracks fallback for $externalPlaylistId threw", e)
            emptyList()
        }

        if (viaInclude.isNotEmpty()) {
            Log.d(TAG, "AM playlist $externalPlaylistId: /tracks returned 0 but " +
                "?include=tracks recovered ${viaInclude.size} tracks (fallback worked)")
            return viaInclude
        }

        Log.w(TAG, "AM playlist $externalPlaylistId returned 0 tracks via both " +
            "/tracks AND ?include=tracks — playlist may genuinely be empty or be a " +
            "Music-app-only smart-playlist source not exposed via API")
        return emptyList()
    }

    /**
     * Apple's library API doesn't expose a single-playlist endpoint
     * for change-token reads, so this implementation refetches the
     * full owned-playlist list and locates the row by ID. Fine for
     * users with O(100) playlists; if perf becomes an issue we can
     * cache the list for the duration of a sync cycle or use the
     * catalog endpoint for shared playlists.
     */
    override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? {
        delay(INTER_REQUEST_DELAY_MS)
        return try {
            // Walk pages until we find the target. Most users have <100,
            // so the first page hits.
            var offset = 0
            while (true) {
                val resp = api.listPlaylists(limit = PAGE_SIZE, offset = offset)
                val match = resp.data.firstOrNull { it.id == externalPlaylistId }
                if (match != null) return match.attributes.lastModifiedDate
                if (resp.next == null || resp.data.size < PAGE_SIZE) return null
                offset += resp.data.size
            }
            @Suppress("UNREACHABLE_CODE") null
        } catch (e: AppleMusicReauthRequiredException) {
            throw e
        } catch (e: AmHttpException) {
            Log.w(TAG, "getPlaylistSnapshotId failed for $externalPlaylistId (HTTP ${e.status})")
            null
        }
    }

    // ── Write methods (TODO: subsequent tasks) ───────────────────────

    override suspend fun createPlaylist(name: String, description: String?): RemoteCreated {
        delay(INTER_REQUEST_DELAY_MS)
        val resp = api.createPlaylist(
            AmCreatePlaylistRequest(
                attributes = AmCreatePlaylistAttributes(name = name, description = description),
            )
        )
        val created = resp.data.firstOrNull()
            ?: throw IllegalStateException("Apple Music createPlaylist returned empty data")
        return RemoteCreated(
            externalId = created.id,
            snapshotId = created.attributes.lastModifiedDate,
        )
    }

    override suspend fun replacePlaylistTracks(
        externalPlaylistId: String,
        externalTrackIds: List<String>,
    ): String? {
        if (externalTrackIds.isEmpty()) {
            // PUT with empty list would clear; if PUT is unsupported we
            // can't clear via append either. Log + return without action.
            Log.w(TAG, "Empty track list for $externalPlaylistId; not pushing (PUT may be unsupported)")
            return getPlaylistSnapshotId(externalPlaylistId)
        }
        val body = AmTracksRequest(externalTrackIds.map { AmTrackReference(it, "songs") })

        if (!amPutUnsupportedForSession) {
            delay(INTER_REQUEST_DELAY_MS)
            val resp = api.replacePlaylistTracks(externalPlaylistId, body)
            if (resp.status.isSuccess()) {
                return getPlaylistSnapshotId(externalPlaylistId)
            }
            if (resp.status.value in setOf(401, 403, 405)) {
                // Documented-unsupported. Flip kill-switch; do NOT retry.
                // Per desktop CLAUDE.md: refresh-and-retry on these
                // endpoints would escalate a benign endpoint rejection
                // into a phantom auth crisis (the 401 is structural,
                // not token-related).
                Log.w(TAG, "PUT replace returned ${resp.status.value} for $externalPlaylistId; flipping session kill-switch, falling back to POST-append")
                amPutUnsupportedForSession = true
            } else {
                throw AmHttpException(resp.status.value)
            }
        }

        // POST-append fallback. Removals stay on the remote — accept this.
        delay(INTER_REQUEST_DELAY_MS)
        val resp = api.appendPlaylistTracks(externalPlaylistId, body)
        if (!resp.status.isSuccess()) {
            if (resp.status.value == 401) throw AppleMusicReauthRequiredException()
            throw AmHttpException(resp.status.value)
        }
        return getPlaylistSnapshotId(externalPlaylistId)
    }

    /**
     * Append [externalTrackIds] (catalog song IDs) non-destructively via the
     * library POST-append endpoint — AM library playlists are add-only, so this
     * IS the native primitive (no PUT-degradation dance needed). Maps 401 →
     * [AppleMusicReauthRequiredException], honors [INTER_REQUEST_DELAY_MS].
     * Returns the new last-modified snapshot.
     *
     * Both remove primitives stay the throwing interface default: AM's
     * [TrackRemoveMode.Unsupported] means the executor never dispatches a remove
     * here.
     */
    override suspend fun addPlaylistTracks(
        externalPlaylistId: String,
        externalTrackIds: List<String>,
    ): String? {
        if (externalTrackIds.isEmpty()) return getPlaylistSnapshotId(externalPlaylistId)
        val body = AmTracksRequest(externalTrackIds.map { AmTrackReference(it, "songs") })
        delay(INTER_REQUEST_DELAY_MS)
        val resp = api.appendPlaylistTracks(externalPlaylistId, body)
        if (!resp.status.isSuccess()) {
            if (resp.status.value == 401) throw AppleMusicReauthRequiredException()
            throw AmHttpException(resp.status.value)
        }
        return getPlaylistSnapshotId(externalPlaylistId)
    }

    override suspend fun updatePlaylistDetails(
        externalPlaylistId: String,
        name: String?,
        description: String?,
    ) {
        if (amPatchUnsupportedForSession) return
        if (name == null && description == null) return

        // **Load-bearing** try/catch: this method runs before the track
        // push in the create-or-link path. A throw here would abort the
        // track push too. Even if the function below never throws under
        // normal rejection, a network error or unexpected 5xx must
        // also not kill the track push. Defense-in-depth.
        try {
            delay(INTER_REQUEST_DELAY_MS)
            val resp = api.updatePlaylistDetails(
                externalPlaylistId,
                AmUpdatePlaylistRequest(AmUpdatePlaylistAttributes(name = name, description = description)),
            )
            if (resp.status.isSuccess()) return
            if (resp.status.value in setOf(401, 403, 405)) {
                Log.w(TAG, "PATCH details returned ${resp.status.value} for $externalPlaylistId; flipping session kill-switch, future calls skip silently")
                amPatchUnsupportedForSession = true
                return
            }
            Log.w(TAG, "PATCH details returned ${resp.status.value} for $externalPlaylistId; not retrying")
        } catch (e: Exception) {
            Log.w(TAG, "PATCH details network error for $externalPlaylistId — silently skipping", e)
        }
    }

    override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult {
        delay(INTER_REQUEST_DELAY_MS)
        return try {
            val resp = api.deletePlaylist(externalPlaylistId)
            when {
                resp.status.isSuccess() -> DeleteResult.Success
                resp.status.value in setOf(401, 403, 405) -> DeleteResult.Unsupported(resp.status.value)
                else -> DeleteResult.Failed(AmHttpException(resp.status.value))
            }
        } catch (e: Exception) {
            DeleteResult.Failed(e)
        }
    }

    /**
     * Catalog-search-based ID hydration (un-defers Decision D1).
     *
     * PRIMARY: the Apple Music CATALOG API by ISRC
     * (`/v1/catalog/{storefront}/songs?filter[isrc]=…`) — EXACT (no fuzzy
     * wrong-variant risk), authed with the dev token we already hold, and the
     * catalog id it returns is what the library add-to-playlist endpoint accepts.
     * Mirrors the ISRC-keyed LB hydration. FALLBACK: iTunes Search (text, no
     * auth) for tracks with no ISRC or a catalog miss.
     *
     * The iTunes fallback is confidence-gated against
     * [com.parachord.shared.resolver.ResolverScoring.MIN_CONFIDENCE_THRESHOLD]
     * (0.60) using [com.parachord.shared.resolver.scoreConfidence].
     */
    override suspend fun searchForTrackId(title: String, artist: String, album: String?, isrc: String?): String? {
        // PRIMARY — exact catalog lookup by ISRC.
        if (!isrc.isNullOrBlank()) {
            val storefront = ensureStorefront()
            if (storefront != null) {
                val catalogId = try {
                    api.getCatalogSongIdByIsrc(storefront, isrc)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "catalog ISRC lookup failed for isrc=$isrc", e); null
                }
                if (catalogId != null) return catalogId
            }
        }

        // FALLBACK — iTunes Search (no ISRC, or catalog miss).
        // Session kill-switch: if we've already hit a 429 from iTunes Search,
        // don't bother asking again until the user restarts the app. Apple
        // doesn't publish the throttle window and stacked 429s just extend it.
        if (iTunesSearchRateLimited) return null
        delay(INTER_REQUEST_DELAY_MS)
        // iTunes Search has no field qualifiers; just concat the metadata
        // and let its ranking find the best song match.
        val term = listOfNotNull(title, artist, album?.takeIf { it.isNotBlank() })
            .joinToString(" ")
        return try {
            val response = catalogClient.search(term = term, media = "music", entity = "song", limit = 5)
            val songs = response.results.filter { it.kind == "song" || it.wrapperType == "track" }
            val candidate = songs.firstOrNull { it.trackId != null } ?: return null
            val confidence = com.parachord.shared.resolver.scoreConfidence(
                targetTitle = title,
                targetArtist = artist,
                matchedTitle = candidate.trackName,
                matchedArtist = candidate.artistName,
            )
            if (confidence < com.parachord.shared.resolver.ResolverScoring.MIN_CONFIDENCE_THRESHOLD) {
                Log.d(TAG, "searchForTrackId: low-confidence match for '$title' by " +
                    "'$artist' (got '${candidate.trackName}' by '${candidate.artistName}', " +
                    "conf=$confidence) — skipping")
                return null
            }
            candidate.trackId.toString()
        } catch (e: ItunesRateLimitedException) {
            // First 429: flip the kill-switch and silently skip. Subsequent
            // tracks in this sync session will short-circuit at the top of
            // this method.
            if (!iTunesSearchRateLimited) {
                Log.w(TAG, "iTunes Search rate-limited (HTTP 429) — disabling " +
                    "track-id hydration for the rest of this session")
                iTunesSearchRateLimited = true
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "searchForTrackId failed for '$title' by '$artist'", e)
            null
        }
    }

    // ── Collection sync: library tracks ──────────────────────────────

    override suspend fun fetchTracks(
        localCount: Int,
        latestExternalId: String?,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedTrack>? {
        val all = mutableListOf<SyncedTrack>()
        // Quick-check shortcut: probe one item to compare against the
        // local state. The probe also returns meta.total which we use
        // for accurate progress reporting through pagination.
        delay(INTER_REQUEST_DELAY_MS)
        // Probe must compute the SAME external id storage uses
        // (`playParams.id ?: id` = the catalog id), not the library top-level
        // `id`. Comparing library-id to the stored catalog-id never matched, so
        // the unchanged-shortcut never fired and the WHOLE song library
        // re-fetched on every sync (#261, same bug as albums).
        val probe = api.listLibrarySongs(limit = 1, offset = 0)
        val probeId = probe.data.firstOrNull()?.let { it.attributes?.playParams?.id ?: it.id }
        if (probeId == latestExternalId && localCount > 0) {
            // Nothing's changed at the head; assume rest is unchanged.
            return null
        }
        // Full pagination. Apple returns total in `meta.total` on every
        // page; first page from the probe gives us a good initial guess.
        var total = probe.meta?.total ?: 0
        var offset = 0
        while (true) {
            delay(INTER_REQUEST_DELAY_MS)
            val resp = api.listLibrarySongs(limit = PAGE_SIZE, offset = offset)
            // Refresh total in case the library shifted mid-paginate.
            resp.meta?.total?.let { total = it }
            for (am in resp.data) {
                am.toSyncedTrack()?.let { all.add(it) }   // skip songs with no usable title
            }
            // Floor total to the running count so progress never reports
            // more than 100% if the remote shrunk during pagination.
            onProgress?.invoke(all.size, maxOf(total, all.size))
            if (resp.next == null || resp.data.size < PAGE_SIZE) break
            offset += resp.data.size
        }
        // Cross-provider dedup: the library API has no ISRC, so fetch it from the
        // catalog and re-key each row by ISRC — the same recording in Spotify
        // Liked Songs then collapses onto one collection row instead of two.
        return attachIsrcs(all)
    }

    /** Re-key AM library tracks by ISRC (fetched from the catalog by catalog id).
     *  Tracks with no catalog ISRC keep their `applemusic-…` id (no merge). */
    private suspend fun attachIsrcs(tracks: List<SyncedTrack>): List<SyncedTrack> {
        if (tracks.isEmpty()) return tracks
        Log.i(TAG, "attachIsrcs: ${tracks.size} AM library songs to re-key by ISRC")
        val storefront = try { api.getStorefront().data.firstOrNull()?.id } catch (e: Exception) {
            Log.w(TAG, "attachIsrcs: getStorefront failed; keeping applemusic- ids", e); null
        } ?: return tracks
        // `spotifyId` is the reused external-id field — for AM it holds the catalog id.
        val isrcs = api.getCatalogSongIsrcs(storefront, tracks.map { it.spotifyId })
        Log.i(TAG, "attachIsrcs: storefront=$storefront, resolved ${isrcs.size} ISRCs for ${tracks.size} songs")
        if (isrcs.isEmpty()) return tracks
        return tracks.map { synced ->
            val isrc = com.parachord.shared.resolver.validateIsrc(isrcs[synced.spotifyId]) ?: return@map synced
            synced.copy(
                entity = synced.entity.copy(
                    id = TrackIdentity.canonicalTrackId(isrc, synced.entity.id),
                    isrc = isrc,
                ),
            )
        }
    }

    /**
     * Apple's documented add-to-library endpoint takes catalog IDs
     * via query string: `POST /me/library?ids[songs]=id1,id2,...`.
     */
    override suspend fun saveTracks(externalIds: List<String>) {
        if (externalIds.isEmpty()) return
        externalIds.chunked(50).forEach { batch ->
            delay(INTER_REQUEST_DELAY_MS)
            val resp = api.addToLibrary(songIds = batch.joinToString(","))
            if (!resp.status.isSuccess()) {
                if (resp.status.value == 401) throw AppleMusicReauthRequiredException()
                Log.w(TAG, "saveTracks returned ${resp.status.value} for ${batch.size} ids")
            }
        }
    }

    /** Per-track DELETE — Apple has no bulk-delete endpoint for library songs. */
    override suspend fun removeTracks(externalIds: List<String>) {
        for (id in externalIds) {
            delay(INTER_REQUEST_DELAY_MS)
            try {
                val resp = api.deleteLibrarySong(id)
                if (!resp.status.isSuccess() && resp.status.value != 404) {
                    Log.w(TAG, "deleteLibrarySong $id returned ${resp.status.value}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteLibrarySong $id threw — continuing", e)
            }
        }
    }

    // ── Collection sync: library albums ──────────────────────────────

    override suspend fun fetchAlbums(
        localCount: Int,
        latestExternalId: String?,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedAlbum>? {
        val all = mutableListOf<SyncedAlbum>()
        delay(INTER_REQUEST_DELAY_MS)
        // Probe must compute the SAME external id storage uses
        // (`playParams.id ?: id` = the catalog id), not the library top-level
        // `id`. The mismatch made the unchanged-shortcut never fire, re-fetching
        // the WHOLE album library every sync — the multi-minute (albums) grind (#261).
        val probe = api.listLibraryAlbums(limit = 1, offset = 0)
        val probeExternalId = probe.data.firstOrNull()?.let { it.attributes.playParams?.id ?: it.id }
        if (probeExternalId == latestExternalId && localCount > 0) return null
        var total = probe.meta?.total ?: 0
        var offset = 0
        while (true) {
            delay(INTER_REQUEST_DELAY_MS)
            val resp = api.listLibraryAlbums(limit = PAGE_SIZE, offset = offset)
            resp.meta?.total?.let { total = it }
            for (am in resp.data) {
                all.add(am.toSyncedAlbum())
            }
            onProgress?.invoke(all.size, maxOf(total, all.size))
            if (resp.next == null || resp.data.size < PAGE_SIZE) break
            offset += resp.data.size
        }
        return all
    }

    override suspend fun saveAlbums(externalIds: List<String>) {
        if (externalIds.isEmpty()) return
        externalIds.chunked(50).forEach { batch ->
            delay(INTER_REQUEST_DELAY_MS)
            val resp = api.addToLibrary(albumIds = batch.joinToString(","))
            if (!resp.status.isSuccess()) {
                if (resp.status.value == 401) throw AppleMusicReauthRequiredException()
                Log.w(TAG, "saveAlbums returned ${resp.status.value} for ${batch.size} ids")
            }
        }
    }

    override suspend fun removeAlbums(externalIds: List<String>) {
        for (id in externalIds) {
            delay(INTER_REQUEST_DELAY_MS)
            try {
                val resp = api.deleteLibraryAlbum(id)
                if (!resp.status.isSuccess() && resp.status.value != 404) {
                    Log.w(TAG, "deleteLibraryAlbum $id returned ${resp.status.value}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteLibraryAlbum $id threw — continuing", e)
            }
        }
    }

    // ── Collection sync: library artists (pull-only) ─────────────────

    override suspend fun fetchArtists(
        localCount: Int,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): List<SyncedArtist>? {
        val all = mutableListOf<SyncedArtist>()
        var total = 0
        var offset = 0
        while (true) {
            delay(INTER_REQUEST_DELAY_MS)
            val resp = api.listLibraryArtists(limit = PAGE_SIZE, offset = offset)
            resp.meta?.total?.let { total = it }
            for (am in resp.data) {
                all.add(am.toSyncedArtist())
            }
            onProgress?.invoke(all.size, maxOf(total, all.size))
            if (resp.next == null || resp.data.size < PAGE_SIZE) break
            offset += resp.data.size
        }
        if (all.size == localCount) return null
        return all
    }

    // followArtists / unfollowArtists inherit the SyncProvider no-op
    // defaults — Apple has no follow API.

    // ── Mappers ──────────────────────────────────────────────────────

    private fun AmPlaylist.toSyncedPlaylist(): SyncedPlaylist {
        val name = attributes.name
        val desc = attributes.description?.standard ?: attributes.description?.short
        // The PlaylistEntity carries Spotify-specific fields by historical
        // accident. Apple Music's identifier goes in the SyncedPlaylist's
        // generic `spotifyId` slot (the field name was Spotify-shaped at
        // the model's birth; renaming is a separate cleanup task).
        val playlistEntity = Playlist(
            id = "applemusic-$id",
            name = name,
            description = desc,
            artworkUrl = resolveArtworkUrl(attributes.artwork?.url),
            trackCount = 0,
            createdAt = 0L,
            updatedAt = currentTimeMillis(),
            spotifyId = null,
            snapshotId = attributes.lastModifiedDate,
            lastModified = 0L,
            locallyModified = false,
            ownerName = null,
            sourceUrl = null,
            sourceContentHash = null,
            localOnly = false,
            // AM has no collaborative concept; canEdit IS the writability signal.
            writable = attributes.canEdit,
        )
        return SyncedPlaylist(
            entity = playlistEntity,
            spotifyId = id,
            snapshotId = attributes.lastModifiedDate,
            trackCount = 0,
            // canEdit defaults to false on the response when the field
            // is omitted; treat that as not-owned (we only push to
            // owned playlists anyway).
            isOwned = attributes.canEdit,
            writable = attributes.canEdit,
        )
    }

    private fun AmTrack.toPlaylistTrack(
        playlistId: String,
        position: Int,
    ): PlaylistTrack = PlaylistTrack(
        playlistId = playlistId,
        position = position,
        trackTitle = attributes.name,
        trackArtist = attributes.artistName,
        trackAlbum = attributes.albumName,
        trackDuration = attributes.durationInMillis?.let { it / 1000 },
        trackArtworkUrl = resolveArtworkUrl(attributes.artwork?.url),
        trackSourceUrl = null,
        trackResolver = "applemusic",
        trackSpotifyUri = null,
        trackSoundcloudId = null,
        trackSpotifyId = null,
        // playParams.id is the catalog ID (preferred for cross-device
        // playback); falls back to the library ID. Either is usable.
        trackAppleMusicId = attributes.playParams?.id ?: id,
    )

    /** Library track → cross-provider [SyncedTrack]. The library row's
     * `id` is the library ID; `playParams.id` is the catalog ID. We
     * store the catalog ID in [TrackEntity.appleMusicId] so playback
     * can resolve via MusicKit. */
    /** Library song → cross-provider [SyncedTrack], or null for a song with no
     *  usable title (skip it rather than fail the whole page). */
    private fun AmLibrarySong.toSyncedTrack(): SyncedTrack? {
        val attrs = attributes ?: return null
        val title = attrs.name?.takeIf { it.isNotBlank() } ?: return null
        val catalogId = attrs.playParams?.id ?: id
        val addedAt = attrs.dateAdded?.let { parseIso(it) } ?: 0L
        return SyncedTrack(
            entity = Track(
                id = "applemusic-$catalogId",
                title = title,
                artist = attrs.artistName?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
                album = attrs.albumName,
                albumId = null,
                duration = attrs.durationInMillis,
                artworkUrl = resolveArtworkUrl(attrs.artwork?.url),
                spotifyUri = null,
                spotifyId = null,
                appleMusicId = catalogId,
                resolver = "applemusic",
                sourceType = "synced",
                addedAt = addedAt,
            ),
            spotifyId = catalogId,
            addedAt = addedAt,
        )
    }

    /** Library album → cross-provider [SyncedAlbum]. */
    private fun AmLibraryAlbum.toSyncedAlbum(): SyncedAlbum {
        val catalogId = attributes.playParams?.id ?: id
        val addedAt = attributes.dateAdded?.let { parseIso(it) } ?: 0L
        return SyncedAlbum(
            entity = Album(
                id = "applemusic-$catalogId",
                title = attributes.name,
                artist = attributes.artistName,
                artworkUrl = resolveArtworkUrl(attributes.artwork?.url),
                trackCount = attributes.trackCount,
                addedAt = addedAt,
                spotifyId = null,
            ),
            spotifyId = catalogId,
            addedAt = addedAt,
        )
    }

    /** Library artist → cross-provider [SyncedArtist]. AM library
     * artist objects don't include images on the library endpoint —
     * the artwork comes from the catalog endpoint, which we'd need
     * one extra request per artist for. Skip for now; artist art on
     * AM-imported rows comes from the metadata enrichment cascade. */
    private fun AmLibraryArtist.toSyncedArtist(): SyncedArtist =
        SyncedArtist(
            entity = Artist(
                id = "applemusic-$id",
                name = attributes.name,
                imageUrl = null,
                spotifyId = null,
                genres = "",
            ),
            spotifyId = id,
        )

    /** Lenient ISO-8601 parse; library timestamps come in `Z` form. */
    private fun parseIso(s: String): Long =
        try { Instant.parse(s).toEpochMilliseconds() } catch (_: Exception) { 0L }

    /** Apple Music artwork URLs come back with literal `{w}` and `{h}`
     *  placeholders (e.g. `.../{w}x{h}bb.jpg`) that the client is
     *  expected to substitute with the desired dimensions. Coil and
     *  the mosaic generator can't fetch URLs with the placeholders
     *  intact, so swap in 600x600 (matches desktop's
     *  `sync-providers/applemusic.js` which uses 500x500 — slight
     *  bump for higher-DPI Android screens). */
    private fun resolveArtworkUrl(url: String?): String? =
        url?.replace("{w}", "600")?.replace("{h}", "600")
}
