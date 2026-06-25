package com.parachord.shared.sync

import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.AlbumDao
import com.parachord.shared.db.dao.ArtistDao
import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.db.dao.PlaylistTrackDao
import com.parachord.shared.db.dao.SyncPlaylistLinkDao
import com.parachord.shared.db.dao.SyncPlaylistBaselineDao
import com.parachord.shared.db.dao.SyncPlaylistNwayDao
import com.parachord.shared.db.dao.SyncPlaylistSourceDao
import com.parachord.shared.db.dao.SyncSourceDao
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.db.dao.TrackProviderIdCacheDao
import com.parachord.shared.model.Album
import com.parachord.shared.model.Artist
import com.parachord.shared.model.Playlist
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.SyncSource
import com.parachord.shared.model.Track
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

class SyncEngine constructor(
    private val db: ParachordDb,
    private val driver: app.cash.sqldelight.db.SqlDriver,
    private val trackDao: TrackDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val syncSourceDao: SyncSourceDao,
    private val syncPlaylistLinkDao: SyncPlaylistLinkDao,
    private val syncPlaylistSourceDao: SyncPlaylistSourceDao,
    // N-way multimaster state (Phase 1 tables). Written by the Phase 2 migration
    // bootstrap; not yet read by live reconciliation. Isolated from the
    // canonical-source link/source DAOs above.
    private val syncPlaylistBaselineDao: SyncPlaylistBaselineDao,
    private val syncPlaylistNwayDao: SyncPlaylistNwayDao,
    // Negative cache for incremental provider-id hydration (incremental-
    // materialization). Threaded into a per-CYCLE [HydrationCoordinator] inside
    // [runNwayPropagation] so an un-findable ADD isn't re-searched every cycle.
    private val trackProviderIdCacheDao: TrackProviderIdCacheDao,
    private val settingsStore: SyncSettingsProvider,
    /**
     * Multi-provider sync surface (Phase 2). Today only Spotify is
     * registered; Phase 4 will add Apple Music. Method bodies still
     * pull `spotifyProvider` out of the list — the iteration over
     * every enabled provider is Phase 3 work. The `first { it.id == "spotify" }
     * + cast` indirection goes away when those bodies are generalized.
     */
    private val providers: List<com.parachord.shared.sync.SyncProvider>,
    private val tombstones: TrackTombstoneService,
) {

    private val spotifyProvider: SpotifySyncProvider
        get() = providers.first { it.id == SpotifySyncProvider.PROVIDER_ID } as SpotifySyncProvider
    companion object {
        private const val TAG = "SyncEngine"
        /** Benign skip result when a sync is already running (tryLock held). Not a failure. */
        const val SYNC_IN_PROGRESS = "Sync already in progress"
        private const val MASS_REMOVAL_THRESHOLD_PERCENT = 0.25
        private const val MASS_REMOVAL_THRESHOLD_COUNT = 50

        /**
         * Watchdog ceiling for a single [syncAll] body. If the sync coroutine
         * ever hangs (observed on-device: a MusicBrainz 503 tight-retry loop),
         * the `finally` that releases [syncMutex] + clears [_syncing] never
         * runs and the app reports "already running" forever — no manual sync
         * can ever start again. 10 min is a safety ceiling: a healthy sync
         * finishes well under it, and on timeout the sync RESUMES idempotently
         * next cycle, so a too-aggressive cancel doesn't lose progress.
         *
         * Plain `var` (not `const`) so tests in the `:app` module can shrink it
         * to drive the timeout path deterministically (an `internal` var would
         * be invisible across the module boundary).
         */
        var SYNC_WATCHDOG_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * Bump this to force a full re-fetch on next sync (bypasses quick-check).
         * The current version is stored in DataStore; when it's less than this,
         * localCount is passed as 0 to force a full diff.
         */
        private const val SYNC_DATA_VERSION = 4

        /** Dead-mirror reconcile (workstream B): if more than this many mirrors
         *  look absent from one provider fetch, treat it as a partial/failed
         *  fetch and skip the reconcile rather than probe-storm / mass-act. */
        private const val DEAD_MIRROR_MASS_FLOOR = 30

        /** Sentinel for the cross-provider local-row dedup migration
         *  ([migrateMergeCrossProviderDuplicates]). Lives above
         *  [SYNC_DATA_VERSION] so it doesn't piggyback on the v3→v4
         *  Spotify dedup migration's gating. Idempotent: runs once
         *  per install. */
        private const val MIGRATION_DEDUP_LOCAL_V1 = 5

        /**
         * Maximum concurrent catalog-search requests during track-id
         * hydration ([hydrateMissingTrackIds]). Apple's iTunes Search
         * starts returning HTTP 429 around 10+ parallel requests; Spotify's
         * catalog search has per-token quotas. 4 keeps us well below either
         * threshold while still hydrating a 100-track playlist in ~30s.
         */
        private const val HYDRATE_MAX_CONCURRENCY = 4

        /**
         * Backfill `sync_playlist_source` from playlist rows whose id prefix
         * identifies a provider pull source. Currently handles the `spotify-`
         * prefix — the convention used by [SpotifySyncProvider.fetchPlaylists]
         * when importing remote playlists into local rows. Idempotent; safe to
         * call on every sync.
         *
         * Mirrors [migrateLinksFromPlaylists] but writes the `syncedFrom`
         * single-source table instead of the per-provider `syncedTo` link map.
         * Where `sync_playlist_link` answers "which remote(s) do we push this
         * local playlist to?", `sync_playlist_source` answers "which remote did
         * this local playlist originally come from?".
         *
         * Defensive: a `spotify-` playlist with a null `spotifyId` column (mid-
         * migration legacy data) is skipped rather than writing a source row
         * with an empty externalId. `local-*` and `hosted-xspf-*` rows are
         * ignored entirely — they may have a `spotifyId` for push, but that's
         * a push target, not a pull source.
         */
        // static for test isolation — avoids constructing SyncEngine with unrelated deps
        suspend fun migrateSourceFromPlaylists(
            db: ParachordDb,
            sourceDao: SyncPlaylistSourceDao,
        ) {
            val providerId = SpotifySyncProvider.PROVIDER_ID
            val now = currentTimeMillis()
            var added = 0
            for (playlist in db.playlistQueries.getAll().executeAsList()) {
                if (!playlist.id.startsWith("$providerId-")) continue
                val spotifyId = playlist.spotifyId ?: continue
                val existing = sourceDao.selectForLocal(playlist.id)
                if (existing != null
                    && existing.providerId == providerId
                    && existing.externalId == spotifyId
                    && existing.snapshotId == playlist.snapshotId // both-null snapshotId is a legitimate match (Kotlin == → null-safe equals)
                ) continue
                sourceDao.upsert(
                    localPlaylistId = playlist.id,
                    providerId = providerId,
                    externalId = spotifyId,
                    snapshotId = playlist.snapshotId,
                    ownerId = null, // playlist row has ownerName but no ownerId; source ownerId populated at pull time
                    syncedAt = if (playlist.lastModified > 0) playlist.lastModified else now,
                )
                added++
            }
            if (added > 0) {
                Log.d(TAG, "Backfilled $added playlist source(s) into sync_playlist_source")
            }
        }

        /**
         * Heal playlists whose `sync_playlist_source.providerId` doesn't match the
         * provider implied by the playlist's `id` prefix. Mirrors desktop's
         * `healImportedSyncedFromMismatch` (runs in `main.js` alongside
         * `migrateSyncLinksFromPlaylists`).
         *
         * Cross-platform invariant: when one client has a sync bug that flips a
         * Spotify-imported playlist's `syncedFrom.resolver` to `applemusic` (or
         * `undefined`), the OTHER client's launch must silently restore it. Without
         * this on Android, an Android regression corrupts a fleet and only desktop
         * heals.
         *
         * Idempotent. Runs every launch. No-op on healthy data (no log unless N > 0).
         *
         * For each playlist row whose `id` starts with `spotify-`, `applemusic-`,
         * or `listenbrainz-`:
         *   1. Derive `impliedProvider` from the prefix and `externalIdFromPrefix`
         *      from the substring after it.
         *   2. Read the current `sync_playlist_source` row.
         *   3. If existing row matches `impliedProvider`, skip (healthy).
         *   4. Otherwise:
         *      a. If the existing row points at a different provider, demote it
         *         into `sync_playlist_link` (only if no link already exists for
         *         that provider — never overwrite, an existing link may carry a
         *         more-recent snapshot).
         *      b. Restore `sync_playlist_source` with the implied provider +
         *         externalId from the prefix. `snapshotId = null` so the next sync
         *         from that provider repopulates it.
         *      c. Clear `playlist.locallyModified` — the row's state on the
         *         implied provider is now considered canonical.
         *
         * Slot in `syncAll()` between `migrateSourceFromPlaylists` and
         * `migrateMergeCrossProviderDuplicates`: the source backfill must run
         * first (it may CREATE source rows from `spotifyId` for installs that
         * pre-date the source table); the heal then validates / corrects those
         * rows.
         */
        // static for test isolation — avoids constructing SyncEngine with unrelated deps
        suspend fun healImportedSyncedFromMismatch(
            playlistDao: PlaylistDao,
            syncPlaylistSourceDao: SyncPlaylistSourceDao,
            syncPlaylistLinkDao: SyncPlaylistLinkDao,
        ) {
            val supportedProviders = listOf(
                SpotifySyncProvider.PROVIDER_ID,
                AppleMusicSyncProvider.PROVIDER_ID,
                ListenBrainzSyncProvider.PROVIDER_ID,
            )
            var healed = 0
            for (playlist in playlistDao.getAllSync()) {
                val impliedProvider = supportedProviders.firstOrNull {
                    playlist.id.startsWith("$it-")
                } ?: continue
                val externalIdFromPrefix = playlist.id.substringAfter("$impliedProvider-")

                val existing = syncPlaylistSourceDao.selectForLocal(playlist.id)
                if (existing != null && existing.providerId == impliedProvider) {
                    // Healthy — providerId matches the prefix.
                    continue
                }

                // Demote: if a source points at the wrong provider, preserve its
                // external mapping as a link (push target) — but only if no link
                // already exists. An existing link may carry a more-recent
                // snapshot we shouldn't clobber.
                if (existing != null && existing.providerId != impliedProvider) {
                    val existingLink = syncPlaylistLinkDao.selectForLink(
                        playlist.id, existing.providerId,
                    )
                    if (existingLink == null) {
                        syncPlaylistLinkDao.upsertWithSnapshot(
                            localPlaylistId = playlist.id,
                            providerId = existing.providerId,
                            externalId = existing.externalId,
                            snapshotId = existing.snapshotId,
                            syncedAt = existing.syncedAt,
                        )
                    }
                }

                // Restore the correct syncedFrom. snapshotId = null so the next
                // sync from impliedProvider repopulates it. ownerId = null —
                // playlist row doesn't persist ownerId separately on Android.
                syncPlaylistSourceDao.upsert(
                    localPlaylistId = playlist.id,
                    providerId = impliedProvider,
                    externalId = externalIdFromPrefix,
                    snapshotId = null,
                    ownerId = null,
                    syncedAt = currentTimeMillis(),
                )

                // Clear locallyModified — the row's state on the implied
                // provider is now considered canonical. Android's Playlist
                // model has no `hasUpdates` field; only locallyModified.
                if (playlist.locallyModified) {
                    playlistDao.update(playlist.copy(locallyModified = false))
                }

                healed++
            }
            if (healed > 0) {
                Log.d(TAG, "Healed $healed imported-playlist syncedFrom mismatches")
            }
        }
    }

    private val syncMutex = Mutex()

    // True while a syncAll is running (any trigger: manual, the in-app timer,
    // WorkManager). UIs observe this to show a "Syncing…" state regardless of
    // who started it, and to treat a tryLock skip as benign, not a failure.
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    // Coarse current-phase signal (tracks/albums/artists/playlists/complete) so
    // the UI can show "Syncing… (playlists)" and we can SEE which phase hangs.
    // Null when idle.
    private val _syncPhase = MutableStateFlow<String?>(null)
    val syncPhase: StateFlow<String?> = _syncPhase

    // N-way SHADOW report (Phase 3 dev surface). Holds the LATEST cycle's
    // would-do entries for every migrated playlist that has a pending delta.
    // Populated only while isNwayEnabled; reviewed in Android Settings before
    // propagation is switched on. Empty = shadow saw no pending changes.
    private val _nwayShadowLog = MutableStateFlow<List<NwayShadowEntry>>(emptyList())
    val nwayShadowLog: StateFlow<List<NwayShadowEntry>> = _nwayShadowLog

    // N-way PROPAGATION report (Phase 4 dev surface). Holds the LATEST
    // [runNwayPropagation] cycle's per-playlist outcome — what was (or, in
    // dry-run, WOULD be) pushed, or why it aborted. Reviewed in Android Settings
    // to validate real-library behavior before/while real writes are enabled.
    private val _nwayPropagationLog = MutableStateFlow<List<NwayPropagationEntry>>(emptyList())
    val nwayPropagationLog: StateFlow<List<NwayPropagationEntry>> = _nwayPropagationLog

    data class TypeSyncResult(
        val added: Int = 0,
        val removed: Int = 0,
        val updated: Int = 0,
        val unchanged: Int = 0,
    )

    data class FullSyncResult(
        val tracks: TypeSyncResult = TypeSyncResult(),
        val albums: TypeSyncResult = TypeSyncResult(),
        val artists: TypeSyncResult = TypeSyncResult(),
        val playlists: TypeSyncResult = TypeSyncResult(),
        val success: Boolean = true,
        val error: String? = null,
    )

    enum class SyncPhase {
        TRACKS, ALBUMS, ARTISTS, PLAYLISTS, COMPLETE
    }

    data class SyncProgress(
        val phase: SyncPhase,
        val current: Int = 0,
        val total: Int = 0,
        val message: String = "",
    )

    suspend fun syncAll(
        onProgress: (SyncProgress) -> Unit = {},
        providerFilter: String? = null,
    ): FullSyncResult {
        val settings = settingsStore.getSyncSettings()
        if (!settings.enabled) {
            return FullSyncResult(success = false, error = "Sync not enabled")
        }

        // Skip-if-held (not wait-if-held): the in-app timer, WorkManager, and
        // manual sync can all fire at once. Running them serially would waste
        // work and — before the three-layer dedup — produce remote duplicates.
        if (!syncMutex.tryLock()) {
            Log.i(TAG, "Sync already in progress, skipping")
            return FullSyncResult(success = false, error = SYNC_IN_PROGRESS)
        }
        _syncing.value = true

        // When called from a single-provider wizard, filter the enabled
        // providers down to just the one being configured. Otherwise
        // showing AM-wizard progress would be drowned out by Spotify's
        // simultaneous sync (which runs unconditionally because syncAll
        // iterates every enabled provider). Prog UX needs to look like
        // it's only syncing what the user asked it to.
        val activeProviderId = providerFilter
        val providerLabel = if (activeProviderId != null) {
            providers.firstOrNull { it.id == activeProviderId }?.displayName ?: activeProviderId
        } else null

        return try {
            // Watchdog: if the sync body hangs (e.g. a MusicBrainz 503
            // tight-retry never returns), withTimeout cancels it so the
            // `finally` below still runs and releases the mutex + clears the
            // syncing indicator. The TimeoutCancellationException is caught
            // SPECIFICALLY below, before the generic catch.
            withTimeout(SYNC_WATCHDOG_TIMEOUT_MS) {
            var trackResult = TypeSyncResult()
            var albumResult = TypeSyncResult()
            var artistResult = TypeSyncResult()
            var playlistResult = TypeSyncResult()

            if (settings.syncTracks) {
                _syncPhase.value = "tracks"
                onProgress(SyncProgress(SyncPhase.TRACKS,
                    message = if (providerLabel != null) "Syncing $providerLabel songs..."
                              else "Syncing liked songs..."))
                trackResult = syncTracks(onProgress, activeProviderId)
            }

            if (settings.syncAlbums) {
                _syncPhase.value = "albums"
                onProgress(SyncProgress(SyncPhase.ALBUMS,
                    message = if (providerLabel != null) "Syncing $providerLabel albums..."
                              else "Syncing saved albums..."))
                albumResult = syncAlbums(onProgress, activeProviderId)
            }

            if (settings.syncArtists) {
                _syncPhase.value = "artists"
                onProgress(SyncProgress(SyncPhase.ARTISTS,
                    message = if (providerLabel != null) "Syncing $providerLabel artists..."
                              else "Syncing followed artists..."))
                artistResult = syncArtists(onProgress, activeProviderId)
            }

            if (settings.syncPlaylists) {
                _syncPhase.value = "playlists"
                onProgress(SyncProgress(SyncPhase.PLAYLISTS,
                    message = if (providerLabel != null) "Syncing $providerLabel playlists..."
                              else "Syncing playlists..."))
                playlistResult = syncPlaylists(settings, onProgress, activeProviderId)
            }

            settingsStore.setLastSyncAt(currentTimeMillis())

            val result = FullSyncResult(
                tracks = trackResult,
                albums = albumResult,
                artists = artistResult,
                playlists = playlistResult,
            )
            onProgress(SyncProgress(SyncPhase.COMPLETE, message = "Sync complete"))
            Log.d(TAG, "Sync complete: $result")
            result
            }
        } catch (e: TimeoutCancellationException) {
            // Caught BEFORE the generic catch: a TimeoutCancellationException is
            // a CancellationException, so swallowing it in the generic catch
            // would both mislabel it ("Sync failed") and wrongly suppress a
            // cancellation. The `finally` below still runs and releases the
            // mutex + clears the syncing indicator. The aborted sync resumes
            // idempotently next cycle.
            Log.e(TAG, "Sync watchdog: timed out after ${SYNC_WATCHDOG_TIMEOUT_MS / 60000} min — aborting; will resume next cycle")
            FullSyncResult(success = false, error = "Sync timed out")
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            FullSyncResult(success = false, error = e.message)
        } finally {
            _syncPhase.value = null
            _syncing.value = false
            syncMutex.unlock()
        }
    }

    // ── Track sync ───────────────────────────────────────────────

    private suspend fun syncTracks(
        onProgress: (SyncProgress) -> Unit,
        providerFilter: String? = null,
    ): TypeSyncResult {
        // One-time cross-provider track-dedup wipe (runs before any fetch below).
        migrateCrossProviderTrackDedup()
        // Spotify legacy path runs only when no filter, OR the filter
        // selects Spotify. Skipping it for non-Spotify wizards keeps
        // the user-visible progress bar focused on the provider the
        // wizard is configuring.
        val enabled = settingsStore.getEnabledSyncProviders()
        // Spotify legacy path gates on BOTH the enabled set AND the axis. A
        // connected-but-not-enabled Spotify (OAuth token present, but the user
        // never turned Spotify sync on) must NOT sync — albums/artists already
        // gate on the set via their generic provider loop, but the tracks +
        // playlists legacy paths historically checked only `providerHasAxis`
        // (which defaults to ALL), so a connected Spotify synced uninvited.
        val runSpotify = (providerFilter == null || providerFilter == SpotifySyncProvider.PROVIDER_ID)
            && SpotifySyncProvider.PROVIDER_ID in enabled
            && providerHasAxis(SpotifySyncProvider.PROVIDER_ID, "tracks")
        var aggregate = if (runSpotify) syncTracksForSpotify(onProgress) else TypeSyncResult()

        val others = providers.filter {
            it.id in enabled && it.id != SpotifySyncProvider.PROVIDER_ID
                && (providerFilter == null || providerFilter == it.id)
        }
        for (provider in others) {
            if (!providerHasAxis(provider.id, "tracks")) continue
            aggregate += syncTracksForProvider(provider, onProgress)
        }
        return aggregate
    }

    /**
     * One-shot cross-provider track-dedup migration. Older syncs stored saved
     * tracks with provider-prefixed ids (`spotify-…` / `applemusic-…`) and no
     * ISRC, so the same recording is two rows. Those rows have no ISRC to merge
     * in place, so wipe the synced track rows + their sources once (prefix-
     * agnostic — collected from `sync_sources`, since `deleteSyncedTracks` only
     * catches `spotify-%`) and let the normal re-fetch re-create them with
     * canonical ISRC-keyed ids that merge across providers. Gated on a dedicated
     * flag (not the shared SYNC_DATA_VERSION counter).
     */
    private suspend fun migrateCrossProviderTrackDedup() {
        if (settingsStore.getTrackDedupV1Done()) return
        val trackItemIds = providers
            .flatMap { syncSourceDao.getAllByProvider(it.id) }
            .filter { it.itemType == "track" }
            .map { it.itemId }
            .toSet()
        if (trackItemIds.isNotEmpty()) {
            Log.d(TAG, "track dedup migration: wiping ${trackItemIds.size} synced tracks for clean re-sync")
            providers.forEach { syncSourceDao.deleteByProviderAndType(it.id, "track") }
            trackItemIds.forEach { id -> trackDao.getById(id)?.let { trackDao.delete(it) } }
        }
        settingsStore.setTrackDedupV1Done()
    }

    /** Per-provider axis opt-in lookup. Defaults to all axes for back-compat. */
    private suspend fun providerHasAxis(providerId: String, axis: String): Boolean =
        axis in settingsStore.getSyncCollectionsForProvider(providerId)

    /**
     * Probe-confirm which of [candidateExternalIds] are DEFINITELY gone after a
     * pull. A mirror absent from the provider's just-fetched [fullRemoteIds] is
     * only a SUSPECT — the fetch may have been partial (a mid-pagination timeout,
     * an API truncation), and treating absence as deletion would mass-delete live
     * playlists. Each suspect is verified with a targeted
     * [SyncProvider.remotePlaylistExists] 404 probe; a transient/partial fetch
     * yields an EMPTY result (the safety gate). Only suspects are probed, so a
     * clean sync costs zero extra requests. See [DeadMirrorReconcile].
     */
    private suspend fun confirmedGoneMirrors(
        provider: SyncProvider,
        candidateExternalIds: Set<String>,
        fullRemoteIds: Set<String>,
    ): Set<String> {
        val suspected = DeadMirrorReconcile.suspectedGone(candidateExternalIds, fullRemoteIds)
        if (suspected.isEmpty()) return emptySet()
        // Mass-disappearance floor (workstream B): if an implausible number of
        // mirrors vanished from a single fetch, that's almost certainly a
        // partial/failed fetch — refuse to act AND skip the probe storm. A
        // genuine bulk deletion stays a per-device manual action; under-removing
        // is always safer than mass-deleting live playlists.
        if (suspected.size > DEAD_MIRROR_MASS_FLOOR) {
            Log.w(TAG, "dead-mirror: ${provider.id} — ${suspected.size} mirror(s) absent (> floor $DEAD_MIRROR_MASS_FLOOR); treating as a partial fetch, skipping reconcile")
            return emptySet()
        }
        val probe = HashMap<String, Boolean>(suspected.size)
        for (ext in suspected) {
            probe[ext] = try {
                provider.remotePlaylistExists(ext)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                true // a failed probe is NOT proof of deletion — bias toward "exists"
            }
        }
        val gone = DeadMirrorReconcile.confirmedGone(suspected, probe)
        if (gone.isNotEmpty()) {
            Log.w(TAG, "dead-mirror: ${provider.id} — ${gone.size}/${suspected.size} suspected mirror(s) confirmed GONE (404)")
        }
        return gone
    }

    private suspend fun syncTracksForSpotify(
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        val localSources = syncSourceDao.getByProvider(providerId, "track")
        val localCount = localSources.size
        val latest = syncSourceDao.getMostRecentByProvider(providerId, "track")

        // One-time fixup (v2→v3): wipe synced tracks and re-fetch cleanly to fix
        // duplicates + correct addedAt timestamps from Spotify
        if (settingsStore.getSyncDataVersion() < 3) {
            Log.d(TAG, "v3 migration: clearing synced tracks for clean re-sync")
            syncSourceDao.deleteByProviderAndType(providerId, "track")
            trackDao.deleteSyncedTracks()
            val result = syncTracksClean(onProgress)
            // Set to 3, not SYNC_DATA_VERSION — playlist dedup (v4) runs separately
            settingsStore.setSyncDataVersion(3)
            return result
        }

        val remote = spotifyProvider.fetchTracks(
            localCount = localCount,
            latestExternalId = latest?.externalId,
            onProgress = { current, total ->
                onProgress(SyncProgress(SyncPhase.TRACKS, current, total, "Syncing liked songs..."))
            },
        ) ?: return TypeSyncResult(unchanged = localCount)

        return applyTrackDiff(remote, localSources, providerId)
    }

    /**
     * Generic per-provider track sync. Used for non-Spotify providers
     * (Apple Music; future providers). Spotify keeps its dedicated
     * path because of the v2→v3 migration that's specific to legacy
     * Spotify imports.
     */
    private suspend fun syncTracksForProvider(
        provider: com.parachord.shared.sync.SyncProvider,
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = provider.id
        val localSources = syncSourceDao.getByProvider(providerId, "track")
        val localCount = localSources.size
        val latest = syncSourceDao.getMostRecentByProvider(providerId, "track")
        val remote = try {
            provider.fetchTracks(
                localCount = localCount,
                latestExternalId = latest?.externalId,
                onProgress = { current, total ->
                    onProgress(SyncProgress(SyncPhase.TRACKS, current, total, "Syncing ${provider.displayName} library..."))
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch tracks from ${provider.displayName}", e)
            return TypeSyncResult()
        } ?: return TypeSyncResult(unchanged = localCount)
        return applyTrackDiff(remote, localSources, providerId)
    }

    private operator fun TypeSyncResult.plus(other: TypeSyncResult): TypeSyncResult =
        TypeSyncResult(
            added = added + other.added,
            removed = removed + other.removed,
            updated = updated + other.updated,
            unchanged = unchanged + other.unchanged,
        )

    /** Clean sync after wiping synced tracks — no local sources to diff against. */
    private suspend fun syncTracksClean(
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        val remote = spotifyProvider.fetchTracks(
            localCount = 0,
            latestExternalId = null,
            onProgress = { current, total ->
                onProgress(SyncProgress(SyncPhase.TRACKS, current, total, "Syncing liked songs..."))
            },
        ) ?: return TypeSyncResult()

        return applyTrackDiff(remote, emptyList(), providerId)
    }

    private suspend fun applyTrackDiff(
        remoteIn: List<SyncedTrack>,
        localSources: List<SyncSource>,
        providerId: String,
    ): TypeSyncResult {
        // Drop tracks the user removed on purpose (and re-arm their TTL on every
        // hit), so a still-present remote track isn't re-imported against the
        // user's intent (#172). Mirrors desktop "filter remote before diff".
        val tombResult = tombstones.filterRemote(remoteIn, providerId) { it.spotifyId }
        val remote = tombResult.filtered ?: remoteIn
        if (tombResult.dropped > 0) {
            Log.d(TAG, "applyTrackDiff: tombstones dropped ${tombResult.dropped} remote tracks for $providerId")
        }
        val remoteByExternalId = remote.associateBy { it.spotifyId }
        val localByExternalId = localSources.associateBy { it.externalId }

        val toAdd = remote.filter { it.spotifyId !in localByExternalId }
        var toRemove = localSources.filter { it.externalId !in remoteByExternalId }
        val toUpdate = remote.filter { synced ->
            synced.spotifyId in localByExternalId
        }
        Log.i(TAG, "applyTrackDiff[$providerId]: remote=${remote.size}, localSources=${localSources.size}, toAdd=${toAdd.size}, toRemove=${toRemove.size}, toUpdate=${toUpdate.size}")

        if (toRemove.size > localSources.size * MASS_REMOVAL_THRESHOLD_PERCENT
            && toRemove.size > MASS_REMOVAL_THRESHOLD_COUNT
        ) {
            Log.w(TAG, "Mass removal safeguard: would remove ${toRemove.size}/${localSources.size} tracks, skipping removals")
            toRemove = emptyList()
        }

        val now = currentTimeMillis()

        // BATCHED writes — load existing canonical rows in a handful of getByIds
        // queries (chunked) and write with insertAll, instead of a getById+insert
        // per track. The per-row form made a large first-time Apple Music sync
        // crawl for minutes (thousands of sequential DB round-trips), which read
        // as a stuck "Syncing…".
        run {
            // ADD: the canonical (ISRC-keyed) row may already exist from ANOTHER
            // provider — merge service IDs onto it (null-only) instead of
            // clobbering. `seen` collapses an intra-batch duplicate canonical id
            // (same recording twice in the library) to one row.
            if (toAdd.isNotEmpty()) {
                val existingById = trackDao.getByIds(toAdd.map { it.entity.id }).associateBy { it.id }
                val seen = HashSet<String>()
                val rows = ArrayList<Track>(toAdd.size)
                val sources = ArrayList<SyncSource>(toAdd.size)
                for (synced in toAdd) {
                    sources.add(
                        SyncSource(
                            itemId = synced.entity.id, itemType = "track", providerId = providerId,
                            externalId = synced.spotifyId, addedAt = synced.addedAt, syncedAt = now,
                        ),
                    )
                    if (!seen.add(synced.entity.id)) continue
                    val existing = existingById[synced.entity.id]
                    rows.add(if (existing != null) mergeSyncedTrack(existing, synced.entity) else synced.entity)
                }
                trackDao.insertAll(rows)
                syncSourceDao.insertAll(sources)
            }

            toRemove.forEach { source ->
                syncSourceDao.deleteByKey(source.itemId, "track", providerId)
                val remaining = syncSourceDao.getByItem(source.itemId, "track")
                if (remaining.isEmpty()) {
                    trackDao.getById(source.itemId)?.let { trackDao.delete(it) }
                }
            }

            // UPDATE: membership unchanged — bump syncedAt and merge (additive, so a
            // re-pull from THIS provider never wipes another provider's IDs off a
            // shared canonical row).
            if (toUpdate.isNotEmpty()) {
                val existingById = trackDao.getByIds(toUpdate.map { it.entity.id }).associateBy { it.id }
                val seen = HashSet<String>()
                val rows = ArrayList<Track>(toUpdate.size)
                for (synced in toUpdate) {
                    if (!seen.add(synced.entity.id)) continue
                    existingById[synced.entity.id]?.let { rows.add(mergeSyncedTrack(it, synced.entity)) }
                }
                if (rows.isNotEmpty()) trackDao.insertAll(rows)
                syncSourceDao.insertAll(toUpdate.map { localByExternalId[it.spotifyId]!!.copy(syncedAt = now) })
            }
        }

        return TypeSyncResult(
            added = toAdd.size,
            removed = toRemove.size,
            updated = toUpdate.size,
            unchanged = toUpdate.size,
        )
    }

    /**
     * Additive merge of an incoming synced track's service IDs onto an existing
     * (possibly other-provider) canonical row. Never overwrites a populated field
     * (null-only fill, mirroring backfillResolverIds); keeps the existing display
     * metadata so the first provider to add the song owns its title/artist/art.
     */
    private fun mergeSyncedTrack(existing: Track, incoming: Track): Track = existing.copy(
        spotifyId = existing.spotifyId ?: incoming.spotifyId,
        appleMusicId = existing.appleMusicId ?: incoming.appleMusicId,
        soundcloudId = existing.soundcloudId ?: incoming.soundcloudId,
        spotifyUri = existing.spotifyUri ?: incoming.spotifyUri,
        isrc = existing.isrc ?: incoming.isrc,
        recordingMbid = existing.recordingMbid ?: incoming.recordingMbid,
        artworkUrl = existing.artworkUrl ?: incoming.artworkUrl,
        album = existing.album ?: incoming.album,
        albumId = existing.albumId ?: incoming.albumId,
    )

    // ── Album sync ───────────────────────────────────────────────

    private suspend fun syncAlbums(
        onProgress: (SyncProgress) -> Unit,
        providerFilter: String? = null,
    ): TypeSyncResult {
        val enabled = settingsStore.getEnabledSyncProviders()
        var aggregate = TypeSyncResult()
        for (provider in providers.filter {
            it.id in enabled && (providerFilter == null || providerFilter == it.id)
        }) {
            if (!providerHasAxis(provider.id, "albums")) continue
            aggregate += syncAlbumsForProvider(provider, onProgress)
        }
        return aggregate
    }

    private suspend fun syncAlbumsForProvider(
        provider: com.parachord.shared.sync.SyncProvider,
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = provider.id
        val localSources = syncSourceDao.getByProvider(providerId, "album")
        val localCount = localSources.size
        val latest = syncSourceDao.getMostRecentByProvider(providerId, "album")

        // Detect entity-table wipes: if `sync_sources` says we have N
        // albums for this provider but the `albums` table has fewer
        // rows with the matching id-prefix, lie to the provider about
        // localCount so its unchanged-shortcut mismatches and forces a
        // full refetch. Without this, a corrupted entity table never
        // self-heals — the provider keeps short-circuiting on probe
        // matches forever. See "Why did synced Albums/Artists
        // disappear?" debugging session 2026-04-25.
        val entityCount = albumDao.countByIdPrefix("$providerId-")
        val effectiveLocalCount = if (entityCount < localCount) {
            Log.w(TAG, "albums entity-table out of sync for $providerId: " +
                "$entityCount entities vs $localCount sources — forcing refetch")
            0
        } else localCount

        val remote = try {
            provider.fetchAlbums(
                localCount = effectiveLocalCount,
                latestExternalId = if (effectiveLocalCount == 0) null else latest?.externalId,
                onProgress = { current, total ->
                    onProgress(SyncProgress(SyncPhase.ALBUMS, current, total, "Syncing ${provider.displayName} albums..."))
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch albums from ${provider.displayName}", e)
            return TypeSyncResult()
        } ?: return TypeSyncResult(unchanged = localCount)

        return applyAlbumDiff(remote, localSources, providerId)
    }

    private suspend fun applyAlbumDiff(
        remote: List<SyncedAlbum>,
        localSources: List<SyncSource>,
        providerId: String,
    ): TypeSyncResult {
        val remoteByExternalId = remote.associateBy { it.spotifyId }
        val localByExternalId = localSources.associateBy { it.externalId }

        val toAdd = remote.filter { it.spotifyId !in localByExternalId }
        var toRemove = localSources.filter { it.externalId !in remoteByExternalId }
        val unchanged = remote.filter { it.spotifyId in localByExternalId }

        if (toRemove.size > localSources.size * MASS_REMOVAL_THRESHOLD_PERCENT
            && toRemove.size > MASS_REMOVAL_THRESHOLD_COUNT
        ) {
            Log.w(TAG, "Mass removal safeguard: albums, skipping removals")
            toRemove = emptyList()
        }

        val now = currentTimeMillis()

        run {
            if (toAdd.isNotEmpty()) {
                albumDao.insertAll(toAdd.map { it.entity })
                syncSourceDao.insertAll(toAdd.map { synced ->
                    SyncSource(
                        itemId = synced.entity.id,
                        itemType = "album",
                        providerId = providerId,
                        externalId = synced.spotifyId,
                        addedAt = synced.addedAt,
                        syncedAt = now,
                    )
                })
            }

            toRemove.forEach { source ->
                syncSourceDao.deleteByKey(source.itemId, "album", providerId)
                val remaining = syncSourceDao.getByItem(source.itemId, "album")
                if (remaining.isEmpty()) {
                    albumDao.getById(source.itemId)?.let { albumDao.delete(it) }
                }
            }

            if (unchanged.isNotEmpty()) {
                albumDao.insertAll(unchanged.map { it.entity })
                syncSourceDao.insertAll(unchanged.mapNotNull { synced ->
                    localByExternalId[synced.spotifyId]?.copy(syncedAt = now)
                })
            }
        }

        return TypeSyncResult(added = toAdd.size, removed = toRemove.size, updated = unchanged.size)
    }

    // ── Artist sync ──────────────────────────────────────────────

    private suspend fun syncArtists(
        onProgress: (SyncProgress) -> Unit,
        providerFilter: String? = null,
    ): TypeSyncResult {
        val enabled = settingsStore.getEnabledSyncProviders()
        var aggregate = TypeSyncResult()
        for (provider in providers.filter {
            it.id in enabled && (providerFilter == null || providerFilter == it.id)
        }) {
            if (!providerHasAxis(provider.id, "artists")) continue
            aggregate += syncArtistsForProvider(provider, onProgress)
        }
        return aggregate
    }

    private suspend fun syncArtistsForProvider(
        provider: com.parachord.shared.sync.SyncProvider,
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = provider.id
        val localSources = syncSourceDao.getByProvider(providerId, "artist")
        val localCount = localSources.size

        // Same entity-table-wipe guard as syncAlbumsForProvider — see
        // its KDoc for context. Force refetch when artist entity rows
        // for this provider's id-prefix fall below the source count.
        val entityCount = artistDao.countByIdPrefix("$providerId-")
        val effectiveLocalCount = if (entityCount < localCount) {
            Log.w(TAG, "artists entity-table out of sync for $providerId: " +
                "$entityCount entities vs $localCount sources — forcing refetch")
            0
        } else localCount

        val remote = try {
            provider.fetchArtists(
                localCount = effectiveLocalCount,
                onProgress = { current, total ->
                    onProgress(SyncProgress(SyncPhase.ARTISTS, current, total, "Syncing ${provider.displayName} artists..."))
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch artists from ${provider.displayName}", e)
            return TypeSyncResult()
        } ?: return TypeSyncResult(unchanged = localCount)

        val remoteByExternalId = remote.associateBy { it.spotifyId }
        val localByExternalId = localSources.associateBy { it.externalId }

        val toAdd = remote.filter { it.spotifyId !in localByExternalId }
        var toRemove = localSources.filter { it.externalId !in remoteByExternalId }
        val unchanged = remote.filter { it.spotifyId in localByExternalId }

        if (toRemove.size > localSources.size * MASS_REMOVAL_THRESHOLD_PERCENT
            && toRemove.size > MASS_REMOVAL_THRESHOLD_COUNT
        ) {
            Log.w(TAG, "Mass removal safeguard: artists, skipping removals")
            toRemove = emptyList()
        }

        val now = currentTimeMillis()

        run {
            if (toAdd.isNotEmpty()) {
                artistDao.insertAll(toAdd.map { it.entity })
                syncSourceDao.insertAll(toAdd.map { synced ->
                    SyncSource(
                        itemId = synced.entity.id,
                        itemType = "artist",
                        providerId = providerId,
                        externalId = synced.spotifyId,
                        syncedAt = now,
                    )
                })
            }

            toRemove.forEach { source ->
                syncSourceDao.deleteByKey(source.itemId, "artist", providerId)
                val remaining = syncSourceDao.getByItem(source.itemId, "artist")
                if (remaining.isEmpty()) {
                    artistDao.deleteById(source.itemId)
                }
            }

            if (unchanged.isNotEmpty()) {
                artistDao.insertAll(unchanged.map { it.entity })
                syncSourceDao.insertAll(unchanged.mapNotNull { synced ->
                    localByExternalId[synced.spotifyId]?.copy(syncedAt = now)
                })
            }
        }

        return TypeSyncResult(added = toAdd.size, removed = toRemove.size, updated = unchanged.size)
    }

    /**
     * Backfill [syncPlaylistLinkDao] from any playlist rows that already have
     * a `spotifyId` set. Idempotent — safe to run on every sync. Ensures the
     * durable link map survives upgrades from pre-link-map installs and stays
     * in sync if an older push path wrote `spotifyId` without writing a link.
     */
    /**
     * One-shot cleanup: merge duplicate local playlist rows that exist
     * because of the cross-provider name-match gap in earlier builds.
     *
     * Before commit ef5a3c2, syncing the same playlist on both Spotify
     * and AM produced two local rows ("Workout 2024" appearing as both
     * `spotify-X` and `applemusic-Y`). This migration finds those
     * groups and collapses them into a single canonical row, moving
     * the AM-side `sync_source` and `sync_playlist_link` (and
     * `sync_playlist_source`, when not already claimed by a different
     * provider) onto the keeper, then deleting the duplicate.
     *
     * Keeper selection: richest `trackCount`, oldest `createdAt` as
     * tiebreak — same heuristic as [findCrossProviderNameMatch] for
     * forward-going pulls. Excludes `local-*` rows (those duplicates
     * are handled by the existing push-path dedup).
     *
     * Idempotent: sets a sync-data version flag so it runs at most once
     * per install. Subsequent syncs use the new pull-path name-match
     * (commit ef5a3c2) so duplicates can't reappear.
     */
    private suspend fun migrateMergeCrossProviderDuplicates() {
        if (settingsStore.getSyncDataVersion() >= MIGRATION_DEDUP_LOCAL_V1) return

        val all = playlistDao.getAllSync().filter { !it.id.startsWith("local-") }
        val byName = all.groupBy { it.name.trim().lowercase() }.filterValues { it.size > 1 }
        if (byName.isEmpty()) {
            settingsStore.setSyncDataVersion(MIGRATION_DEDUP_LOCAL_V1)
            Log.d(TAG, "Cross-provider dedup migration: no duplicates found")
            return
        }

        var mergedCount = 0
        for ((nameKey, group) in byName) {
            // Keeper = richest trackCount, then oldest createdAt
            val keeper = group.maxWithOrNull(
                compareBy<Playlist> { it.trackCount }.thenByDescending { it.createdAt }
            ) ?: continue
            val duplicates = group.filter { it.id != keeper.id }
            if (duplicates.isEmpty()) continue

            Log.d(TAG, "Merging ${duplicates.size} duplicate(s) of '$nameKey' into ${keeper.id}")

            val keeperLinks = syncPlaylistLinkDao.selectAll()
                .filter { it.localPlaylistId == keeper.id }
                .associateBy { it.providerId }
            val keeperHasSource = syncPlaylistSourceDao.selectForLocal(keeper.id) != null

            for (dup in duplicates) {
                // Move sync_source rows: re-key from dup.id → keeper.id.
                // The PK is (itemId, itemType, providerId) so insert
                // OR REPLACE handles the case where keeper already has
                // a row for the same provider (dup wins because it has
                // a real externalId; keeper's was likely a stub).
                val dupSources = syncSourceDao.getByItem(dup.id, "playlist")
                for (src in dupSources) {
                    syncSourceDao.insert(SyncSource(
                        itemId = keeper.id,
                        itemType = "playlist",
                        providerId = src.providerId,
                        externalId = src.externalId,
                        addedAt = src.addedAt,
                        syncedAt = src.syncedAt,
                    ))
                    syncSourceDao.deleteByKey(dup.id, "playlist", src.providerId)
                }

                // Move sync_playlist_link rows: only when keeper doesn't
                // already have a link for that provider (otherwise we'd
                // overwrite keeper's externalId, which might break an
                // existing valid mirror).
                val dupLinks = syncPlaylistLinkDao.selectAll()
                    .filter { it.localPlaylistId == dup.id }
                for (link in dupLinks) {
                    if (link.providerId !in keeperLinks) {
                        syncPlaylistLinkDao.upsertWithSnapshot(
                            localPlaylistId = keeper.id,
                            providerId = link.providerId,
                            externalId = link.externalId,
                            snapshotId = link.snapshotId,
                            syncedAt = link.syncedAt,
                        )
                    }
                }
                syncPlaylistLinkDao.deleteForLocal(dup.id)

                // Move sync_playlist_source: only when keeper has no
                // syncedFrom yet. Otherwise keeper's pull source wins
                // (preserves syncedFrom invariant from CLAUDE.md).
                val dupSource = syncPlaylistSourceDao.selectForLocal(dup.id)
                if (dupSource != null && !keeperHasSource) {
                    syncPlaylistSourceDao.upsert(
                        localPlaylistId = keeper.id,
                        providerId = dupSource.providerId,
                        externalId = dupSource.externalId,
                        snapshotId = dupSource.snapshotId,
                        ownerId = dupSource.ownerId,
                        syncedAt = dupSource.syncedAt,
                    )
                }
                syncPlaylistSourceDao.deleteForLocal(dup.id)

                // Drop the duplicate's tracks; keeper's stay canonical.
                playlistTrackDao.deleteByPlaylistId(dup.id)
                playlistDao.delete(dup)
                mergedCount++
            }
        }

        settingsStore.setSyncDataVersion(MIGRATION_DEDUP_LOCAL_V1)
        Log.d(TAG, "Cross-provider dedup migration: merged $mergedCount duplicate row(s)")
    }

    /**
     * Repair AM artwork URLs that were stored before
     * [com.parachord.android.sync.AppleMusicSyncProvider.resolveArtworkUrl]
     * landed — pre-fix AM pulls persisted Apple's raw URLs with the
     * `{w}` `{h}` placeholders intact, which Coil and the mosaic
     * generator can't fetch. Substitutes 600x600 in place. Touches
     * playlists, playlist_tracks, tracks, albums, and artists tables.
     * Idempotent: a URL with no placeholders is a no-op replacement.
     */
    private suspend fun repairAppleMusicArtworkPlaceholders() {
        var fixed = 0
        for (pl in playlistDao.getAllSync()) {
            val art = pl.artworkUrl ?: continue
            if ("{w}" !in art && "{h}" !in art) continue
            playlistDao.update(pl.copy(
                artworkUrl = art.replace("{w}", "600").replace("{h}", "600")
            ))
            fixed++
        }
        if (fixed > 0) {
            Log.d(TAG, "repairAppleMusicArtworkPlaceholders: fixed $fixed playlist row(s)")
        }
        // Bulk SQL for the row-y tables (playlist_tracks, albums, tracks,
        // artists). Raw driver execute because SQLDelight's UPDATE setter
        // parser doesn't accept REPLACE() function calls. Each statement
        // is idempotent — rows without `{w}`/`{h}` are excluded by the
        // WHERE clause and untouched. Originally only walked
        // playlist_tracks; extended 2026-04-29 after a user reported
        // 7,318 AM albums with literal `.../{w}x{h}bb.jpg` URLs in their
        // collection (artwork was never resolved at sync time on those
        // rows; possibly imported via a path that bypassed
        // AppleMusicSyncProvider.resolveArtworkUrl, possibly Apple
        // returned the raw template for some albums). Walk all four
        // entity tables defensively.
        val placeholderRepairs = listOf(
            "playlist_tracks" to "trackArtworkUrl",
            "albums" to "artworkUrl",
            "tracks" to "artworkUrl",
            "artists" to "imageUrl",
        )
        for ((table, column) in placeholderRepairs) {
            try {
                driver.execute(
                    null,
                    "UPDATE $table " +
                        "SET $column = REPLACE(REPLACE($column, '{w}', '600'), '{h}', '600') " +
                        "WHERE $column LIKE '%{w}%' OR $column LIKE '%{h}%'",
                    0,
                )
            } catch (e: Exception) {
                Log.w(TAG, "$table.$column artwork-placeholder repair failed", e)
            }
        }
    }

    /**
     * Walk every playlist whose `trackCount` field doesn't match its
     * actual `playlist_tracks` row count, and fix it. Cheap (one
     * query + one update per mismatched row). Runs every sync cycle
     * because the AM-side trackCount drift can re-emerge any time
     * a snapshot-matched cycle skips pullPlaylist (which is the only
     * other path that writes trackCount).
     */
    private suspend fun reconcileTrackCounts() {
        var fixed = 0
        for (playlist in playlistDao.getAllSync()) {
            val actual = playlistTrackDao.getByPlaylistIdSync(playlist.id).size
            if (playlist.trackCount != actual) {
                playlistDao.update(playlist.copy(trackCount = actual))
                fixed++
            }
        }
        if (fixed > 0) {
            Log.d(TAG, "reconcileTrackCounts: fixed $fixed playlist(s)")
        }
    }

    private suspend fun migrateLinksFromPlaylists() {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        var added = 0
        for (playlist in playlistDao.getAllSync()) {
            val spotifyId = playlist.spotifyId ?: continue
            val existing = syncPlaylistLinkDao.selectForLink(playlist.id, providerId)
            if (existing?.externalId == spotifyId) continue
            syncPlaylistLinkDao.upsert(
                localPlaylistId = playlist.id,
                providerId = providerId,
                externalId = spotifyId,
                syncedAt = if (playlist.lastModified > 0) playlist.lastModified
                else currentTimeMillis(),
            )
            added++
        }
        if (added > 0) {
            Log.d(TAG, "Backfilled $added playlist link(s) from spotifyId into sync_playlist_link")
        }
    }

    // ── Playlist sync ────────────────────────────────────────────

    /**
     * N-way migration bootstrap (Phase 2). Run once per synced playlist the
     * first time N-way is enabled: seed the 3-way-merge baseline from the
     * current LOCAL tracklist and seed each mirror's N-way state from the token
     * we already have stored. Idempotent (skips a playlist that already has a
     * baseline row) and READ-ONLY against providers — the "normalize mirrors"
     * push from the design is DEFERRED to the propagation phase so the merge
     * logic is validated (shadow mode) before we ever push. Gated entirely on
     * [SyncSettingsProvider.isNwayEnabled]; inert (early return) while OFF.
     */
    private suspend fun migrateAllPlaylistsToNway() {
        if (!settingsStore.isNwayEnabled()) return
        val channels = getAllEffectivePlaylistChannels()
        if (channels.isEmpty()) return
        var migrated = 0
        for ((localId, providers) in channels) {
            if (providers.isEmpty()) continue
            val playlist = playlistDao.getById(localId) ?: continue
            if (migratePlaylistToNway(playlist)) migrated++
        }
        if (migrated > 0) Log.d(TAG, "N-way migration: seeded baseline for $migrated playlist(s)")
    }

    /**
     * Seed one playlist's baseline + per-provider N-way state. Returns true if
     * it migrated, false if already migrated (idempotent). No provider writes —
     * each mirror's `changeToken` is seeded from the canonical-source snapshot
     * we already hold; `editedAt = now` is a bootstrap floor (the true
     * per-provider edit time is captured the next time that token changes).
     */
    private suspend fun migratePlaylistToNway(playlist: Playlist): Boolean {
        val existing = syncPlaylistBaselineDao.selectForLocal(playlist.id)
        val localTracks = playlistTrackDao.getByPlaylistIdSync(playlist.id)
        // Skip if already migrated in the current (TrackKeys) format. An existing
        // row that decodes EMPTY while the playlist has tracks is the old Phase
        // 1/2 single-key-string format → re-seed it as TrackKeys.
        if (existing != null && (existing.tracks.isNotEmpty() || localTracks.isEmpty())) return false
        val now = currentTimeMillis()
        syncPlaylistBaselineDao.upsert(playlist.id, nwayBaselineTrackKeys(localTracks), now)
        val source = syncPlaylistSourceDao.selectForLocal(playlist.id)
        for ((providerId, _) in getPlaylistMirrors(playlist.id)) {
            val token = syncPlaylistLinkDao.selectForLink(playlist.id, providerId)?.snapshotId
                ?: source?.takeIf { it.providerId == providerId }?.snapshotId
            syncPlaylistNwayDao.upsert(playlist.id, providerId, token, now, now)
        }
        return true
    }

    /** One copy's pre-unification TrackKeys + detection result, gathered before
     *  [unifyTrackKeys] rewrites them to representative keys. */
    private data class ShadowCopyInput(
        val id: String,
        val keys: List<TrackKeys>,
        val editedAt: Long,
        val changed: Boolean,
    )

    /**
     * N-way SHADOW MODE (Phase 3). For every migrated playlist, detect which
     * copies changed, fetch only those, run the 3-way merge, and **LOG what it
     * would do — push nothing, mutate nothing** (not the baseline, not the
     * tokens). Lets us validate the merge against real libraries at zero risk
     * before propagation (Phase 4) is switched on. Gated on
     * [SyncSettingsProvider.isNwayEnabled]; inert (early return) while OFF.
     */
    /**
     * On-demand N-way SHADOW scan (Phase 3 dev validation). Detects divergence
     * CHEAPLY — ONE paginated playlist-list call per enabled provider (each
     * [SyncedPlaylist] already carries snapshot + trackCount), fetching a full
     * tracklist ONLY for a mirror that actually changed. Runs the merge,
     * populates [nwayShadowLog]. Pushes nothing, mutates nothing. Dev-triggered
     * (NOT per-sync — see syncPlaylists), so routine syncs pay nothing. Gated on
     * isNwayEnabled.
     */
    suspend fun runNwayShadowScan() {
        if (!settingsStore.isNwayEnabled()) return
        val baselines = syncPlaylistBaselineDao.selectAll()
        if (baselines.isEmpty()) {
            _nwayShadowLog.value = emptyList()
            return
        }
        // One list call per enabled provider → externalId -> SyncedPlaylist.
        val enabled = settingsStore.getEnabledSyncProviders()
        val remoteLists = mutableMapOf<String, Map<String, SyncedPlaylist>>()
        for (provider in providers.filter { it.id in enabled }) {
            remoteLists[provider.id] = runCatching {
                provider.fetchPlaylists(null).associateBy { it.spotifyId }
            }.getOrElse { emptyMap() }
        }
        val entries = mutableListOf<NwayShadowEntry>()
        for (baseline in baselines) {
            try {
                shadowReconcilePlaylist(baseline, remoteLists)?.let { entries.add(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Partial-fetch guard: a changed copy whose tracklist-fetch
                // failed aborts THIS playlist for the cycle — never read a
                // failed fetch as "removed everything".
                Log.d(TAG, "N-way SHADOW: skipped '${baseline.localPlaylistId}' (${e.message})")
            }
        }
        _nwayShadowLog.value = entries
    }

    private suspend fun shadowReconcilePlaylist(
        baseline: SyncPlaylistBaselineDao.Baseline,
        remoteLists: Map<String, Map<String, SyncedPlaylist>>,
    ): NwayShadowEntry? {
        val localId = baseline.localPlaylistId
        val playlist = playlistDao.getById(localId) ?: return null
        val mirrors = getPlaylistMirrors(localId)
        if (mirrors.isEmpty()) return null
        val storedTokens = syncPlaylistNwayDao.selectForLocal(localId).associateBy { it.providerId }
        val now = currentTimeMillis()

        // Gather each copy's TrackKeys (NOT yet unified). An unchanged copy reuses
        // the baseline's TrackKeys (no fetch). editedAt = detection-time (now) for
        // changed provider copies is a Phase 4 refinement (Spotify MAX(added_at) /
        // AM·LB last_modified); it only affects ORDER, not presence.
        val inputs = mutableListOf<ShadowCopyInput>()
        val localChanged = playlist.locallyModified || playlist.lastModified > baseline.baselineSyncedAt
        inputs.add(
            ShadowCopyInput(
                "local",
                nwayBaselineTrackKeys(playlistTrackDao.getByPlaylistIdSync(localId)),
                playlist.lastModified,
                localChanged,
            ),
        )
        for ((providerId, externalId) in mirrors) {
            val provider = providers.firstOrNull { it.id == providerId } ?: continue
            val listEntry = remoteLists[providerId]?.get(externalId)
            val storedToken = storedTokens[providerId]?.changeToken
            val changed = when {
                listEntry == null -> false // not in the owned list (deleted/unowned) — skip
                listEntry.snapshotId != null -> listEntry.snapshotId != storedToken
                else -> listEntry.trackCount != baseline.tracks.size
            }
            if (!changed) {
                inputs.add(ShadowCopyInput(providerId, baseline.tracks, now, changed = false))
                continue
            }
            val keys = provider.fetchPlaylistTracks(externalId).map {
                trackKeysOf(isrc = it.trackIsrc, recordingMbid = it.trackRecordingMbid, artist = it.trackArtist, title = it.trackTitle)
            }
            inputs.add(ShadowCopyInput(providerId, keys, now, changed = true))
        }

        // Cross-copy key unification (Phase 4): unify baseline + all copies so the
        // same song matches across services BEFORE the merge runs on the resulting
        // representative keys. This is what collapses the norm-/mbid- false-drops.
        val unified = unifyTrackKeys(listOf(baseline.tracks) + inputs.map { it.keys })
        val baselineRepr = unified[0]
        val copies = inputs.mapIndexed { i, c -> NwayCopyState(c.id, unified[i + 1], c.editedAt, c.changed) }

        val plan = computeNwayShadowPlan(baselineRepr, copies) ?: return null
        val dropFraction = nwayBaselineDropFraction(baselineRepr, plan.merged)
        val dropPercent = (dropFraction * 100).toInt()
        // Total-wipe floor only (matches propagation): "would abort" iff the merge
        // collapses a non-empty playlist to ZERO. Large non-empty drops propagate.
        val massChange = plan.merged.isEmpty() && baselineRepr.isNotEmpty()
        Log.d(
            TAG,
            "N-way SHADOW [${playlist.name}]: changed=${plan.changedCopyIds} → merged ${plan.merged.size} " +
                "track(s), wouldPush=${plan.wouldPushTo}, drop=$dropPercent%" +
                (if (massChange) " — TOTAL-WIPE, would abort" else "") +
                " [no push — shadow mode]",
        )
        return NwayShadowEntry(
            playlistName = playlist.name,
            localPlaylistId = localId,
            changedCopies = plan.changedCopyIds,
            mergedCount = plan.merged.size,
            wouldPushTo = plan.wouldPushTo,
            dropPercent = dropPercent,
            massChange = massChange,
        )
    }

    /** One copy's pre-unification TrackKeys + the actual [PlaylistTrack]s behind
     *  them (needed to resolve merged representative keys back to tracks for the
     *  push — Trap 1). For an unchanged copy [tracks] is empty (its keys reuse the
     *  baseline; it can still be a push target, but its current tracks aren't
     *  needed to build the merged list). */
    private data class PropagationCopyInput(
        val id: String,
        val tracks: List<PlaylistTrack>,
        val keys: List<TrackKeys>,
        val editedAt: Long,
        val changed: Boolean,
    )

    /**
     * N-way PROPAGATION (Phase 4) — the REAL writes. For every migrated playlist,
     * detect which copies changed, fetch only those, run the unify + 3-way merge,
     * then push the merged tracklist to every WRITABLE copy that lags it, capture
     * each provider's post-push token (echo suppression), and advance the
     * baseline so the next cycle detects every copy as unchanged.
     *
     * Gated on BOTH [SyncSettingsProvider.isNwayEnabled] and
     * [SyncSettingsProvider.isNwayPropagateEnabled] — inert (early return) unless
     * both are on. [dryRun] computes + logs the plan (and resolves the merged
     * tracks) but skips the actual provider writes — the on-device validation
     * step before real writes are enabled.
     *
     * Mirrors [runNwayShadowScan]'s cheap-detection structure (one paginated
     * list call per enabled provider; full-tracklist fetch only for a CHANGED
     * mirror) with the same CancellationException-rethrow + partial-fetch guard.
     */
    /**
     * Dev/recovery: wipe all N-way tracking state — every playlist's baseline
     * ancestor + per-provider change tokens — and clear the in-memory reports.
     * The next sync re-runs the migration bootstrap (re-seeds each baseline from
     * the current local tracklist) and re-detects from scratch. Used to recover a
     * playlist left in a bad state by the pre-fix executor (baseline advanced past
     * an unfilled mirror → stuck in mass-change-abort).
     *
     * NOTE: a clean re-seed re-fills an EMPTY mirror only when the merge sees it
     * as "lagging" (first-fill, baseline empty), not as a deletion — the deeper
     * "fill an empty mirror whose source already has content" case is part of the
     * deferred reconciliation redesign. For a stuck content-bearing playlist whose
     * mirrors are empty, deleting the empty mirror (or re-importing) is the
     * reliable recovery; the fixed hydration then fills it via the normal push.
     */
    suspend fun resetNwayState() {
        for (b in syncPlaylistBaselineDao.selectAll()) {
            syncPlaylistBaselineDao.deleteForLocal(b.localPlaylistId)
        }
        for (s in syncPlaylistNwayDao.selectAll()) {
            syncPlaylistNwayDao.deleteForLocal(s.localPlaylistId)
        }
        _nwayPropagationLog.value = emptyList()
        _nwayShadowLog.value = emptyList()
        Log.d(TAG, "N-way state reset — baselines + tokens cleared; next sync re-seeds")
    }

    suspend fun runNwayPropagation(dryRun: Boolean = false) {
        // A DRY-RUN (preview only, no writes) needs just isNwayEnabled so the
        // user can validate the readout on their real library BEFORE arming
        // writes. REAL writes require the stricter isNwayPropagateEnabled too.
        Log.d(TAG, "N-way PROPAGATE: run requested (dryRun=$dryRun)")
        if (!settingsStore.isNwayEnabled()) {
            Log.d(TAG, "N-way PROPAGATE: skipped — N-way not enabled")
            return
        }
        if (!dryRun && !settingsStore.isNwayPropagateEnabled()) {
            Log.d(TAG, "N-way PROPAGATE: skipped — real writes not enabled (enable the toggle, or use dry-run)")
            return
        }
        val baselines = syncPlaylistBaselineDao.selectAll()
        if (baselines.isEmpty()) {
            Log.d(TAG, "N-way PROPAGATE: no baselines yet — run a SYNC first so migration seeds them " +
                "(or you just tapped Reset state)")
            return
        }
        Log.d(TAG, "N-way PROPAGATE: ${baselines.size} baseline(s) to reconcile")
        val remoteLists = fetchEnabledProviderPlaylistLists()
        // ONE HydrationCoordinator per cycle — its per-cycle live-search budget +
        // cooldown span the whole run, so an un-findable ADD isn't re-searched per
        // playlist (see [newHydrationCoordinator]).
        val coordinator = newHydrationCoordinator()
        val entries = mutableListOf<NwayPropagationEntry>()
        for (baseline in baselines) {
            try {
                propagateReconcilePlaylist(baseline, remoteLists, coordinator, dryRun)?.let { entries.add(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Partial-fetch guard: a changed copy whose tracklist-fetch failed
                // aborts THIS playlist for the cycle — never push a partial/empty.
                Log.d(TAG, "N-way PROPAGATE: skipped '${baseline.localPlaylistId}' (${e.message})")
            }
        }
        _nwayPropagationLog.value = entries
    }

    /** One paginated playlist-list call per enabled provider → externalId map. */
    private suspend fun fetchEnabledProviderPlaylistLists(): Map<String, Map<String, SyncedPlaylist>> {
        val enabled = settingsStore.getEnabledSyncProviders()
        val out = mutableMapOf<String, Map<String, SyncedPlaylist>>()
        for (provider in providers.filter { it.id in enabled }) {
            out[provider.id] = runCatching {
                provider.fetchPlaylists(null).associateBy { it.spotifyId }
            }.getOrElse { emptyMap() }
        }
        return out
    }

    /**
     * One [HydrationCoordinator] per propagation cycle. The per-cycle live-search
     * budget + the negative-cache cooldown span the WHOLE run, so an un-findable
     * ADD is searched at most a few times across the whole library, never once per
     * playlist per cycle (the death-spiral [hydrateMissingTrackIds] would cause).
     *
     * [HydrationCoordinator.persistRowId] additively backfills the freshly-resolved
     * native id onto the LOCAL `playlist_track` row (null-only COALESCE — never
     * overwrites a populated id), so subsequent cycles read the id off the row and
     * skip the search entirely. The track carries its own `playlistId` + `position`,
     * so no per-playlist closure rebind is needed — the per-cycle coordinator
     * persists to the right row directly. Column mapping mirrors [applyResolvedId].
     */
    private fun newHydrationCoordinator(): HydrationCoordinator =
        HydrationCoordinator(
            cache = trackProviderIdCacheDao,
            persistRowId = { track, providerId, nativeId ->
                when (providerId) {
                    SpotifySyncProvider.PROVIDER_ID -> playlistTrackDao.backfillResolverIds(
                        playlistId = track.playlistId,
                        position = track.position,
                        spotifyId = nativeId.removePrefix("spotify:track:").takeIf { it.isNotBlank() },
                        spotifyUri = nativeId,
                        appleMusicId = null,
                        soundcloudId = null,
                        recordingMbid = null,
                    )
                    AppleMusicSyncProvider.PROVIDER_ID -> playlistTrackDao.backfillResolverIds(
                        playlistId = track.playlistId,
                        position = track.position,
                        spotifyId = null,
                        spotifyUri = null,
                        appleMusicId = nativeId,
                        soundcloudId = null,
                        recordingMbid = null,
                    )
                    ListenBrainzSyncProvider.PROVIDER_ID -> playlistTrackDao.backfillResolverIds(
                        playlistId = track.playlistId,
                        position = track.position,
                        spotifyId = null,
                        spotifyUri = null,
                        appleMusicId = null,
                        soundcloudId = null,
                        recordingMbid = nativeId,
                    )
                    else -> { /* unknown provider — cache-only, no row column to backfill */ }
                }
            },
        )

    /**
     * Run propagation for a SINGLE playlist (dev — scoped real-write validation
     * so the user can fill one playlist without firing the whole 141-baseline
     * run). Same gates as [runNwayPropagation]; updates that playlist's entry in
     * [nwayPropagationLog] in place.
     */
    suspend fun runNwayPropagationForPlaylist(localPlaylistId: String, dryRun: Boolean = false) {
        Log.d(TAG, "N-way PROPAGATE(single): run requested for $localPlaylistId (dryRun=$dryRun)")
        if (!settingsStore.isNwayEnabled()) return
        if (!dryRun && !settingsStore.isNwayPropagateEnabled()) {
            Log.d(TAG, "N-way PROPAGATE(single): skipped — real writes not enabled")
            return
        }
        val baseline = syncPlaylistBaselineDao.selectForLocal(localPlaylistId) ?: run {
            Log.d(TAG, "N-way PROPAGATE(single): no baseline for $localPlaylistId")
            return
        }
        val remoteLists = fetchEnabledProviderPlaylistLists()
        val coordinator = newHydrationCoordinator()
        val entry = try {
            propagateReconcilePlaylist(baseline, remoteLists, coordinator, dryRun)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "N-way PROPAGATE(single): skipped '$localPlaylistId' (${e.message})")
            null
        } ?: return
        val cur = _nwayPropagationLog.value
        _nwayPropagationLog.value =
            if (cur.any { it.localPlaylistId == localPlaylistId }) {
                cur.map { if (it.localPlaylistId == localPlaylistId) entry else it }
            } else {
                cur + entry
            }
    }

    /**
     * Pending-detection bridge for the merge augmentation: is the track behind
     * representative [representativeKey] PENDING (un-materialized) on [providerId]?
     *
     * The merge keyspace (representative keys) can be ISRC-tier, but the hydration
     * cache is keyed by [canonicalTrackKey] of a [PlaylistTrack] — which IGNORES
     * isrc (mbid→norm only) — so a representative key can't index the cache
     * directly. Bridge: representativeKey → [keyToTrack] (the concrete track) →
     * `canonicalTrackKey(track)` → `cache.select(thatKey, providerId)`.
     *
     * Cache-key collision residual (honest acknowledgement): because the cache key
     * is `canonicalTrackKey(track)` (mbid→norm, ISRC-ignored), two DISTINCT merge
     * representative keys that collapse to the same `norm-artist|title` (e.g. two
     * no-MBID recordings with identical artist|title) index the SAME cache row —
     * inheriting the accepted, fixture-pinned `norm`-tier collision residual that
     * `unifyTrackKeys`/`canonicalTrackKey` already document. The harm is bounded
     * (rare within a single playlist; never drops a present track via this path).
     *
     * PENDING (return true) = cache entry ABSENT or its `resolvedId` is null. This
     * is the conservative SAFE default — without positive evidence the provider
     * ever materialized this track (a non-null `resolvedId`), we must NOT let its
     * absence drive a deletion. A confirmed-materialized track (resolvedId
     * non-null) that's now absent is a GENUINE deletion → return false → delete-
     * wins drops it. A key with no resolvable track (shouldn't happen for a
     * baseline key, but defensive) is treated as pending so it's never deleted.
     */
    private suspend fun isProviderPendingForKey(
        providerId: String,
        representativeKey: String,
        keyToTrack: Map<String, PlaylistTrack>,
    ): Boolean {
        val track = keyToTrack[representativeKey] ?: return true
        val cacheKey = canonicalTrackKey(track)
        val entry = trackProviderIdCacheDao.select(cacheKey, providerId)
        return entry?.resolvedId.isNullOrBlank()
    }

    private suspend fun propagateReconcilePlaylist(
        baseline: SyncPlaylistBaselineDao.Baseline,
        remoteLists: Map<String, Map<String, SyncedPlaylist>>,
        coordinator: HydrationCoordinator,
        dryRun: Boolean,
    ): NwayPropagationEntry? {
        val localId = baseline.localPlaylistId
        val playlist = playlistDao.getById(localId) ?: return null
        val mirrors = getPlaylistMirrors(localId)
        if (mirrors.isEmpty()) return null
        val storedTokens = syncPlaylistNwayDao.selectForLocal(localId).associateBy { it.providerId }
        val now = currentTimeMillis()

        // Gather each copy WITH its tracks (local always; CHANGED providers fetched;
        // unchanged providers reuse the baseline keys, no fetch, no tracks).
        val inputs = mutableListOf<PropagationCopyInput>()
        val localTracks = playlistTrackDao.getByPlaylistIdSync(localId).sortedBy { it.position }
        val localKeys = localTracks.map {
            trackKeysOf(isrc = it.trackIsrc, recordingMbid = it.trackRecordingMbid, artist = it.trackArtist, title = it.trackTitle)
        }
        val localChanged = playlist.locallyModified || playlist.lastModified > baseline.baselineSyncedAt
        inputs.add(PropagationCopyInput("local", localTracks, localKeys, playlist.lastModified, localChanged))

        for ((providerId, externalId) in mirrors) {
            val provider = providers.firstOrNull { it.id == providerId } ?: continue
            val listEntry = remoteLists[providerId]?.get(externalId)
            val storedToken = storedTokens[providerId]?.changeToken
            val changed = when {
                listEntry == null -> false // not in the owned list (deleted/unowned) — skip
                listEntry.snapshotId != null -> listEntry.snapshotId != storedToken
                else -> listEntry.trackCount != baseline.tracks.size
            }
            if (!changed) {
                inputs.add(PropagationCopyInput(providerId, emptyList(), baseline.tracks, now, changed = false))
                continue
            }
            val tracks = provider.fetchPlaylistTracks(externalId)
            // EMPTY-MIRROR rule: a 0-track mirror against a non-empty baseline is
            // treated as UNPOPULATED (a fill target), NOT as a deletion of every
            // baseline track. Otherwise its emptiness drags the 3-way merge toward
            // 0 and trips the mass-change guard — the phantom-deletion that strands
            // a mirror an earlier failed/partial push left empty (the real-device
            // incident). changed=false excludes it from deletion deltas; keys=[]
            // (its true empty state) keeps it a push target so it gets filled. This
            // is consistent with the mass-change guard (we never honor a near-total
            // deletion anyway). A PARTIALLY-populated mirror still contributes its
            // deltas normally — only the fully-empty case is special-cased.
            if (tracks.isEmpty() && baseline.tracks.isNotEmpty()) {
                Log.d(TAG, "N-way PROPAGATE [${playlist.name}]: $providerId mirror is empty — treating as " +
                    "fill target, not a deletion")
                inputs.add(PropagationCopyInput(providerId, emptyList(), emptyList(), now, changed = false))
                continue
            }
            val keys = tracks.map {
                trackKeysOf(isrc = it.trackIsrc, recordingMbid = it.trackRecordingMbid, artist = it.trackArtist, title = it.trackTitle)
            }
            inputs.add(PropagationCopyInput(providerId, tracks, keys, now, changed = true))
        }

        // Cross-copy key unification (same as shadow) so the same song matches
        // across services before the merge runs on the representative keys.
        val unified = unifyTrackKeys(listOf(baseline.tracks) + inputs.map { it.keys })
        val baselineRepr = unified[0]
        val copies = inputs.mapIndexed { i, c -> NwayCopyState(c.id, unified[i + 1], c.editedAt, c.changed) }

        // Per-copy writability: only the canonical SOURCE copy (the id-prefix
        // provider) is gated on the playlist's `writable` flag — a followed
        // (read-only) source must not receive merged changes back. `local` and
        // every other (owned/collaborative) mirror stay writable. #269: this is
        // exactly what keeps followed playlists mirroring OUT but never back IN.
        val sourcePrefix = localId.substringBefore('-')
        val writableById = inputs.associate { c ->
            c.id to if (c.id != "local" && c.id == sourcePrefix) playlist.writable else true
        }

        // ── PULL-SOURCE AUTHORITY (Daily Brew / SiriusXMU rotate-out fix) ───────────
        // The authoritative PULL SOURCE has the authority to DROP a baseline track:
        // when a churning source (Spotify Daily Brew rotates ~40/day; hosted-XSPF
        // replaces daily) rotates a track OUT, that absence must read as a genuine
        // deletion — NOT be re-added by the pending augmentation. Add-only mirrors
        // (Apple Music) still keep genuine catalog-gap tracks because the augmentation
        // stays ON for them.
        //
        //   • sync_playlist_source.providerId's copy is authoritative for an imported
        //     playlist (use the source ROW, not the id-prefix — a cross-provider push
        //     mirror's prefix can differ from the real pull source).
        //   • the LOCAL copy is authoritative for a hosted-XSPF playlist (sourceUrl).
        //   • NONE for a local-authored multimaster playlist → augment all (unchanged).
        //
        // Authority is granted ONLY when the source is REMOVAL-CAPABLE. An add-only
        // source (TrackRemoveMode.Unsupported, e.g. an AM-imported playlist) cannot
        // legitimately "drop" a track, and a transient PARTIAL fetch from it would
        // otherwise read as deletions with no safety net (the empty-mirror rule and
        // the total-wipe floor only catch collapse-to-zero) — so it falls back to
        // augment-all.
        val pullSource = syncPlaylistSourceDao.selectForLocal(localId)
        val authoritativeCopyId: String? = when {
            pullSource != null -> {
                val sourceProvider = providers.firstOrNull { it.id == pullSource.providerId }
                if (sourceProvider != null &&
                    sourceProvider.features.trackRemoveMode != TrackRemoveMode.Unsupported
                ) pullSource.providerId else null
            }
            playlist.sourceUrl != null -> "local"   // hosted XSPF — local copy is the truth
            else -> null                            // local-authored multimaster — augment all
        }

        // The set of baseline keys the AUTHORITATIVE copy itself dropped this cycle.
        // These must NEVER be re-augmented onto ANY copy (a source deletion is final).
        // Computed only when the authoritative copy is CHANGED (an unchanged copy reuses
        // baseline.tracks, so it dropped nothing). Keyed off the copy's actual keys, NOT
        // the pending cache.
        val authoritativeCopy = authoritativeCopyId?.let { aid ->
            copies.firstOrNull { it.id == aid }?.takeIf { it.changed }
        }
        val authoritativeDropped: Set<String> =
            authoritativeCopy?.let { ac -> baselineRepr.toSet() - ac.keys.toSet() } ?: emptySet()

        // Trap 1 — resolve representative keys → tracks. Build keyToTrack from the
        // copies that carry real tracks (local FIRST so its richer metadata wins).
        // Moved AHEAD of the merge so the pending-aware augmentation below can
        // bridge a representative key → its concrete track → the hydration cache
        // (the cache is keyed by canonicalTrackKey(track), which IGNORES isrc, so a
        // representative key — possibly isrc-tier — can't be looked up directly).
        val keyToTrack = linkedMapOf<String, PlaylistTrack>()
        // ISRC backfill map: the FIRST non-blank ISRC seen for a representative
        // key across ANY copy. keyToTrack prefers local (richer metadata) but
        // local DB rows carry no ISRC (it's transient), so without this the ISRC →
        // MusicBrainz `/isrc/` fallback can't fire for a track that also exists
        // locally. Spotify's freshly-fetched copy DOES carry external_ids.isrc, so
        // harvest it here and graft it onto the merged track below.
        val keyToIsrc = linkedMapOf<String, String>()
        inputs.forEachIndexed { i, c ->
            val reprKeys = unified[i + 1]
            c.tracks.forEachIndexed { j, t ->
                val key = reprKeys[j]
                if (key !in keyToTrack) keyToTrack[key] = t
                val isrc = t.trackIsrc
                if (!isrc.isNullOrBlank() && key !in keyToIsrc) keyToIsrc[key] = isrc
            }
        }

        // ── PENDING-AWARE MERGE AUGMENTATION (catalog-gap phantom-deletion fix) ──
        // The merge consumes each CHANGED provider's fetched tracklist. A track a
        // provider couldn't MATERIALIZE (an un-hydratable ADD left PENDING) is, to
        // the merge, indistinguishable from one the user DELETED there — delete-
        // always-wins reads its absence as a deletion and drops it from canonical
        // → removed everywhere (the P1b catalog-gap phantom deletion). The executor
        // protects the WRITE (a pending add never drives a remove), but the MERGE
        // runs UPSTREAM and would over-delete without this.
        //
        // SAFETY INVARIANT: a provider's absence of a track is a DELETION only if we
        // have POSITIVE evidence the provider HAD it — the hydration cache's
        // non-null resolvedId. So for each CHANGED provider copy, re-add the
        // baseline representative keys it LACKS *only where that track is PENDING
        // for it* (cache entry absent OR resolvedId null). A confirmed-materialized
        // (resolvedId non-null) absence is a GENUINE deletion → NOT re-added → still
        // dropped by delete-wins. The provider's OTHER genuine adds/removes still
        // flow (it stays changed=true with its real keys), so this resolves the
        // trilemma: suppress phantom deletes, honor real deletes, propagate adds.
        val augmentedCopies = copies.mapIndexed { i, copy ->
            val input = inputs[i]
            // Skip: local (always), unchanged copies, AND the authoritative source copy —
            // the source's missing baseline keys are GENUINE deletions, not pending fills.
            if (input.id == "local" || input.id == authoritativeCopyId || !copy.changed) {
                return@mapIndexed copy
            }
            val present = copy.keys.toSet()
            val lacked = baselineRepr.filter { it !in present }
            if (lacked.isEmpty()) return@mapIndexed copy
            val pendingLacked = lacked.filter { key ->
                // Never re-augment a key the authoritative source deleted — even onto a
                // mirror that's pending on it. A source deletion wins over a mirror's
                // pending-protection (the AM-residue closer).
                key !in authoritativeDropped &&
                    isProviderPendingForKey(input.id, key, keyToTrack)
            }
            if (pendingLacked.isEmpty()) copy
            else copy.copy(keys = copy.keys + pendingLacked)
        }

        // The merge runs on the AUGMENTED copies (phantom deletes suppressed); the
        // resulting `merged` is canonical. A provider augmented to equal the
        // baseline may now contribute no delta (filtered from the merge) — its
        // genuine lag is recovered below from the UN-augmented copies, decoupling
        // the materialize-target decision from the merge.
        val plan = computeNwayPropagationPlan(baselineRepr, augmentedCopies, writableById)
        if (plan?.massChangeAbort == true) {
            // Total-wipe floor only: the merge collapsed a non-empty playlist to
            // ZERO. Large non-empty turnover is NOT blocked (propagate-all-changes).
            Log.d(TAG, "N-way PROPAGATE [${playlist.name}]: TOTAL-WIPE (merge → 0 tracks) — abort, no push")
            return NwayPropagationEntry(playlist.name, localId, plan.merged.size, plan.pushTargets, "total-wipe-abort")
        }

        // Canonical = the merge's output when there's a delta; otherwise the
        // baseline (a pending-only fill re-attempt converged the merge to baseline).
        val mergedRepr = plan?.merged ?: baselineRepr

        // MATERIALIZE TARGETS — decoupled from the merge. A WRITABLE copy is a
        // target iff its CURRENT (un-augmented) representative keys lag the
        // canonical: i.e. it's missing a canonical track (a pending fill to
        // re-attempt now the catalog may have recovered) OR it carries a track the
        // canonical dropped (a delete to propagate). Augmentation hides a pending
        // provider's lag from the merge's wouldPushTo; computing the lag from the
        // un-augmented keys recovers it so the executor re-attempts the fill. The
        // executor fetches each mirror's ACTUAL remote and diffs, so a converged
        // mirror no-ops and an un-findable add stays pending (coordinator cooldown).
        val mergedReprSet = mergedRepr.toSet()
        val pushTargets = copies.filter { c ->
            writableById[c.id] != false && (c.keys.toSet() != mergedReprSet || c.keys.size != mergedRepr.size)
        }.map { c -> c.id }

        // TRULY-CONVERGED SHORT-CIRCUIT — no merge delta AND no writable mirror
        // lags ⇒ idempotent no-op: don't fetch, don't materialize, don't advance.
        // (Keeps `idempotency ×2` green + avoids per-cycle fetches on a converged
        // playlist.) When the plan IS non-null there's a real delta; when a mirror
        // lags there's a pending fill / pending delete to apply — proceed in both.
        if (plan == null && pushTargets.all { it == "local" }) return null

        val resolved = mergedRepr.map { keyToTrack[it] }
        if (resolved.any { it == null }) {
            Log.d(TAG, "N-way PROPAGATE [${playlist.name}]: ${resolved.count { it == null }} merged " +
                "key(s) unresolved to a track — abort (never push a partial list)")
            return NwayPropagationEntry(playlist.name, localId, mergedRepr.size, pushTargets, "partial-abort")
        }
        val mergedTracks = mergedRepr.mapIndexed { idx, key ->
            val t = keyToTrack.getValue(key)
            // Graft the harvested ISRC (if the chosen track lacks one) so LB
            // hydration's /isrc/ fallback works during a mapper outage.
            t.copy(playlistId = localId, position = idx, trackIsrc = t.trackIsrc ?: keyToIsrc[key])
        }

        if (dryRun) {
            Log.d(TAG, "N-way PROPAGATE (DRY-RUN) [${playlist.name}]: would push ${mergedTracks.size} " +
                "track(s) to $pushTargets [no write]")
            return NwayPropagationEntry(playlist.name, localId, mergedTracks.size, pushTargets, "would-push")
        }

        // ── PUSH (NON-DESTRUCTIVE INCREMENTAL MATERIALIZE) ──────────────
        // Apply the merged canonical to each WRITABLE target via the per-provider,
        // capability-dispatched [materializeToProvider]. Each target is independent
        // (an Apple-Music catalog miss never blocks ListenBrainz).
        //
        // Why this is whole-cycle-safe WITHOUT a coverage SKIP / fill-target marker
        // / replace-all (all of which this swap deletes): the executor's removes come
        // ONLY from a POSITIVE identity-diff (a REMOTE track absent from canonical),
        // so a track the provider can't hydrate is left a PENDING ADD — the remote
        // keeps everything else, never shrinks. A catalog gap is therefore inherently
        // non-destructive every cycle; the baseline can advance unconditionally
        // (materialization completeness is tracked PER-TRACK via the diff + the
        // hydration cache, not via pinning the whole-playlist baseline). The
        // coordinator's per-cycle budget + cooldown keep an un-findable ADD from
        // re-searching every cycle.
        var added = 0
        var removed = 0
        var pendingAdds = 0
        var unsupportedRemoves = 0
        val pushedTo = mutableListOf<String>()
        for (targetId in pushTargets) {
            if (targetId == "local") { pushedTo.add("local"); continue }
            val provider = providers.firstOrNull { it.id == targetId } ?: continue
            val externalId = mirrors[targetId] ?: continue
            // Each target is materialized in its OWN try/catch so ONE provider's
            // failure (e.g. Apple Music HTTP 400 when its mirror is stale/404) only
            // skips THAT provider — the others (LB, Spotify) still materialize. The
            // comment above ("an Apple-Music catalog miss never blocks ListenBrainz")
            // is true for coverage MISSES via the non-destructive diff; this guard
            // extends the same isolation to thrown EXCEPTIONS. A thrown provider is
            // left un-materialized = it lags canonical next cycle (the hydration cache
            // has no confirmed entry ⇒ its missing tracks read as PENDING, not a
            // deletion), so it's safely retried and the baseline can still advance.
            try {
                val remote = provider.fetchPlaylistTracks(externalId)
                val res = materializeToProvider(
                    provider = provider,
                    externalId = externalId,
                    canonical = mergedTracks,
                    remote = remote,
                    resolveNativeId = { track -> coordinator.resolve(provider, track) },
                )
                added += res.added
                removed += res.removed
                pendingAdds += res.pendingAdds
                unsupportedRemoves += res.unsupportedRemoves
                // Refresh the per-provider change token for echo suppression. A snapshot
                // provider (Spotify/AM) returns its fresh token; a snapshot-less provider
                // (LB) returns null → next-cycle detection falls back to trackCount, which
                // now equals the merged size → no re-detect. On a fetch failure here we
                // leave the old token; the next cycle re-detects and re-materializes
                // (idempotent — the diff yields no ops once converged).
                val token = runCatching { provider.getPlaylistSnapshotId(externalId) }.getOrNull()
                syncPlaylistNwayDao.upsert(localId, provider.id, token, now, now)
                pushedTo.add(provider.id)
                Log.d(TAG, "N-way PROPAGATE [${playlist.name}]: ${provider.id} materialized " +
                    "+$added/-$removed (pending $pendingAdds, unsupported-remove $unsupportedRemoves)")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "N-way PROPAGATE [${playlist.name}]: ${provider.id} materialize " +
                    "failed — skipping this provider, others continue", e)
                // SELF-HEAL: a materialize failure CAN mean the remote mirror was
                // deleted on the provider's side (Apple Music returns HTTP 400/404
                // on a stale/404 mirror, and the per-provider isolation above just
                // skips it — so the dead link would persist forever and that
                // provider stays permanently empty for this playlist). Probe the
                // provider for a DEFINITIVE deletion signal (a 404 on the playlist
                // RESOURCE). If — and ONLY if — it's definitively gone, clear the
                // dead link + N-way token so the next sync's create-or-link path
                // mints a fresh mirror. The probe is conservative by contract
                // (returns true on any transient/ambiguous error); we additionally
                // guard it here so a probe failure can never throw out of the loop
                // or be mistaken for "gone".
                val mirrorGone = try {
                    !provider.remotePlaylistExists(externalId)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (probe: Exception) {
                    false
                }
                if (mirrorGone) {
                    Log.w(TAG, "N-way PROPAGATE [${playlist.name}]: ${provider.id} mirror " +
                        "$externalId is GONE (404) — clearing dead link for recreation next sync")
                    syncPlaylistLinkDao.deleteForLink(localId, provider.id)
                    syncPlaylistNwayDao.deleteForLink(localId, provider.id)
                }
            }
        }

        // Persist the merged list to the LOCAL rows. The coordinator already
        // additively backfilled any freshly-resolved provider ids onto these rows
        // (persistRowId), so the merged list reflects local identity + ordering; the
        // ids carry forward for future cycles via the row + the hydration cache.
        playlistTrackDao.replaceAll(localId, mergedTracks)

        // Advance the baseline + clear locallyModified EVERY cycle. Decoupled from
        // any "all covered" gate (deleted): the executor is non-destructive, so a
        // catalog gap leaves the provider holding a coverable subset with the missing
        // track tracked as a PENDING ADD (re-attempted, budget-permitting, next cycle
        // via the hydration cache) — never a phantom deletion when the baseline
        // advances. Pinning the baseline is no longer how completeness is enforced.
        syncPlaylistBaselineDao.upsert(localId, nwayBaselineTrackKeys(mergedTracks), now)
        playlistDao.clearLocallyModified(localId)
        return NwayPropagationEntry(
            playlist.name, localId, mergedTracks.size, pushedTo, "pushed",
            pendingAdds = pendingAdds, unsupportedRemoves = unsupportedRemoves,
        )
    }

    private suspend fun syncPlaylists(
        settings: SyncSettings,
        onProgress: (SyncProgress) -> Unit,
        providerFilter: String? = null,
    ): TypeSyncResult {
        val providerId = SpotifySyncProvider.PROVIDER_ID
        var added = 0
        var removed = 0
        var updated = 0
        var unchanged = 0

        // Idempotent startup migration: make sure any playlist that already
        // has a `spotifyId` set has a matching row in `sync_playlist_link`.
        // Cheap (single query + conditional upsert) and guarantees the
        // durable map is populated even if older syncs wrote `spotifyId`
        // without writing a link. Mirrors desktop's
        // migrateSyncLinksFromPlaylists (main.js).
        migrateLinksFromPlaylists()

        // One-time idempotent cleanup: the N-way swap deleted the
        // NWAY_FILL_PENDING_ACTION="nway-fill" writer, but stale 'nway-fill'
        // values linger in sync_playlist_link.pendingAction on real installs.
        // The legacy push (`pushPlaylistsForProvider`) skips any non-null
        // pendingAction, so a stale marker permanently strands the playlist
        // (never syncs while N-way real-writes are default-OFF). Null them out.
        syncPlaylistLinkDao.clearStaleNwayFillMarkers()

        // Sibling backfill for the per-playlist pull-source table. Any
        // `spotify-<externalId>` playlist row from a prior import that
        // predates Phase 1's `sync_playlist_source` table gets a
        // `syncedFrom` row so future pull cycles have a stable key beyond
        // the id-prefix convention.
        migrateSourceFromPlaylists(db, syncPlaylistSourceDao)

        // Heal `spotify-*` / `applemusic-*` rows whose
        // `sync_playlist_source.providerId` doesn't match the id prefix.
        // Mirrors desktop's `healImportedSyncedFromMismatch` (runs in
        // main.js alongside migrateSyncLinksFromPlaylists). Cross-
        // platform invariant: when one client has a sync bug that flips
        // a Spotify-imported playlist's syncedFrom to applemusic, the
        // OTHER client's launch must silently restore it. Without this
        // on Android, an Android regression corrupts a fleet and only
        // desktop heals.
        //
        // Runs AFTER migrateSourceFromPlaylists so the backfill has
        // populated any missing rows from `spotifyId`, and BEFORE
        // migrateMergeCrossProviderDuplicates so the dedup pass sees
        // the corrected providerId values.
        healImportedSyncedFromMismatch(
            playlistDao,
            syncPlaylistSourceDao,
            syncPlaylistLinkDao,
        )

        // N-way migration bootstrap (Phase 2) — gated on isNwayEnabled (OFF by
        // default → inert). Seeds the baseline + per-provider state for every
        // synced playlist the first time N-way activates. Runs AFTER the
        // link/source migrations so mirrors are resolved; read-only vs providers
        // (the normalize push is deferred to the propagation phase).
        migrateAllPlaylistsToNway()

        // N-way SHADOW MODE (Phase 3) is dev-triggered ON DEMAND via
        // [runNwayShadowScan] — NOT run inline here. Per-sync shadow would add a
        // playlist-list fetch (and, pre-fix, a per-playlist tracklist fetch) to
        // every background sync; keeping it on-demand means routine syncs pay
        // nothing for it. Phase 4 integrates real propagation into the sync.

        // One-shot cleanup for installs that pre-date the cross-provider
        // pull-path name-match (commit ef5a3c2). Walks all playlist
        // rows, finds duplicates by name across providers, merges them
        // into a single canonical row. Runs at most once per install.
        migrateMergeCrossProviderDuplicates()

        // Self-healing trackCount reconciliation. Earlier AM pulls
        // inserted Playlist rows with trackCount=0 (AM's library
        // playlist response always carries 0; the field doesn't exist
        // on the response). Later pulls would only update trackCount
        // via pullPlaylist (which runs on snapshot mismatch). Steady-
        // state matched-snapshot rows therefore display "0 tracks" in
        // the playlist list even when playlist_tracks actually has
        // rows. Cheap to run every cycle — single SQL pass over the
        // playlists table joined with the playlist_tracks counts.
        reconcileTrackCounts()

        // Self-healing AM artwork URL repair. Earlier AM pulls stored
        // raw `{w}x{h}` placeholder URLs from Apple's response; Coil
        // can't fetch those, so playlist mosaics never generated
        // (because the per-track artwork URLs they composed from
        // weren't fetchable). Now resolveArtworkUrl substitutes
        // 600x600 at write time, but existing rows still carry the
        // raw placeholders. Fix them in place — cheap and idempotent.
        repairAppleMusicArtworkPlaceholders()

        // ── Spotify pull (existing block; preserves dedup-cleanup migration) ──
        // The dedup-cleanup migration block below is Spotify-only by design
        // (one-time fix for playlists created by old Spotify sync bugs); it
        // can't be generalized so we keep the Spotify pull inline rather
        // than duplicating the whole helper. When Spotify has opted out
        // of the playlists axis (per-provider opt-in via the wizard),
        // skip the fetch entirely — the push and non-Spotify-pull loops
        // below have their own per-axis gates.
        // Gate the Spotify pull on the enabled set too (not just the axis) — a
        // connected-but-not-enabled Spotify must not pull playlists. The removal
        // cleanup below is ALSO gated on this flag: skipping the fetch leaves
        // `remotePlaylists` empty, and an empty remote must NOT be read as
        // "everything was deleted remotely" (that would wipe the user's Spotify
        // playlist rows).
        val spotifyPlaylistsEnabled = SpotifySyncProvider.PROVIDER_ID in settingsStore.getEnabledSyncProviders()
            && providerHasAxis(SpotifySyncProvider.PROVIDER_ID, "playlists")
            && (providerFilter == null || providerFilter == SpotifySyncProvider.PROVIDER_ID)
        val remotePlaylists = if (spotifyPlaylistsEnabled) {
            spotifyProvider.fetchPlaylists { current, total ->
                onProgress(SyncProgress(SyncPhase.PLAYLISTS, current, total, "Fetching playlists..."))
            }
        } else emptyList()

        // #269 self-heal: refresh writable for EVERY pulled Spotify playlist. The
        // per-playlist reconcile below only sets writable on the create path and
        // skips unchanged existing rows — so a followed playlist (writable=false)
        // would otherwise keep its stale default writable=1 and keep mirroring.
        // Cheap targeted update; runs even on a no-op sync.
        for (remote in remotePlaylists) {
            val existing = playlistDao.getBySpotifyId(remote.spotifyId)
            if (existing != null && existing.writable != remote.entity.writable) {
                playlistDao.setWritable(existing.id, remote.entity.writable)
                Log.d(TAG, "#269: refreshed writable=${remote.entity.writable} for '${existing.name}'")
            }
        }

        // Pull allowlist (per-provider) + per-playlist channel override. The
        // override is authoritative for a given playlist; otherwise the allowlist
        // (empty = import all) applies.
        val spotifyAllow = settingsStore.getPullPlaylists(SpotifySyncProvider.PROVIDER_ID)
        val selectedRemote = remotePlaylists.filter {
            val override = settingsStore.getPlaylistChannels("spotify-${it.spotifyId}")
            if (override != null) SpotifySyncProvider.PROVIDER_ID in override
            else spotifyAllow.isEmpty() || it.spotifyId in spotifyAllow
        }

        val localSources = syncSourceDao.getByProvider(providerId, "playlist")
        val localByExternalId = localSources.associateBy { it.externalId }

        for ((index, remote) in selectedRemote.withIndex()) {
            onProgress(SyncProgress(
                SyncPhase.PLAYLISTS,
                index + 1,
                selectedRemote.size,
                "Syncing playlist: ${remote.entity.name}",
            ))
            val existingSource = localByExternalId[remote.spotifyId]

            if (existingSource == null) {
                val now = currentTimeMillis()
                // Check if a playlist with this spotifyId already exists under a
                // different primary key (e.g. pushed local playlist or import).
                // If so, merge into the existing entry to avoid duplicates.
                var existingBySpotifyId = playlistDao.getBySpotifyId(remote.spotifyId)

                // Cross-provider name-match fallback. If Spotify's pull
                // sees "Workout 2024" but no Spotify-side sync_source
                // exists, and an AM-pulled local row already has that
                // exact name, link Spotify to the existing local row
                // instead of creating a duplicate `spotify-X` next to
                // `applemusic-Y`.
                if (existingBySpotifyId == null) {
                    val nameMatch = findCrossProviderNameMatch(remote.entity.name, providerId)
                    if (nameMatch != null) {
                        Log.d(TAG, "Pull name-match: Spotify remote '${remote.entity.name}' " +
                            "absorbed by existing local ${nameMatch.id}")
                        existingBySpotifyId = nameMatch
                    }
                }
                val targetId = existingBySpotifyId?.id ?: remote.entity.id

                // Phase 3 — cross-provider syncedFrom preservation.
                // If the local row we're matching already has a `syncedFrom`
                // pointing at a DIFFERENT provider, this match fired because
                // the local is a push target for the current provider — its
                // pull source is elsewhere. Don't clobber syncedFrom and
                // don't refetch tracks (the source provider is authoritative).
                // Only the sync_playlist_link's syncedAt advances. Without
                // this guard, the original pull-source provider would see the
                // local as a new playlist on its next sync and create a
                // duplicate remote.
                //
                // **Hosted-XSPF preservation (bugfix Apr 2026):** hosted XSPF
                // playlists are *also* push-mirror targets — the XSPF URL is
                // canonical (poller-driven), and Spotify/AM remotes are
                // downstream mirrors via the auto-push filter. They mark
                // canonicality with `sourceUrl != null` instead of a
                // `sync_playlist_source` row, so the existing pull-source
                // check missed them. Without this guard, the import path
                // matched the hosted-XSPF local row by name (because the
                // pushed Spotify/AM mirror has the same name), then deleted
                // the local row + tracks and re-inserted using the remote's
                // entity (sourceUrl=null, often 0 tracks if the push
                // hadn't hydrated track IDs yet). Result: hosted playlist
                // disappears, replaced by an empty `spotify-*` / `applemusic-*`
                // row with no `🌐 Hosted` chip.
                val existingPullSource = if (existingBySpotifyId != null) {
                    syncPlaylistSourceDao.selectForLocal(existingBySpotifyId.id)
                } else null
                val isCrossProviderPushMirror = existingPullSource != null
                    && existingPullSource.providerId != providerId
                val isHostedXspf = existingBySpotifyId?.sourceUrl != null
                val preserveLocal = isCrossProviderPushMirror || isHostedXspf

                if (existingBySpotifyId != null && existingBySpotifyId.id != remote.entity.id && !preserveLocal) {
                    // Remove the old entry's tracks — they'll be replaced below
                    playlistTrackDao.deleteByPlaylistId(existingBySpotifyId.id)
                    playlistDao.delete(existingBySpotifyId)
                    // Clean up any orphaned sync source for the old ID
                    syncSourceDao.deleteAllForItem(existingBySpotifyId.id, "playlist")
                }

                if (preserveLocal) {
                    // Preserve the existing local row + tracks + sourceUrl /
                    // syncedFrom. Just record that we synced with this
                    // provider as a push mirror by updating
                    // sync_playlist_link.syncedAt.
                    val mirrorReason = when {
                        isHostedXspf -> "sourceUrl=${existingBySpotifyId!!.sourceUrl}"
                        else -> "syncedFrom=${existingPullSource!!.providerId}"
                    }
                    Log.d(TAG, "Cross-provider push mirror for ${existingBySpotifyId!!.id}: " +
                        "$mirrorReason; preserving local tracks")
                    syncPlaylistLinkDao.upsertWithSnapshot(
                        localPlaylistId = existingBySpotifyId.id,
                        providerId = providerId,
                        externalId = remote.spotifyId,
                        snapshotId = remote.snapshotId,
                    )
                    syncSourceDao.insert(SyncSource(
                        itemId = existingBySpotifyId.id,
                        itemType = "playlist",
                        providerId = providerId,
                        externalId = remote.spotifyId,
                        syncedAt = currentTimeMillis(),
                    ))
                    unchanged++
                    continue
                }

                playlistDao.insert(remote.entity.copy(
                    id = targetId,
                    createdAt = existingBySpotifyId?.createdAt ?: now,
                    updatedAt = now,
                    lastModified = now,
                ))
                val tracks = spotifyProvider.fetchPlaylistTracks(remote.spotifyId)
                playlistTrackDao.deleteByPlaylistId(targetId)
                val remappedTracks = if (targetId != remote.entity.id) {
                    tracks.map { it.copy(playlistId = targetId) }
                } else {
                    tracks
                }
                playlistTrackDao.insertAll(remappedTracks)
                syncSourceDao.insert(SyncSource(
                    itemId = targetId,
                    itemType = "playlist",
                    providerId = providerId,
                    externalId = remote.spotifyId,
                    syncedAt = currentTimeMillis(),
                ))
                // Standard pull-source path: record syncedFrom for this
                // provider so subsequent imports recognize it as own source
                // (and Phase 4's AM-import branch correctly identifies us
                // as a cross-provider push mirror at that point).
                syncPlaylistSourceDao.upsert(
                    localPlaylistId = targetId,
                    providerId = providerId,
                    externalId = remote.spotifyId,
                    snapshotId = remote.snapshotId,
                    ownerId = null,
                    syncedAt = currentTimeMillis(),
                )
                added++
            } else {
                val localPlaylist = playlistDao.getById(existingSource.itemId)
                if (localPlaylist == null) {
                    unchanged++
                    continue
                }

                val remoteSnapshotId = remote.snapshotId
                val localSnapshotId = localPlaylist.snapshotId
                val localModified = localPlaylist.locallyModified
                val isHosted = localPlaylist.sourceUrl != null

                // Self-heal for "Spotify-sourced playlist with 0 actual
                // tracks" (Daily Brew, Daily Mix, etc. that AM clobbered
                // before the cross-provider push-mirror guard landed).
                // Force a refetch from Spotify even if snapshot matches
                // — Spotify is the canonical source, and we trust it to
                // give us non-empty tracks.
                val pullSource = syncPlaylistSourceDao.selectForLocal(localPlaylist.id)
                val ownPullSource = pullSource == null
                    || pullSource.providerId == providerId
                val localTrackCount = playlistTrackDao.getByPlaylistIdSync(localPlaylist.id).size
                val needsTrackRecovery = ownPullSource && localTrackCount == 0
                    && remote.trackCount > 0

                when {
                    // Hosted XSPF: XSPF is canonical. Only push (when the
                    // poller flipped locallyModified); never pull, because the
                    // next poll tick would revert any pulled Spotify state
                    // within 5 minutes anyway. Desktop parity.
                    isHosted && localModified -> {
                        pushPlaylist(localPlaylist, spotifyProvider)
                        updated++
                    }
                    isHosted -> {
                        syncSourceDao.insert(existingSource.copy(syncedAt = currentTimeMillis()))
                        unchanged++
                    }
                    localModified && remoteSnapshotId != localSnapshotId -> {
                        val localIsNewer = localPlaylist.lastModified > existingSource.syncedAt
                        if (localIsNewer) {
                            pushPlaylist(localPlaylist, spotifyProvider)
                        } else {
                            pullPlaylist(localPlaylist, remote)
                        }
                        updated++
                    }
                    localModified -> {
                        pushPlaylist(localPlaylist, spotifyProvider)
                        updated++
                    }
                    remoteSnapshotId != localSnapshotId || needsTrackRecovery -> {
                        if (needsTrackRecovery) {
                            Log.d(TAG, "Self-heal: Spotify playlist " +
                                "'${localPlaylist.name}' has 0 local tracks (remote=" +
                                "${remote.trackCount}); forcing refetch despite snapshot match")
                        }
                        pullPlaylist(localPlaylist, remote)
                        updated++
                    }
                    else -> {
                        syncSourceDao.insert(existingSource.copy(syncedAt = currentTimeMillis()))
                        unchanged++
                    }
                }
            }
        }

        // One-time cleanup of duplicate playlists on Spotify created by earlier
        // sync bugs. Only runs during the v3→v4 migration, not on every sync.
        val deletedSpotifyIds = mutableSetOf<String>()
        if (settingsStore.getSyncDataVersion() < SYNC_DATA_VERSION) {
            val currentLocalSources = syncSourceDao.getByProvider(providerId, "playlist")
            val ownedByName = remotePlaylists.filter { it.isOwned }
                .groupBy { it.entity.name.lowercase() }
            val dupeGroups = ownedByName.values.filter { it.size > 1 }
            if (dupeGroups.isNotEmpty()) {
                val totalDupes = dupeGroups.sumOf { it.size - 1 }
                var dedupProgress = 0
                onProgress(SyncProgress(SyncPhase.PLAYLISTS, 0, totalDupes, "Cleaning up duplicate playlists..."))

                for (dupes in dupeGroups) {
                    val trackedIds = currentLocalSources.mapNotNull { it.externalId }.toSet()
                    val tracked = dupes.filter { it.spotifyId in trackedIds }
                    val keep = tracked.firstOrNull() ?: dupes.first()
                    for (dupe in dupes) {
                        if (dupe.spotifyId == keep.spotifyId) continue
                        try {
                            Log.d(TAG, "Removing duplicate Spotify playlist: ${dupe.entity.name} (${dupe.spotifyId})")
                            val result = spotifyProvider.deletePlaylist(dupe.spotifyId)
                            when (result) {
                                is com.parachord.shared.sync.DeleteResult.Success ->
                                    deletedSpotifyIds.add(dupe.spotifyId)
                                is com.parachord.shared.sync.DeleteResult.Unsupported -> {
                                    Log.w(TAG, "Spotify deletePlaylist returned Unsupported(${result.status}); skipping")
                                    continue
                                }
                                is com.parachord.shared.sync.DeleteResult.Failed -> {
                                    Log.w(TAG, "Spotify deletePlaylist failed for ${dupe.spotifyId}", result.error)
                                    continue
                                }
                            }
                            val orphanSource = currentLocalSources.find { it.externalId == dupe.spotifyId }
                            if (orphanSource != null) {
                                syncSourceDao.deleteByKey(orphanSource.itemId, "playlist", providerId)
                            }
                            // Prune the durable link map too so the next sync
                            // doesn't burn a validation round-trip on a
                            // known-dead externalId.
                            syncPlaylistLinkDao.deleteByExternalId(providerId, dupe.spotifyId)
                            removed++
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to remove duplicate playlist ${dupe.spotifyId}", e)
                        }
                        dedupProgress++
                        onProgress(SyncProgress(SyncPhase.PLAYLISTS, dedupProgress, totalDupes, "Cleaning up duplicate playlists..."))
                    }
                }
            }
            settingsStore.setSyncDataVersion(SYNC_DATA_VERSION)
        }

        // ── Phase 4.5: per-provider push loop ────────────────────────
        // Iterate every enabled SyncProvider; for each, push the
        // playlists eligible for that provider (see `isPushCandidate`)
        // through the three-layer dedup + create-or-link logic.
        //
        // Spotify's existing behavior is preserved exactly — same
        // candidate filter (local-* and hosted-XSPF), same dedup, same
        // post-push row update. Apple Music adds a second iteration
        // when the user has it enabled in `enabled_sync_providers`;
        // for AM the candidate set ALSO includes Spotify-imported
        // playlists since the Phase 3 syncedFrom guard correctly skips
        // any playlist whose pull source matches the current push
        // target (Spotify-imported playlists' source IS Spotify, so
        // Spotify push skips them; AM push doesn't, so they propagate
        // into AM as expected).
        if (settings.pushLocalPlaylists) {
            val enabledProviderIds = settingsStore.getEnabledSyncProviders()
            val enabledProviders = providers.filter {
                it.id in enabledProviderIds && (providerFilter == null || providerFilter == it.id)
            }
            for (provider in enabledProviders) {
                // Per-provider axis opt-in — skip push for providers that
                // didn't enable the playlists axis in their wizard.
                if (!providerHasAxis(provider.id, "playlists")) continue
                // Spotify already loaded `remotePlaylists` + `deletedSpotifyIds`
                // above for the dedup-cleanup phase; reuse those for Spotify
                // and fetch fresh for any other provider.
                val providerRemote = if (provider.id == SpotifySyncProvider.PROVIDER_ID) {
                    remotePlaylists
                } else {
                    try {
                        _syncPhase.value = "playlists · ${provider.id} fetch-remote"
                        provider.fetchPlaylists(null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch ${provider.id} playlists for push pass", e)
                        continue
                    }
                }
                val deletedExternalIds = if (provider.id == SpotifySyncProvider.PROVIDER_ID) {
                    deletedSpotifyIds
                } else emptySet()
                val pushed = pushPlaylistsForProvider(
                    provider = provider,
                    remotePlaylists = providerRemote,
                    deletedExternalIds = deletedExternalIds,
                )
                added += pushed
            }
        }

        // Handle remote removals — ONLY when the Spotify pull actually ran. If
        // Spotify is connected but not enabled (or the playlists axis is off),
        // `remotePlaylists`/`selectedRemote` are empty by design, and treating
        // that empty list as the source of truth would delete every Spotify
        // playlist row. Skip the cleanup entirely in that case.
        // Probe-gated removal (workstream B): a source absent from the FULL
        // fetched list is removed ONLY when remotePlaylistExists CONFIRMS a 404 —
        // a partial/truncated fetch (which would otherwise look like every
        // playlist vanished) removes nothing. Suspected against the full
        // `remotePlaylists`, NOT `selectedRemote`: a merely-deselected-but-present
        // playlist stays (deselection is handled by the picker's keep/remove
        // prompt), matching the non-Spotify helper's documented behavior.
        val fullRemoteIds = remotePlaylists.map { it.spotifyId }.toSet()
        val goneIds = if (spotifyPlaylistsEnabled) {
            confirmedGoneMirrors(spotifyProvider, localSources.mapNotNull { it.externalId }.toSet(), fullRemoteIds)
        } else emptySet()
        val removedSources = localSources.filter { it.externalId != null && it.externalId in goneIds }
        removedSources.forEach { source ->
            val localPlaylist = playlistDao.getById(source.itemId)
            // Locally-owned playlists (hosted XSPF — canonical via `sourceUrl`
            // — and user-created `local-*` playlists) must NEVER be deleted by
            // sync cleanup. Spotify is just a downstream mirror for these:
            // a missing remote means "deselected from sync" or "deleted on
            // Spotify externally", neither of which should wipe the user's
            // local row. Skip the entire cleanup so the next push (or next
            // hosted-XSPF poll) can recover the remote if needed.
            if (localPlaylist?.sourceUrl != null ||
                localPlaylist?.id?.startsWith("local-") == true
            ) {
                return@forEach
            }
            syncSourceDao.deleteByKey(source.itemId, "playlist", providerId)
            val remaining = syncSourceDao.getByItem(source.itemId, "playlist")
            if (remaining.isEmpty()) {
                localPlaylist?.let { playlistDao.delete(it) }
                playlistTrackDao.deleteByPlaylistId(source.itemId)
            }
            removed++
        }

        // ── Phase 5: pull from non-Spotify enabled providers ─────────
        // The Spotify pull above is left inline because it's coupled
        // with the dedup-cleanup migration block (one-time Spotify-only
        // fix). Other providers (Apple Music today; Tidal-style future
        // ones) run through the helper, which mirrors the Spotify
        // import/cleanup body but parameterized on `provider`.
        val enabledProviderIds = settingsStore.getEnabledSyncProviders()
        val nonSpotifyProviders = providers.filter {
            it.id in enabledProviderIds && it.id != SpotifySyncProvider.PROVIDER_ID
                && (providerFilter == null || providerFilter == it.id)
        }
        for (provider in nonSpotifyProviders) {
            if (!providerHasAxis(provider.id, "playlists")) continue
            val pullResult = pullPlaylistsForProvider(provider, onProgress)
            added += pullResult.added
            removed += pullResult.removed
            updated += pullResult.updated
            unchanged += pullResult.unchanged
        }

        // Phase 3 — Fix 4 (multi-provider mirror propagation):
        // Clear `locallyModified` for any playlist whose relevant mirrors
        // are all caught up. `relevantMirrors` excludes the source
        // provider — the push loop never targets it (Fix 3 guard) so its
        // syncedAt would never advance and would strand the flag forever.
        // Phase 5 generalizes to all enabled providers (was Spotify-only).
        clearLocallyModifiedFlags(enabledProviderIds)

        // N-way PROPAGATION (Phase 4) — runs AFTER the normal pull/push so it sees
        // the freshest local + remote state and reconciles any remaining
        // cross-provider divergence the single-source push loop can't (e.g. an
        // edit pulled in via Spotify that must mirror OUT to Apple Music + LB).
        // Internally gated on isNwayEnabled() && isNwayPropagateEnabled() — a no-op
        // (early return) unless the user has armed real writes. Echo-suppressed
        // (post-push token + advanced baseline) so it never re-pushes a converged
        // playlist on the next cycle. Per-playlist pushes are sequential and go
        // through the gated SpotifyClient (150ms inter-request gap + concurrency
        // cap), so a many-playlist cycle is naturally staggered against the
        // account-wide rate limit. Skipped on a provider-filtered partial sync.
        if (providerFilter == null) {
            runNwayPropagation()
        }

        return TypeSyncResult(added = added, removed = removed, updated = updated, unchanged = unchanged)
    }

    /**
     * Phase 5 — generic per-provider pull branch. Fetches the provider's
     * remote playlists, runs the import-or-update flow with the cross-
     * provider `syncedFrom` preservation guard, and cleans up sources
     * for remotes that have been deselected or deleted upstream.
     *
     * Mirrors the inline Spotify pull body in [syncPlaylists] but
     * parameterized on `provider` and stripped of the one-time dedup-
     * cleanup migration (Spotify-only by design).
     */
    private suspend fun pullPlaylistsForProvider(
        provider: com.parachord.shared.sync.SyncProvider,
        onProgress: (SyncProgress) -> Unit,
    ): TypeSyncResult {
        val providerId = provider.id
        var added = 0
        var removed = 0
        var updated = 0
        var unchanged = 0

        val remotePlaylists = try {
            provider.fetchPlaylists { current, total ->
                onProgress(SyncProgress(SyncPhase.PLAYLISTS, current, total, "Fetching ${provider.displayName} playlists..."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch playlists from ${provider.displayName}", e)
            return TypeSyncResult()
        }

        // Per-provider PULL allowlist (the user's "which of my <provider>
        // playlists to sync" choice). EMPTY = import all. The IMPORT loop uses
        // the filtered list; the REMOVAL cleanup below intentionally uses the
        // FULL `remotePlaylists` so a merely-deselected (but still-remote)
        // playlist is NOT auto-deleted here — the picker's keep/remove prompt
        // handles deselection explicitly (detach to keep, delete to remove).
        val pullAllow = settingsStore.getPullPlaylists(providerId)
        val importPlaylists = remotePlaylists.filter {
            val override = settingsStore.getPlaylistChannels("$providerId-${it.spotifyId}")
            if (override != null) providerId in override
            else pullAllow.isEmpty() || it.spotifyId in pullAllow
        }

        val localSources = syncSourceDao.getByProvider(providerId, "playlist")
        val localByExternalId = localSources.associateBy { it.externalId }

        for ((index, remote) in importPlaylists.withIndex()) {
            _syncPhase.value = "playlists · $providerId pull ${index + 1}/${importPlaylists.size}"
            onProgress(SyncProgress(
                SyncPhase.PLAYLISTS,
                index + 1,
                importPlaylists.size,
                "Syncing ${provider.displayName} playlist: ${remote.entity.name}",
            ))
            val existingSource = localByExternalId[remote.spotifyId]

            if (existingSource == null) {
                val now = currentTimeMillis()
                // The "merge into existing" path here used playlist.spotifyId
                // for Spotify; for other providers there's no equivalent
                // scalar — the link table is the lookup.
                var existingByLink = syncPlaylistLinkDao.selectByExternalId(providerId, remote.spotifyId)
                var existingLocalPlaylist = existingByLink?.let { playlistDao.getById(it.localPlaylistId) }

                // Cross-provider name-match fallback. When neither the
                // sync_source nor sync_playlist_link rows have anything
                // for (this-provider, this-external-id), check whether
                // an existing local row from a DIFFERENT provider has
                // the same playlist name. If so, treat it as a cross-
                // provider push mirror — link this provider's remote
                // to the existing local and preserve the original
                // tracks. Without this fallback, syncing the same
                // playlist on both Spotify and AM produces two local
                // rows ("Workout 2024" appearing twice).
                if (existingLocalPlaylist == null) {
                    val nameMatch = findCrossProviderNameMatch(remote.entity.name, providerId)
                    if (nameMatch != null) {
                        Log.d(TAG, "Pull name-match: AM remote '${remote.entity.name}' " +
                            "absorbed by existing local ${nameMatch.id}")
                        existingLocalPlaylist = nameMatch
                    }
                }
                val targetId = existingLocalPlaylist?.id ?: remote.entity.id

                // Per-playlist channel override on the MATCHED local row. The
                // upfront `importPlaylists` filter keys the override on the
                // would-be id (`<provider>-<remoteId>`), which MISSES a
                // cross-mirrored playlist whose local row has a DIFFERENT id
                // (e.g. a Spotify-imported `spotify-X` row that's also on LB —
                // the LB pull's would-be id is `listenbrainz-<mbid>`). Re-check
                // against the resolved local id so turning a provider off in the
                // playlist Sync menu actually stops the re-link/re-import here.
                if (existingLocalPlaylist != null) {
                    val ov = settingsStore.getPlaylistChannels(existingLocalPlaylist.id)
                    if (ov != null && providerId !in ov) {
                        Log.d(TAG, "Pull skip: '${existingLocalPlaylist.name}' channel override excludes $providerId")
                        continue
                    }
                }

                // Phase 3 — cross-provider syncedFrom preservation:
                // if the local row's pull source points at a DIFFERENT
                // provider, this match fired because we're a push mirror
                // for that local. Don't refetch tracks; only update the
                // link's syncedAt + sync_source row.
                //
                // **Hosted-XSPF preservation (bugfix Apr 2026):** see the
                // matching guard in the inline Spotify pull above for the
                // full rationale. Hosted-XSPF playlists mark canonicality
                // with `sourceUrl != null` rather than a sync_playlist_source
                // row, so the existing pull-source check alone missed them
                // and the AM/Tidal/etc. import path was clobbering them
                // identically to the Spotify path.
                val existingPullSource = if (existingLocalPlaylist != null) {
                    syncPlaylistSourceDao.selectForLocal(existingLocalPlaylist.id)
                } else null
                val isCrossProviderPushMirror = existingPullSource != null
                    && existingPullSource.providerId != providerId
                val isHostedXspf = existingLocalPlaylist?.sourceUrl != null
                val preserveLocal = isCrossProviderPushMirror || isHostedXspf

                if (existingLocalPlaylist != null && existingLocalPlaylist.id != remote.entity.id && !preserveLocal) {
                    playlistTrackDao.deleteByPlaylistId(existingLocalPlaylist.id)
                    playlistDao.delete(existingLocalPlaylist)
                    syncSourceDao.deleteAllForItem(existingLocalPlaylist.id, "playlist")
                }

                if (preserveLocal) {
                    val mirrorReason = when {
                        isHostedXspf -> "sourceUrl=${existingLocalPlaylist!!.sourceUrl}"
                        else -> "syncedFrom=${existingPullSource!!.providerId}"
                    }
                    Log.d(TAG, "Cross-provider push mirror for ${existingLocalPlaylist!!.id}: " +
                        "$mirrorReason; preserving local tracks")
                    syncPlaylistLinkDao.upsertWithSnapshot(
                        localPlaylistId = existingLocalPlaylist.id,
                        providerId = providerId,
                        externalId = remote.spotifyId,
                        snapshotId = remote.snapshotId,
                    )
                    syncSourceDao.insert(SyncSource(
                        itemId = existingLocalPlaylist.id,
                        itemType = "playlist",
                        providerId = providerId,
                        externalId = remote.spotifyId,
                        syncedAt = currentTimeMillis(),
                    ))
                    unchanged++
                    continue
                }

                val tracks = try {
                    provider.fetchPlaylistTracks(remote.spotifyId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch tracks for ${provider.displayName} playlist ${remote.spotifyId}", e)
                    emptyList()
                }
                // Insert with the real trackCount from the fetched tracks.
                // AM's playlist response always carries trackCount=0
                // (the field isn't on the library endpoint), so without
                // this override every AM-pulled playlist would show
                // "0 tracks" in the UI even when tracks were fetched
                // successfully.
                playlistDao.insert(remote.entity.copy(
                    id = targetId,
                    createdAt = existingLocalPlaylist?.createdAt ?: now,
                    updatedAt = now,
                    lastModified = now,
                    trackCount = tracks.size,
                ))
                playlistTrackDao.deleteByPlaylistId(targetId)
                val remappedTracks = if (targetId != remote.entity.id) {
                    tracks.map { it.copy(playlistId = targetId) }
                } else {
                    tracks
                }
                playlistTrackDao.insertAll(remappedTracks)
                syncSourceDao.insert(SyncSource(
                    itemId = targetId,
                    itemType = "playlist",
                    providerId = providerId,
                    externalId = remote.spotifyId,
                    syncedAt = currentTimeMillis(),
                ))
                syncPlaylistSourceDao.upsert(
                    localPlaylistId = targetId,
                    providerId = providerId,
                    externalId = remote.spotifyId,
                    snapshotId = remote.snapshotId,
                    ownerId = null,
                    syncedAt = currentTimeMillis(),
                )
                syncPlaylistLinkDao.upsertWithSnapshot(
                    localPlaylistId = targetId,
                    providerId = providerId,
                    externalId = remote.spotifyId,
                    snapshotId = remote.snapshotId,
                )
                added++
            } else {
                val localPlaylist = playlistDao.getById(existingSource.itemId)
                if (localPlaylist == null) {
                    unchanged++
                    continue
                }

                // Cross-provider push-mirror guard. The CREATE branch
                // already has this guard (line ~1178); the UPDATE branch
                // didn't, which let AM destructively pullPlaylist over a
                // Spotify-pulled keeper after the dedup migration moved
                // AM's sync_source onto it. Symptom: 79-track Spotify
                // playlist gets clobbered with AM's empty track list,
                // artwork swaps to AM's stock art, etc.
                //
                // Rule: if the local row's syncedFrom points at a
                // DIFFERENT provider, we are a push mirror — only
                // refresh the link's snapshotId + syncedAt, NEVER call
                // pullPlaylist (which deletes + reinserts tracks).
                val pullSource = syncPlaylistSourceDao.selectForLocal(localPlaylist.id)
                val isCrossProviderPushMirror = pullSource != null
                    && pullSource.providerId != providerId
                if (isCrossProviderPushMirror) {
                    syncPlaylistLinkDao.upsertWithSnapshot(
                        localPlaylistId = localPlaylist.id,
                        providerId = providerId,
                        externalId = remote.spotifyId,
                        snapshotId = remote.snapshotId,
                    )
                    syncSourceDao.insert(existingSource.copy(syncedAt = currentTimeMillis()))
                    unchanged++
                    continue
                }

                // Per-provider snapshot: read from the link table (NOT
                // from playlist.snapshotId, which is the Spotify-only
                // legacy scalar).
                val link = syncPlaylistLinkDao.selectForLink(localPlaylist.id, providerId)
                val remoteSnapshotId = remote.snapshotId
                val localSnapshotId = link?.snapshotId
                val localModified = localPlaylist.locallyModified
                val isHosted = localPlaylist.sourceUrl != null

                // Self-heal for "playlist with 0 tracks" — if the
                // local has zero tracks AND we're its pull source
                // (i.e. NOT a cross-provider push mirror — that case
                // already returned above), force a refetch even if
                // the snapshot matches. Fixes the case where an
                // earlier sync stored the snapshot but persisted 0
                // tracks (Apple's library-tracks endpoint is flaky
                // for some playlists; the fetchPlaylistTracks
                // ?include=tracks fallback runs only when actually
                // called, not for cached-empty rows).
                val localTrackCount = playlistTrackDao.getByPlaylistIdSync(localPlaylist.id).size
                val ownPullSource = pullSource?.providerId == providerId
                val needsTrackRecovery = ownPullSource && localTrackCount == 0

                when {
                    isHosted && localModified -> {
                        pushPlaylist(localPlaylist, provider)
                        updated++
                    }
                    isHosted -> {
                        syncSourceDao.insert(existingSource.copy(syncedAt = currentTimeMillis()))
                        unchanged++
                    }
                    localModified && remoteSnapshotId != localSnapshotId -> {
                        val localIsNewer = localPlaylist.lastModified > existingSource.syncedAt
                        if (localIsNewer) {
                            pushPlaylist(localPlaylist, provider)
                        } else {
                            pullPlaylist(localPlaylist, remote, provider)
                        }
                        updated++
                    }
                    localModified -> {
                        pushPlaylist(localPlaylist, provider)
                        updated++
                    }
                    // Receive a changed remote (e.g. edited on desktop, pushed to
                    // LB). Snapshot/last-modified is the primary signal; a
                    // track-count difference is a cheap, robust fallback so a
                    // remote edit is caught even if the timestamp were stale.
                    // `remote.trackCount > 0` guards providers that don't report
                    // a real count (Apple Music's library list always returns 0)
                    // — they fall back to the snapshot check only.
                    remoteSnapshotId != localSnapshotId || needsTrackRecovery ||
                        (remote.trackCount > 0 && remote.trackCount != localTrackCount) -> {
                        if (needsTrackRecovery) {
                            Log.d(TAG, "Self-heal: ${provider.id} playlist " +
                                "'${localPlaylist.name}' has 0 local tracks; " +
                                "forcing refetch despite snapshot match")
                        }
                        pullPlaylist(localPlaylist, remote, provider)
                        updated++
                    }
                    else -> {
                        syncSourceDao.insert(existingSource.copy(syncedAt = currentTimeMillis()))
                        unchanged++
                    }
                }
            }
        }

        // Removed-source cleanup, mirroring the Spotify path. A locally-
        // owned playlist (hosted XSPF or local-*) is NEVER deleted by
        // sync cleanup — it's just deselected/deleted from the provider.
        // Probe-gated (workstream B): a source absent from the fetched list is
        // removed ONLY when remotePlaylistExists confirms a 404, so a partial
        // fetch can't mass-delete live playlists.
        val fullRemoteIds = remotePlaylists.map { it.spotifyId }.toSet()
        val goneIds = confirmedGoneMirrors(provider, localSources.mapNotNull { it.externalId }.toSet(), fullRemoteIds)
        val removedSources = localSources.filter { it.externalId != null && it.externalId in goneIds }
        removedSources.forEach { source ->
            val localPlaylist = playlistDao.getById(source.itemId)
            if (localPlaylist?.sourceUrl != null ||
                localPlaylist?.id?.startsWith("local-") == true
            ) {
                return@forEach
            }
            syncSourceDao.deleteByKey(source.itemId, "playlist", providerId)
            val remaining = syncSourceDao.getByItem(source.itemId, "playlist")
            if (remaining.isEmpty()) {
                localPlaylist?.let { playlistDao.delete(it) }
                playlistTrackDao.deleteByPlaylistId(source.itemId)
            }
            removed++
        }

        return TypeSyncResult(added = added, removed = removed, updated = updated, unchanged = unchanged)
    }

    /**
     * Sweep `locallyModified` playlists and clear the flag iff every
     * relevant push mirror's `syncedAt` has advanced past the row's
     * `lastModified`. Relevant mirrors are the enabled providers that
     * have a `sync_playlist_link` row for this playlist, EXCLUDING the
     * pull source (since the push loop's Fix 3 guard never pushes back
     * to source). When `relevantMirrors` is empty (e.g. source-only
     * playlists), the flag clears immediately.
     *
     * Mirrors desktop's post-push clear logic (app.js L5916+).
     */
    private suspend fun clearLocallyModifiedFlags(enabledProviders: Set<String>) {
        val candidates = playlistDao.getLocallyModified()
        for (playlist in candidates) {
            val sourceProvider = syncPlaylistSourceDao.selectForLocal(playlist.id)?.providerId
            val mirrors = syncPlaylistLinkDao.selectForLocal(playlist.id)
            val relevantMirrors = mirrors.filter { link ->
                link.providerId in enabledProviders && link.providerId != sourceProvider
            }
            if (relevantMirrors.isEmpty()) {
                playlistDao.clearLocallyModified(playlist.id)
                continue
            }
            val allCaught = relevantMirrors.all { it.syncedAt >= playlist.lastModified }
            if (allCaught) playlistDao.clearLocallyModified(playlist.id)
        }
    }

    /**
     * Phase 4.5 — generic per-provider push branch. Iterates the
     * provider-aware candidates, runs three-layer dedup against the
     * provider's owned remote playlists, creates or links each one,
     * pushes the tracks (filtered to those that have the relevant
     * external ID), and records both the durable
     * `sync_playlist_link` row and the legacy `sync_source` row.
     *
     * Spotify-specific scalars (`playlists.spotifyId`,
     * `playlists.snapshotId`) are written ONLY when pushing to
     * Spotify — for any other provider, the link table is the
     * single source of truth (per Decision 5).
     *
     * Returns the number of new pushes (newly-linked or freshly
     * created remotes).
     */
    private suspend fun pushPlaylistsForProvider(
        provider: com.parachord.shared.sync.SyncProvider,
        remotePlaylists: List<SyncedPlaylist>,
        deletedExternalIds: Set<String>,
    ): Int {
        val providerId = provider.id
        var added = 0

        // Per-provider playlist-push selection (the user's "which playlists, if
        // any" choice). ListenBrainz defaults to NONE, so a fresh install never
        // pushes to LB — NONE makes `candidates` empty, short-circuiting the
        // whole push (no fetch-remote, no loop). SELECTED restricts to the
        // chosen local ids; ALL preserves mirror-everything.
        val selection = settingsStore.getPlaylistSelection(providerId)
        val allPlaylists = playlistDao.getAllSync()
        // A per-playlist channel override (from the playlist Sync menu) is
        // AUTHORITATIVE — it overrides the provider's push mode for that one
        // playlist. Otherwise fall back to the per-provider selection.
        val candidates = allPlaylists.filter {
            it.name.isNotBlank() && isPushCandidate(it, providerId) && run {
                val override = settingsStore.getPlaylistChannels(it.id)
                if (override != null) providerId in override else selection.includes(it.id)
            }
        }

        val liveRemote = remotePlaylists.filter { it.spotifyId !in deletedExternalIds }
        val remoteById = liveRemote.associateBy { it.spotifyId }
        val ownedRemoteByName = liveRemote.filter { it.isOwned }
            .groupBy { it.entity.name.trim().lowercase() }

        // Pre-fetch each candidate's link once: reused by the mass-floor pre-pass
        // below AND by the per-playlist dedup loop (so the link is read once, not
        // twice).
        val linksByPlaylistId = candidates.associate {
            it.id to syncPlaylistLinkDao.selectForLink(it.id, providerId)
        }

        // Dead-mirror DETACH mass-floor (push-path companion to
        // confirmedGoneMirrors' bail). On a partial/truncated provider fetch where
        // many candidates are all mirrored to THIS provider, every link looks stale
        // (absent from remoteById), and the stale-link branch below would probe
        // `remotePlaylistExists` for each one sequentially through the rate-limit
        // gate — a slow probe-storm. If an implausible number of links are absent
        // at once, that's almost certainly a partial fetch: skip the dead-mirror
        // probe/detach entirely for this push pass (keep all links, don't probe).
        // The normal id-link / Layer-3 dedup still runs; only the dead-mirror branch
        // is gated. Under-removing is always safer than detaching live mirrors.
        val suspectedDeadLinks = candidates.count {
            val link = linksByPlaylistId[it.id]
            link != null && link.externalId !in remoteById
        }
        val skipDeadMirrorDetach = suspectedDeadLinks > DEAD_MIRROR_MASS_FLOOR
        if (skipDeadMirrorDetach) {
            Log.w(TAG, "dead-mirror push: $providerId — $suspectedDeadLinks candidate link(s) absent from fetch (> floor $DEAD_MIRROR_MASS_FLOOR); treating as a partial fetch, skipping detach-probe")
        }

        for ((pIdx, playlist) in candidates.withIndex()) {
            _syncPhase.value = "playlists · $providerId push ${pIdx + 1}/${candidates.size}"
            try {
                // Phase 3 — Fix 3 + pendingAction skip:
                // Skip rows the user marked remote-deleted; the playlist
                // detail banner will let the user re-push or unlink. Lift
                // the link lookup here so the dedup-Layer-1 block below
                // can reuse it without a second query.
                val link = linksByPlaylistId[playlist.id]
                if (link?.pendingAction != null) {
                    Log.d(TAG, "Skipping push for ${playlist.id} on $providerId — pendingAction=${link.pendingAction}")
                    continue
                }

                // Phase 3 — Fix 3: provider-scoped `syncedFrom` guard.
                // Only skip when the CURRENT push target equals the
                // playlist's pull source — never blanket-skip on
                // `syncedFrom != null`. A Spotify-imported playlist
                // must remain pushable to Apple Music.
                val pullSource = syncPlaylistSourceDao.selectForLocal(playlist.id)
                if (pullSource?.providerId == providerId) {
                    Log.d(TAG, "Skipping push for ${playlist.id} on $providerId — its pull source")
                    continue
                }

                // Three-layer dedup — mirrors desktop's
                // sync:create-playlist IPC handler. Each layer yields an
                // `existing` remote we can link to instead of creating a
                // duplicate. Stop at the first hit; fall through to
                // create only if all three miss.

                // Layer 1: durable sync_playlist_link map.
                var existing: SyncedPlaylist? = null
                var matchSource = ""
                if (link != null) {
                    val fromLink = remoteById[link.externalId]
                    if (fromLink != null && fromLink.isOwned) {
                        existing = fromLink
                        matchSource = "id-link"
                    } else {
                        // The stored mirror isn't in the just-fetched remote list.
                        // Absence is NOT proof of deletion (the fetch may have been
                        // partial), and blindly clearing the link here makes Layer 3
                        // re-create a DUPLICATE. Probe before acting (workstream A).
                        //
                        // Mass-floor gate: if too many candidate links were absent
                        // at once (likely a partial fetch), skip the probe + detach
                        // entirely — keep the link and this push cycle's no-op,
                        // exactly as the probe-says-EXISTS path does below.
                        if (skipDeadMirrorDetach) {
                            Log.d(TAG, "Link $providerId:${link.externalId} for ${playlist.id} absent from fetch but mass-floor active — keeping link, skipping push")
                            continue
                        }
                        val stillExists = try {
                            provider.remotePlaylistExists(link.externalId)
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            true
                        }
                        if (stillExists) {
                            // Partial fetch / transient: keep the live link, skip
                            // this push cycle (don't clear, don't re-create).
                            Log.d(TAG, "Link $providerId:${link.externalId} for ${playlist.id} absent from fetch but probe says EXISTS — keeping link, skipping push")
                            continue
                        }
                        // Confirmed gone (404): DETACH this dead mirror instead of
                        // re-creating it, so the chip disappears (both platforms) and
                        // this loop won't recreate it; the user re-enables via the
                        // Sync menu if they want it back. Seed the new override from
                        // the playlist's EFFECTIVE channels (override ?: links) —
                        // override-aware ON PURPOSE: it preserves the user's existing
                        // allowlist and never re-enables a provider they turned off
                        // (it may legitimately go empty when the dead mirror was the
                        // only enabled provider). Drop the link + N-way token FIRST,
                        // then write the override: if the write throws, the next sync
                        // still sees the dropped link rather than an override masking
                        // an orphan.
                        val effective = getPlaylistMirrors(playlist.id).keys.toSet()
                        syncPlaylistLinkDao.deleteForLink(playlist.id, providerId)
                        syncPlaylistNwayDao.deleteForLink(playlist.id, providerId)
                        settingsStore.setPlaylistChannels(
                            playlist.id,
                            DeadMirrorReconcile.overrideAfterDetach(effective, providerId),
                        )
                        Log.w(TAG, "dead-mirror detach: '${playlist.name}' $providerId mirror ${link.externalId} confirmed 404 — channel override excludes $providerId, link dropped")
                        continue
                    }
                }

                // Cheap unchanged short-circuit. An already-linked playlist with
                // NO local edits has nothing to push — skip the hydrate + remote
                // tracklist fetch + replace entirely (the link already exists and
                // its content is current). This is what stops ListenBrainz from
                // doing a per-playlist round-trip for ALL N playlists on every
                // sync; the previous skip-unchanged still fetched each remote
                // tracklist to compare. A `locallyModified` playlist (or one
                // whose link is missing/stale) falls through to the full push.
                if (matchSource == "id-link" && !playlist.locallyModified) {
                    continue
                }

                // Layer 2: playlist.spotifyId (Spotify-only convenience
                // cache; the candidate filter already excludes rows
                // with this set when iterating Spotify, so this only
                // ever matters as defense-in-depth).
                if (existing == null && providerId == SpotifySyncProvider.PROVIDER_ID && playlist.spotifyId != null) {
                    val fromField = remoteById[playlist.spotifyId]
                    if (fromField != null && fromField.isOwned) {
                        existing = fromField
                        matchSource = "playlist-field"
                    }
                }

                // Layer 3: name match (case-insensitive, trimmed, owned).
                if (existing == null) {
                    val nameCandidates = ownedRemoteByName[playlist.name.trim().lowercase()]
                    val pick = nameCandidates?.maxByOrNull { it.trackCount }
                    if (pick != null) {
                        existing = pick
                        matchSource = if ((nameCandidates.size) > 1)
                            "name-match (${nameCandidates.size} candidates)"
                        else "name-match"
                    }
                }

                // #269 disambiguation guard: NEVER claim a remote that's already
                // linked to a DIFFERENT local playlist. With two same-name local
                // rows (e.g. a followed playlist + your own copy), the name-match
                // above would otherwise link BOTH to the same remote mirror,
                // tangling them. A remote (provider, externalId) belongs to at
                // most one local row; if this candidate is taken, drop it and
                // fall through to create a separate mirror. (Layer 1's own-link
                // match is exempt — it IS this playlist's link.)
                if (existing != null && matchSource != "id-link") {
                    val owner = syncPlaylistLinkDao.selectByExternalId(providerId, existing.spotifyId)
                    if (owner != null && owner.localPlaylistId != playlist.id) {
                        Log.d(
                            TAG,
                            "#269: $providerId remote ${existing.spotifyId} already linked to " +
                                "'${owner.localPlaylistId}' — not claiming it for '${playlist.name}'",
                        )
                        existing = null
                        matchSource = ""
                    }
                }

                // Resolve the pushable tracklist BEFORE deciding to create a
                // remote. A playlist with no pushable tracks must NEVER create
                // a fresh empty mirror (desktop's "don't create an empty
                // mirror" rule). This is what flooded ListenBrainz with
                // hundreds of 0-track playlists: Apple-Music-imported rows
                // whose tracks were never fetched locally have an empty
                // `playlist_track` table, so the create ran but the track push
                // was skipped — leaving an empty remote playlist. Hydration is
                // a no-op for a 0-track playlist, so this adds no cost there.
                val rawTracks = playlistTrackDao.getByPlaylistIdSync(playlist.id)
                val tracks = hydrateMissingTrackIds(playlist.id, rawTracks, provider)
                val externalTrackIds = extractExternalTrackIds(tracks, providerId)
                if (externalTrackIds.isEmpty() && existing == null) {
                    Log.d(TAG, "Skip push '${playlist.name}' to $providerId — 0 pushable tracks, refusing to create an empty mirror")
                    continue
                }

                val externalId: String
                val snapshotId: String?
                if (existing != null) {
                    Log.d(TAG, "Linked '${playlist.name}' to existing $providerId playlist ${existing.spotifyId} via $matchSource")
                    externalId = existing.spotifyId
                    snapshotId = existing.snapshotId
                } else {
                    val created = provider.createPlaylist(playlist.name, playlist.description)
                    externalId = created.externalId
                    snapshotId = created.snapshotId
                    Log.d(TAG, "Created $providerId playlist '${playlist.name}' ($externalId)")
                }

                // Write the durable link IMMEDIATELY — before track push
                // and before the playlist row update. If either of those
                // fails, the link still protects the next sync from
                // creating a fresh duplicate.
                syncPlaylistLinkDao.upsertWithSnapshot(
                    localPlaylistId = playlist.id,
                    providerId = providerId,
                    externalId = externalId,
                    snapshotId = snapshotId,
                )

                if (externalTrackIds.isNotEmpty()) {
                    // Skip-unchanged short-circuit (desktop's
                    // canShortCircuitPlaylistUpdate, sync-providers/listenbrainz.js
                    // "Step 3.5"). `replacePlaylistTracks` is a delete-all +
                    // add-all on LB, so re-pushing an identical tracklist every
                    // cycle bumps the remote's last_modified and burns API calls
                    // for no benefit (the Achordion maintainer flagged ~128
                    // playlists "modified" in a day from exactly this). When the
                    // remote already matches the intended list we skip it.
                    //
                    // Currently wired for ListenBrainz only: Spotify already
                    // short-circuits structurally via its `spotifyId == null`
                    // candidate exclusion, and Apple Music's append-only PUT
                    // degradation makes a remote-compare unreliable. A
                    // locallyModified playlist always re-pushes (the flag clears
                    // immediately below), so a local edit is never short-circuited.
                    val canShortCircuit = providerId == ListenBrainzSyncProvider.PROVIDER_ID &&
                        !playlist.locallyModified &&
                        remoteTracklistMatches(provider, externalId, externalTrackIds)
                    if (canShortCircuit) {
                        Log.d(TAG, "Skip-unchanged: '${playlist.name}' on $providerId — remote already matches (${externalTrackIds.size} tracks)")
                    } else {
                        provider.replacePlaylistTracks(externalId, externalTrackIds)
                    }
                }

                // Spotify-specific scalars stay write-through for backward
                // compat with code that still reads playlist.spotifyId
                // directly. For other providers, the link table is
                // authoritative (per Decision 5).
                if (providerId == SpotifySyncProvider.PROVIDER_ID) {
                    playlistDao.update(playlist.copy(
                        spotifyId = externalId,
                        snapshotId = snapshotId,
                        locallyModified = false,
                    ))
                } else {
                    playlistDao.update(playlist.copy(locallyModified = false))
                }
                syncSourceDao.insert(SyncSource(
                    itemId = playlist.id,
                    itemType = "playlist",
                    providerId = providerId,
                    externalId = externalId,
                    syncedAt = currentTimeMillis(),
                ))
                added++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push playlist ${playlist.name} to $providerId", e)
            }
        }
        return added
    }

    /**
     * Per-provider push-eligibility predicate. Spotify pushes only
     * locally-created (`local-*`) and hosted-XSPF playlists, matching
     * legacy behavior — and excludes rows that are already linked
     * (`spotifyId != null`). Apple Music has the same baseline plus
     * Spotify-imported playlists (`spotify-*`), since the Phase 3
     * `syncedFrom` guard correctly skips a Spotify-imported playlist
     * when targeting Spotify but not when targeting Apple Music.
     */
    private fun isPushCandidate(playlist: Playlist, providerId: String): Boolean =
        isPlaylistPushCandidate(playlist, providerId)

    /**
     * Extracts the per-provider external IDs from a playlist's tracks.
     * Tracks without the relevant ID are silently skipped — call
     * [hydrateMissingTrackIds] beforehand to populate IDs via catalog
     * search.
     */
    private fun extractExternalTrackIds(
        tracks: List<PlaylistTrack>,
        providerId: String,
    ): List<String> = when (providerId) {
        SpotifySyncProvider.PROVIDER_ID -> tracks.mapNotNull { it.trackSpotifyUri }
        AppleMusicSyncProvider.PROVIDER_ID -> tracks.mapNotNull { it.trackAppleMusicId }
        ListenBrainzSyncProvider.PROVIDER_ID -> tracks.mapNotNull { it.trackRecordingMbid }
        else -> emptyList()
    }

    /**
     * Skip-unchanged check: true when [provider]'s CURRENT remote tracklist
     * for [externalPlaylistId] already equals [intendedExternalIds] exactly —
     * same IDs, same order. Ports the desktop's push short-circuit
     * (sync-providers/listenbrainz.js "Step 3.5", parachord#796): an
     * order-aware compare against the live remote, so the caller can skip a
     * `replacePlaylistTracks` (delete-all + add-all) that would otherwise be a
     * no-op costing one remote `last_modified` bump per cycle.
     *
     * Order-aware: ListenBrainz honors playlist order, so a reorder must fall
     * through to a real replace. Case-insensitive: recording MBIDs are hex and
     * the remote vs. local store may differ in case.
     *
     * Provider-generic (uses [SyncProvider.fetchPlaylistTracks] +
     * [extractExternalTrackIds]) but only the LB push wires it today — see the
     * call site. On any fetch failure it returns false: we never skip a push
     * because we couldn't confirm the remote state.
     */
    private suspend fun remoteTracklistMatches(
        provider: com.parachord.shared.sync.SyncProvider,
        externalPlaylistId: String,
        intendedExternalIds: List<String>,
    ): Boolean = try {
        val remoteTracks = provider.fetchPlaylistTracks(externalPlaylistId)
        val remoteIds = extractExternalTrackIds(remoteTracks, provider.id)
        remoteIds.size == intendedExternalIds.size &&
            remoteIds.indices.all { i ->
                remoteIds[i].equals(intendedExternalIds[i], ignoreCase = true)
            }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "Short-circuit check failed for $externalPlaylistId on ${provider.id}; will push", e)
        false
    }

    /**
     * True if [track] is missing the external ID required to push it on
     * [providerId]. Used by [hydrateMissingTrackIds] to identify which
     * tracks need a catalog-search round-trip before push.
     */
    private fun missingProviderId(track: PlaylistTrack, providerId: String): Boolean = when (providerId) {
        SpotifySyncProvider.PROVIDER_ID -> track.trackSpotifyUri.isNullOrBlank()
        AppleMusicSyncProvider.PROVIDER_ID -> track.trackAppleMusicId.isNullOrBlank()
        ListenBrainzSyncProvider.PROVIDER_ID -> track.trackRecordingMbid.isNullOrBlank()
        else -> true
    }

    /**
     * Apply a freshly-resolved provider ID to a [PlaylistTrack] row.
     * For Spotify the resolved value is the full `spotify:track:<id>`
     * URI; we also derive the bare `<id>` for the legacy `trackSpotifyId`
     * scalar. For Apple Music the resolved value is the bare numeric
     * catalog ID — written directly into `trackAppleMusicId`.
     */
    private fun applyResolvedId(
        track: PlaylistTrack,
        providerId: String,
        resolvedId: String,
    ): PlaylistTrack = when (providerId) {
        SpotifySyncProvider.PROVIDER_ID -> {
            val bareId = resolvedId.removePrefix("spotify:track:")
            track.copy(
                trackSpotifyUri = resolvedId,
                trackSpotifyId = bareId.takeIf { it.isNotBlank() } ?: track.trackSpotifyId,
            )
        }
        AppleMusicSyncProvider.PROVIDER_ID -> track.copy(trackAppleMusicId = resolvedId)
        ListenBrainzSyncProvider.PROVIDER_ID -> track.copy(trackRecordingMbid = resolvedId)
        else -> track
    }

    /**
     * Catalog-search-based ID hydration (un-defers Decision D1).
     *
     * For each track in [tracks] missing the provider's ID, calls
     * [com.parachord.shared.sync.SyncProvider.searchForTrackId] and persists
     * the resolved ID back to the playlist_track row. Returns the
     * (potentially-updated) track list ready for [extractExternalTrackIds].
     *
     * Without this step, a freshly-imported hosted XSPF would push a 0-track
     * mirror to Apple Music (since the tracks lack `trackAppleMusicId`),
     * which then poisons the next sync's import-by-name path. With this
     * step, every track that the provider's catalog can match gets pushed.
     *
     * **Persistence:** uses [PlaylistTrackDao.replaceAll] (atomic delete +
     * insert in a single SQLDelight transaction) so the playlist isn't
     * briefly empty during the write. The track list returned reflects
     * the just-persisted state.
     *
     * **Concurrency:** runs catalog-search calls in parallel via
     * `coroutineScope { ... }.awaitAll()`. SpotifyClient is rate-limited by
     * Ktor's HttpTimeout; the AM iTunes Search API is unauthenticated and
     * pacing is governed by `INTER_REQUEST_DELAY_MS` inside
     * [AppleMusicSyncProvider.searchForTrackId]. Worst case is a ~10-15s
     * one-time hydration on first sync of a 50-track hosted playlist —
     * subsequent syncs hit the persisted IDs and skip the search.
     */
    private suspend fun hydrateMissingTrackIds(
        playlistId: String,
        tracks: List<PlaylistTrack>,
        provider: com.parachord.shared.sync.SyncProvider,
        persist: Boolean = true,
    ): List<PlaylistTrack> {
        val needsHydration = tracks.filter { missingProviderId(it, provider.id) }
        if (needsHydration.isEmpty()) return tracks

        Log.d(TAG, "Hydrating ${needsHydration.size}/${tracks.size} track IDs for " +
            "$playlistId on ${provider.id}")

        // Bound concurrency: unbounded parallel `awaitAll` against a 100-track
        // playlist trips iTunes Search rate-limiting (HTTP 429) instantly,
        // and Spotify's catalog search has its own per-token quotas. 4
        // concurrent requests is enough to keep latency reasonable without
        // burning through quota in seconds.
        val hydrationSem = Semaphore(HYDRATE_MAX_CONCURRENCY)
        val resolved: List<Pair<Int, String?>> = coroutineScope {
            needsHydration.map { track ->
                async {
                    val resolvedId = hydrationSem.withPermit {
                        try {
                            provider.searchForTrackId(
                                title = track.trackTitle,
                                artist = track.trackArtist,
                                album = track.trackAlbum,
                                isrc = track.trackIsrc,
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "hydrate: search threw for '${track.trackTitle}'", e)
                            null
                        }
                    }
                    track.position to resolvedId
                }
            }.awaitAll()
        }

        val resolvedByPosition: Map<Int, String> = resolved
            .mapNotNull { (pos, id) -> if (id != null) pos to id else null }
            .toMap()

        if (resolvedByPosition.isEmpty()) {
            Log.d(TAG, "Hydrate: no IDs resolved for $playlistId on ${provider.id}")
            return tracks
        }

        val updatedTracks = tracks.map { track ->
            val newId = resolvedByPosition[track.position]
            if (newId != null) applyResolvedId(track, provider.id, newId) else track
        }

        // [persist] = false lets a caller hydrate purely in-memory (returns the
        // resolved list without writing the rows) — used by the propagation
        // coverage pre-check so an under-covered ABORT leaves no partial mutation.
        if (persist) {
            playlistTrackDao.replaceAll(playlistId, updatedTracks)
            Log.d(TAG, "Hydrated ${resolvedByPosition.size} ${provider.id} IDs into $playlistId " +
                "(${tracks.size - resolvedByPosition.size - (tracks.size - needsHydration.size)} unresolved)")
        }
        return updatedTracks
    }

    /**
     * Push a locally-modified playlist's tracks to a previously-linked
     * remote on [provider]. Used by the import-branch's update flow
     * when the local rev is newer than the remote.
     *
     * Resolves the external ID from `sync_playlist_link` for the
     * provider; falls back to the legacy `playlist.spotifyId` scalar
     * only for Spotify (backward-compat with installs that predate
     * Phase 1's link table). For any other provider, no link → no push.
     */
    private suspend fun pushPlaylist(
        playlist: Playlist,
        provider: com.parachord.shared.sync.SyncProvider,
    ) {
        // Resilience: a single playlist's push failure (e.g. an Apple Music
        // HTTP 400 from replacePlaylistTracks) must NOT abort the whole sync
        // cycle. The import/pull call sites that invoke pushPlaylist are not
        // wrapped per-playlist, so an uncaught throw bubbles to the top-level
        // syncAll catch and kills every remaining provider/playlist. Match the
        // sibling pushPlaylistsForProvider loop's per-playlist catch. PRESERVE
        // two exceptions: CancellationException (KMP cancellation rule) and
        // AppleMusicReauthRequiredException (a 401 reauth must surface to the
        // higher-level handler — a transient 400 must not).
        try {
            val link = syncPlaylistLinkDao.selectForLink(playlist.id, provider.id)
            // Resolve the external id: link table is authoritative; legacy
            // playlist.spotifyId is a fallback for Spotify only (backward
            // compat with installs that predate Phase 1's link table).
            val externalIdNullable: String? = link?.externalId
                ?: if (provider.id == SpotifySyncProvider.PROVIDER_ID) playlist.spotifyId else null
            val externalId: String = externalIdNullable ?: run {
                Log.w(TAG, "pushPlaylist: no link for ${playlist.id} on ${provider.id}; skip")
                return
            }
            val rawTracks = playlistTrackDao.getByPlaylistIdSync(playlist.id)
            // Hydrate provider-specific IDs for tracks lacking them — see
            // [hydrateMissingTrackIds] for rationale. Same pattern as the
            // first-push branch above.
            val tracks = hydrateMissingTrackIds(playlist.id, rawTracks, provider)
            val externalTrackIds = extractExternalTrackIds(tracks, provider.id)
            val snapshotId = provider.replacePlaylistTracks(externalId, externalTrackIds)

            // Spotify-specific scalars stay write-through for backward compat
            // (per Decision 5). For other providers, only the link's
            // snapshot column is the source of truth.
            if (provider.id == SpotifySyncProvider.PROVIDER_ID) {
                playlistDao.update(playlist.copy(
                    snapshotId = snapshotId ?: playlist.snapshotId,
                    trackCount = tracks.size,
                    locallyModified = false,
                ))
            } else {
                playlistDao.update(playlist.copy(
                    trackCount = tracks.size,
                    locallyModified = false,
                ))
            }
            if (snapshotId != null) {
                syncPlaylistLinkDao.upsertWithSnapshot(
                    localPlaylistId = playlist.id,
                    providerId = provider.id,
                    externalId = externalId,
                    snapshotId = snapshotId,
                )
            }

            val source = syncSourceDao.get(playlist.id, "playlist", provider.id)
            if (source != null) {
                syncSourceDao.insert(source.copy(syncedAt = currentTimeMillis()))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AppleMusicReauthRequiredException) {
            throw e // reauth must surface to the higher-level handler; do not swallow
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push playlist ${playlist.name} to ${provider.id} — continuing sync", e)
        }
    }

    private suspend fun pullPlaylist(
        localPlaylist: Playlist,
        remote: SyncedPlaylist,
        provider: com.parachord.shared.sync.SyncProvider = spotifyProvider,
    ) {
        // Fetch tracks first, then update playlist metadata with actual count
        // (use update, not insert/REPLACE, because REPLACE does DELETE+INSERT
        // which CASCADE-deletes playlist_tracks)
        val tracks = provider.fetchPlaylistTracks(remote.spotifyId)

        // Empty-remote safety net: if the provider returned 0 tracks
        // AND we currently have non-zero tracks locally, treat the
        // empty result as an API hiccup (Apple Music's library
        // playlist tracks endpoint returns empty for many shared/
        // curated playlists; Spotify rate-limits can return empty
        // pages). Do NOT delete the local tracks. The next snapshot
        // change will trigger another pull and we'll get real data
        // then. Worst case: a legitimately-emptied playlist takes
        // one extra sync to reflect — way better than blowing away
        // a 79-track playlist on a single bad response.
        val existingTrackCount = playlistTrackDao.getByPlaylistIdSync(localPlaylist.id).size
        if (tracks.isEmpty() && existingTrackCount > 0) {
            Log.w(TAG, "pullPlaylist: ${provider.id} returned 0 tracks for " +
                "'${remote.entity.name}' but local has $existingTrackCount — " +
                "treating as transient and preserving local tracks")
            // Still refresh metadata + link so syncedAt advances and we
            // don't loop on the same mismatch every cycle.
            val now = currentTimeMillis()
            val resolvedArtwork = preserveLocalMosaic(localPlaylist.artworkUrl, remote.entity.artworkUrl)
            playlistDao.update(localPlaylist.copy(
                name = remote.entity.name,
                description = remote.entity.description,
                artworkUrl = resolvedArtwork,
                updatedAt = now,
            ))
            syncPlaylistLinkDao.upsertWithSnapshot(
                localPlaylistId = localPlaylist.id,
                providerId = provider.id,
                externalId = remote.spotifyId,
                snapshotId = remote.snapshotId,
            )
            return
        }

        playlistTrackDao.deleteByPlaylistId(localPlaylist.id)
        playlistTrackDao.insertAll(tracks)

        val now = currentTimeMillis()
        // Fix 1 (multi-provider mirror propagation): a pull replaces the
        // local tracks with the remote's. If this playlist also has push-
        // mirror entries on OTHER providers, those copies are now stale.
        // Flag locallyModified so the next push loop catches them up.
        // Without this, an Android-edit → Spotify → desktop pull stops
        // at the desktop and never reaches Apple Music.
        val hasOtherMirrors = syncPlaylistLinkDao.hasOtherMirrors(
            localPlaylist.id, provider.id,
        )
        // Spotify-specific scalars stay write-through (per Decision 5).
        // For other providers, sync_playlist_link.snapshotId is the
        // source of truth and gets refreshed via the upsert below.
        // Preserve locally-generated mosaic art (`file://...filesDir/playlist_mosaics/`)
        // from being overwritten by the provider's stock playlist art. Mosaics are
        // built from album-art tiles in ImageEnrichmentService and are the canonical
        // visual identity for playlists in Parachord. The provider's art gets used
        // only when we have no local mosaic yet.
        val resolvedArtwork = preserveLocalMosaic(localPlaylist.artworkUrl, remote.entity.artworkUrl)
        if (provider.id == SpotifySyncProvider.PROVIDER_ID) {
            playlistDao.update(localPlaylist.copy(
                name = remote.entity.name,
                description = remote.entity.description,
                artworkUrl = resolvedArtwork,
                trackCount = tracks.size,
                snapshotId = remote.snapshotId,
                updatedAt = now,
                lastModified = now,
                locallyModified = hasOtherMirrors,
                ownerName = remote.entity.ownerName,
            ))
        } else {
            playlistDao.update(localPlaylist.copy(
                name = remote.entity.name,
                description = remote.entity.description,
                artworkUrl = resolvedArtwork,
                trackCount = tracks.size,
                updatedAt = now,
                lastModified = now,
                locallyModified = hasOtherMirrors,
                ownerName = remote.entity.ownerName,
            ))
        }
        // Refresh the link snapshot for this provider — the per-provider
        // pull-vs-push decision in the next sync cycle reads from here
        // (not from the legacy playlists.snapshotId scalar).
        syncPlaylistLinkDao.upsertWithSnapshot(
            localPlaylistId = localPlaylist.id,
            providerId = provider.id,
            externalId = remote.spotifyId,
            snapshotId = remote.snapshotId,
        )

        val source = syncSourceDao.get(localPlaylist.id, "playlist", provider.id)
        if (source != null) {
            syncSourceDao.insert(source.copy(syncedAt = currentTimeMillis()))
        }
    }

    // ── Bidirectional removal ────────────────────────────────────

    suspend fun onTrackRemoved(track: Track) {
        val sources = syncSourceDao.getByItem(track.id, "track")
        val providersById = providers.associateBy { it.id }
        // Tombstone EVERY synced provider for this track up front, so a failed or
        // unsupported remote removal can't be undone by the next sync's re-import (#172).
        val entries = sources.mapNotNull { s ->
            s.externalId?.let { TombstoneEntry(s.providerId, it) }
        }
        if (entries.isNotEmpty()) tombstones.addAll(entries)
        for (source in sources) {
            val externalId = source.externalId ?: continue
            val provider = providersById[source.providerId] ?: continue
            try {
                provider.removeTracks(listOf(externalId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove track from ${provider.displayName}", e)
            }
        }
        syncSourceDao.deleteAllForItem(track.id, "track")
    }

    suspend fun onAlbumRemoved(album: Album) {
        val sources = syncSourceDao.getByItem(album.id, "album")
        val providersById = providers.associateBy { it.id }
        for (source in sources) {
            val externalId = source.externalId ?: continue
            val provider = providersById[source.providerId] ?: continue
            try {
                provider.removeAlbums(listOf(externalId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove album from ${provider.displayName}", e)
            }
        }
        syncSourceDao.deleteAllForItem(album.id, "album")
    }

    suspend fun onArtistRemoved(artist: Artist) {
        val sources = syncSourceDao.getByItem(artist.id, "artist")
        val providersById = providers.associateBy { it.id }
        for (source in sources) {
            val externalId = source.externalId ?: continue
            val provider = providersById[source.providerId] ?: continue
            try {
                provider.unfollowArtists(listOf(externalId))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unfollow artist on ${provider.displayName}", e)
            }
        }
        syncSourceDao.deleteAllForItem(artist.id, "artist")
    }

    /**
     * Per-provider result of attempting to delete a playlist's remote
     * mirror. Surfaced to the UI so the user can be told when an Apple
     * Music mirror couldn't be deleted via the API (Decision D8) — the
     * playlist still exists in Apple Music and the user has to remove
     * it from the Music app manually.
     */
    data class PlaylistDeletionAttempt(
        val providerId: String,
        val providerDisplayName: String,
        val externalId: String,
        val result: com.parachord.shared.sync.DeleteResult,
    )

    /**
     * Phase 6.5 — sync-aware playlist deletion. Iterates the playlist's
     * `sync_playlist_link` rows and calls each provider's
     * [com.parachord.shared.sync.SyncProvider.deletePlaylist], returning
     * the per-provider results. Cleanup of the local link rows + sync
     * sources happens regardless of provider response so the local
     * state stays consistent — if Apple Music returns Unsupported, we
     * unlink Parachord-side and surface the limitation to the user.
     *
     * Caller is responsible for the actual local row deletion
     * (`playlistDao.delete`) — this method only handles the remote
     * cleanup so it composes with `LibraryRepository.deletePlaylist`
     * without ordering surprises.
     */
    suspend fun onPlaylistRemoved(
        playlist: Playlist,
        deleteFromProviders: Set<String>? = null,
    ): List<PlaylistDeletionAttempt> {
        val attempts = mutableListOf<PlaylistDeletionAttempt>()
        val mirrors = getPlaylistMirrors(playlist.id)
        for ((providerId, externalId) in mirrors) {
            // null = delete from EVERY mirror (legacy behavior). Otherwise only
            // the user-selected providers get a remote delete; the rest keep
            // their remote playlist. Local cleanup below runs regardless.
            if (deleteFromProviders != null && providerId !in deleteFromProviders) continue
            val provider = providers.firstOrNull { it.id == providerId } ?: continue
            val result = try {
                provider.deletePlaylist(externalId)
            } catch (e: Exception) {
                Log.e(TAG, "deletePlaylist threw for $providerId:$externalId", e)
                com.parachord.shared.sync.DeleteResult.Failed(e)
            }
            attempts.add(PlaylistDeletionAttempt(
                providerId = providerId,
                providerDisplayName = provider.displayName,
                externalId = externalId,
                result = result,
            ))
        }
        // Local cleanup — drop every link, sync source, and the pull source so
        // the next sync can't re-link or re-import the deleted playlist. (Kept
        // remotes may still re-import on a later pull — playlist tombstones are
        // out of scope; matches desktop.)
        syncPlaylistLinkDao.deleteForLocal(playlist.id)
        syncSourceDao.getByItem(playlist.id, "playlist").forEach {
            syncSourceDao.deleteByKey(playlist.id, "playlist", it.providerId)
        }
        syncPlaylistSourceDao.deleteForLocal(playlist.id)
        return attempts
    }

    /**
     * Detach a playlist from sync entirely WITHOUT deleting it: drop its sync
     * sources, push links, and pull-source row, keeping the local row. Used by
     * the per-playlist "keep local copy, stop syncing" choice. The caller MUST
     * also exclude this playlist's external id from the provider's pull
     * allowlist ([SyncSettingsProvider.setPullPlaylists]) — otherwise the next
     * pull re-imports it by name-match. The row survives as a local-only copy.
     */
    suspend fun detachPlaylistFromSync(localPlaylistId: String) {
        syncPlaylistLinkDao.deleteForLocal(localPlaylistId)
        syncSourceDao.getByItem(localPlaylistId, "playlist").forEach {
            syncSourceDao.deleteByKey(localPlaylistId, "playlist", it.providerId)
        }
        syncPlaylistSourceDao.deleteForLocal(localPlaylistId)
    }

    /**
     * Delete a playlist from ONE provider's remote (used by the Sync menu's
     * "delete from this service" choice). Returns the [DeleteResult] so the UI
     * can surface an Unsupported (e.g. Apple Music can't delete via its API).
     * Does NOT touch the local row or other mirrors — pair with
     * [detachPlaylistFromProvider] for the local cleanup.
     */
    suspend fun deletePlaylistOnProvider(
        providerId: String,
        externalId: String,
    ): com.parachord.shared.sync.DeleteResult {
        val provider = providers.firstOrNull { it.id == providerId }
            ?: return com.parachord.shared.sync.DeleteResult.Unsupported(0)
        return try {
            provider.deletePlaylist(externalId)
        } catch (e: Exception) {
            Log.e(TAG, "deletePlaylistOnProvider threw for $providerId:$externalId", e)
            com.parachord.shared.sync.DeleteResult.Failed(e)
        }
    }

    /**
     * Detach a playlist from ONE provider's sync, leaving every OTHER provider's
     * linkage intact. Used by the per-provider pull picker's "keep local copy"
     * choice: deselecting a playlist in the Spotify picker must NOT strip its
     * ListenBrainz link — doing so makes the LB pull's name-match
     * ([findCrossProviderNameMatch] requires "no link for this provider") re-claim
     * the row and re-import a DUPLICATE on the next sync. Removing only the
     * deselected provider's source + link keeps the row recognized by its other
     * mirrors, so nothing re-imports. Also clears the pull-source row only when
     * it points at THIS provider.
     */
    suspend fun detachPlaylistFromProvider(localPlaylistId: String, providerId: String) {
        syncPlaylistLinkDao.deleteForLink(localPlaylistId, providerId)
        syncSourceDao.deleteByKey(localPlaylistId, "playlist", providerId)
        val pullSource = syncPlaylistSourceDao.selectForLocal(localPlaylistId)
        if (pullSource?.providerId == providerId) {
            syncPlaylistSourceDao.deleteForLocal(localPlaylistId)
        }
    }

    /**
     * Local ids that currently mirror to [providerId] AND still exist in the
     * `playlists` table, EXCLUDING any whose pull-source is [providerId] (so an
     * LB-imported `listenbrainz-*` row — whose pull import wrote a
     * `sync_playlist_link[listenbrainz]` row — is NOT surfaced as a push target
     * the user could un-check and thereby corrupt its own pull sync). Seeds the
     * #266 push-config picker's "checked" set. Orphan links (no playlist row)
     * are dropped here; their cleanup is a separate workstream.
     */
    suspend fun linkedPlaylistIdsForProvider(providerId: String): Set<String> {
        // Override-AWARE: a playlist syncs to [providerId] if its per-playlist
        // channel OVERRIDE includes it (authoritative — set by the Sync context
        // menu OR this picker), OR — when there's no override — it has an actual
        // mirror link. Without the override branch the picker reads only links, so
        // a playlist just enabled via the Sync menu (override set, link not created
        // until the next sync's push) shows UN-checked here — the two UIs disagree.
        // Both UIs write the channel override, so both must READ it too.
        val all = playlistDao.getAllSync()
        val linkedIds = syncPlaylistLinkDao.selectForProvider(providerId)
            .map { it.localPlaylistId }
            .toSet()
        return all
            .filter { p ->
                val override = settingsStore.getPlaylistChannels(p.id)
                if (override != null) providerId in override else p.id in linkedIds
            }
            // Never surface a playlist whose PULL SOURCE is this provider (a
            // `<provider>-*` import) — un-checking it would corrupt its pull sync.
            .filter { syncPlaylistSourceDao.selectForLocal(it.id)?.providerId != providerId }
            .map { it.id }
            .toSet()
    }

    /**
     * Local-only "stop pushing this playlist to [providerId]" for a PUSH
     * provider (#266). Removes the durable link + N-way echo-state, and writes
     * an authoritative channel override that EXCLUDES [providerId] so neither the
     * legacy push loop (candidates filter) nor N-way ([getPlaylistMirrors]
     * filter) re-adds it next cycle — including masking the id-prefix re-add for
     * `<provider>-*` rows. NEVER deletes the remote playlist. NEVER touches the
     * baseline (the shared 3-way-merge ancestor for the row's OTHER providers).
     */
    suspend fun unlinkPlaylistFromProviderLocally(localPlaylistId: String, providerId: String) {
        Log.d(TAG, "LBpush: unlink $localPlaylistId from $providerId")
        // 1. Drop the durable push link → removed from getPlaylistMirrors' link
        //    source AND from the legacy three-layer dedup's Layer-1 reuse.
        syncPlaylistLinkDao.deleteForLink(localPlaylistId, providerId)
        // 2. Clear per-(playlist,provider) N-way change-token / editedAt echo state.
        syncPlaylistNwayDao.deleteForLink(localPlaylistId, providerId)
        // 3. Authoritative channel override (allowlist) that survives the next
        //    sync AND masks the id-prefix re-add. Seed from the EFFECTIVE channel
        //    set (mode-defaults folded in) so other providers (Spotify/AM) on the
        //    same row — including mode-default mirrors with no link row yet — are
        //    preserved, then drop only [providerId].
        val current = effectivePlaylistChannels(localPlaylistId)
        settingsStore.setPlaylistChannels(localPlaylistId, current - providerId)
    }

    /**
     * Providers a playlist EFFECTIVELY syncs with right now: the authoritative
     * channel override if present, else the live mirror set (links + id-prefix +
     * pull-source via getPlaylistMirrors) UNIONED with each ENABLED provider whose
     * per-provider push mode currently admits this playlist (e.g. Spotify's default
     * mode=ALL mirrors every push-candidate row with no link row yet). This is the
     * SAFE seed for writing a channel-override allowlist — seeding from
     * getPlaylistMirrors() alone silently DROPS a mode-default Spotify/AM mirror
     * that has no link yet (data loss).
     */
    private suspend fun effectivePlaylistChannels(localPlaylistId: String): Set<String> {
        settingsStore.getPlaylistChannels(localPlaylistId)?.let { return it }
        val playlist = playlistDao.getById(localPlaylistId) ?: return emptySet()
        val result = getPlaylistMirrors(localPlaylistId).keys.toMutableSet()
        for (pid in settingsStore.getEnabledSyncProviders()) {
            if (pid in result) continue
            if (isPushCandidate(playlist, pid) &&
                settingsStore.getPlaylistSelection(pid).includes(localPlaylistId)) {
                result.add(pid)
            }
        }
        return result
    }

    /**
     * Local-only "start pushing this playlist to [providerId]" for a PUSH
     * provider (#266). Writes the channel override (allowlist) ADDING
     * [providerId] so the row is admitted into the push candidate set. Does NOT
     * create the link or push — `pushPlaylistsForProvider` does that on the next
     * sync (createPlaylist → upsertWithSnapshot link → track push).
     */
    suspend fun linkPlaylistToProviderLocally(localPlaylistId: String, providerId: String) {
        Log.d(TAG, "LBpush: link $localPlaylistId to $providerId")
        val current = effectivePlaylistChannels(localPlaylistId)
        settingsStore.setPlaylistChannels(localPlaylistId, current + providerId)
    }

    /**
     * All remote mirrors of a local playlist: providerId -> externalId. Union of
     * the id-prefix-derived source, the pull-source row, and every push link.
     * Each entry is a remote we can show a chip for / offer to delete from.
     */
    suspend fun getPlaylistMirrors(localPlaylistId: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        when {
            localPlaylistId.startsWith("spotify-") ->
                out["spotify"] = localPlaylistId.removePrefix("spotify-")
            localPlaylistId.startsWith("applemusic-") ->
                out["applemusic"] = localPlaylistId.removePrefix("applemusic-")
            localPlaylistId.startsWith("listenbrainz-") ->
                out["listenbrainz"] = localPlaylistId.removePrefix("listenbrainz-")
        }
        syncPlaylistSourceDao.selectForLocal(localPlaylistId)?.let { out[it.providerId] = it.externalId }
        // Push links last — they carry the authoritative externalId.
        syncPlaylistLinkDao.selectForLocal(localPlaylistId).forEach { out[it.providerId] = it.externalId }
        // A per-playlist channel override is authoritative — hide providers the
        // user turned off (so chips / delete options reflect the live sync state,
        // not the id-prefix of a since-detached row).
        val override = settingsStore.getPlaylistChannels(localPlaylistId)
        return if (override == null) out else out.filterKeys { it in override }
    }

    /**
     * localPlaylistId -> the providers it EFFECTIVELY syncs with (override, else
     * id-prefix source + push links). Batch, for the playlist-list source chips
     * so they reflect the live channel state rather than the row's id-prefix.
     */
    suspend fun getAllEffectivePlaylistChannels(): Map<String, List<String>> {
        val links = syncPlaylistLinkDao.selectAll()
            .groupBy { it.localPlaylistId }
            .mapValues { e -> e.value.map { it.providerId }.toMutableSet() }
        val out = mutableMapOf<String, List<String>>()
        for (p in playlistDao.getAllSync()) {
            val override = settingsStore.getPlaylistChannels(p.id)
            val effective = override ?: buildSet {
                when {
                    p.id.startsWith("spotify-") -> add("spotify")
                    p.id.startsWith("applemusic-") -> add("applemusic")
                    p.id.startsWith("listenbrainz-") -> add("listenbrainz")
                }
                links[p.id]?.let { addAll(it) }
            }
            out[p.id] = effective.toList()
        }
        return out
    }

    /**
     * localPlaylistId -> the providers it mirrors to (push links). Batch (one
     * query) for the playlist-list source chips. The id-prefix source is derived
     * by the UI; this adds the cross-provider push mirrors the id can't reveal.
     */
    suspend fun getAllPlaylistLinkProviders(): Map<String, List<String>> =
        syncPlaylistLinkDao.selectAll()
            .groupBy { it.localPlaylistId }
            .mapValues { e -> e.value.map { it.providerId }.distinct() }

    // ── Stop syncing ─────────────────────────────────────────────

    /** Legacy single-provider (Spotify) stop — also wipes ALL sync settings.
     *  Kept for the Android wizard's existing "disconnect sync" action. */
    suspend fun stopSyncing(removeItems: Boolean) {
        stopProviderSync(SpotifySyncProvider.PROVIDER_ID, removeItems)
        settingsStore.clearSyncSettings()
    }

    /**
     * Stop syncing ONE provider (multi-provider). [removeItems] deletes items
     * sourced ONLY by this provider; an item still sourced by another provider
     * survives (it just loses this provider's `sync_source`). Settings / enabled-
     * provider-set management is the CALLER's job (iOS `setSyncProviderEnabled`,
     * Android wizard) — this only touches the per-provider sync data.
     */
    suspend fun stopProviderSync(providerId: String, removeItems: Boolean) {
        if (removeItems) {
            val allSources = syncSourceDao.getAllByProvider(providerId)
            for (source in allSources) {
                val otherSources = syncSourceDao.getByItem(source.itemId, source.itemType)
                    .filter { it.providerId != providerId }
                if (otherSources.isEmpty()) {
                    when (source.itemType) {
                        "track" -> trackDao.getById(source.itemId)?.let { trackDao.delete(it) }
                        "album" -> albumDao.getById(source.itemId)?.let { albumDao.delete(it) }
                        "artist" -> artistDao.deleteById(source.itemId)
                        "playlist" -> {
                            playlistDao.getById(source.itemId)?.let { playlistDao.delete(it) }
                            playlistTrackDao.deleteByPlaylistId(source.itemId)
                        }
                    }
                }
            }
        }
        syncSourceDao.deleteAllForProvider(providerId)
        syncPlaylistLinkDao.deleteForProvider(providerId)
        // Drop the hydration negative-cache rows for this provider too, so they
        // don't orphan across a disconnect/reconnect cycle (Task-7 review #3).
        trackProviderIdCacheDao.deleteForProvider(providerId)
    }

    /**
     * Per-axis cleanup: walk every `sync_sources` row for
     * (`providerId`, [axis]) and remove the underlying entity row IF
     * no other provider has a `sync_sources` row pointing at it. Used
     * by the wizard's opt-out flow when the user un-checks an axis
     * they had previously enabled — they get to choose Keep (no-op)
     * or Remove (this method).
     *
     * [axis] values: `"tracks"` / `"albums"` / `"artists"` / `"playlists"`.
     * Internally translates to the singular `itemType` used by the
     * sync_sources schema (`"track"` / `"album"` / `"artist"` / `"playlist"`).
     *
     * Cross-provider survival: an item that's also synced from another
     * provider stays in the library — only the dropped provider's
     * sync_source row is deleted, not the entity. This matches the
     * existing `stopSyncing(removeItems = true)` behavior at the
     * provider level, applied at axis granularity.
     */
    suspend fun removeItemsForProviderAxis(providerId: String, axis: String): Int {
        val itemType = when (axis) {
            "tracks" -> "track"
            "albums" -> "album"
            "artists" -> "artist"
            "playlists" -> "playlist"
            else -> return 0
        }
        val table = when (itemType) {
            "track" -> "tracks"
            "album" -> "albums"
            "artist" -> "artists"
            "playlist" -> "playlists"
            else -> return 0
        }

        // Snapshot how many sync_source rows we're about to act on.
        // Used for the return value AND for logging.
        val scanned = syncSourceDao.getByProvider(providerId, itemType).size
        if (scanned == 0) return 0

        // Bulk SQL inside a single transaction. The previous implementation
        // walked sources in a Kotlin for-loop calling 4 DAO methods per
        // iteration (`getByItem`, `deleteByKey`, `getById`, `delete`). For
        // 7,000+ rows that meant ~30,000 sequential DB round-trips, taking
        // 30+ seconds during which Flow observers (`albumDao.getAll()` etc.)
        // re-emitted continuously while the table was mid-mutation. That
        // race produced an NPE in the SQLDelight cursor mapper —
        // `AlbumQueries.getAll$lambda$0` reading a transient null `id` from
        // a cursor pointing at a row in flight.
        //
        // The bulk approach: one transaction, three SQL statements, ~10ms
        // for any axis size. Observers see the table go from "before" to
        // "after" atomically — no intermediate state, no race window.
        //
        // Cross-provider survival: the entity-table delete uses NOT EXISTS
        // against `sync_sources` filtered by other-provider rows, so an
        // album that's ALSO synced from Spotify survives the AM purge
        // (only its `sync_sources` row for `providerId` gets deleted).
        // Spotify and AM use disjoint id prefixes (`spotify-*` /
        // `applemusic-*`), so in practice nothing survives — but the guard
        // matters for any future provider that might share ids.
        try {
            db.transaction {
                // 1. Delete entity rows that have no OTHER-provider sync_source
                //    pointing at them.
                driver.execute(
                    null,
                    """
                    DELETE FROM $table
                    WHERE id IN (
                        SELECT s.itemId FROM sync_sources s
                        WHERE s.providerId = ? AND s.itemType = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM sync_sources s2
                              WHERE s2.itemId = s.itemId
                                AND s2.itemType = ?
                                AND s2.providerId != ?
                          )
                    )
                    """.trimIndent(),
                    4,
                ) {
                    bindString(0, providerId)
                    bindString(1, itemType)
                    bindString(2, itemType)
                    bindString(3, providerId)
                }

                // 2. Playlist-only side effects: drop tracks + per-provider
                //    link / source rows whose local playlist row was just
                //    deleted. Skip-if-not-deleted is implicit since FK
                //    targets are already gone.
                if (itemType == "playlist") {
                    driver.execute(
                        null,
                        """
                        DELETE FROM playlist_tracks
                        WHERE playlistId IN (
                            SELECT s.itemId FROM sync_sources s
                            WHERE s.providerId = ? AND s.itemType = 'playlist'
                              AND NOT EXISTS (
                                  SELECT 1 FROM sync_sources s2
                                  WHERE s2.itemId = s.itemId
                                    AND s2.itemType = 'playlist'
                                    AND s2.providerId != ?
                              )
                        )
                        """.trimIndent(),
                        2,
                    ) {
                        bindString(0, providerId)
                        bindString(1, providerId)
                    }
                    driver.execute(
                        null,
                        """
                        DELETE FROM sync_playlist_link
                        WHERE localPlaylistId IN (
                            SELECT s.itemId FROM sync_sources s
                            WHERE s.providerId = ? AND s.itemType = 'playlist'
                              AND NOT EXISTS (
                                  SELECT 1 FROM sync_sources s2
                                  WHERE s2.itemId = s.itemId
                                    AND s2.itemType = 'playlist'
                                    AND s2.providerId != ?
                              )
                        )
                        """.trimIndent(),
                        2,
                    ) {
                        bindString(0, providerId)
                        bindString(1, providerId)
                    }
                    driver.execute(
                        null,
                        """
                        DELETE FROM sync_playlist_source
                        WHERE localPlaylistId IN (
                            SELECT s.itemId FROM sync_sources s
                            WHERE s.providerId = ? AND s.itemType = 'playlist'
                              AND NOT EXISTS (
                                  SELECT 1 FROM sync_sources s2
                                  WHERE s2.itemId = s.itemId
                                    AND s2.itemType = 'playlist'
                                    AND s2.providerId != ?
                              )
                        )
                        """.trimIndent(),
                        2,
                    ) {
                        bindString(0, providerId)
                        bindString(1, providerId)
                    }
                }

                // 3. Delete this provider's sync_source rows (always last,
                //    so the NOT EXISTS subqueries above still see them).
                driver.execute(
                    null,
                    "DELETE FROM sync_sources WHERE providerId = ? AND itemType = ?",
                    2,
                ) {
                    bindString(0, providerId)
                    bindString(1, itemType)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "removeItemsForProviderAxis bulk delete failed " +
                "(provider=$providerId axis=$axis): ${e.message}", e)
            return 0
        }

        Log.d(TAG, "Per-axis purge: provider=$providerId axis=$axis " +
            "scanned=$scanned (atomic bulk delete; entities tracked under " +
            "another provider survived via NOT EXISTS guard)")
        return scanned
    }

    /** Quick count for the wizard's confirmation prompt — number of
     *  items the user has on this provider+axis that would be touched
     *  by [removeItemsForProviderAxis]. Includes both purge-able rows
     *  and rows that would survive under another provider. */
    suspend fun countItemsForProviderAxis(providerId: String, axis: String): Int {
        val itemType = when (axis) {
            "tracks" -> "track"
            "albums" -> "album"
            "artists" -> "artist"
            "playlists" -> "playlist"
            else -> return 0
        }
        return syncSourceDao.getByProvider(providerId, itemType).size
    }

    /**
     * Don't overwrite a locally-generated mosaic with the provider's
     * stock playlist art. Mosaics live under `filesDir/playlist_mosaics/`
     * and are written as `file://...` URIs by [com.parachord.android.data.metadata.ImageEnrichmentService.enrichPlaylistArt].
     * Returns:
     *  - the existing mosaic when it's a `file://` path (don't overwrite)
     *  - the remote URL when there's no existing artwork
     *  - the existing remote URL when both are remote (no-op churn)
     */
    private fun preserveLocalMosaic(existing: String?, remote: String?): String? {
        if (existing != null && existing.startsWith("file://")) return existing
        return remote ?: existing
    }

    /**
     * Cross-provider name-match dedup for the pull path. Returns the
     * existing local playlist that should "absorb" [remoteName] from
     * [currentProviderId], or null if no match.
     *
     * Used to prevent duplicate local rows when the same playlist
     * exists on both Spotify and Apple Music. Without this, AM's pull
     * after Spotify's pull creates `applemusic-Y` next to `spotify-X`
     * for "Workout 2024" — two visible rows for the same playlist.
     *
     * Match rules (mirror of the push-path name dedup at line 1273):
     * - case-insensitive, trimmed name equality
     * - exclude rows that already have a link for [currentProviderId]
     *   (those are this-provider's own playlists; matching them would
     *   collapse two distinct remotes into one local)
     * - exclude `local-*` rows (those are Parachord-originated and
     *   handled by the push-path dedup)
     * - if multiple match, pick the one with the most tracks
     *   (richest wins, deterministic tiebreak)
     */
    private suspend fun findCrossProviderNameMatch(
        remoteName: String,
        currentProviderId: String,
    ): Playlist? {
        val key = remoteName.trim().lowercase()
        if (key.isEmpty()) return null
        val candidates = playlistDao.getAllSync().filter { local ->
            local.name.trim().lowercase() == key
                && !local.id.startsWith("local-")
                && syncPlaylistLinkDao.selectForLink(local.id, currentProviderId) == null
        }
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.trackCount }
    }
}
