package com.parachord.shared.metadata

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Port of desktop's createArtistBioCache tests (parachord#1133, #429). */
class ArtistInfoCacheTest {
    private val wiki = ArtistInfo(name = "Wild Pink", bio = "Wild Pink is an American rock band.", bioSource = "wikipedia")
    private val lastfm = ArtistInfo(name = "Wild Pink", bio = "x".repeat(60), bioSource = "lastfm")
    private var now = 0L
    private fun kotlinx.coroutines.test.TestScope.cache(ttl: Long = 1_000L, fallbackTtl: Long = 100L, max: Int = 300) =
        ArtistInfoCache(
        ttlMs = ttl, fallbackTtlMs = fallbackTtl, maxEntries = max, nowMs = { now },
        // A supervisor scope on the test scheduler: a failing fetch must reach the
        // caller, not fail the test the way backgroundScope does.
        scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        ),
    )

    @Test fun aFoundBioIsReusedByTheNextCaller() = runTest {
        val c = cache()
        var fetches = 0
        assertEquals(wiki, c.load("Wild Pink") { fetches++; wiki })
        assertEquals(wiki, c.load("wild pink ") { fetches++; wiki })
        assertEquals(1, fetches)
    }

    @Test fun concurrentCallersShareOneFetch() = runTest {
        val c = cache()
        val release = CompletableDeferred<Unit>()
        var fetches = 0
        val a = async { c.load("Wild Pink") { fetches++; release.await(); wiki } }
        val b = async { c.load("Wild Pink") { fetches++; release.await(); wiki } }
        testScheduler.runCurrent()
        release.complete(Unit)
        assertEquals(listOf(wiki, wiki), listOf(a.await(), b.await()))
        assertEquals(1, fetches)
    }

    @Test fun noBioIsNotKept() = runTest {
        val c = cache()
        val noBio = ArtistInfo(name = "Wild Pink")
        assertEquals(noBio, c.load("Wild Pink") { noBio })   // returned, not kept
        assertEquals(wiki, c.load("Wild Pink") { wiki })
    }

    @Test fun aNonWikipediaBioIsKeptOnlyBriefly_soAWikipediaBioCanReplaceIt() = runTest {
        val c = cache(ttl = 1_000L, fallbackTtl = 100L)
        assertEquals(lastfm, c.load("Wild Pink") { lastfm })
        now = 150L
        assertEquals(wiki, c.load("Wild Pink") { wiki })
        now = 900L
        assertEquals(wiki, c.load("Wild Pink") { error("should be cached") })
    }

    @Test fun entriesExpire_andTheCacheIsBounded() = runTest {
        val c = cache(ttl = 1_000L, max = 2)
        var fetches = 0
        c.load("A") { fetches++; wiki }
        now = 1_500L
        c.load("A") { fetches++; wiki }
        assertEquals(2, fetches)
        c.load("B") { wiki }
        c.load("C") { wiki }
        assertEquals(2, c.size)
    }

    @Test fun aFetchThatThrowsIsNotKept() = runTest {
        val c = cache()
        assertFailsWith<IllegalStateException> { c.load("Wild Pink") { error("boom") } }
        assertEquals(wiki, c.load("Wild Pink") { wiki })
    }

    @Test fun noNameNoFetch() = runTest {
        assertNull(cache().load("  ") { error("should not fetch") })
    }
}
