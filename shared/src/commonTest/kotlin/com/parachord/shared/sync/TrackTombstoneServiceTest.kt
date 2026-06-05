package com.parachord.shared.sync

import com.parachord.shared.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class TrackTombstoneServiceTest {
    private class FakeStore : TombstoneStore {
        var data: MutableMap<String, MutableMap<String, Tombstone>> = mutableMapOf()
        override fun read() = data
        override fun write(d: Map<String, Map<String, Tombstone>>) {
            data = d.mapValues { (_, v) -> v.toMutableMap() }.toMutableMap()
        }
    }

    @Test fun `deriveTombstoneEntries pulls spotify applemusic listenbrainz`() {
        val t = Track(id = "1", title = "t", artist = "a",
            spotifyId = "sp", appleMusicId = "am", recordingMbid = "mb")
        val e = TrackTombstoneService.deriveTombstoneEntries(t)
        assertEquals(setOf(
            TombstoneEntry("spotify", "sp"),
            TombstoneEntry("applemusic", "am"),
            TombstoneEntry("listenbrainz", "mb"),
        ), e.toSet())
    }

    @Test fun `deriveTombstoneEntries skips null external ids`() {
        val t = Track(id = "1", title = "t", artist = "a", spotifyId = "sp")
        assertEquals(listOf(TombstoneEntry("spotify", "sp")),
            TrackTombstoneService.deriveTombstoneEntries(t))
    }

    @Test fun `deriveTombstoneEntries dedups duplicate provider+external`() {
        // Same id under the same provider must not double-up.
        val t = Track(id = "1", title = "t", artist = "a", spotifyId = "x")
        val e = TrackTombstoneService.deriveTombstoneEntries(t)
        assertEquals(1, e.size)
    }

    @Test fun `addAll then filterRemote drops the item`() = runTest {
        val svc = TrackTombstoneService(FakeStore())
        svc.addAll(listOf(TombstoneEntry("spotify", "x")))
        val r = svc.filterRemote(listOf("x", "y"), "spotify") { it }
        assertEquals(listOf("y"), r.filtered)
        assertEquals(1, r.dropped)
    }

    @Test fun `clearAll lets a previously tombstoned item through`() = runTest {
        val svc = TrackTombstoneService(FakeStore())
        svc.addAll(listOf(TombstoneEntry("spotify", "x")))
        svc.clearAll(listOf(TombstoneEntry("spotify", "x")))
        val r = svc.filterRemote(listOf("x"), "spotify") { it }
        assertEquals(listOf("x"), r.filtered)
        assertEquals(0, r.dropped)
    }

    @Test fun `prune removes expired`() = runTest {
        val store = FakeStore()
        store.data = mutableMapOf("spotify" to mutableMapOf("old" to Tombstone(0)))
        val svc = TrackTombstoneService(store)
        val pruned = svc.prune(ttlMs = 1L) // anything older than 1ms ago is expired
        assertEquals(1, pruned)
        assertNull(TrackTombstones.getTombstone(store, "spotify", "old"))
    }

    @Test fun `addAll returns count written`() = runTest {
        val svc = TrackTombstoneService(FakeStore())
        assertEquals(2, svc.addAll(listOf(TombstoneEntry("spotify", "a"), TombstoneEntry("applemusic", "b"))))
    }
}
