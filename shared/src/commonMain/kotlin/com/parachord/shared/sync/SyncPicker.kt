package com.parachord.shared.sync

import com.parachord.shared.db.dao.PlaylistDao
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The rows of a provider's "Configure what syncs" picker, one copy for both
 * apps (#591).
 *
 * The local playlists for which the provider is a channel (its own imports,
 * push candidates, current mirrors), as [PlaylistSyncChannelManager] lists
 * them. For a pull provider (Spotify, Apple Music), the provider's own
 * playlists that aren't imported yet are added after them, so the user can
 * check one to start syncing it. One of those is left out when it's already
 * a local row, a linked mirror copy, or has the name of a local playlist:
 * otherwise it shows twice. A not-imported row is checked when the user
 * already checked it (its override is set before the import runs).
 *
 * The provider's list is fetched at most once a minute ([FETCH_CACHE_MS]), so
 * reopening the picker doesn't call Spotify or Apple Music again. A failed
 * fetch shows the local rows only.
 */
class SyncPickerRows(
    private val channelManager: PlaylistSyncChannelManager,
    private val syncEngine: SyncEngine,
    private val playlistDao: PlaylistDao,
    private val settings: SyncSettingsProvider,
    private val provider: (providerId: String) -> SyncProvider?,
    private val nowMs: () -> Long = { currentTimeMillis() },
) {
    companion object {
        const val FETCH_CACHE_MS = 60_000L

        /** Spotify and Apple Music import their playlists; ListenBrainz only receives ours. */
        fun isPullProvider(providerId: String): Boolean = providerId == "spotify" || providerId == "applemusic"

        /**
         * [local] then the [remote] playlists not already shown (see the class).
         * [checked] holds the not-imported rows' ids the user already checked.
         */
        fun merge(
            providerId: String,
            local: List<ProviderChannelPlaylist>,
            linkedExternalIds: Set<String>,
            localNames: List<String>,
            remote: List<SyncedPlaylist>,
            checked: Set<String>,
        ): List<ProviderChannelPlaylist> {
            val nativeLocalIds = local.filter { !it.isMirror }.map { it.localId }.toSet()
            val names = localNames.mapNotNull { it.trim().lowercase().ifBlank { null } }.toSet()
            val notImported = remote
                .filter {
                    "$providerId-${it.spotifyId}" !in nativeLocalIds &&
                        it.spotifyId !in linkedExternalIds &&
                        it.entity.name.trim().lowercase() !in names
                }
                .map { sp ->
                    val futureId = "$providerId-${sp.spotifyId}"
                    ProviderChannelPlaylist(
                        localId = futureId,
                        name = sp.entity.name,
                        trackCount = sp.trackCount,
                        ownerName = sp.entity.ownerName,
                        createdAt = sp.entity.createdAt,
                        updatedAt = sp.entity.updatedAt,
                        lastModified = sp.entity.lastModified,
                        isMirror = false,
                        originLabel = null,
                        enabled = futureId in checked,
                        notImported = true,
                    )
                }
            return local + notImported
        }
    }

    private val mutex = Mutex()
    private val fetched = mutableMapOf<String, Pair<Long, List<SyncedPlaylist>>>()

    suspend fun rows(providerId: String): List<ProviderChannelPlaylist> {
        val local = channelManager.getProviderChannelPlaylists(providerId)
        if (!isPullProvider(providerId)) return local
        val remote = remotePlaylists(providerId)
        val checked = remote.map { "$providerId-${it.spotifyId}" }
            .filter { settings.getPlaylistChannels(it)?.contains(providerId) == true }
            .toSet()
        return merge(
            providerId = providerId,
            local = local,
            linkedExternalIds = syncEngine.linkedExternalIdsForProvider(providerId),
            localNames = playlistDao.getAllSync().map { it.name },
            remote = remote,
            checked = checked,
        )
    }

    private suspend fun remotePlaylists(providerId: String): List<SyncedPlaylist> {
        val now = nowMs()
        mutex.withLock { fetched[providerId] }?.let { (at, list) -> if (now - at < FETCH_CACHE_MS) return list }
        val source = provider(providerId) ?: return emptyList()
        return try {
            source.fetchPlaylists().also { list -> mutex.withLock { fetched[providerId] = now to list } }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }
}

/** The Sync screen's status text, one copy for both apps (#591). */
object SyncStatusText {
    /** What a provider syncs, e.g. "Liked Songs · Albums · Playlists". */
    fun axesSummary(axes: Set<String>): String =
        listOf("tracks" to "Liked Songs", "albums" to "Albums", "artists" to "Artists", "playlists" to "Playlists")
            .filter { it.first in axes }
            .joinToString(" · ") { it.second }

    /** When the last sync ran: "Never", "Just now", "5 minutes ago", "3 hours ago", "2 days ago". */
    fun lastSynced(timestampMs: Long): String = lastSynced(timestampMs, currentTimeMillis())

    fun lastSynced(timestampMs: Long, nowMs: Long): String {
        if (timestampMs == 0L) return "Never"
        val diff = nowMs - timestampMs
        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000} minutes ago"
            diff < 86_400_000 -> "${diff / 3_600_000} hours ago"
            else -> "${diff / 86_400_000} days ago"
        }
    }
}
