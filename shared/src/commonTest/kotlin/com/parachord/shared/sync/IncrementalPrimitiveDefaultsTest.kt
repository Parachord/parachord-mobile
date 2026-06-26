package com.parachord.shared.sync

import com.parachord.shared.model.PlaylistTrack
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Guards the incremental add/remove PRIMITIVES introduced in Task 3 of the
 * incremental-materialization plan.
 *
 * The materialize executor (Task 5) dispatches removals on
 * [ProviderFeatures.trackRemoveMode]; the two remove variants and the append
 * MUST default to THROWING so a mis-dispatch (or a provider that forgot to
 * override the relevant primitive) is LOUD, never silently destructive.
 *
 * A provider that only implements the abstract members of [SyncProvider] — and
 * thus inherits the three new defaults — must throw [UnsupportedOperationException]
 * from each of `addPlaylistTracks`, `removePlaylistTracksByNativeId`, and
 * `removePlaylistTracksByPosition`.
 */
class IncrementalPrimitiveDefaultsTest {

    /** Minimal stub implementing ONLY the abstract members of SyncProvider. */
    private fun stubProvider(): SyncProvider = object : SyncProvider {
        override val id = "stub"
        override val displayName = "Stub"
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
    fun `addPlaylistTracks default throws UnsupportedOperationException`() = runTest {
        val stub = stubProvider()
        assertFailsWith<UnsupportedOperationException> {
            stub.addPlaylistTracks("ext", listOf("a"))
        }
    }

    @Test
    fun `removePlaylistTracksByNativeId default throws UnsupportedOperationException`() = runTest {
        val stub = stubProvider()
        assertFailsWith<UnsupportedOperationException> {
            stub.removePlaylistTracksByNativeId("ext", listOf("a"))
        }
    }

    @Test
    fun `removePlaylistTracksByPosition default throws UnsupportedOperationException`() = runTest {
        val stub = stubProvider()
        assertFailsWith<UnsupportedOperationException> {
            stub.removePlaylistTracksByPosition("ext", listOf(0))
        }
    }
}
