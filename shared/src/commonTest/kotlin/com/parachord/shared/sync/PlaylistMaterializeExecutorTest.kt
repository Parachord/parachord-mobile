package com.parachord.shared.sync

import com.parachord.shared.model.PlaylistTrack
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Focused tests for the capability-dispatched, non-destructive materialize
 * executor ([materializeToProvider]).
 *
 * Each test drives a [FakeProvider] with a configurable
 * [ProviderFeatures.trackRemoveMode] and asserts on the RECORDED calls — what the
 * executor actually issued — not just the returned counts. The core invariant
 * under test is non-destructive: an add that can't resolve a native id is left
 * pending and NO existing remote track is dropped.
 */
class PlaylistMaterializeExecutorTest {

    // Distinct title+artist per track so the `norm` identity tier doesn't union
    // unrelated tracks. The recording-mbid tier is the strongest present, so the
    // representative is `mbid-<value>` and identity == the mbid string.
    private fun t(id: String): PlaylistTrack =
        PlaylistTrack(
            playlistId = "p",
            position = 0,
            trackTitle = "title-$id",
            trackArtist = "artist-$id",
            trackRecordingMbid = id,
        )

    /**
     * Minimal in-test provider. `nativeIdOf` returns the track's recordingMbid as a
     * stand-in native id (the same column the diff keys on, so remote-side native
     * ids are deterministic). Records every write call. Inherits the Task-3
     * throwing defaults for the remove variant that doesn't match its mode.
     */
    private class FakeProvider(
        override val features: ProviderFeatures,
        // recordingMbid values for which nativeIdOf returns null — simulates an
        // existing canonical track whose provider native-id column is null (it matched
        // the remote on the isrc/norm identity tier, not the native-id column).
        private val nullNativeIdFor: Set<String> = emptySet(),
    ) : SyncProvider {
        override val id = "fake"
        override val displayName = "Fake"

        val addCalls = mutableListOf<List<String>>()
        val removeByNativeIdCalls = mutableListOf<List<String>>()
        val removeByPositionCalls = mutableListOf<List<Int>>()
        val replaceCalls = mutableListOf<List<String>>()

        override fun nativeIdOf(track: PlaylistTrack): String? =
            track.trackRecordingMbid?.takeIf { it !in nullNativeIdFor }

        override suspend fun addPlaylistTracks(
            externalPlaylistId: String,
            externalTrackIds: List<String>,
        ): String? {
            addCalls.add(externalTrackIds)
            return "snap"
        }

        override suspend fun removePlaylistTracksByNativeId(
            externalPlaylistId: String,
            externalTrackIds: List<String>,
        ): String? {
            // Honor the mode dispatch contract: throw if mis-dispatched.
            if (features.trackRemoveMode != TrackRemoveMode.ByNativeId) {
                return super.removePlaylistTracksByNativeId(externalPlaylistId, externalTrackIds)
            }
            removeByNativeIdCalls.add(externalTrackIds)
            return "snap"
        }

        override suspend fun removePlaylistTracksByPosition(
            externalPlaylistId: String,
            positions: List<Int>,
        ): String? {
            if (features.trackRemoveMode != TrackRemoveMode.ByPosition) {
                return super.removePlaylistTracksByPosition(externalPlaylistId, positions)
            }
            removeByPositionCalls.add(positions)
            return "snap"
        }

        override suspend fun replacePlaylistTracks(
            externalPlaylistId: String,
            externalTrackIds: List<String>,
        ): String? {
            replaceCalls.add(externalTrackIds)
            return "snap"
        }

        // ── Remaining abstract members — unused by the executor ──
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

        override suspend fun updatePlaylistDetails(
            externalPlaylistId: String,
            name: String?,
            description: String?,
        ) { /* no-op */ }

        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult =
            DeleteResult.Success
    }

    private fun features(
        mode: TrackRemoveMode,
        canReorder: Boolean = false,
    ) = ProviderFeatures(snapshots = SnapshotKind.None, trackRemoveMode = mode, canReorder = canReorder)

    /** resolveNativeId that hydrates only tracks whose id is in [resolvable]. */
    private fun resolverFor(resolvable: Set<String>): suspend (PlaylistTrack) -> String? =
        { track -> track.trackRecordingMbid?.takeIf { it in resolvable } }

    /** resolveNativeId that hydrates everything (id == recordingMbid). */
    private val resolveAll: suspend (PlaylistTrack) -> String? =
        { it.trackRecordingMbid }

    @Test
    fun `ByNativeId — adds resolved are appended and removes target remote native ids`() = runTest {
        // canonical = {a, b, c}; remote = {a, x}. add {b, c}; remove {x}.
        val provider = FakeProvider(features(TrackRemoveMode.ByNativeId))
        val result = materializeToProvider(
            provider = provider,
            externalId = "ext",
            canonical = listOf(t("a"), t("b"), t("c")),
            remote = listOf(t("a"), t("x")),
            resolveNativeId = resolveAll,
        )

        assertEquals(1, provider.addCalls.size)
        assertEquals(setOf("b", "c"), provider.addCalls.single().toSet())
        assertEquals(1, provider.removeByNativeIdCalls.size)
        assertEquals(listOf("x"), provider.removeByNativeIdCalls.single())
        assertEquals(2, result.added)
        assertEquals(1, result.removed)
        assertEquals(0, result.pendingAdds)
        assertEquals(0, result.unsupportedRemoves)
    }

    @Test
    fun `ByPosition — removes dispatch as DISTINCT positions`() = runTest {
        // remote of 4: [a, b, c, d]; canonical drops index 1 (b) and 3 (d) → {a, c}.
        val provider = FakeProvider(features(TrackRemoveMode.ByPosition))
        val result = materializeToProvider(
            provider = provider,
            externalId = "ext",
            canonical = listOf(t("a"), t("c")),
            remote = listOf(t("a"), t("b"), t("c"), t("d")),
            resolveNativeId = resolveAll,
        )

        assertEquals(1, provider.removeByPositionCalls.size)
        val positions = provider.removeByPositionCalls.single()
        assertEquals(setOf(1, 3), positions.toSet())
        assertEquals(positions.size, positions.distinct().size) // distinct
        assertEquals(2, result.removed)
        assertEquals(0, result.added)
        assertTrue(provider.addCalls.isEmpty())
        assertTrue(provider.removeByNativeIdCalls.isEmpty())
    }

    @Test
    fun `Unsupported — removes are skipped and counted while adds still applied`() = runTest {
        // canonical = {a, b}; remote = {a, x}. add {b}; remove {x} is unsupported.
        val provider = FakeProvider(features(TrackRemoveMode.Unsupported))
        val result = materializeToProvider(
            provider = provider,
            externalId = "ext",
            canonical = listOf(t("a"), t("b")),
            remote = listOf(t("a"), t("x")),
            resolveNativeId = resolveAll,
        )

        assertEquals(1, result.unsupportedRemoves) // one removeKey
        assertTrue(provider.removeByNativeIdCalls.isEmpty())
        assertTrue(provider.removeByPositionCalls.isEmpty())
        // Adds still applied.
        assertEquals(1, provider.addCalls.size)
        assertEquals(listOf("b"), provider.addCalls.single())
        assertEquals(1, result.added)
        assertEquals(0, result.removed)
    }

    @Test
    fun `ReplaceOnly full coverage replaces - partial coverage degrades to add-only`() = runTest {
        // ── Sub-case 1: full coverage (every add resolves) AND a remove → one replace.
        // canonical = {a, b}; remote = {a, x}. add {b} (resolvable), remove {x}.
        val full = FakeProvider(features(TrackRemoveMode.ReplaceOnly))
        val r1 = materializeToProvider(
            provider = full,
            externalId = "ext",
            canonical = listOf(t("a"), t("b")),
            remote = listOf(t("a"), t("x")),
            resolveNativeId = resolverFor(setOf("a", "b")),
        )
        assertEquals(1, full.replaceCalls.size)
        // Replace carries the full canonical native-id list in canonical order.
        assertEquals(listOf("a", "b"), full.replaceCalls.single())
        assertTrue(full.addCalls.isEmpty())
        assertEquals(1, r1.removed)
        assertEquals(1, r1.added)
        assertEquals(0, r1.pendingAdds)
        assertEquals(0, r1.unsupportedRemoves)

        // ── Sub-case 2: partial coverage (an add can't resolve) → NO replace, add-only.
        // canonical = {a, b, c}; remote = {a, x}. add {b, c} but c won't resolve;
        // remove {x} can't be honored (replace unsafe).
        val partial = FakeProvider(features(TrackRemoveMode.ReplaceOnly))
        val r2 = materializeToProvider(
            provider = partial,
            externalId = "ext",
            canonical = listOf(t("a"), t("b"), t("c")),
            remote = listOf(t("a"), t("x")),
            resolveNativeId = resolverFor(setOf("a", "b")), // c unresolvable
        )
        assertTrue(partial.replaceCalls.isEmpty()) // never replaced
        // Add-only path: only the resolvable add (b) is appended; c is pending.
        assertEquals(1, partial.addCalls.size)
        assertEquals(listOf("b"), partial.addCalls.single())
        assertEquals(1, r2.added)
        assertEquals(1, r2.pendingAdds)
        assertEquals(1, r2.unsupportedRemoves) // remove couldn't be honored
        assertEquals(0, r2.removed)
    }

    @Test
    fun `ReplaceOnly — an existing canonical track with a null native id degrades to additive with no shrinking replace`() = runTest {
        // The I1 regression: every ADD resolves, but an EXISTING canonical track (`a`,
        // already on the remote) has a null native id — it matched the remote on the
        // isrc/norm identity tier, not its native-id column. The OLD full-coverage gate
        // only checked add-coverage, then silently filtered `a` out of the replace list
        // and issued a SHRINKING replace [b] — dropping `a` from the remote.
        //
        // canonical = {a, b}; remote = {a, x}. add {b} (resolves), remove {x}.
        // `a`'s nativeIdOf returns null → coverage is incomplete → NO replace; degrade
        // to additive: append the resolvable add (b), skip the remove, keep `a`.
        val provider = FakeProvider(
            features(TrackRemoveMode.ReplaceOnly),
            nullNativeIdFor = setOf("a"),
        )
        val result = materializeToProvider(
            provider = provider,
            externalId = "ext",
            canonical = listOf(t("a"), t("b")),
            remote = listOf(t("a"), t("x")),
            resolveNativeId = resolverFor(setOf("a", "b")), // both adds would resolve
        )

        assertTrue(provider.replaceCalls.isEmpty()) // no shrinking replace
        // Only the resolvable add (b) is appended; `a` stays put on the remote.
        assertEquals(1, provider.addCalls.size)
        assertEquals(listOf("b"), provider.addCalls.single())
        assertEquals(1, result.added)
        assertEquals(0, result.removed)
        assertEquals(1, result.unsupportedRemoves) // remove couldn't be honored
        assertEquals(0, result.pendingAdds)
    }

    @Test
    fun `unresolvable add is left pending and never drops existing remote`() = runTest {
        // The core non-destructive guarantee. canonical = {a, b}; remote = {a}.
        // add {b} but b won't resolve → pending; remote's existing track a untouched,
        // NO remove issued.
        val provider = FakeProvider(features(TrackRemoveMode.ByNativeId))
        val result = materializeToProvider(
            provider = provider,
            externalId = "ext",
            canonical = listOf(t("a"), t("b")),
            remote = listOf(t("a")),
            resolveNativeId = resolverFor(emptySet()), // nothing resolves
        )

        assertEquals(1, result.pendingAdds)
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
        assertTrue(provider.addCalls.isEmpty()) // empty-add guard
        assertTrue(provider.removeByNativeIdCalls.isEmpty()) // no remove of existing track
    }

    @Test
    fun `no resolved adds — addPlaylistTracks is NOT called`() = runTest {
        // canonical = {a, b} vs remote = {a}; add {b} but it won't resolve.
        val provider = FakeProvider(features(TrackRemoveMode.ByNativeId))
        materializeToProvider(
            provider = provider,
            externalId = "ext",
            canonical = listOf(t("a"), t("b")),
            remote = listOf(t("a")),
            resolveNativeId = resolverFor(emptySet()),
        )
        assertTrue(provider.addCalls.isEmpty())
    }

    @Test
    fun `idempotent — canonical equals remote produces zero ops`() = runTest {
        val provider = FakeProvider(features(TrackRemoveMode.ByNativeId, canReorder = true))
        val tracks = listOf(t("a"), t("b"), t("c"))
        val result = materializeToProvider(
            provider = provider,
            externalId = "ext",
            canonical = tracks,
            remote = tracks,
            resolveNativeId = resolveAll,
        )

        assertTrue(provider.addCalls.isEmpty())
        assertTrue(provider.removeByNativeIdCalls.isEmpty())
        assertTrue(provider.removeByPositionCalls.isEmpty())
        assertTrue(provider.replaceCalls.isEmpty())
        assertEquals(MaterializeResult(added = 0, removed = 0, pendingAdds = 0, unsupportedRemoves = 0), result)
    }
}
