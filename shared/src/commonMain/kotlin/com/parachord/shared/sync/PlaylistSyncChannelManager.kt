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
 * One-way-mirror state for a playlist's Sync menu toggle.
 *  - [effective] drives the toggle's CHECKED state — is the playlist reconciled
 *    as a one-way mirror this cycle? (forced OR the user flag).
 *  - [forced] = the playlist is INHERENTLY one-way and can't be made two-way: a
 *    FOLLOWED (read-only, `writable=false`) provider source — you don't own it, so
 *    it can only mirror OUT — or a hosted-XSPF (`sourceUrl != null`). When forced
 *    the toggle is shown ON but LOCKED (disabled). Only an owned playlist's flag is
 *    user-toggleable.
 */
data class MirrorOnlyState(val effective: Boolean, val forced: Boolean)

/**
 * Per-playlist Sync menu backend (shared, both platforms). The per-playlist
 * channel override is AUTHORITATIVE — disabling detaches (no dup), enabling
 * mirrors it to that service on the next sync.
 *
 * Ported verbatim from iOS's `IosContainer.getPlaylistSyncChannels` /
 * `setPlaylistChannel` / `disablePlaylistChannel` so the two platforms can't
 * drift. Only shared methods are used.
 */
/**
 * One row of the per-provider "Configure what syncs" picker: a playlist that CAN
 * sync with the provider, plus whether it currently does ([enabled]). [isMirror] =
 * a push mirror to this provider (the local row belongs to another provider or is
 * local-only) — rendered with a "mirror" chip. Selection toggles through
 * [PlaylistSyncChannelManager.setChannel] (the same authoritative override the
 * per-playlist Sync menu writes).
 */
data class ProviderChannelPlaylist(
    val localId: String,
    val name: String,
    val trackCount: Int,
    val ownerName: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastModified: Long,
    val isMirror: Boolean,
    /** For a non-native row, the playlist's HOME provider display name
     *  ("Spotify" / "Local" / …) — shown as a chip so the user knows what it is;
     *  null for a provider-native row. */
    val originLabel: String?,
    val enabled: Boolean,
    /** True for a live-fetched provider playlist that has no local row yet —
     *  checking it imports it on the next sync. */
    val notImported: Boolean = false,
)

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
     * Transpose of [getChannels]: for ONE provider, every playlist where that
     * provider is an AVAILABLE channel (the playlist is provider-native, OR a
     * valid push target), each with its current enabled state. Powers the
     * per-provider "Configure what syncs" picker off the SAME authoritative
     * channel override that [getChannels] / [setChannel] use, so the picker and
     * the per-playlist Sync menu can never drift. Toggling a row goes through
     * [setChannel] (detach-on-disable, never delete).
     */
    suspend fun getProviderChannelPlaylists(providerId: String): List<ProviderChannelPlaylist> {
        return playlistDao.getAllSync()
            .filter {
                // Native (this provider's own imports) OR a push candidate (a local
                // playlist that CAN sync to this provider). Both show; checked =
                // currently synced, unchecked = available to enable.
                it.name.isNotBlank() &&
                    (it.id.startsWith("$providerId-") || isPlaylistPushCandidate(it, providerId))
            }
            .map { p ->
                val isNative = p.id.startsWith("$providerId-")
                val override = settingsStore.getPlaylistChannels(p.id)
                val effective = override ?: libraryRepository.getPlaylistMirrors(p.id).keys
                ProviderChannelPlaylist(
                    localId = p.id,
                    name = p.name,
                    trackCount = p.trackCount,
                    ownerName = p.ownerName,
                    createdAt = p.createdAt,
                    updatedAt = p.updatedAt,
                    lastModified = p.lastModified,
                    isMirror = !isNative,
                    originLabel = if (isNative) null else originLabelFor(p.id),
                    enabled = providerId in effective,
                )
            }
    }

    /** A non-native playlist's home-provider chip label (by id prefix). */
    private fun originLabelFor(localId: String): String? = when {
        localId.startsWith("spotify-") -> "Spotify"
        localId.startsWith("applemusic-") -> "Apple Music"
        localId.startsWith("listenbrainz-") -> "ListenBrainz"
        localId.startsWith("local-") -> "Local"
        localId.startsWith("hosted-") -> "Hosted"
        else -> null
    }

    /** Effective + forced one-way-mirror state for this playlist's Sync toggle.
     *  A FOLLOWED (read-only) source or a hosted-XSPF is INHERENTLY one-way
     *  (`forced` → toggle ON + locked); an owned playlist reflects the user flag
     *  and stays toggleable. See [MirrorOnlyState]. */
    suspend fun getMirrorOnlyState(localId: String): MirrorOnlyState {
        val playlist = playlistDao.getById(localId)
        val forced = playlist != null && (!playlist.writable || playlist.sourceUrl != null)
        val flag = settingsStore.getPlaylistMirrorOnly(localId)
        return MirrorOnlyState(effective = forced || flag, forced = forced)
    }

    /** Flag (or clear) this playlist as a one-way mirror. No-op semantics when the
     *  playlist is already FORCED one-way (the UI locks the toggle there). The NEXT
     *  sync reconciles it as single-authority: the source replaces the mirrors each
     *  cycle (exact rotation) instead of being streak-gate-protected. For
     *  OWNED-but-dynamic playlists (a SmarterPlaylists-managed Daily Brew that
     *  Spotify reports as yours) this is the only way to get exact rotation — the
     *  writability split alone can't tell it apart from a hand-curated owned
     *  playlist. */
    suspend fun setMirrorOnly(localId: String, mirrorOnly: Boolean) {
        settingsStore.setPlaylistMirrorOnly(localId, mirrorOnly)
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
