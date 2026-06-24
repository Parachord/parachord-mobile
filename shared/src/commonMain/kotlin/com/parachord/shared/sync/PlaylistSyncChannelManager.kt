package com.parachord.shared.sync

import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.repository.LibraryRepository
import com.parachord.shared.settings.SettingsStore

/**
 * A sync channel row in a playlist's Sync menu. [connected] = the provider is
 * enabled for sync (else dim + "connect in Settings"); [enabled] = this playlist
 * currently syncs with it; [available] = the playlist CAN sync with it (it's the
 * source, or a valid push target) — a non-available channel can't be toggled on.
 */
data class PlaylistSyncChannel(
    val providerId: String,
    val displayName: String,
    val connected: Boolean,
    val enabled: Boolean,
    val available: Boolean,
)

/**
 * Per-playlist Sync menu backend (shared, both platforms). The per-playlist
 * channel override is AUTHORITATIVE — disabling detaches (no dup), enabling
 * mirrors it to that service on the next sync.
 *
 * Ported verbatim from iOS's `IosContainer.getPlaylistSyncChannels` /
 * `setPlaylistChannel` / `disablePlaylistChannel` so the two platforms can't
 * drift. Only shared methods are used.
 */
class PlaylistSyncChannelManager(
    private val settingsStore: SettingsStore,
    private val libraryRepository: LibraryRepository,
    private val playlistDao: PlaylistDao,
    private val syncEngine: SyncEngine,
) {
    private val syncChannelProviders = listOf("spotify", "applemusic", "listenbrainz")

    private fun providerDisplay(id: String): String = when (id) {
        "spotify" -> "Spotify"
        "applemusic" -> "Apple Music"
        "listenbrainz" -> "ListenBrainz"
        else -> id.replaceFirstChar { it.uppercase() }
    }

    /** The sync channels for one playlist — each provider with connected /
     *  enabled / available state, for the playlist Sync menu. */
    suspend fun getChannels(localId: String): List<PlaylistSyncChannel> {
        val enabledProviders = settingsStore.getEnabledSyncProviders()
        val override = settingsStore.getPlaylistChannels(localId)
        val mirrors = libraryRepository.getPlaylistMirrors(localId).keys
        val effective = override ?: mirrors
        val playlist = playlistDao.getById(localId)
        return syncChannelProviders.map { pid ->
            val isSource = localId.startsWith("$pid-")
            val canPush = playlist != null && isPlaylistPushCandidate(playlist, pid)
            PlaylistSyncChannel(
                providerId = pid,
                displayName = providerDisplay(pid),
                connected = pid in enabledProviders,
                enabled = pid in effective,
                available = isSource || canPush,
            )
        }
    }

    /** Toggle one channel for one playlist. Writes the per-playlist override
     *  (authoritative). Disabling also detaches the existing linkage so the
     *  state updates immediately and the next sync's override gate keeps it off.
     *  Enabling sets the override; the next sync mirrors it (push) if it's a
     *  valid target. */
    suspend fun setChannel(localId: String, providerId: String, enabled: Boolean) {
        val override = settingsStore.getPlaylistChannels(localId)
        val current = override ?: libraryRepository.getPlaylistMirrors(localId).keys
        val updated = if (enabled) current + providerId else current - providerId
        settingsStore.setPlaylistChannels(localId, updated)
        if (!enabled) {
            syncEngine.detachPlaylistFromProvider(localId, providerId)
        }
    }

    /**
     * Turn a channel OFF for a playlist, with the "delete from this service too"
     * choice. [deleteRemote] = true also removes the playlist on that provider's
     * remote (best-effort); false just stops syncing (keeps the remote). Returns
     * the provider display name when the remote delete was UNSUPPORTED (Apple
     * Music) so the UI can say "remove it manually", else null.
     */
    suspend fun disableChannel(
        localId: String,
        providerId: String,
        deleteRemote: Boolean,
    ): String? {
        // Capture the external id BEFORE we change the override (getPlaylistMirrors
        // is override-aware, and the override still includes this provider here).
        val externalId = libraryRepository.getPlaylistMirrors(localId)[providerId]
        var unsupported: String? = null
        if (deleteRemote && externalId != null) {
            val result = syncEngine.deletePlaylistOnProvider(providerId, externalId)
            if (result is DeleteResult.Unsupported) {
                unsupported = providerDisplay(providerId)
            }
        }
        val override = settingsStore.getPlaylistChannels(localId)
        val current = override ?: libraryRepository.getPlaylistMirrors(localId).keys
        settingsStore.setPlaylistChannels(localId, current - providerId)
        syncEngine.detachPlaylistFromProvider(localId, providerId)
        return unsupported
    }
}
