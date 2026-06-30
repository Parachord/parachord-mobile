package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KvTombstoneStoreTest {
    private class MemBlob {
        var blob: String? = null
        fun store() = KvTombstoneStore({ blob }, { blob = it })
    }

    @Test fun `round-trips through JSON`() {
        val m = MemBlob()
        val s = m.store()
        TrackTombstones.addTombstone(s, "spotify", "abc", 1234)
        // New store instance reading the same blob sees the entry
        val s2 = m.store()
        assertEquals(Tombstone(1234), TrackTombstones.getTombstone(s2, "spotify", "abc"))
    }

    @Test fun `empty store reads as empty map`() {
        assertTrue(MemBlob().store().read().isEmpty())
    }

    @Test fun `corrupt blob reads as empty and does not throw`() {
        val m = MemBlob().apply { blob = "{not valid json" }
        assertTrue(m.store().read().isEmpty())
    }

    @Test fun `persists nested provider buckets`() {
        val m = MemBlob()
        TrackTombstones.addTombstones(m.store(), listOf(
            TombstoneEntry("spotify", "a"),
            TombstoneEntry("applemusic", "b"),
        ), 1000)
        val reread = m.store()
        assertEquals(Tombstone(1000), TrackTombstones.getTombstone(reread, "spotify", "a"))
        assertEquals(Tombstone(1000), TrackTombstones.getTombstone(reread, "applemusic", "b"))
        assertNull(TrackTombstones.getTombstone(reread, "spotify", "missing"))
    }

    @Test fun `empty-object entry decodes to null removedAt - desktop corrupt-entry shape`() {
        // Mirrors desktop's `{ [provider]: { [ext]: {} } }` corrupt entry: an
        // entry with no removedAt must round-trip to Tombstone(null) so
        // pruneExpired treats it as corrupt and sweeps it. Depends on
        // encodeDefaults=false (null omitted) + the nullable constructor default.
        val m = MemBlob().apply { blob = """{"spotify":{"abc":{}}}""" }
        assertEquals(Tombstone(null), TrackTombstones.getTombstone(m.store(), "spotify", "abc"))
    }
}
