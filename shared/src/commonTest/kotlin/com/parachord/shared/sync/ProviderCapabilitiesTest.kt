package com.parachord.shared.sync

import com.parachord.shared.model.PlaylistTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Guards the per-provider track-remove capability surface (Task 2 of the
 * incremental-materialization plan). The materialize executor dispatches
 * removals on [ProviderFeatures.trackRemoveMode] and NEVER branches on
 * `provider.id`, so the conservative default must be non-destructive.
 */
class ProviderCapabilitiesTest {

    /** Minimal stub implementing only the abstract members of SyncProvider. */
    private fun stubProvider(): SyncProvider = object : SyncProvider {
        override val id = "x"
        override val displayName = "x"
        override val features = ProviderFeatures(snapshots = SnapshotKind.None)

        override suspend fun fetchPlaylists(
            onProgress: ((current: Int, total: Int) -> Unit)?,
        ): List<SyncedPlaylist> = emptyList()

        override suspend fun fetchPlaylistTracks(
            externalPlaylistId: String,
        ): List<PlaylistTrack> = emptyList()

        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null

        override suspend fun createPlaylist(
            name: String,
            description: String?,
        ): RemoteCreated = RemoteCreated(externalId = "id", snapshotId = null)

        override suspend fun replacePlaylistTracks(
            externalPlaylistId: String,
            externalTrackIds: List<String>,
        ): String? = null

        override suspend fun updatePlaylistDetails(
            externalPlaylistId: String,
            name: String?,
            description: String?,
        ) { /* no-op */ }

        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult =
            DeleteResult.Success
    }

    @Test
    fun `trackRemoveMode defaults to ReplaceOnly`() {
        val stub = stubProvider()
        assertEquals(TrackRemoveMode.ReplaceOnly, stub.features.trackRemoveMode)
        assertFalse(stub.features.canReorder)
    }
}
