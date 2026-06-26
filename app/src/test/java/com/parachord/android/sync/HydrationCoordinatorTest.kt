package com.parachord.android.sync

import com.parachord.android.data.db.TestDatabaseFactory
import com.parachord.shared.db.dao.TrackProviderIdCacheDao
import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.sync.DeleteResult
import com.parachord.shared.sync.HydrationCoordinator
import com.parachord.shared.sync.ProviderFeatures
import com.parachord.shared.sync.RemoteCreated
import com.parachord.shared.sync.SnapshotKind
import com.parachord.shared.sync.SyncProvider
import com.parachord.shared.sync.SyncedPlaylist
import com.parachord.shared.sync.canonicalTrackKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HydrationCoordinator (incremental-materialization Task 7b) — budgeted, cached,
 * cooldown-gated provider-id resolution. Uses a real in-memory
 * [TrackProviderIdCacheDao] (via [TestDatabaseFactory]) + a fake [SyncProvider]
 * with a counting `searchForTrackId` and `nativeIdOf` configurable per track.
 */
class HydrationCoordinatorTest {

    /**
     * Fake provider: counts `searchForTrackId` calls and returns
     * [searchResultFor] per track title. `nativeIdOf` returns whatever
     * [nativeId] yields (null by default → no existing-row id).
     */
    private class FakeProvider(
        val searchResultFor: (title: String) -> String? = { null },
        val nativeId: (PlaylistTrack) -> String? = { null },
    ) : SyncProvider {
        override val id = "fakeprovider"
        override val displayName = "Fake"
        override val features = ProviderFeatures(snapshots = SnapshotKind.None)

        var searchCalls = 0
            private set

        override fun nativeIdOf(track: PlaylistTrack): String? = nativeId(track)

        override suspend fun searchForTrackId(
            title: String,
            artist: String,
            album: String?,
            isrc: String?,
        ): String? {
            searchCalls++
            return searchResultFor(title)
        }

        // Unused surface for these tests.
        override suspend fun fetchPlaylists(onProgress: ((Int, Int) -> Unit)?): List<SyncedPlaylist> = emptyList()
        override suspend fun fetchPlaylistTracks(externalPlaylistId: String): List<PlaylistTrack> = emptyList()
        override suspend fun getPlaylistSnapshotId(externalPlaylistId: String): String? = null
        override suspend fun createPlaylist(name: String, description: String?): RemoteCreated =
            RemoteCreated("x", null)
        override suspend fun replacePlaylistTracks(externalPlaylistId: String, externalTrackIds: List<String>): String? = null
        override suspend fun updatePlaylistDetails(externalPlaylistId: String, name: String?, description: String?) {}
        override suspend fun deletePlaylist(externalPlaylistId: String): DeleteResult = DeleteResult.Success
    }

    private fun track(title: String, artist: String = "A", album: String? = null) = PlaylistTrack(
        playlistId = "pl",
        position = 0,
        trackTitle = title,
        trackArtist = artist,
        trackAlbum = album,
    )

    @Test
    fun `existing id on the row short-circuits — no search`() = runBlocking {
        val dao = TrackProviderIdCacheDao(TestDatabaseFactory.create())
        val provider = FakeProvider(nativeId = { "already-on-row" })
        val coordinator = HydrationCoordinator(dao)

        val id = coordinator.resolve(provider, track("Song"))

        assertEquals("already-on-row", id)
        assertEquals(0, provider.searchCalls)
        // No cache row written for an existing-id short-circuit.
        assertNull(dao.select(canonicalTrackKey(track("Song")), provider.id))
    }

    @Test
    fun `cache hit short-circuits — search not called`() = runBlocking {
        val dao = TrackProviderIdCacheDao(TestDatabaseFactory.create())
        val t = track("Cached")
        // Pre-seed a resolved cache entry.
        dao.upsert(canonicalTrackKey(t), "fakeprovider", resolvedId = "cached-id", lastAttemptAt = 0L, attempts = 1L)
        val provider = FakeProvider(searchResultFor = { "should-not-be-used" })
        val coordinator = HydrationCoordinator(dao)

        val id = coordinator.resolve(provider, t)

        assertEquals("cached-id", id)
        assertEquals(0, provider.searchCalls)
    }

    @Test
    fun `cooldown — a failed attempt is NOT re-searched within the window`() = runBlocking {
        val dao = TrackProviderIdCacheDao(TestDatabaseFactory.create())
        var now = 1_000_000L
        // Always-miss provider.
        val provider = FakeProvider(searchResultFor = { null })
        val coordinator = HydrationCoordinator(dao, nowMs = { now })
        val t = track("Missing")

        // First resolve: misses, records the attempt + stamps the cache.
        val first = coordinator.resolve(provider, t)
        assertNull(first)
        assertEquals(1, provider.searchCalls)
        // The cache now holds a null-resolvedId miss with attempts=1.
        val entry = dao.select(canonicalTrackKey(t), provider.id)
        assertNotNull(entry)
        assertNull(entry!!.resolvedId)
        assertEquals(1L, entry.attempts)

        // Advance the clock by LESS than the first-miss cooldown (7 days).
        now += 6L * 24 * 3600_000 // 6 days < 7-day window

        // Second resolve: must short-circuit on the cooldown — search count UNCHANGED.
        val second = coordinator.resolve(provider, t)
        assertNull(second)
        assertEquals("re-searched within cooldown window", 1, provider.searchCalls)
    }

    @Test
    fun `cooldown expiry — past the window it re-searches`() = runBlocking {
        val dao = TrackProviderIdCacheDao(TestDatabaseFactory.create())
        var now = 1_000_000L
        val provider = FakeProvider(searchResultFor = { null })
        val coordinator = HydrationCoordinator(dao, nowMs = { now })
        val t = track("Missing")

        coordinator.resolve(provider, t)
        assertEquals(1, provider.searchCalls)

        // Advance PAST the 7-day first-miss cooldown.
        now += 8L * 24 * 3600_000

        coordinator.resolve(provider, t)
        assertEquals("should re-search after the cooldown expires", 2, provider.searchCalls)
    }

    @Test
    fun `budget cap — the 3rd distinct unresolved track returns null without a search`() = runBlocking {
        val dao = TrackProviderIdCacheDao(TestDatabaseFactory.create())
        val provider = FakeProvider(searchResultFor = { null }) // all miss → each consumes budget
        val coordinator = HydrationCoordinator(dao, maxInlineLookups = 2)

        coordinator.resolve(provider, track("One"))
        coordinator.resolve(provider, track("Two"))
        val third = coordinator.resolve(provider, track("Three"))

        assertNull(third)
        // Only the first two used a live search; the 3rd short-circuited on budget.
        assertEquals(2, provider.searchCalls)
        // The over-budget track was NOT stamped (no attempt was actually made).
        assertNull(dao.select(canonicalTrackKey(track("Three")), provider.id))
    }

    @Test
    fun `resolve success writes the cache and calls persistRowId`() = runBlocking {
        val dao = TrackProviderIdCacheDao(TestDatabaseFactory.create())
        var persisted: Triple<String, String, String>? = null
        val provider = FakeProvider(searchResultFor = { "found-id" })
        val coordinator = HydrationCoordinator(
            dao,
            persistRowId = { track, providerId, nativeId ->
                persisted = Triple(track.trackTitle, providerId, nativeId)
            },
        )
        val t = track("Findable")

        val id = coordinator.resolve(provider, t)

        assertEquals("found-id", id)
        // Cache row written with the resolved id.
        val entry = dao.select(canonicalTrackKey(t), provider.id)
        assertNotNull(entry)
        assertEquals("found-id", entry!!.resolvedId)
        assertEquals(1L, entry.attempts)
        // persistRowId was invoked with (track, providerId, nativeId).
        assertEquals(Triple("Findable", "fakeprovider", "found-id"), persisted)
    }
}
