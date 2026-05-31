package com.parachord.android.ui.screens.sync

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.data.db.dao.SyncSourceDao
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.sync.SpotifySyncProvider
import com.parachord.android.sync.SyncEngine
import com.parachord.android.sync.SyncScheduler
import com.parachord.android.sync.SyncedPlaylist
import com.parachord.shared.sync.SyncProvider
import com.parachord.shared.sync.SyncSettings
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
class SyncViewModel constructor(
    private val syncEngine: SyncEngine,
    private val syncScheduler: SyncScheduler,
    private val settingsStore: SettingsStore,
    private val spotifyProvider: SpotifySyncProvider,
    private val syncSourceDao: SyncSourceDao,
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
        _currentStep.value = SetupStep.SYNCING
        viewModelScope.launch {
            val result = syncEngine.syncAll(
                onProgress = { progress -> _syncProgress.value = progress },
            )
            _syncResult.value = result
            _currentStep.value = SetupStep.COMPLETE
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
}
