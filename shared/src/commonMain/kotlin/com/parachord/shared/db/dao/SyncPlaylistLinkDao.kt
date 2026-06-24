package com.parachord.shared.db.dao

import com.parachord.shared.db.ParachordDb
import com.parachord.shared.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Durable local→remote playlist ID map, stored independently of the playlists
 * table so a playlist-save that forgets to forward `spotifyId` cannot clobber
 * it. Read before creating a remote playlist to avoid duplicates; written
 * immediately after a successful create or link.
 *
 * Mirrors the desktop `sync_playlist_links` electron-store entry.
 */
class SyncPlaylistLinkDao(private val db: ParachordDb) {

    private val queries get() = db.syncPlaylistLinkQueries

    data class Link(
        val localPlaylistId: String,
        val providerId: String,
        val externalId: String,
        val syncedAt: Long,
        val snapshotId: String? = null,
        val pendingAction: String? = null,
    )

    suspend fun selectAll(): List<Link> = withContext(Dispatchers.Default) {
        queries.selectAll().executeAsList().map { it.toLink() }
    }

    suspend fun selectForLink(localPlaylistId: String, providerId: String): Link? =
        withContext(Dispatchers.Default) {
            queries.selectForLink(localPlaylistId, providerId).executeAsOneOrNull()?.toLink()
        }

    suspend fun upsert(
        localPlaylistId: String,
        providerId: String,
        externalId: String,
        syncedAt: Long = currentTimeMillis(),
    ): Unit = withContext(Dispatchers.Default) {
        queries.upsert(localPlaylistId, providerId, externalId, syncedAt)
    }

    suspend fun upsertWithSnapshot(
        localPlaylistId: String,
        providerId: String,
        externalId: String,
        snapshotId: String?,
        syncedAt: Long = currentTimeMillis(),
    ): Unit = withContext(Dispatchers.Default) {
        queries.upsertWithSnapshot(localPlaylistId, providerId, externalId, snapshotId, syncedAt)
    }

    /**
     * DAO signature is keys-first (localPlaylistId, providerId, pendingAction) to match
     * sibling methods like selectForLink/deleteForLink. The SQLDelight-generated
     * queries.setPendingAction binds in SQL order (pendingAction, localPlaylistId, providerId);
     * the rebinding below is intentional — do NOT "fix" the apparent mismatch.
     */
    suspend fun setPendingAction(
        localPlaylistId: String,
        providerId: String,
        pendingAction: String?,
    ): Unit = withContext(Dispatchers.Default) {
        queries.setPendingAction(pendingAction, localPlaylistId, providerId)
    }

    suspend fun clearPendingAction(localPlaylistId: String, providerId: String): Unit =
        withContext(Dispatchers.Default) {
            queries.clearPendingAction(localPlaylistId, providerId)
        }

    /**
     * One-time cleanup: the N-way swap deleted the NWAY_FILL_PENDING_ACTION="nway-fill"
     * writer, but stale values linger and the legacy push skips any non-null pendingAction
     * (`if (link?.pendingAction != null) continue`), permanently stranding the playlist.
     * Idempotent (0 rows after the first run).
     */
    suspend fun clearStaleNwayFillMarkers(): Unit = withContext(Dispatchers.Default) {
        queries.clearNwayFillPendingActions()
    }

    suspend fun selectPendingForProvider(providerId: String): List<Link> =
        withContext(Dispatchers.Default) {
            queries.selectPendingForProvider(providerId).executeAsList().map { it.toLink() }
        }

    suspend fun selectForLocal(localPlaylistId: String): List<Link> =
        withContext(Dispatchers.Default) {
            queries.selectForLocal(localPlaylistId).executeAsList().map { it.toLink() }
        }

    /**
     * Reverse lookup: find the link row for a given provider's
     * external ID. Used by the per-provider import branch (Phase 5)
     * to detect when an incoming remote already has a local row
     * via a previous sync.
     */
    suspend fun selectByExternalId(providerId: String, externalId: String): Link? =
        withContext(Dispatchers.Default) {
            queries.selectByExternalId(providerId, externalId).executeAsOneOrNull()?.toLink()
        }

    /**
     * True iff at least one mirror exists for [localPlaylistId] under a
     * provider OTHER than [currentProviderId]. Used by Fix 1 of the
     * multi-provider propagation rules: a pull from one provider must
     * flag locallyModified=true so the other mirrors get the update on
     * the next push loop.
     */
    suspend fun hasOtherMirrors(localPlaylistId: String, currentProviderId: String): Boolean =
        withContext(Dispatchers.Default) {
            queries.countOtherMirrors(localPlaylistId, currentProviderId).executeAsOne() > 0L
        }

    /**
     * All mirrors for [localPlaylistId] except those under [excludeProviderId].
     * Used by Fix 4 (`relevantMirrors` clear) — the source provider is
     * excluded because the push loop never targets it, so its `syncedAt`
     * never advances and would strand the flag forever if included.
     */
    suspend fun selectMirrorsExcluding(
        localPlaylistId: String,
        excludeProviderId: String,
    ): List<Link> = withContext(Dispatchers.Default) {
        queries.selectMirrorsExcluding(localPlaylistId, excludeProviderId)
            .executeAsList()
            .map { it.toLink() }
    }

    suspend fun deleteForLink(localPlaylistId: String, providerId: String): Unit =
        withContext(Dispatchers.Default) {
            queries.deleteForLink(localPlaylistId, providerId)
        }

    suspend fun deleteByExternalId(providerId: String, externalId: String): Unit =
        withContext(Dispatchers.Default) {
            queries.deleteByExternalId(providerId, externalId)
        }

    suspend fun deleteForLocal(localPlaylistId: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteForLocal(localPlaylistId)
    }

    suspend fun deleteForProvider(providerId: String): Unit = withContext(Dispatchers.Default) {
        queries.deleteForProvider(providerId)
    }

    private fun com.parachord.shared.db.Sync_playlist_link.toLink() = Link(
        localPlaylistId = localPlaylistId,
        providerId = providerId,
        externalId = externalId,
        snapshotId = snapshotId,
        pendingAction = pendingAction,
        syncedAt = syncedAt,
    )

    /**
     * `selectPendingForProvider` filters on `pendingAction IS NOT NULL`, so
     * SQLDelight generates a bespoke `SelectPendingForProvider` row type with
     * `pendingAction: String` (non-null) instead of reusing `Sync_playlist_link`.
     * Widen back to the nullable `Link.pendingAction` so callers see a single type.
     */
    private fun com.parachord.shared.db.SelectPendingForProvider.toLink() = Link(
        localPlaylistId = localPlaylistId,
        providerId = providerId,
        externalId = externalId,
        snapshotId = snapshotId,
        pendingAction = pendingAction,
        syncedAt = syncedAt,
    )
}
