package com.parachord.android.sync

import com.parachord.shared.sync.ProviderFeatures
import com.parachord.shared.sync.SnapshotKind
import com.parachord.shared.sync.SyncProvider
import com.parachord.shared.sync.TrackRemoveMode
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies SpotifySyncProvider satisfies the [SyncProvider] interface and
 * declares its capability flags correctly. SyncEngine treats Spotify as
 * snapshot-aware (Opaque token) with full follow/delete/rename/replace
 * support — the contract this test pins.
 *
 * If the contract changes (e.g. Spotify deprecates an endpoint and we
 * downgrade a flag), this test must be updated in the same commit so the
 * intent is explicit.
 */
class SpotifySyncProviderConformanceTest {
    private val provider: SyncProvider = SpotifySyncProvider(
        spotifyClient = mockk(relaxed = true),
    )

    @Test
    fun `id is spotify`() {
        assertEquals("spotify", provider.id)
    }

    @Test
    fun `displayName is human-readable Spotify`() {
        assertEquals("Spotify", provider.displayName)
    }

    @Test
    fun `features declare full Spotify capability`() {
        val expected = ProviderFeatures(
            snapshots = SnapshotKind.Opaque,
            supportsFollow = true,
            supportsPlaylistDelete = true,
            supportsPlaylistRename = true,
            supportsTrackReplace = true,
            // Spotify removes by track URI and preserves explicit order on push.
            trackRemoveMode = TrackRemoveMode.ByNativeId,
            canReorder = true,
        )
        assertEquals(expected, provider.features)
    }

    @Test
    fun `is assignable to SyncProvider`() {
        // Compile-time check: the type system accepts SpotifySyncProvider
        // as SyncProvider. The mere fact that the class-level `provider`
        // val type-checks above is the real proof; this assertion is a
        // belt-and-suspenders runtime check.
        assertTrue(provider is SyncProvider)
    }
}
