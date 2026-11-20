package com.parachord.shared.repository

import com.parachord.shared.home.HistoryMix
import com.parachord.shared.plugin.PluginNotReadyException
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Desktop's `loadHistoryMixes` / `loadHomeHistoryMixes` (`app.js` ~L28127–28330), shared. */
class HistoryMixesRepositoryTest {

    private class FakeSecureStore : SecureTokenStore {
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) {}
        override fun remove(key: String) {}
        override fun contains(key: String): Boolean = false
        override fun observe(key: String): Flow<String?> = flowOf(null)
    }

    /** Each provider answers with the next function queued for it; the last one repeats. */
    private class FakeSource(
        val providerList: List<RecommendationProvider> = listOf(LB),
    ) : HistoryMixSource {
        val answers = mutableMapOf<String, MutableList<suspend () -> String>>()
        val asked = mutableListOf<String>()
        fun answer(id: String, vararg next: suspend () -> String) { answers[id] = next.toMutableList() }
        override suspend fun providers() = providerList
        override suspend fun fetch(providerId: String): String {
            asked += providerId
            val queue = answers[providerId] ?: throw IllegalStateException("no answer")
            val next = if (queue.size > 1) queue.removeAt(0) else queue.single()
            return next()
        }
    }

    private class Disk(var saved: String? = null)
    private class Clock(var now: Long = 1_000_000_000L)

    private class Rig(
        val repo: HistoryMixesRepository,
        val source: FakeSource,
        val settings: SettingsStore,
        val disk: Disk,
        val clock: Clock,
        val ids: MutableList<Set<String>>,
        val lookups: MutableList<String>,
    )

    private fun TestScope.rig(
        source: FakeSource = FakeSource(),
        listenBrainz: String? = "a",
        disk: Disk = Disk(),
        clock: Clock = Clock(),
        mosaic: suspend (HistoryMix) -> List<String> = { emptyList() },
    ): Rig {
        val prefs = listOfNotNull(listenBrainz?.let { SettingsStore.LISTENBRAINZ_USERNAME to it }).toTypedArray()
        val settings = SettingsStore(FakeSecureStore(), KvStore(MapSettings(*prefs)))
        val ids = mutableListOf<Set<String>>()
        val lookups = mutableListOf<String>()
        val repo = HistoryMixesRepository(
            source = source,
            settingsStore = settings,
            providerIsOn = { true },
            cacheRead = { disk.saved },
            cacheWrite = { disk.saved = it },
            mosaicCovers = { m -> lookups += m.id; mosaic(m) },
            onMixIds = { ids += it },
            nowMs = { clock.now },
            scope = backgroundScope,
        )
        return Rig(repo, source, settings, disk, clock, ids, lookups)
    }

    private fun mixes(vararg ids: String) =
        ids.joinToString(",", "[", "]") { """{"type":"playlist","id":"$it","name":"Mix $it","url":"https://x/$it","imageUrl":"https://img/$it"}""" }

    private fun Rig.shown(): List<String> = repo.mixes.value.flat.map { it.id }

    @Test
    fun `a fresh cache for the same account is served without a fetch`() = runTest {
        val disk = Disk()
        val first = rig(FakeSource().apply { answer("listenbrainz", { mixes("j1") }) }, disk = disk)
        first.repo.load()
        assertEquals(listOf("j1"), first.shown())

        val source = FakeSource().apply { answer("listenbrainz", { mixes("j2") }) }
        val second = rig(source, disk = disk, clock = Clock(first.clock.now + 30 * MINUTE))
        second.repo.load()
        assertTrue(source.asked.isEmpty())
        assertEquals(listOf("j1"), second.shown())
    }

    @Test
    fun `a stale cache shows at once and is replaced when the fetch answers`() = runTest {
        val disk = Disk()
        val first = rig(FakeSource().apply { answer("listenbrainz", { mixes("old") }) }, disk = disk)
        first.repo.load()

        val gate = CompletableDeferred<Unit>()
        val source = FakeSource().apply { answer("listenbrainz", { gate.await(); mixes("new") }) }
        val second = rig(source, disk = disk, clock = Clock(first.clock.now + 2 * HOUR))
        val job = launch { second.repo.load() }
        runCurrent()
        assertEquals(listOf("old"), second.shown())
        assertTrue(second.repo.mixes.value.loading)
        gate.complete(Unit)
        job.join()
        assertEquals(listOf("new"), second.shown())
        assertFalse(second.repo.mixes.value.loading)

        // Older than 7 days: not shown while it refreshes.
        val gate2 = CompletableDeferred<Unit>()
        val third = rig(FakeSource().apply { answer("listenbrainz", { gate2.await(); mixes("newest") }) }, disk = Disk(disk.saved), clock = Clock(second.clock.now + 8 * DAY))
        val job2 = launch { third.repo.load() }
        runCurrent()
        assertEquals(emptyList(), third.shown())
        gate2.complete(Unit)
        job2.join()
        assertEquals(listOf("newest"), third.shown())
    }

    @Test
    fun `an empty answer is stored and not shown and a later load fetches again`() = runTest {
        val source = FakeSource().apply { answer("listenbrainz", { mixes("j1") }, { "[]" }, { mixes("j3") }) }
        val r = rig(source)
        r.repo.load()
        r.clock.now += 2 * HOUR
        r.repo.load()
        assertEquals(emptyList(), r.repo.mixes.value.entries)
        // An empty answer leaves nothing to show, so the throttle doesn't count it.
        r.clock.now += MINUTE
        r.repo.load()
        assertEquals(3, source.asked.size)
        assertEquals(listOf("j3"), r.shown())
    }

    @Test
    fun `a failure keeps the cached entry — not ready drops it`() = runTest {
        val source = FakeSource().apply {
            answer("listenbrainz", { mixes("j1") }, { throw IllegalStateException("HTTP 503") }, { throw PluginNotReadyException("signed out") })
        }
        val r = rig(source)
        r.repo.load()
        r.clock.now += 2 * HOUR
        r.repo.load(force = true)
        assertEquals(listOf("j1"), r.shown())

        r.repo.load(force = true)
        assertEquals(emptyList(), r.shown())
        // And it's gone from disk too.
        val reopened = rig(FakeSource().apply { answer("listenbrainz", { throw IllegalStateException("offline") }) }, disk = Disk(r.disk.saved), clock = r.clock)
        reopened.repo.load()
        assertEquals(emptyList(), reopened.shown())
    }

    @Test
    fun `a cached entry saved for one ListenBrainz user isn't shown for another`() = runTest {
        val disk = Disk()
        val first = rig(FakeSource().apply { answer("listenbrainz", { mixes("a-mix") }) }, listenBrainz = "a", disk = disk)
        first.repo.load()
        assertEquals(listOf("a-mix"), first.shown())

        // Same process: the username changes, then a load (within the throttle window).
        first.settings.setListenBrainzUsername("b")
        val gate = CompletableDeferred<Unit>()
        first.source.answer("listenbrainz", { gate.await(); mixes("b-mix") })
        val job = launch { first.repo.load() }
        runCurrent()
        assertEquals(emptyList(), first.shown())
        gate.complete(Unit)
        job.join()
        assertEquals(listOf("b-mix"), first.shown())

        // A fresh start as "c" never shows b's saved mixes, even when the fetch fails.
        val other = rig(FakeSource().apply { answer("listenbrainz", { throw IllegalStateException("offline") }) }, listenBrainz = "c", disk = Disk(disk.saved), clock = first.clock)
        other.repo.load()
        assertEquals(emptyList(), other.shown())
    }

    @Test
    fun `an overlapping older load never overwrites the newer one`() = runTest {
        val slow = CompletableDeferred<Unit>()
        val source = FakeSource().apply {
            answer("listenbrainz", { slow.await(); mixes("first") }, { mixes("second") })
        }
        val r = rig(source)
        val first = launch { r.repo.load() }
        runCurrent()
        r.repo.load(force = true)
        assertEquals(listOf("second"), r.shown())
        slow.complete(Unit)
        first.join()
        assertEquals(listOf("second"), r.shown())
        assertFalse(r.repo.mixes.value.loading)
    }

    @Test
    fun `a provider slower than 20 s doesn't hold back the others`() = runTest {
        val am = RecommendationProvider("applemusic", "Apple Music", isMetaService = false, needsAccount = true)
        val source = FakeSource(listOf(am, LB)).apply {
            answer("applemusic", { delay(60_000); mixes("am-late") })
            answer("listenbrainz", { mixes("lb") })
        }
        val r = rig(source)
        val job = launch { r.repo.load() }
        advanceTimeBy(20_001)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(listOf("lb"), r.shown())
        // The late answer is still applied.
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(listOf("am-late", "lb"), r.shown())
    }

    @Test
    fun `a load within 10 minutes of a finished load is skipped unless forced`() = runTest {
        val source = FakeSource().apply { answer("listenbrainz", { mixes("j1") }) }
        val r = rig(source)
        r.repo.load() // t0: fetches
        assertEquals(1, source.asked.size)
        // t0+55 min: the throttle is open, the cache fresh: no fetch, but the load counts.
        r.clock.now += 55 * MINUTE
        r.repo.load()
        assertEquals(1, source.asked.size)
        // t0+61 min: the cache is stale, but the last load was 6 minutes ago: skipped.
        r.clock.now += 6 * MINUTE
        r.repo.load()
        assertEquals(1, source.asked.size)
        // t0+66 min: 11 minutes after the last load, the stale cache is fetched again.
        r.clock.now += 5 * MINUTE
        r.repo.load()
        assertEquals(2, source.asked.size)
        // Forced: fetches though a load just finished.
        r.repo.load(force = true)
        assertEquals(3, source.asked.size)
        assertEquals(listOf("j1"), r.shown())
    }

    @Test
    fun `onMixIds gets every mix id after each paint`() = runTest {
        val am = RecommendationProvider("applemusic", "Apple Music")
        val source = FakeSource(listOf(am, LB)).apply {
            answer("applemusic", { mixes("a1", "a2") })
            answer("listenbrainz", { mixes("jams") })
        }
        val r = rig(source)
        r.repo.load()
        runCurrent()
        assertEquals(setOf("a1", "a2", "jams"), r.ids.last())
    }

    @Test
    fun `a mix without a cover gets a mosaic — a failed lookup is retried after 60 s at most 3 times`() = runTest {
        var calls = 0
        val source = FakeSource().apply {
            answer("listenbrainz", { """[{"id":"jams","name":"Weekly Jams","url":"https://lb/jams"},{"id":"ok","name":"Ok","url":"https://lb/ok"},{"id":"img","name":"Img","url":"https://x","imageUrl":"https://img"}]""" })
        }
        val r = rig(source, mosaic = { m ->
            if (m.id == "jams") { calls++; throw IllegalStateException("offline") }
            listOf("c1", "c2", "c3", "c4", "c5")
        })
        r.repo.load()
        runCurrent()
        val flat = r.repo.mixes.value.flat
        assertEquals(listOf("c1", "c2", "c3", "c4"), flat.first { it.id == "ok" }.mosaic)
        assertEquals(emptyList(), flat.first { it.id == "jams" }.mosaic)
        assertFalse("img" in r.lookups)
        assertEquals(1, calls)

        advanceTimeBy(59_000); runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(61_000); runCurrent()
        assertEquals(3, calls)
        advanceTimeBy(10 * 61_000L); runCurrent()
        assertEquals(3, calls)
        // The successful one was looked up once.
        assertEquals(1, r.lookups.count { it == "ok" })
        assertNotNull(r.repo.mixes.value.flat.first { it.id == "ok" })
    }

    private companion object {
        val LB = RecommendationProvider("listenbrainz", "ListenBrainz", isMetaService = true, needsAccount = true)
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
