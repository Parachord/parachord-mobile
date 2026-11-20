package com.parachord.shared.home

import com.parachord.shared.model.Resource
import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.repository.RecommendationPageState
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The loading rules behind Home's sources, shared by both apps. */
class HomeFeedsTest {

    private class Fetcher(vararg answers: Resource<List<String>>) {
        private val queue = answers.toMutableList()
        val refreshes = mutableListOf<Boolean>()
        fun fetch(refresh: Boolean): Flow<Resource<List<String>>> {
            refreshes += refresh
            val next = if (queue.size > 1) queue.removeAt(0) else queue.single()
            return flowOf(next)
        }
        val asked get() = refreshes.size
    }

    @Test
    fun `a plain load asks once — a return visit asks nothing`() = runTest {
        val f = Fetcher(Resource.Success(listOf("a")))
        val feed = HomeFeedLoader<String>("t")
        assertTrue(feed.load(false, fetch = f::fetch))
        assertFalse(feed.load(false, fetch = f::fetch))
        assertEquals(1, f.asked)
        assertEquals(HomeFeed(listOf("a")), feed.state.value)
    }

    @Test
    fun `a plain visit retries a failed feed`() = runTest {
        val f = Fetcher(Resource.Error("offline"), Resource.Success(listOf("a")))
        val feed = HomeFeedLoader<String>("t")
        feed.load(false, fetch = f::fetch)
        assertTrue(feed.state.value.failed)
        feed.load(false, fetch = f::fetch)
        assertEquals(2, f.asked)
        assertEquals(HomeFeed(listOf("a")), feed.state.value)
    }

    @Test
    fun `a throw marks the feed failed and a plain visit retries it`() = runTest {
        var n = 0
        val feed = HomeFeedLoader<String>("t")
        val fetch: (Boolean) -> Flow<Resource<List<String>>> = {
            flow { n++; if (n == 1) throw IllegalStateException("x") else emit(Resource.Success(listOf("b"))) }
        }
        feed.load(false, fetch = fetch)
        assertTrue(feed.state.value.failed)
        assertFalse(feed.state.value.loading)
        feed.load(false, fetch = fetch)
        assertEquals(listOf("b"), feed.state.value.items)
    }

    @Test
    fun `force reloads only a failed or empty feed and keeps a loaded one`() = runTest {
        val loaded = Fetcher(Resource.Success(listOf("a")), Resource.Success(listOf("z")))
        val ok = HomeFeedLoader<String>("ok")
        ok.load(false, fetch = loaded::fetch)
        assertFalse(ok.load(true, fetch = loaded::fetch))
        assertEquals(listOf("a"), ok.state.value.items)

        val empty = Fetcher(Resource.Success(emptyList()), Resource.Success(listOf("e")))
        val e = HomeFeedLoader<String>("empty")
        e.load(false, fetch = empty::fetch)
        assertTrue(e.load(true, fetch = empty::fetch))
        assertEquals(listOf(false, true), empty.refreshes)
        assertEquals(listOf("e"), e.state.value.items)

        val failing = Fetcher(Resource.Error("x"), Resource.Success(listOf("f")))
        val failed = HomeFeedLoader<String>("failed")
        failed.load(false, fetch = failing::fetch)
        assertTrue(failed.load(true, fetch = failing::fetch))
        assertEquals(HomeFeed(listOf("f")), failed.state.value)
    }

    @Test
    fun `an empty chart reads as failed`() = runTest {
        val feed = HomeFeedLoader<String>("charts")
        feed.load(false) { listOrFailed { emptyList() } }
        assertTrue(feed.state.value.failed)
    }

    private fun loc(lat: Double?, lon: Double?, city: String? = null, radius: Int = 50) =
        SettingsStore.ConcertLocation(lat, lon, city, radius)

    @Test
    fun `concerts load only with a location and again when it changes`() = runTest {
        val asked = mutableListOf<Pair<SettingsStore.ConcertLocation, Boolean>>()
        val loader = HomeConcertsLoader { l, refresh ->
            asked += l to refresh
            flowOf(Resource.Success(emptyList()))
        }
        loader.load(null)
        loader.load(loc(null, null, city = " "))
        assertTrue(asked.isEmpty())

        val austin = loc(30.0, -97.0, "Austin")
        loader.load(austin)
        loader.load(austin)
        assertEquals(listOf(austin to false), asked)

        val wider = austin.copy(radiusMiles = 100)
        loader.load(wider)
        assertEquals(listOf(austin to false, wider to true), asked)
    }

    @Test
    fun `concert events with no artists are none — with artists the search`() = runTest {
        val none = concertEvents({ emptyList() }, { _, _, _ -> error("not asked") }, loc(1.0, 1.0), false).first()
        assertEquals(Resource.Success(emptyList()), none)
    }

    @Test
    fun `recommendation sections are null until a load answers`() = runTest {
        val page = MutableStateFlow(RecommendationPageState())
        val recs = HomeRecommendations(page, { HomeRecsLoad(1, 0, 0) }, { null })
        assertNull(recs.sections.first())
        recs.load(false)
        assertEquals(emptyList(), recs.sections.first())
    }

    private fun plugin(id: String, auth: Boolean, generic: Boolean = true) = PluginInfo(
        id = id, name = id, version = "1",
        capabilities = mapOf("resolve" to true, "stream" to true),
        requiresAuth = auth,
    )

    @Test
    fun `an unconfigured Subsonic is not connected — nothing else is`() = runTest {
        val plugins = listOf(
            plugin("spotify", auth = true), plugin("applemusic", auth = true), plugin("soundcloud", auth = true),
            plugin("subsonic", auth = true), plugin("bandcamp", auth = false), plugin(LOCAL_FILES_ID, auth = false),
        )
        val host = mapOf("spotify" to false, "applemusic" to false, "soundcloud" to false)
        val accounts = homePluginAccounts(plugins, host) { id -> id != "subsonic" }
        assertEquals(false, accounts["subsonic"])
        val resolvers = homePlaybackResolvers(plugins, accounts) { true }
        assertFalse("subsonic" in resolvers)
        assertFalse(serviceConnected(stepOnePlugins(plugins).services, accounts, playbackResolvers = resolvers))

        // Set up, it counts.
        val configured = homePluginAccounts(plugins, host) { true }
        val on = homePlaybackResolvers(plugins, configured) { true }
        assertTrue(serviceConnected(stepOnePlugins(plugins).services, configured, playbackResolvers = on))
    }

    @Test
    fun `a plug-in whose configured check gave no answer is not connected`() = runTest {
        val plugins = listOf(
            plugin("subsonic", auth = true),
        )
        val accounts = homePluginAccounts(plugins, emptyMap()) { null }
        assertEquals(false, accounts["subsonic"])
        assertFalse("subsonic" in homePlaybackResolvers(plugins, accounts) { true })
    }

    @Test
    fun `a plug-in whose configured check threw is not connected`() = runTest {
        val plugins = listOf(
            plugin("subsonic", auth = true),
        )
        val accounts = homePluginAccounts(plugins, emptyMap()) { error("plugin crashed") }
        assertEquals(false, accounts["subsonic"])
    }

    @Test
    fun `a load cancelled while another holds the lock still ends — a later load runs`() = runTest {
        val lock = Mutex()
        val feed = HomeFeedLoader<String>("t", lock)
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            feed.load(false) { flow { gate.await(); emit(Resource.Success(listOf("a"))) } }
        }
        runCurrent()
        assertTrue(feed.state.value.loading)
        lock.lock()
        job.cancel()
        runCurrent()
        lock.unlock()
        job.join()
        assertFalse(feed.state.value.loading)
        assertTrue(feed.load(false) { flowOf(Resource.Success(listOf("b"))) })
        assertEquals(listOf("b"), feed.state.value.items)
    }

    @Test
    fun `a feed starts loading so its half shows a skeleton until the first answer`() = runTest {
        val feed = HomeFeedLoader<String>("t")
        assertTrue(feed.state.value.loading)
        assertTrue(homeHalves(0, feed.state.value.loading, 0, false, 0, false, false).fresh)
        assertTrue(feed.load(false) { flowOf(Resource.Success(listOf("a"))) })
        assertEquals(HomeFeed(listOf("a")), feed.state.value)
    }

    @Test
    fun `a feed that starts loading still answers a failed first load`() = runTest {
        val feed = HomeFeedLoader<String>("t")
        assertTrue(feed.load(false) { flowOf(Resource.Error("offline")) })
        assertFalse(feed.state.value.loading)
        assertTrue(feed.state.value.failed)
    }

    @Test
    fun `concerts don't start loading since they load only with a location`() = runTest {
        val concerts = HomeConcertsLoader { _, _ -> flowOf(Resource.Success(emptyList())) }
        assertFalse(concerts.state.value.loading)
        concerts.load(null)
        assertFalse(concerts.state.value.loading)
    }
}

