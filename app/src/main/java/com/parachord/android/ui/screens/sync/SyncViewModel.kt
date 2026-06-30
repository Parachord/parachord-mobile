package com.parachord.android.ui.screens.sync

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.dao.SyncSourceDao
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.sync.SpotifySyncProvider
import com.parachord.android.sync.SyncEngine
import com.parachord.android.sync.SyncScheduler
import com.parachord.android.sync.SyncedPlaylist
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.shared.sync.ListenBrainzSyncProvider
import com.parachord.shared.sync.SyncProvider
import com.parachord.shared.sync.SyncSettings
import com.parachord.shared.sync.isPlaylistPushCandidate
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
class SyncViewModel constructor(
    private val syncEngine: SyncEngine,
    private val syncScheduler: SyncScheduler,
    private val settingsStore: SettingsStore,
    private val spotifyProvider: SpotifySyncProvider,
    private val syncSourceDao: SyncSourceDao,
    private val playlistDao: PlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val libraryRepository: LibraryRepository,
    private val providers: List<SyncProvider>,
) : ViewModel() {

    private companion object {
        /** Universal axis set used for orphan detection. Compared against the
         *  user's NEW opt-in (not the prior stored opt-in) so the wizard
         *  surfaces orphaned items even when they were dropped in a previous
         *  run that never completed cleanup. See `startSync` comment. */
        val ALL_SYNC_AXES = setOf("tracks", "albums", "artists", "playlists")
    }

    /** Which provider this wizard instance is configuring. Mutable so the
     *  same VM can be reused between Spotify-config and AM-config flows
     *  (the Settings screen swaps it before opening the sheet). Defaults
     *  to Spotify for backwards compat with the Library-screen entry. */
    private val _activeProviderId = MutableStateFlow(SpotifySyncProvider.PROVIDER_ID)
    val activeProviderId: StateFlow<String> = _activeProviderId
    val activeProvider: StateFlow<SyncProvider?> = _activeProviderId
        .map { id -> providers.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, providers.firstOrNull { it.id == SpotifySyncProvider.PROVIDER_ID })

    fun setActiveProvider(providerId: String) {
        _activeProviderId.value = providerId
        // Always re-enter the wizard at OPTIONS for the new provider —
        // never carry a stale SYNCING / COMPLETE step over from a
        // previous wizard run (e.g. the user just finished Spotify
        // and is now opening AM). Without this reset, the wizard could
        // skip past the per-axis picker and land directly on the
        // sync-in-progress / done screen.
        _currentStep.value = SetupStep.OPTIONS
        _syncResult.value = null
        viewModelScope.launch {
            // Pre-fill axis checkboxes from the per-provider opt-in.
            val collections = settingsStore.getSyncCollectionsForProvider(providerId)
            if (providerId == com.parachord.shared.sync.ListenBrainzSyncProvider.PROVIDER_ID) {
                // ListenBrainz only syncs playlists — loved tracks go via the
                // scrobbler love-push path, and albums/artists aren't supported.
                // Force the non-playlist axes off (the wizard hides them) and
                // default playlists on for a fresh setup so "Start Sync" is
                // enabled immediately.
                _syncTracks.value = false
                _syncAlbums.value = false
                _syncArtists.value = false
                _syncPlaylists.value = collections.isEmpty() || "playlists" in collections
            } else {
                _syncTracks.value = "tracks" in collections
                _syncAlbums.value = "albums" in collections
                _syncArtists.value = "artists" in collections
                _syncPlaylists.value = "playlists" in collections
            }
        }
    }

    enum class SetupStep { OPTIONS, CONFIRM_REMOVAL, PLAYLISTS, SYNCING, COMPLETE }

    /**
     * State for the CONFIRM_REMOVAL step — the axes the user just
     * unchecked (compared to their previously-saved opt-in) and a
     * count of items affected per axis. The user picks Keep or Remove;
     * Remove fires [SyncEngine.removeItemsForProviderAxis] for each.
     */
    data class RemovalConfirmation(
        val providerId: String,
        val droppedAxes: List<String>,           // e.g. ["artists", "albums"]
        val itemCountByAxis: Map<String, Int>,   // "artists" → 84
    )

    private val _pendingRemoval = MutableStateFlow<RemovalConfirmation?>(null)
    val pendingRemoval: StateFlow<RemovalConfirmation?> = _pendingRemoval

    private val _currentStep = MutableStateFlow(SetupStep.OPTIONS)
    val currentStep: StateFlow<SetupStep> = _currentStep

    private val _syncTracks = MutableStateFlow(true)
    val syncTracks: StateFlow<Boolean> = _syncTracks

    private val _syncAlbums = MutableStateFlow(true)
    val syncAlbums: StateFlow<Boolean> = _syncAlbums

    private val _syncArtists = MutableStateFlow(true)
    val syncArtists: StateFlow<Boolean> = _syncArtists

    private val _syncPlaylists = MutableStateFlow(true)
    val syncPlaylists: StateFlow<Boolean> = _syncPlaylists

    fun setSyncTracks(v: Boolean) { _syncTracks.value = v }
    fun setSyncAlbums(v: Boolean) { _syncAlbums.value = v }
    fun setSyncArtists(v: Boolean) { _syncArtists.value = v }
    fun setSyncPlaylists(v: Boolean) { _syncPlaylists.value = v }

    private val _availablePlaylists = MutableStateFlow<List<SyncedPlaylist>>(emptyList())
    val availablePlaylists: StateFlow<List<SyncedPlaylist>> = _availablePlaylists

    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading

    private val _playlistsError = MutableStateFlow<String?>(null)
    val playlistsError: StateFlow<String?> = _playlistsError

    private val _selectedPlaylistIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedPlaylistIds: StateFlow<Set<String>> = _selectedPlaylistIds

    private val _playlistFilter = MutableStateFlow("all")
    val playlistFilter: StateFlow<String> = _playlistFilter

    fun setPlaylistFilter(filter: String) { _playlistFilter.value = filter }
    fun togglePlaylistSelection(spotifyId: String) {
        _selectedPlaylistIds.value = _selectedPlaylistIds.value.let {
            if (spotifyId in it) it - spotifyId else it + spotifyId
        }
    }
    fun selectAllPlaylists(spotifyIds: List<String>) {
        _selectedPlaylistIds.value = _selectedPlaylistIds.value + spotifyIds
    }
    fun deselectAllPlaylists(spotifyIds: List<String>) {
        _selectedPlaylistIds.value = _selectedPlaylistIds.value - spotifyIds.toSet()
    }

    private val _syncProgress = MutableStateFlow(com.parachord.shared.sync.SyncEngine.SyncProgress(com.parachord.shared.sync.SyncEngine.SyncPhase.TRACKS))
    val syncProgress: StateFlow<com.parachord.shared.sync.SyncEngine.SyncProgress> = _syncProgress

    private val _syncResult = MutableStateFlow<com.parachord.shared.sync.SyncEngine.FullSyncResult?>(null)
    val syncResult: StateFlow<com.parachord.shared.sync.SyncEngine.FullSyncResult?> = _syncResult

    /** True while a full sync is running — drives the Sync-settings status header
     *  (animated indicator + disabled "Sync now"). Set by [syncNow]. */
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    /** Per-provider "what's synced" summary for the Sync-settings cards (#303 item
     *  10), e.g. "Liked Songs · Albums · Playlists". Refreshed by
     *  [loadSyncedSummaries] when the Sync tab shows + after a config change. */
    private val _syncedSummaries = MutableStateFlow<Map<String, String>>(emptyMap())
    val syncedSummaries: StateFlow<Map<String, String>> = _syncedSummaries

    fun loadSyncedSummaries() {
        viewModelScope.launch {
            _syncedSummaries.value = listOf("spotify", "applemusic", "listenbrainz")
                .associateWith { formatAxesSummary(settingsStore.getSyncCollectionsForProvider(it)) }
        }
    }

    private fun formatAxesSummary(axes: Set<String>): String {
        val labels = listOf(
            "tracks" to "Liked Songs",
            "albums" to "Albums",
            "artists" to "Artists",
            "playlists" to "Playlists",
        )
        return labels.filter { it.first in axes }.joinToString(" · ") { it.second }
    }

    val syncEnabled = settingsStore.syncEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val lastSyncAt = settingsStore.lastSyncAtFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun proceedFromOptions() {
        // Per Decision D1, Apple Music doesn't expose per-playlist
        // selection in the wizard yet (all-or-nothing). The picker is
        // Spotify-only; non-Spotify providers skip straight to sync.
        val canPickPlaylists = _activeProviderId.value == SpotifySyncProvider.PROVIDER_ID
        if (_syncPlaylists.value && canPickPlaylists) {
            // Transition immediately — show skeleton loaders while fetching
            _playlistsLoading.value = true
            _playlistsError.value = null
            _availablePlaylists.value = emptyList()
            _currentStep.value = SetupStep.PLAYLISTS
            viewModelScope.launch {
                try {
                    val playlists = spotifyProvider.fetchPlaylists()
                    _availablePlaylists.value = playlists
                    // Restore previously saved selection from DataStore.
                    // If DataStore was wiped (app update, data clear), recover
                    // from the sync_sources table which tracks what was synced.
                    val saved = settingsStore.getSyncSettings().selectedPlaylistIds
                    val allIds = playlists.map { it.spotifyId }.toSet()
                    _selectedPlaylistIds.value = if (saved.isNotEmpty()) {
                        saved.intersect(allIds) // keep only IDs that still exist
                    } else {
                        // Recover from Room: if playlists were previously synced,
                        // their Spotify IDs are in sync_sources.externalId
                        val synced = syncSourceDao.getByProvider("spotify", "playlist")
                            .mapNotNull { it.externalId }
                            .toSet()
                        val recovered = synced.intersect(allIds)
                        if (recovered.isNotEmpty()) recovered else allIds
                    }
                } catch (e: Exception) {
                    _playlistsError.value = e.message ?: "Failed to load playlists"
                } finally {
                    _playlistsLoading.value = false
                }
            }
        } else {
            startSync()
        }
    }

    fun goBackToOptions() {
        _currentStep.value = SetupStep.OPTIONS
        _playlistsError.value = null
    }

    fun startSync() {
        viewModelScope.launch {
            val activeId = _activeProviderId.value

            // Compute the new axis set from the wizard checkboxes.
            val newCollections = buildSet {
                if (_syncTracks.value) add("tracks")
                if (_syncAlbums.value) add("albums")
                if (_syncArtists.value) add("artists")
                if (_syncPlaylists.value) add("playlists")
            }

            // Compare against the universal axis set, NOT the previously-stored
            // opt-in. Detect every axis that:
            //   (a) the user is NOT opting into this round, AND
            //   (b) still has items in the user's library (sync_sources count > 0)
            // and surface them in the Keep/Remove confirm dialog.
            //
            // Why universal not oldCollections: a prior wizard run could have
            // updated the stored opt-in (e.g. dropped to `{playlists}`) without
            // its `removeItemsForProviderAxis` call actually completing —
            // coroutine cancelled mid-loop, app backgrounded, the user picked
            // Keep instead of Remove, or the user simply never unchecked that
            // axis in the first place. After that, the persisted opt-in says
            // `{playlists}` but the sync_sources table still has thousands of
            // orphan rows for the dropped axes. Subsequent wizard runs that
            // compute `oldCollections - newCollections` see `{}` and skip the
            // dialog entirely — orphans are invisible forever.
            //
            // The universal-set comparison is self-healing: every wizard run
            // re-checks every axis against current item counts, so orphans
            // surface on the very next pass through the wizard regardless of
            // how the user got there.
            val droppedAxes = (ALL_SYNC_AXES - newCollections)
                .filter { axis -> syncEngine.countItemsForProviderAxis(activeId, axis) > 0 }
            if (droppedAxes.isNotEmpty()) {
                val counts = droppedAxes.associateWith {
                    syncEngine.countItemsForProviderAxis(activeId, it)
                }
                _pendingRemoval.value = RemovalConfirmation(
                    providerId = activeId,
                    droppedAxes = droppedAxes,
                    itemCountByAxis = counts,
                )
                _pendingNewCollections = newCollections
                _currentStep.value = SetupStep.CONFIRM_REMOVAL
                return@launch
            }

            // No removals — proceed straight to syncing.
            persistAndRunSync(activeId, newCollections)
        }
    }

    /** Stash for the post-confirmation continuation. */
    private var _pendingNewCollections: Set<String> = emptySet()

    /** "Keep items" branch — same effect as before: stop tracking
     *  the dropped axes but leave existing items in the library
     *  (their sync_sources rows for this provider stay; future syncs
     *  ignore them per the per-axis gate). */
    fun confirmRemovalKeep() {
        // Switch to SYNCING immediately so the user sees feedback the
        // moment they tap, not after persistAndRunSync's first internal
        // state update.
        _currentStep.value = SetupStep.SYNCING
        _syncProgress.value = com.parachord.shared.sync.SyncEngine.SyncProgress(
            com.parachord.shared.sync.SyncEngine.SyncPhase.TRACKS, 0, 0, "Saving sync settings…"
        )
        viewModelScope.launch {
            try {
                val activeId = _activeProviderId.value
                persistAndRunSync(activeId, _pendingNewCollections)
            } catch (e: Exception) {
                Log.e("SyncViewModel", "confirmRemovalKeep failed", e)
                _syncResult.value = com.parachord.shared.sync.SyncEngine.FullSyncResult(
                    success = false,
                    error = e.message ?: "Failed to save settings",
                )
                _currentStep.value = SetupStep.COMPLETE
            } finally {
                _pendingRemoval.value = null
            }
        }
    }

    /** "Remove items" branch — purge the user's items for each dropped
     *  axis on this provider. Cross-provider survival: items also
     *  synced from another provider stay (we only delete the entity
     *  when no other provider's sync_source row references it). */
    fun confirmRemovalRemove() {
        // Switch to SYNCING immediately so the user sees a spinner
        // while removeItemsForProviderAxis runs (can take seconds for
        // libraries with hundreds of items). Without this, the wizard
        // appears frozen on CONFIRM_REMOVAL after tapping Remove —
        // exactly the "nothing happens" symptom the user reported.
        _currentStep.value = SetupStep.SYNCING
        val confirmation = _pendingRemoval.value
        val totalToRemove = confirmation?.itemCountByAxis?.values?.sum() ?: 0
        _syncProgress.value = com.parachord.shared.sync.SyncEngine.SyncProgress(
            com.parachord.shared.sync.SyncEngine.SyncPhase.TRACKS, 0, totalToRemove, "Removing items…"
        )
        viewModelScope.launch {
            try {
                val activeId = _activeProviderId.value
                if (confirmation != null) {
                    for (axis in confirmation.droppedAxes) {
                        syncEngine.removeItemsForProviderAxis(activeId, axis)
                    }
                }
                persistAndRunSync(activeId, _pendingNewCollections)
            } catch (e: Exception) {
                Log.e("SyncViewModel", "confirmRemovalRemove failed", e)
                _syncResult.value = com.parachord.shared.sync.SyncEngine.FullSyncResult(
                    success = false,
                    error = e.message ?: "Failed to remove items",
                )
                _currentStep.value = SetupStep.COMPLETE
            } finally {
                _pendingRemoval.value = null
            }
        }
    }

    fun cancelRemoval() {
        // User backed out — bounce back to OPTIONS, axis checkboxes
        // retain their wizard-side state. They can adjust and try
        // Start Sync again.
        _pendingRemoval.value = null
        _currentStep.value = SetupStep.OPTIONS
    }

    private suspend fun persistAndRunSync(activeId: String, collections: Set<String>) {
        _currentStep.value = SetupStep.SYNCING
        // Persist per-provider axis opt-in. Earlier this was a single
        // global SyncSettings struct; per-provider lets AM sync only
        // playlists while Spotify syncs everything (or vice versa).
        settingsStore.setSyncCollectionsForProvider(activeId, collections)

        // Add this provider to enabledSyncProviders. Spotify is in
        // by default; AM gets added the first time its wizard finishes.
        val enabled = settingsStore.getEnabledSyncProviders() + activeId
        settingsStore.setEnabledSyncProviders(enabled)

        // Spotify path: keep writing the legacy SyncSettings struct so
        // the existing global-toggle codepaths (sync mass-removal
        // safeguard, etc.) continue to work. AM doesn't write this —
        // its per-provider opt-in is the source of truth.
        if (activeId == SpotifySyncProvider.PROVIDER_ID) {
            settingsStore.saveSyncSettings(SyncSettings(
                enabled = true,
                provider = "spotify",
                syncTracks = _syncTracks.value,
                syncAlbums = _syncAlbums.value,
                syncArtists = _syncArtists.value,
                syncPlaylists = _syncPlaylists.value,
                selectedPlaylistIds = _selectedPlaylistIds.value,
                pushLocalPlaylists = true,
            ))
        }

        syncScheduler.startInAppTimer()
        syncScheduler.enableWorkManagerSync()

        // Filter syncAll to just the active provider so the wizard's
        // progress UI shows ONLY this provider's messages — otherwise
        // a fresh AM-wizard run would also kick off Spotify and the
        // Spotify track-sync messages would dominate the progress
        // display, confusing the user who thinks they're configuring
        // AM. Other providers continue syncing on their normal
        // periodic schedule.
        val result = syncEngine.syncAll(
            onProgress = { progress -> _syncProgress.value = progress },
            providerFilter = activeId,
        )
        _syncResult.value = result
        _currentStep.value = SetupStep.COMPLETE
    }

    fun syncNow() {
        if (_isSyncing.value) return // guard: ignore taps while a sync is running
        _currentStep.value = SetupStep.SYNCING
        _isSyncing.value = true
        viewModelScope.launch {
            try {
                val result = syncEngine.syncAll(
                    onProgress = { progress -> _syncProgress.value = progress },
                )
                _syncResult.value = result
                _currentStep.value = SetupStep.COMPLETE
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun stopSyncing(removeItems: Boolean) {
        viewModelScope.launch {
            syncEngine.stopSyncing(removeItems)
            syncScheduler.stopInAppTimer()
            syncScheduler.disableWorkManagerSync()
        }
    }

    fun resetSetup() {
        _currentStep.value = SetupStep.OPTIONS
        _syncResult.value = null
    }

    // ─────────────────────────────────────────────────────────────────
    // Per-provider sync-config sheet (#266) — the "Configure what syncs"
    // surface reached by tapping an enabled provider. Mirrors iOS's
    // ProviderConfigModel (iosApp SettingsView.swift). Distinct from the
    // wizard above: this is a live, per-axis + per-playlist editor, not a
    // linear setup flow.
    //
    // Two playlist-picker shapes depending on the provider's sync
    // direction:
    //   - PULL providers (Spotify / Apple Music): a checklist of the
    //     user's IMPORTED playlists (id-prefix `<provider>-`). Unchecking
    //     one + confirming raises a Keep/Remove prompt (see
    //     [pendingPullRemoval]).
    //   - PUSH provider (ListenBrainz): a plain checklist of the local
    //     push-eligible playlists, seeded from the ACTUAL live mirrors
    //     (sync_playlist_link) rather than a selection pref. Unchecking one +
    //     confirming raises a single-action "Stop syncing" prompt (see
    //     [pendingPushRemoval]); the save path does a local-only unlink. Checking
    //     a new one writes a channel override admitting the playlist; the next
    //     sync creates + links it.
    // ─────────────────────────────────────────────────────────────────

    /** Lightweight row for the config-sheet pickers (local id + name + count). */
    data class ConfigPlaylist(val id: String, val name: String, val trackCount: Int)

    /** Keep/remove prompt raised when imported playlists are deselected in a
     *  pull provider's picker. [names] drives the dialog copy. */
    data class PullRemovalPrompt(val names: List<String>)

    /** Single-action "Stop syncing" prompt raised when push-mirrored playlists
     *  are deselected in a PUSH provider's picker. [names] drives the copy. */
    data class PushRemovalPrompt(val names: List<String>)

    private val _configProviderId = MutableStateFlow<String?>(null)
    val configProviderId: StateFlow<String?> = _configProviderId

    private val _configLoading = MutableStateFlow(true)
    val configLoading: StateFlow<Boolean> = _configLoading

    /** Axes currently enabled for the config provider (live edit). */
    private val _configAxes = MutableStateFlow<Set<String>>(emptySet())
    val configAxes: StateFlow<Set<String>> = _configAxes

    /** The axes as they were at load — used to detect drops on Done. */
    private var configOriginalAxes: Set<String> = emptySet()

    // Push picker (ListenBrainz). A plain checklist, seeded from the live
    // sync_playlist_link mirrors (NOT the vestigial selection pref). Mutations
    // are buffered in [_configPushChecked] and persisted on Done so a deselect
    // can raise a confirmation first.
    private val _configPushable = MutableStateFlow<List<ConfigPlaylist>>(emptyList())
    val configPushable: StateFlow<List<ConfigPlaylist>> = _configPushable
    /** Local ids currently checked (mirrored) in the push picker. */
    private val _configPushChecked = MutableStateFlow<Set<String>>(emptySet())
    val configPushChecked: StateFlow<Set<String>> = _configPushChecked
    /** Snapshot of the checked set at load — diffed against on Done. */
    private var configOriginalPushChecked: Set<String> = emptySet()

    private val _pendingPushRemoval = MutableStateFlow<PushRemovalPrompt?>(null)
    val pendingPushRemoval: StateFlow<PushRemovalPrompt?> = _pendingPushRemoval

    // Pull picker (Spotify / Apple Music).
    private val _configImported = MutableStateFlow<List<ConfigPlaylist>>(emptyList())
    val configImported: StateFlow<List<ConfigPlaylist>> = _configImported
    /** Local ids of imported playlists the user is keeping synced. */
    private val _configPullChecked = MutableStateFlow<Set<String>>(emptySet())
    val configPullChecked: StateFlow<Set<String>> = _configPullChecked

    // Axis-drop keep/remove prompt (reuses [RemovalConfirmation] + the
    // existing count/remove engine methods).
    private val _configPendingRemoval = MutableStateFlow<RemovalConfirmation?>(null)
    val configPendingRemoval: StateFlow<RemovalConfirmation?> = _configPendingRemoval

    private val _pendingPullRemoval = MutableStateFlow<PullRemovalPrompt?>(null)
    val pendingPullRemoval: StateFlow<PullRemovalPrompt?> = _pendingPullRemoval

    /** Spotify + Apple Music PULL the catalog (choose which service playlists to
     *  import); ListenBrainz PUSHES local playlists up. */
    fun isPullProvider(providerId: String): Boolean =
        providerId == SpotifySyncProvider.PROVIDER_ID || providerId == "applemusic"

    /** ListenBrainz mirrors local playlists OUT (push). */
    fun isPushProvider(providerId: String): Boolean =
        providerId == ListenBrainzSyncProvider.PROVIDER_ID

    /** Axes a provider can sync. ListenBrainz is playlists-only. */
    fun supportedAxes(providerId: String): List<String> =
        if (providerId == com.parachord.shared.sync.ListenBrainzSyncProvider.PROVIDER_ID)
            listOf("playlists")
        else listOf("tracks", "albums", "artists", "playlists")

    private fun remoteIdFor(providerId: String, localId: String): String {
        val prefix = "$providerId-"
        return if (localId.startsWith(prefix)) localId.removePrefix(prefix) else localId
    }

    /** Open the per-provider config sheet, loading its current state. */
    fun openConfig(providerId: String) {
        _configProviderId.value = providerId
        _configLoading.value = true
        _configPendingRemoval.value = null
        _pendingPullRemoval.value = null
        _pendingPushRemoval.value = null
        viewModelScope.launch {
            val ax = settingsStore.getSyncCollectionsForProvider(providerId)
            _configAxes.value = ax
            configOriginalAxes = ax
            if (isPushProvider(providerId)) {
                // Seed "checked" from the ACTUAL live mirrors (links ∩ existing
                // playlists, pull-source rows excluded), NOT the corrupt/vestigial
                // selection pref. The displayed list = push candidates ∪
                // currently-linked rows, so a linked row that's no longer a
                // candidate still appears and can be UNchecked.
                val all = playlistDao.getAllSync().filter { it.name.isNotBlank() }
                val linkedIds = syncEngine.linkedPlaylistIdsForProvider(providerId)
                val pushable = all
                    .filter { isPlaylistPushCandidate(it, providerId) || it.id in linkedIds }
                    .map { ConfigPlaylist(it.id, it.name, it.trackCount) }
                _configPushable.value = pushable
                _configPushChecked.value = linkedIds
                configOriginalPushChecked = linkedIds
            }
            if (isPullProvider(providerId)) {
                val imported = playlistDao.getAllSync()
                    .filter { it.id.startsWith("$providerId-") && it.name.isNotBlank() }
                    .map { ConfigPlaylist(it.id, it.name, it.trackCount) }
                val allow = settingsStore.getPullPlaylists(providerId)
                _configImported.value = imported
                // Empty allowlist = sync all → everything checked. Otherwise check
                // only the imported rows whose remote id is in the allowlist.
                _configPullChecked.value = if (allow.isEmpty()) {
                    imported.map { it.id }.toSet()
                } else {
                    imported.filter { remoteIdFor(providerId, it.id) in allow }
                        .map { it.id }.toSet()
                }
            }
            _configLoading.value = false
        }
    }

    fun closeConfig() {
        _configProviderId.value = null
        _configPendingRemoval.value = null
        _pendingPullRemoval.value = null
        _pendingPushRemoval.value = null
        loadSyncedSummaries() // #303: refresh the card summaries after a config change
    }

    /** Toggle an axis live; persists immediately (matches iOS). */
    fun toggleConfigAxis(axis: String, on: Boolean) {
        val next = if (on) _configAxes.value + axis else _configAxes.value - axis
        _configAxes.value = next
        val providerId = _configProviderId.value ?: return
        viewModelScope.launch {
            settingsStore.setSyncCollectionsForProvider(providerId, next)
        }
    }

    /** Toggle a push-picker row in memory only — persisted on Done (so a
     *  deselect can raise a confirmation first). */
    fun toggleConfigPushChecked(localId: String) {
        _configPushChecked.value = _configPushChecked.value.let {
            if (localId in it) it - localId else it + localId
        }
    }

    fun toggleConfigPullChecked(localId: String) {
        _configPullChecked.value = _configPullChecked.value.let {
            if (localId in it) it - localId else it + localId
        }
    }

    /**
     * Called when the user taps Done. Returns true if a keep/remove prompt was
     * raised (caller must keep the sheet open); false if it persisted cleanly
     * and the sheet may dismiss. Axis-drop prompt takes precedence over the
     * pull-deselect prompt (matches iOS ordering).
     */
    suspend fun configNeedsPrompt(): Boolean {
        val providerId = _configProviderId.value ?: return false
        // 1) Axis-off keep/remove.
        val dropped = configOriginalAxes - _configAxes.value
        val withItems = dropped.filter { syncEngine.countItemsForProviderAxis(providerId, it) > 0 }
        if (withItems.isNotEmpty()) {
            _configPendingRemoval.value = RemovalConfirmation(
                providerId = providerId,
                droppedAxes = withItems,
                itemCountByAxis = withItems.associateWith {
                    syncEngine.countItemsForProviderAxis(providerId, it)
                },
            )
            return true
        }
        // 2) Pull-deselect keep/remove (Spotify / Apple Music only).
        if (isPullProvider(providerId)) {
            val deselected = _configImported.value.filter { it.id !in _configPullChecked.value }
            if (deselected.isNotEmpty()) {
                _pendingPullRemoval.value = PullRemovalPrompt(deselected.map { it.name })
                return true
            }
            // Nothing deselected — persist the (possibly empty) allowlist now.
            applyPullSelection(providerId, removeLocalIds = emptyList(), keepLocalIds = emptyList())
        }
        // 3) Push-deselect "Stop syncing" (ListenBrainz only).
        if (isPushProvider(providerId)) {
            val deselected = _configPushable.value.filter {
                it.id in configOriginalPushChecked && it.id !in _configPushChecked.value
            }
            if (deselected.isNotEmpty()) {
                _pendingPushRemoval.value = PushRemovalPrompt(deselected.map { it.name })
                return true
            }
            // Nothing deselected (additions only / no change) — persist now.
            applyPushSelection()
        }
        return false
    }

    /** Axis keep — leave dropped axes' items in the collection, stop tracking. */
    fun configConfirmAxisKeep() {
        configOriginalAxes = _configAxes.value
        _configPendingRemoval.value = null
    }

    /** Axis remove — purge dropped axes' provider-sourced items (cross-provider
     *  survival applies). Re-uses the shared [SyncEngine.removeItemsForProviderAxis]. */
    fun configConfirmAxisRemove(onDone: () -> Unit) {
        val confirmation = _configPendingRemoval.value
        val providerId = _configProviderId.value
        viewModelScope.launch {
            try {
                if (confirmation != null && providerId != null) {
                    for (axis in confirmation.droppedAxes) {
                        syncEngine.removeItemsForProviderAxis(providerId, axis)
                    }
                }
            } catch (e: Exception) {
                Log.e("SyncViewModel", "configConfirmAxisRemove failed", e)
            } finally {
                configOriginalAxes = _configAxes.value
                _configPendingRemoval.value = null
                onDone()
            }
        }
    }

    fun configCancelAxisRemoval() { _configPendingRemoval.value = null }

    /** Pull keep — detach each deselected playlist from THIS provider only (its
     *  other mirrors keep it, so nothing re-imports), then persist the allowlist. */
    fun configConfirmPullKeep(onDone: () -> Unit) {
        val providerId = _configProviderId.value
        viewModelScope.launch {
            try {
                if (providerId != null) {
                    val deselected = _configImported.value
                        .filter { it.id !in _configPullChecked.value }
                        .map { it.id }
                    applyPullSelection(providerId, removeLocalIds = emptyList(), keepLocalIds = deselected)
                }
            } catch (e: Exception) {
                Log.e("SyncViewModel", "configConfirmPullKeep failed", e)
            } finally {
                _pendingPullRemoval.value = null
                onDone()
            }
        }
    }

    /** Pull remove — delete each deselected playlist's local copy (stays on the
     *  service: [LibraryRepository.deletePlaylistWithSync] with an empty
     *  provider set is a local-only delete), then persist the allowlist. */
    fun configConfirmPullRemove(onDone: () -> Unit) {
        val providerId = _configProviderId.value
        viewModelScope.launch {
            try {
                if (providerId != null) {
                    val deselected = _configImported.value
                        .filter { it.id !in _configPullChecked.value }
                        .map { it.id }
                    applyPullSelection(providerId, removeLocalIds = deselected, keepLocalIds = emptyList())
                }
            } catch (e: Exception) {
                Log.e("SyncViewModel", "configConfirmPullRemove failed", e)
            } finally {
                _pendingPullRemoval.value = null
                onDone()
            }
        }
    }

    fun configCancelPullRemoval() { _pendingPullRemoval.value = null }

    /**
     * Persist the pull picker's result. The allowlist is the still-checked
     * REMOTE ids (empty when everything is checked, so newly-added service
     * playlists keep auto-syncing). Each deselected playlist is resolved:
     * REMOVE deletes the local copy (keeps it on the service);
     * KEEP detaches it from THIS provider so the row survives but stops syncing.
     * The allowlist MUST exclude kept ids or detached rows re-import.
     */
    private suspend fun applyPullSelection(
        providerId: String,
        removeLocalIds: List<String>,
        keepLocalIds: List<String>,
    ) {
        for (id in removeLocalIds) {
            playlistDao.getById(id)?.let { libraryRepository.deletePlaylistWithSync(it, emptySet()) }
            playlistTrackDao.deleteByPlaylistId(id)
        }
        for (id in keepLocalIds) {
            // Detach from THIS provider only — stripping all links would let
            // another mirror's pull (e.g. ListenBrainz) re-import a duplicate.
            syncEngine.detachPlaylistFromProvider(id, providerId)
        }
        val checked = _configPullChecked.value
        val imported = _configImported.value
        val allChecked = checked.size == imported.size
        val allowlist = if (allChecked) emptySet()
        else imported.filter { it.id in checked }.map { remoteIdFor(providerId, it.id) }.toSet()
        settingsStore.setPullPlaylists(providerId, allowlist)
    }

    // ── Push-deselect "Stop syncing" (ListenBrainz) ──────────────────

    /** Confirm the stop-syncing dialog → persist the push diff, then dismiss. */
    fun configConfirmPushStop(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                applyPushSelection()
            } catch (e: Exception) {
                Log.e("SyncViewModel", "configConfirmPushStop failed", e)
            } finally {
                _pendingPushRemoval.value = null
                onDone()
            }
        }
    }

    /** Cancel the stop-syncing dialog — leave the checked set as-is so the user
     *  can re-check; sheet stays open. */
    fun configCancelPushRemoval() { _pendingPushRemoval.value = null }

    /**
     * Persist the push picker's diff. DESELECTED rows (was linked, now unchecked)
     * get a local-only unlink ([SyncEngine.unlinkPlaylistFromProviderLocally]:
     * link + N-way state torn down, channel override excludes the provider, remote
     * untouched). NEWLY-CHECKED rows get the channel override admitting them
     * ([SyncEngine.linkPlaylistToProviderLocally]); the next sync creates + links.
     * Only the diffed rows are mutated; idempotent (re-baselines on completion).
     */
    private suspend fun applyPushSelection() {
        val providerId = _configProviderId.value ?: return
        val deselected = configOriginalPushChecked - _configPushChecked.value
        val newlyChecked = _configPushChecked.value - configOriginalPushChecked
        for (id in deselected) syncEngine.unlinkPlaylistFromProviderLocally(id, providerId)
        for (id in newlyChecked) syncEngine.linkPlaylistToProviderLocally(id, providerId)
        configOriginalPushChecked = _configPushChecked.value
    }
}
