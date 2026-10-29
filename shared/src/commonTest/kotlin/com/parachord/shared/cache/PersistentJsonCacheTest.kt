package com.parachord.shared.cache

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersistentJsonCacheTest {
    private var disk: String? = null
    private var now = 1_000_000L

    private fun kotlinx.coroutines.test.TestScope.cache(ttl: Long = 10_000L, max: Int = 3) = PersistentJsonCache(
        String.serializer(), read = { disk }, write = { disk = it },
        ttlMs = ttl, maxEntries = max, scope = backgroundScope, nowMs = { now },
    )

    @Test
    fun survivesARestart() = runTest {
        val first = cache()
        first.put("a", "tracklist A")
        first.flush()
        assertEquals("tracklist A", cache().get("a"))
    }

    @Test
    fun putsAreSavedWithoutAnExplicitFlush() = runTest {
        cache().put("a", "A")
        // The debounced save runs on the background scope once its delay passes.
        testScheduler.advanceTimeBy(3_000L)
        testScheduler.runCurrent()
        assertEquals("A", cache().get("a"))
    }

    @Test
    fun expiredEntriesAreMisses() = runTest {
        val c = cache(ttl = 10_000L)
        c.put("a", "A")
        now += 10_000L
        assertNull(c.get("a"))
    }

    @Test
    fun theOldestEntriesGoOverTheCap() = runTest {
        val c = cache(max = 2)
        c.put("a", "A"); now += 1
        c.put("b", "B"); now += 1
        c.put("c", "C")
        assertNull(c.get("a"))
        assertEquals("B", c.get("b"))
        assertEquals("C", c.get("c"))
    }

    @Test
    fun anUnreadableBlobStartsEmpty() = runTest {
        disk = "{not json"
        assertNull(cache().get("a"))
    }

    @Test
    fun allReturnsTheLiveEntriesAfterARestart() = runTest {
        val first = cache(ttl = 10_000L)
        first.put("old", "O")
        now += 6_000L
        first.put("new", "N")
        first.flush()
        now += 5_000L
        assertEquals(mapOf("new" to "N"), cache(ttl = 10_000L).all())
    }
}
