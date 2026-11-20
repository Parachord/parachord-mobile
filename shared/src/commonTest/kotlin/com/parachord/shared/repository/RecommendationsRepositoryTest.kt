package com.parachord.shared.repository

import com.parachord.shared.ai.AiAlbumSuggestion
import com.parachord.shared.ai.AiArtistSuggestion
import com.parachord.shared.ai.AiRecommendations
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.RecommendedArtist
import com.parachord.shared.model.RecommendedTrack
import com.parachord.shared.model.Resource
import com.parachord.shared.plugin.PluginNotReadyException
import com.parachord.shared.recommendations.RecommendationPageSection
import com.parachord.shared.recommendations.RecommendationType
import com.parachord.shared.recommendations.ReleaseDates
import com.parachord.shared.recommendations.SimilarUser
import com.parachord.shared.recommendations.flattenRecommendationItems
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Recommendations page's data: desktop's `loadRecommendations`,
 * `refreshMergedRecommendations` and `loadMoreRecommendationSection`
 * (`app.js` ~L27622–28440), shared.
 */
class RecommendationsRepositoryTest {

    private class FakeSecureStore : SecureTokenStore {
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) {}
        override fun remove(key: String) {}
        override fun contains(key: String): Boolean = false
        override fun observe(key: String): Flow<String?> = flowOf(null)
    }

    /** Each provider answers with the next function queued for it; the last one repeats. */
    private class FakeSource(
        val providerList: List<RecommendationProvider> = listOf(LASTFM, LISTENBRAINZ),
    ) : RecommendationSource {
        val answers = mutableMapOf<String, MutableList<suspend () -> String>>()
        val asked = mutableListOf<String>()
        val expanded = mutableListOf<Triple<String, String, String?>>()
        var expandAnswer: suspend (id: String, sectionId: String, cursor: String?) -> String? = { _, _, _ -> null }

        fun answer(id: String, vararg next: suspend () -> String) {
            answers[id] = next.toMutableList()
        }

        override suspend fun providers() = providerList
        override suspend fun fetch(providerId: String): String {
            asked += providerId
            val queue = answers[providerId] ?: throw IllegalStateException("no answer")
            val next = if (queue.size > 1) queue.removeAt(0) else queue.single()
            return next()
        }

        override suspend fun expand(providerId: String, sectionId: String, cursor: String?): String? {
            expanded += Triple(providerId, sectionId, cursor)
            return expandAnswer(providerId, sectionId, cursor)
        }
    }

    private class FakePicks(
        var picks: AiRecommendations? = null,
        var hasProvider: Boolean = false,
        var shares: Boolean = true,
        var next: suspend () -> AiRecommendations = { AiRecommendations(emptyList(), emptyList()) },
    ) : ShuffleupagusPicks {
        var loads = 0
        override suspend fun cached() = picks
        override suspend fun hasProvider() = hasProvider
        override suspend fun sharesHistory() = shares
        override suspend fun load(): AiRecommendations {
            loads++
            return next().also { picks = it }
        }
    }

    private class Rig(
        val repo: RecommendationsRepository,
        val source: FakeSource,
        val settings: SettingsStore,
        val disk: Disk,
        val clock: Clock,
    )

    private class Disk(var saved: String? = null) { val writes = mutableListOf<String>() }
    private class Clock(var now: Long = 1_000_000_000L)

    private fun TestScope.rig(
        source: FakeSource = FakeSource(),
        lastFm: String? = null,
        listenBrainz: String? = "listener",
        disk: Disk = Disk(),
        picks: FakePicks = FakePicks(),
        isOn: (RecommendationProvider) -> Boolean = { true },
        clock: Clock = Clock(),
        similarUsers: suspend (Boolean) -> Map<String, List<SimilarUser>> = { emptyMap() },
        canAskSimilarUsers: Boolean = true,
    ): Rig {
        val prefs = listOfNotNull(
            lastFm?.let { SettingsStore.LASTFM_USERNAME to it },
            listenBrainz?.let { SettingsStore.LISTENBRAINZ_USERNAME to it },
        ).toTypedArray()
        val settings = SettingsStore(FakeSecureStore(), KvStore(MapSettings(*prefs)))
        val repo = RecommendationsRepository(
            source = source,
            settingsStore = settings,
            metadataService = MetadataService(emptyList(), getDisabledProviders = { emptySet() }),
            aiRecommendations = picks,
            releaseDates = ReleaseDates(load = { null }, save = {}, lookup = { _, _ -> null }, nowMs = { clock.now }),
            providerIsOn = { isOn(it) },
            cacheRead = { disk.saved },
            cacheWrite = { disk.saved = it; disk.writes += it },
            similarUsers = similarUsers,
            canAskSimilarUsers = { canAskSimilarUsers },
            nowMs = { clock.now },
            scope = backgroundScope,
        )
        return Rig(repo, source, settings, disk, clock)
    }

    private fun songs(vararg items: String) = """{"sections":[{"items":[${items.joinToString(",")}]}]}"""
    private fun song(name: String, artist: String = "A") = """{"type":"song","name":"$name","artist":"$artist"}"""
    private val empty = """{"sections":[]}"""

    private fun Rig.names(type: RecommendationType? = RecommendationType.SONG): List<String> =
        repo.page.value.sections.filter { type == null || it.type == type }
            .let { flattenRecommendationItems(it) }.map { it.name }

    private fun List<RecommendationPageSection>.row(key: String) = flatMap { it.rows }.firstOrNull { it.key == key }

    // ── Which providers are asked ────────────────────────────────────

    @Test
    fun `asks only providers that are on and not switched off for recommendations`() = runTest {
        val source = FakeSource(
            listOf(
                LASTFM, LISTENBRAINZ,
                RecommendationProvider("applemusic", "Apple Music", isMetaService = false, needsAccount = true, canExpand = true),
                RecommendationProvider("spotify", "Spotify", isMetaService = false, needsAccount = true, canExpand = true),
                RecommendationProvider("amazon", "Amazon Music", isMetaService = false, needsAccount = true, canExpand = true),
            ),
        )
        for (id in listOf("lastfm", "listenbrainz", "applemusic", "spotify", "amazon")) source.answer(id, { songs(song("From $id")) })
        // No Last.fm username; Spotify isn't on; Amazon's recommendations are switched off.
        val r = rig(source, isOn = { it.id != "spotify" })
        r.settings.setRecommendationsOffResolvers(setOf("amazon"))
        r.repo.load()

        assertEquals(setOf("listenbrainz", "applemusic"), source.asked.toSet())
        assertEquals(2, source.asked.size)
        // Content resolvers first, then meta-services.
        assertEquals(listOf("From applemusic", "From listenbrainz"), r.names(RecommendationType.SONG))
        assertEquals("Personalized picks for listener", r.repo.page.value.hero)
    }

    @Test
    fun `not configured with no usernames and no providers on`() = runTest {
        val source = FakeSource(
            listOf(LASTFM, LISTENBRAINZ, RecommendationProvider("applemusic", "Apple Music", false, true, true)),
        )
        val r = rig(source, listenBrainz = null, isOn = { it.isMetaService })
        r.repo.load()

        assertEquals(RecommendationsError.NotConfigured, r.repo.page.value.error)
        assertTrue(source.asked.isEmpty())
        assertEquals("Personalized picks from your services", r.repo.page.value.hero)

        // An AI plugin that shares listening history is a source too.
        val withAi = rig(FakeSource(), listenBrainz = null, picks = FakePicks(hasProvider = true, shares = true))
        withAi.repo.load()
        assertFalse(withAi.repo.page.value.error == RecommendationsError.NotConfigured)
    }

    // ── Failures, not-ready, empty ───────────────────────────────────

    @Test
    fun `a provider that fails keeps its last sections`() = runTest {
        val source = FakeSource().apply { answer("listenbrainz", { songs(song("One")) }, { throw IllegalStateException("HTTP 503") }) }
        val r = rig(source)
        r.repo.load()
        assertEquals(listOf("One"), r.names())

        r.clock.now += 2 * HOUR
        r.repo.load()
        assertEquals(listOf("listenbrainz", "listenbrainz"), source.asked)
        assertEquals(listOf("One"), r.names())
        assertNull(r.repo.page.value.error)
    }

    @Test
    fun `a not-ready provider loses its cached sections`() = runTest {
        val source = FakeSource().apply {
            answer("listenbrainz", { songs(song("One")) }, { throw PluginNotReadyException("sign in") })
        }
        val r = rig(source)
        r.repo.load()
        r.clock.now += 2 * HOUR
        r.repo.load()

        assertTrue(r.names().isEmpty())
        assertFalse(r.disk.saved.orEmpty().contains("One"), "dropped from disk too: ${r.disk.saved}")
    }

    @Test
    fun `an empty answer replaces the cache but is not served from it next time`() = runTest {
        val source = FakeSource().apply { answer("listenbrainz", { songs(song("One")) }, { empty }) }
        val r = rig(source)
        r.repo.load()
        r.clock.now += 2 * HOUR
        r.repo.load()

        assertTrue(r.names().isEmpty())
        assertEquals(
            RecommendationsError.Message(emptyRecommendationsMessage(hasListenBrainz = true, hasLastFm = false)),
            r.repo.page.value.error,
        )
        // Within the hour, the empty answer isn't served: the provider is asked again.
        r.clock.now += 1_000
        r.repo.load()
        assertEquals(3, source.asked.size)
    }

    // ── Freshness and accounts ───────────────────────────────────────

    @Test
    fun `fresh for an hour — then shown while it refreshes for 7 days`() = runTest {
        val gate = CompletableDeferred<String>()
        val source = FakeSource().apply { answer("listenbrainz", { songs(song("One")) }, { gate.await() }) }
        val r = rig(source)
        r.repo.load()
        r.clock.now += 30 * MINUTE
        r.repo.load()
        assertEquals(1, source.asked.size, "fresh for an hour")

        r.clock.now += HOUR
        val job = launch { r.repo.load() }
        runCurrent()
        assertEquals(listOf("One"), r.names(), "last time's sections show while it refreshes")
        assertTrue(r.repo.page.value.pendingProviders.isEmpty())
        assertFalse(r.repo.page.value.loading)
        gate.complete(songs(song("Two")))
        job.join()
        assertEquals(listOf("Two"), r.names())

        // Past 7 days a saved answer isn't shown while its provider refreshes.
        val gate2 = CompletableDeferred<String>()
        val clock = Clock(r.clock.now + 8 * DAY)
        val next = rig(FakeSource().apply { answer("listenbrainz", { gate2.await() }) }, disk = Disk(r.disk.saved), clock = clock)
        val job2 = launch { next.repo.load() }
        runCurrent()
        assertTrue(next.names().isEmpty())
        assertEquals(setOf("listenbrainz"), next.repo.page.value.pendingProviders)
        gate2.complete(songs(song("Three")))
        job2.join()
    }

    @Test
    fun `a saved answer is shown at once on the next launch`() = runTest {
        val first = rig(FakeSource().apply { answer("listenbrainz", { songs(song("One")) }) })
        first.repo.load()
        val gate = CompletableDeferred<String>()
        val next = rig(FakeSource().apply { answer("listenbrainz", { gate.await() }) }, disk = Disk(first.disk.saved), clock = Clock(first.clock.now + 2 * HOUR))
        val job = launch { next.repo.load() }
        runCurrent()
        assertEquals(listOf("One"), next.names())
        gate.complete(songs(song("Two")))
        job.join()
        assertEquals(listOf("Two"), next.names())
    }

    @Test
    fun `a cached answer for another account is not shown`() = runTest {
        val alice = rig(FakeSource().apply { answer("listenbrainz", { songs(song("Alice's")) }) }, listenBrainz = "alice")
        alice.repo.load()

        val bob = rig(
            FakeSource().apply { answer("listenbrainz", { throw IllegalStateException("offline") }) },
            listenBrainz = "bob",
            disk = Disk(alice.disk.saved),
        )
        bob.repo.load()
        assertTrue(bob.names().isEmpty())
        assertEquals(1, bob.source.asked.size)
    }

    @Test
    fun `a provider that answers after 20 seconds is still applied and saved`() = runTest {
        val gate = CompletableDeferred<String>()
        val r = rig(FakeSource().apply { answer("listenbrainz", { gate.await() }) })
        r.repo.load()
        // The load ended at the bound, as a failure with nothing to keep.
        assertEquals(RecommendationsError.Message(RecommendationsRepository.PAGE_FAILED_MESSAGE), r.repo.page.value.error)
        gate.complete(songs(song("Late")))
        runCurrent()
        assertEquals(listOf("Late"), r.names())
        assertNull(r.repo.page.value.error)
        assertTrue(r.disk.saved.orEmpty().contains("Late"))
    }

    @Test
    fun `only the latest load updates the page`() = runTest {
        val slow = CompletableDeferred<String>()
        val source = FakeSource().apply { answer("listenbrainz", { slow.await() }, { songs(song("Newer")) }) }
        val r = rig(source)
        val first = launch { r.repo.load() }
        runCurrent()
        r.repo.load(force = true)
        assertEquals(listOf("Newer"), r.names())

        slow.complete(songs(song("Older")))
        first.join()
        assertEquals(listOf("Newer"), r.names())
    }

    // ── Paging ───────────────────────────────────────────────────────

    private val titled = """{"sections":[{"id":"picks","title":"Picks","nextCursor":"c0","items":[${song("First")}]}]}"""

    @Test
    fun `opening a row pages until the provider runs out — at most 10 pages`() = runTest {
        val source = FakeSource().apply {
            answer("listenbrainz", { titled })
            var n = 0
            expandAnswer = { _, _, _ -> n++; """{"items":[${song("Paged $n")}],"nextCursor":"c$n"}""" }
        }
        val r = rig(source)
        r.repo.load()
        r.repo.openRow("type:song")

        assertEquals(10, source.expanded.size)
        assertEquals(listOf("c0", "c1", "c2"), source.expanded.take(3).map { it.third })
        assertEquals(11, r.names(RecommendationType.SONG).size)
        val row = r.repo.page.value.sections.row("type:song")!!
        assertFalse(row.canLoadMore)

        // A provider that runs out sooner stops paging there.
        val short = FakeSource().apply {
            answer("listenbrainz", { titled })
            var n = 0
            expandAnswer = { _, _, _ -> n++; if (n > 2) """{"items":[]}""" else """{"items":[${song("P$n")}],"nextCursor":"x$n"}""" }
        }
        val r2 = rig(short)
        r2.repo.load()
        r2.repo.openRow("type:song")
        assertEquals(3, short.expanded.size)
        assertEquals(listOf("First", "P1", "P2"), r2.names(RecommendationType.SONG))
    }

    @Test
    fun `a cursor that doesn't move ends paging`() = runTest {
        val source = FakeSource().apply {
            answer("listenbrainz", { titled })
            expandAnswer = { _, _, cursor -> """{"items":[${song("Again")}],"nextCursor":"$cursor"}""" }
        }
        val r = rig(source)
        r.repo.load()
        r.repo.openRow("type:song")
        assertEquals(1, source.expanded.size)
        assertFalse(r.repo.page.value.sections.row("type:song")!!.canLoadMore)
    }

    @Test
    fun `a failed page says so and stops`() = runTest {
        val source = FakeSource().apply {
            answer("listenbrainz", { titled })
            expandAnswer = { _, _, _ -> throw IllegalStateException("HTTP 500") }
        }
        val r = rig(source)
        r.repo.load()
        val messages = mutableListOf<String>()
        val collector = launch { r.repo.messages.collect { messages += it } }
        runCurrent()
        r.repo.openRow("type:song")
        runCurrent()
        assertEquals(1, source.expanded.size)
        assertEquals(listOf("Couldn't load more from ListenBrainz"), messages)
        assertTrue(r.repo.page.value.sections.row("type:song")!!.canLoadMore, "a failure isn't exhaustion")
        collector.cancel()
    }

    @Test
    fun `a forced refresh forgets the paged items`() = runTest {
        val source = FakeSource().apply {
            answer("listenbrainz", { titled })
            expandAnswer = { _, _, _ -> """{"items":[${song("Paged")}]}""" }
        }
        val r = rig(source)
        r.repo.load()
        r.repo.openRow("type:song")
        assertEquals(listOf("First", "Paged"), r.names())
        r.repo.load(force = true)
        assertEquals(listOf("First"), r.names())
    }

    // ── Shuffleupagus, the blocklist, the old flows ──────────────────

    @Test
    fun `listenersPending is true while similar users are fetched — false after`() = runTest {
        val gate = CompletableDeferred<Map<String, List<SimilarUser>>>()
        val r = rig(similarUsers = { gate.await() })
        val job = launch { r.repo.load() }
        runCurrent()
        assertTrue(r.repo.page.value.listenersPending)
        gate.complete(emptyMap())
        job.join()
        assertFalse(r.repo.page.value.listenersPending)
    }

    @Test
    fun `listenersPending stays false when no similar-users provider can be asked`() = runTest {
        val gate = CompletableDeferred<Map<String, List<SimilarUser>>>()
        val r = rig(similarUsers = { gate.await() }, canAskSimilarUsers = false)
        val job = launch { r.repo.load() }
        runCurrent()
        assertFalse(r.repo.page.value.listenersPending)
        gate.complete(emptyMap())
        job.join()
        assertFalse(r.repo.page.value.listenersPending)
    }

    @Test
    fun `listenersPending ends false when the lookup throws`() = runTest {
        val gate = CompletableDeferred<Map<String, List<SimilarUser>>>()
        val r = rig(similarUsers = { gate.await() })
        val job = launch { r.repo.load() }
        runCurrent()
        assertTrue(r.repo.page.value.listenersPending)
        gate.completeExceptionally(IllegalStateException("down"))
        job.join()
        assertFalse(r.repo.page.value.listenersPending)
    }

    @Test
    fun `Shuffleupagus picks land under Based on your listening`() = runTest {
        val gate = CompletableDeferred<AiRecommendations>()
        val picks = FakePicks(hasProvider = true, next = { gate.await() })
        val source = FakeSource().apply {
            answer("listenbrainz", { """{"sections":[{"items":[{"type":"album","name":"New One","artist":"B","category":"new"}]}]}""" })
        }
        val r = rig(source, picks = picks)
        r.repo.load()
        assertTrue(r.repo.page.value.aiLoading)
        gate.complete(AiRecommendations(listOf(AiAlbumSuggestion("AI Album", "C", "peak")), emptyList()))
        runCurrent()

        assertFalse(r.repo.page.value.aiLoading)
        val row = r.repo.page.value.sections.row("albums:personal")!!
        assertEquals("Based on your listening", row.title)
        assertEquals(listOf("AI Album"), row.items.map { it.name })
        assertEquals("peak", row.items.single().reason)
        assertEquals(1, picks.loads)

        // A load with picks already cached doesn't ask the AI again.
        r.repo.load()
        assertEquals(1, picks.loads)
    }

    @Test
    fun `a blocked artist — album or track is left out everywhere`() = runTest {
        val picks = FakePicks(
            picks = AiRecommendations(
                listOf(AiAlbumSuggestion("Sunbather", "Deafheaven", "x"), AiAlbumSuggestion("Kept", "Other", "y")),
                listOf(AiArtistSuggestion("Deafheaven", "z")),
            ),
        )
        val source = FakeSource().apply {
            answer("listenbrainz", {
                """{"sections":[{"id":"picks","title":"Picks","items":[${song("Dream House", "Deafheaven")},${song("Keep Me", "Other")}]}]}"""
            })
            expandAnswer = { _, _, _ -> """{"items":[${song("Paged Block", "deafheaven")},${song("Blocked Track", "Someone")}]}""" }
        }
        val r = rig(source, picks = picks)
        r.settings.addBlockedRecommendation("artist:Deafheaven")
        r.settings.addBlockedRecommendation("track:Someone:Blocked Track")
        r.repo.load()
        r.repo.openRow("type:song")

        val all = r.names(null)
        assertTrue("Keep Me" in all && "Kept" in all, "$all")
        assertTrue(all.none { it in setOf("Dream House", "Sunbather", "Deafheaven", "Paged Block", "Blocked Track") }, "$all")
    }

    @Test
    fun `the old tracks and artists flows still come from the page`() = runTest {
        val source = FakeSource().apply {
            answer(
                "listenbrainz",
                {
                    songs(
                        """{"type":"song","name":"Dream House","artist":"Deafheaven","duration":555,"mbid":"9D59B396-1F9D-4A70-8B17-6B1DD97D1C73","isrc":"gb5ep1400001"}""",
                        song("Two", "deafheaven"),
                        song("Three", "Anathema"),
                    )
                },
            )
        }
        val r = rig(source)
        assertNull(r.repo.cachedTracksList)
        val tracks = r.repo.getRecommendedTracks().toList()
        assertEquals(Resource.Loading, tracks.first())
        assertEquals(
            listOf(
                RecommendedTrack(
                    title = "Dream House", artist = "Deafheaven", duration = 555_000, source = "listenbrainz",
                    recordingMbid = "9d59b396-1f9d-4a70-8b17-6b1dd97d1c73", isrc = "GB5EP1400001",
                ),
                RecommendedTrack(title = "Two", artist = "deafheaven", source = "listenbrainz"),
                RecommendedTrack(title = "Three", artist = "Anathema", source = "listenbrainz"),
            ),
            (tracks.last() as Resource.Success).data,
        )
        val artists = r.repo.getRecommendedArtists().toList()
            .filterIsInstance<Resource.Success<List<RecommendedArtist>>>().last().data
        assertEquals(listOf("Deafheaven", "Anathema"), artists.map { it.name })
        assertEquals(1, source.asked.size, "the second list read the first one's answer")
        assertEquals(3, r.repo.cachedTracksList?.size)

        // A miss: the list empties and says why.
        val miss = rig(FakeSource().apply { answer("listenbrainz", { empty }) })
        assertEquals(
            listOf(Resource.Loading, Resource.Success(emptyList()), Resource.Error(emptyRecommendationsMessage(true, false))),
            miss.repo.getRecommendedTracks().toList(),
        )
        // A failure with nothing to show.
        val fail = rig(FakeSource().apply { answer("listenbrainz", { throw IllegalStateException("x") }) })
        assertEquals(Resource.Error(RecommendationsRepository.FAILED_MESSAGE), fail.repo.getRecommendedTracks().toList().last())
        // Nothing connected.
        val none = rig(FakeSource(), listenBrainz = null)
        assertEquals(Resource.Error(RecommendationsRepository.NOT_CONFIGURED_MESSAGE), none.repo.getRecommendedTracks().toList().last())
    }

    @Test
    fun `the old lists say not configured for an AI-only user`() = runTest {
        val r = rig(FakeSource(), listenBrainz = null, picks = FakePicks(hasProvider = true, shares = true))
        assertEquals(Resource.Error(RecommendationsRepository.NOT_CONFIGURED_MESSAGE), r.repo.getRecommendedTracks().toList().last())
        assertEquals(Resource.Error(RecommendationsRepository.NOT_CONFIGURED_MESSAGE), r.repo.getRecommendedArtists().toList().last())
        // The page itself counts the AI as a source.
        r.repo.load()
        assertFalse(r.repo.page.value.error == RecommendationsError.NotConfigured)
    }

    @Test
    fun `the old artists list leaves out Shuffleupagus's picks`() = runTest {
        val picks = FakePicks(picks = AiRecommendations(emptyList(), listOf(AiArtistSuggestion("AI Only", "x"), AiArtistSuggestion("Anathema", "y"))))
        val source = FakeSource().apply { answer("listenbrainz", { songs(song("One", "Anathema"), song("Two", "Deafheaven")) }) }
        val r = rig(source, picks = picks)
        val artists = r.repo.getRecommendedArtists().toList()
            .filterIsInstance<Resource.Success<List<RecommendedArtist>>>().last().data
        assertEquals(listOf("Anathema", "Deafheaven"), artists.map { it.name })
        assertEquals(listOf("listenbrainz", "listenbrainz"), artists.map { it.source })
        // The page shows them all.
        assertTrue("AI Only" in r.names(RecommendationType.ARTIST))
    }

    @Test
    fun `a connected service whose plugin isn't loaded is a failure — not a miss`() = runTest {
        val r = rig(FakeSource(providerList = emptyList()))
        assertEquals(Resource.Error(RecommendationsRepository.FAILED_MESSAGE), r.repo.getRecommendedTracks().toList().last())
    }

    @Test
    fun `the message names the connected services`() {
        assertTrue(emptyRecommendationsMessage(true, false).startsWith("ListenBrainz hasn't made recommendations"))
        assertTrue(emptyRecommendationsMessage(true, true).startsWith("Neither ListenBrainz nor Last.fm"))
        assertTrue(emptyRecommendationsMessage(false, true).startsWith("Last.fm doesn't have"))
        assertEquals(
            "No recommendations right now. Check back once your services have more of your listening history.",
            emptyRecommendationsMessage(false, false),
        )
    }

    @Test
    fun `an Apple-Music-only user's empty old lists name no service they never connected`() = runTest {
        val am = RecommendationProvider("applemusic", "Apple Music", isMetaService = false, needsAccount = true, canExpand = true)
        val source = FakeSource(providerList = listOf(LASTFM, LISTENBRAINZ, am)).apply { answer("applemusic", { empty }) }
        val r = rig(source, listenBrainz = null)
        val generic = emptyRecommendationsMessage(hasListenBrainz = false, hasLastFm = false)
        assertEquals(Resource.Error(generic), r.repo.getRecommendedTracks().toList().last())
        assertEquals(Resource.Error(generic), r.repo.getRecommendedArtists().toList().last())
        assertFalse("Last.fm" in generic || "ListenBrainz" in generic)
    }

    @Test
    fun `a saved list from a provider switched off since doesn't show at launch`() = runTest {
        val am = RecommendationProvider("applemusic", "Apple Music", isMetaService = false, needsAccount = true, canExpand = true)
        val first = rig(
            FakeSource(providerList = listOf(LASTFM, LISTENBRAINZ, am)).apply {
                answer("listenbrainz", { songs(song("From LB")) })
                answer("applemusic", { songs(song("From AM")) })
            },
        )
        first.repo.load()
        assertEquals(setOf("From LB", "From AM"), first.names().toSet())

        // Next launch: Apple Music's plug-in is off, and ListenBrainz is off for recommendations.
        val gate = CompletableDeferred<String>()
        val next = rig(
            FakeSource(providerList = listOf(LASTFM, LISTENBRAINZ, am)).apply { answer("listenbrainz", { gate.await() }) },
            disk = Disk(first.disk.saved),
            isOn = { it.id != "applemusic" },
        )
        val seen = mutableListOf<List<String>>()
        val watch = launch { next.repo.page.collect { seen += next.names() } }
        val tracks = launch { next.repo.getRecommendedTracks().toList() }
        runCurrent()
        assertTrue(seen.none { "From AM" in it }, "$seen")
        assertEquals(listOf("From LB"), next.names())
        gate.complete(songs(song("From LB")))
        tracks.join()
        watch.cancel()

        val off = rig(
            FakeSource(providerList = listOf(LASTFM, LISTENBRAINZ, am)).apply { answer("listenbrainz", { CompletableDeferred<String>().await() }) },
            disk = Disk(first.disk.saved),
        )
        off.settings.setRecommendationsOffResolvers(setOf("listenbrainz"))
        val offTracks = launch { off.repo.getRecommendedTracks().toList() }
        runCurrent()
        assertTrue("From LB" !in off.names(), "${off.names()}")
        offTracks.cancel()
    }

    @Test
    fun `a failing release-date or exclusions read doesn't stop later rebuilds`() = runTest {
        var failExclusions = true
        val clock = Clock()
        val dates = ReleaseDates(
            load = { throw IllegalStateException("disk") },
            save = { throw IllegalStateException("disk full") },
            lookup = { title, _ -> if (title == "Broken") throw IllegalStateException("mb") else "2026-01-0${title.last()}" },
            nowMs = { clock.now },
        )
        val prefs = SettingsStore(FakeSecureStore(), KvStore(MapSettings(SettingsStore.LISTENBRAINZ_USERNAME to "listener")))
        val source = FakeSource().apply {
            answer("listenbrainz", {
                songs(
                    song("One"),
                    """{"type":"album","name":"Broken","artist":"A"}""",
                    """{"type":"album","name":"Record 1","artist":"A"}""",
                    """{"type":"album","name":"Record 2","artist":"A"}""",
                )
            })
        }
        val repo = RecommendationsRepository(
            source = source,
            settingsStore = prefs,
            metadataService = MetadataService(emptyList(), getDisabledProviders = { emptySet() }),
            aiRecommendations = FakePicks(),
            releaseDates = dates,
            providerIsOn = { true },
            cacheRead = { null },
            cacheWrite = {},
            friendExclusions = {
                if (failExclusions) throw IllegalStateException("friends") else com.parachord.shared.recommendations.FriendExclusions()
            },
            nowMs = { clock.now },
            scope = backgroundScope,
        )
        repo.load()
        fun albums() = flattenRecommendationItems(repo.page.value.sections.filter { it.type == RecommendationType.ALBUM })
        fun album(name: String) = albums().first { it.name == name }

        // A lookup that throws and a rebuild whose exclusions read throws.
        repo.requestReleaseDates(listOf(album("Broken"), album("Record 1")))
        runCurrent()
        assertEquals("2026-01-01", album("Record 1").releaseDate)
        assertEquals(listOf("One"), flattenRecommendationItems(repo.page.value.sections.filter { it.type == RecommendationType.SONG }).map { it.name })

        // Later changes still rebuild the page.
        failExclusions = false
        repo.requestReleaseDates(listOf(album("Record 2")))
        runCurrent()
        assertEquals("2026-01-02", album("Record 2").releaseDate)
    }

    @Test
    fun `found covers and photos are applied to the page's album and artist cards`() = runTest {
        val clock = Clock()
        var artistCalls = 0
        val prefs = SettingsStore(FakeSecureStore(), KvStore(MapSettings(SettingsStore.LISTENBRAINZ_USERNAME to "listener")))
        val source = FakeSource().apply {
            answer("listenbrainz", {
                songs(
                    """{"type":"album","name":"Dummy","artist":"Portishead"}""",
                    """{"type":"album","name":"Mezzanine","artist":"Massive Attack","imageUrl":"https://own"}""",
                    """{"type":"artist","name":"Tricky"}""",
                )
            })
        }
        val repo = RecommendationsRepository(
            source = source,
            settingsStore = prefs,
            metadataService = MetadataService(emptyList(), getDisabledProviders = { emptySet() }),
            aiRecommendations = FakePicks(),
            releaseDates = ReleaseDates(load = { null }, save = {}, lookup = { _, _ -> null }, nowMs = { clock.now }),
            providerIsOn = { true },
            cacheRead = { null },
            cacheWrite = {},
            nowMs = { clock.now },
            scope = backgroundScope,
            images = com.parachord.shared.recommendations.RecommendationImages(
                artistImage = { artistCalls++; "https://photo/$it" },
                albumArt = { title, _ -> "https://cover/$title" },
            ),
        )
        repo.load()
        fun item(name: String) = flattenRecommendationItems(repo.page.value.sections).first { it.name == name }
        repo.requestImages(listOf(item("Dummy"), item("Mezzanine"), item("Tricky")))
        runCurrent()
        assertEquals("https://cover/Dummy", item("Dummy").imageUrl)
        assertEquals("https://own", item("Mezzanine").imageUrl)
        assertEquals("https://photo/Tricky", item("Tricky").imageUrl)
        repo.requestImages(listOf(item("Tricky")))
        runCurrent()
        assertEquals(1, artistCalls)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        val LASTFM = RecommendationProvider("lastfm", "Last.fm", isMetaService = true, needsAccount = false, canExpand = true)
        val LISTENBRAINZ = RecommendationProvider("listenbrainz", "ListenBrainz", isMetaService = true, needsAccount = false, canExpand = true)
    }
}
