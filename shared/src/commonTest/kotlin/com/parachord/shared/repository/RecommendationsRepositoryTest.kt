package com.parachord.shared.repository

import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.RecommendedArtist
import com.parachord.shared.model.RecommendedTrack
import com.parachord.shared.model.Resource
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recommendations come from the plugins that declare the capability (#606,
 * desktop parachord#1309). A miss is an empty answer and replaces the saved
 * list (desktop parachord#1121); a provider that couldn't answer keeps it.
 */
class RecommendationsRepositoryTest {

    private class FakeSecureStore : SecureTokenStore {
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) {}
        override fun remove(key: String) {}
        override fun contains(key: String): Boolean = false
        override fun observe(key: String): Flow<String?> = flowOf(null)
    }

    /** Answers per provider id: sections JSON, or null to fail. */
    private class FakeSource(
        private val providerList: List<RecommendationProvider> = listOf(
            RecommendationProvider("lastfm", "Last.fm"),
            RecommendationProvider("listenbrainz", "ListenBrainz"),
        ),
        val answers: MutableMap<String, String?> = mutableMapOf(),
    ) : RecommendationSource {
        val asked = mutableListOf<String>()
        override suspend fun providers() = providerList
        override suspend fun fetch(providerId: String): String {
            asked += providerId
            return answers[providerId] ?: throw IllegalStateException("Plugin call failed: HTTP 503")
        }
    }

    /** A saved list from before #606: tracks only, no per-provider answers. */
    private val savedCache =
        """{"tracks":[{"title":"My Top Song","artist":"Me","source":"listenbrainz"}],"artists":[]}"""

    private class Rig(val repo: RecommendationsRepository, val source: FakeSource, val writes: MutableList<String>) {
        var now = 1_000_000L
    }

    private fun rig(
        source: FakeSource,
        lastFm: String? = null,
        listenBrainz: String? = "listener",
        saved: String? = savedCache,
    ): Rig {
        val writes = mutableListOf<String>()
        val prefs = listOfNotNull(
            lastFm?.let { SettingsStore.LASTFM_USERNAME to it },
            listenBrainz?.let { SettingsStore.LISTENBRAINZ_USERNAME to it },
        ).toTypedArray()
        lateinit var rig: Rig
        val repo = RecommendationsRepository(
            source = source,
            settingsStore = SettingsStore(FakeSecureStore(), KvStore(MapSettings(*prefs))),
            metadataService = MetadataService(emptyList(), getDisabledProviders = { emptySet() }),
            cacheRead = { saved },
            cacheWrite = { writes += it },
            nowMs = { rig.now },
        )
        rig = Rig(repo, source, writes)
        return rig
    }

    private val empty = "[]"
    private fun songs(vararg items: String) = """[{"items":[${items.joinToString(",")}]}]"""

    // ── A miss and a failure ─────────────────────────────────────────

    @Test
    fun aMissShowsNothingAndSaysWhy() = runTest {
        val r = rig(FakeSource(answers = mutableMapOf("listenbrainz" to empty)))
        val emissions = r.repo.getRecommendedTracks().toList()

        // Saved list first, then the empty answer replaces it, then the reason.
        assertEquals(
            listOf(
                Resource.Success(listOf(RecommendedTrack(title = "My Top Song", artist = "Me", source = "listenbrainz"))),
                Resource.Success(emptyList()),
                Resource.Error(emptyRecommendationsMessage(hasListenBrainz = true, hasLastFm = false)),
            ),
            emissions,
        )
        // The stale list is not saved again.
        assertTrue(r.writes.single().contains("\"tracks\":[]"), "wrote: ${r.writes}")
        // Last.fm isn't connected, so its plugin isn't asked.
        assertEquals(listOf("listenbrainz"), r.source.asked)
    }

    @Test
    fun aProviderThatCouldntAnswerKeepsTheSavedList() = runTest {
        val r = rig(FakeSource(answers = mutableMapOf("listenbrainz" to null)))
        val emissions = r.repo.getRecommendedTracks().toList()

        assertEquals(Resource.Error(RecommendationsRepository.FAILED_MESSAGE), emissions.last())
        assertFalse(emissions.any { it == Resource.Success(emptyList<RecommendedTrack>()) })
        assertTrue(r.writes.isEmpty(), "a failure must not overwrite the saved list: ${r.writes}")
    }

    @Test
    fun aMissingPluginIsAFailureNotAMiss() = runTest {
        // The plugin runtime didn't start: nothing to ask, but a service is connected.
        val r = rig(FakeSource(providerList = emptyList()))
        val emissions = r.repo.getRecommendedTracks().toList()

        assertEquals(Resource.Error(RecommendationsRepository.FAILED_MESSAGE), emissions.last())
        assertTrue(r.writes.isEmpty())
    }

    @Test
    fun oneProviderFailingKeepsItsLastAnswerBesideTheOthersNewOne() = runTest {
        val source = FakeSource(
            answers = mutableMapOf(
                "lastfm" to songs("""{"type":"song","name":"Old Last.fm Song","artist":"A"}"""),
                "listenbrainz" to songs("""{"type":"song","name":"LB Song","artist":"B"}"""),
            ),
        )
        val r = rig(source, lastFm = "listener", saved = null)
        assertEquals(listOf("Old Last.fm Song", "LB Song"), r.repo.getRecommendedTracks().toList().last().tracks().map { it.title })

        r.now += RecommendationsRepository.SHARE_WINDOW_MS
        source.answers["lastfm"] = null
        source.answers["listenbrainz"] = songs("""{"type":"song","name":"New LB Song","artist":"B"}""")
        assertEquals(listOf("Old Last.fm Song", "New LB Song"), r.repo.getRecommendedTracks().toList().last().tracks().map { it.title })
    }

    @Test
    fun nothingConnectedAsksNobody() = runTest {
        val r = rig(FakeSource(), listenBrainz = null, saved = null)
        assertEquals(
            listOf(Resource.Loading, Resource.Error(RecommendationsRepository.NOT_CONFIGURED_MESSAGE)),
            r.repo.getRecommendedTracks().toList(),
        )
        assertTrue(r.source.asked.isEmpty())
    }

    @Test
    fun aProviderThatIsNotLastFmOrListenBrainzIsAskedWithNoUsername() = runTest {
        val source = FakeSource(
            providerList = listOf(RecommendationProvider("newservice", "New Service")),
            answers = mutableMapOf("newservice" to songs("""{"type":"song","name":"Song","artist":"A"}""")),
        )
        val r = rig(source, listenBrainz = null, saved = null)
        assertEquals(listOf("Song"), r.repo.getRecommendedTracks().toList().last().tracks().map { it.title })
    }

    // ── What a song carries ──────────────────────────────────────────

    @Test
    fun aSongCarriesItsIdsAndItsLengthInMilliseconds() = runTest {
        val source = FakeSource(
            answers = mutableMapOf(
                "listenbrainz" to songs(
                    """{"type":"song","id":"9D59B396-1F9D-4A70-8B17-6B1DD97D1C73","name":"Dream House","artist":"Deafheaven",
                        "duration":555,"mbid":"9D59B396-1F9D-4A70-8B17-6B1DD97D1C73","isrc":"gb5ep1400001"}""",
                    """{"type":"song","name":"Bad Ids","artist":"A","mbid":"not-an-mbid","isrc":"nope"}""",
                    """{"type":"song","name":"No Artist"}""",
                ),
            ),
        )
        val tracks = rig(source, saved = null).repo.getRecommendedTracks().toList().last().tracks()

        assertEquals(
            listOf(
                RecommendedTrack(
                    title = "Dream House", artist = "Deafheaven", duration = 555_000, source = "listenbrainz",
                    recordingMbid = "9d59b396-1f9d-4a70-8b17-6b1dd97d1c73", isrc = "GB5EP1400001",
                ),
                RecommendedTrack(title = "Bad Ids", artist = "A", source = "listenbrainz"),
            ),
            tracks,
        )
    }

    @Test
    fun providersMergeInOrderAndTheSameSongShowsOnce() = runTest {
        val source = FakeSource(
            answers = mutableMapOf(
                "lastfm" to songs(
                    """{"type":"song","name":"Shared","artist":"Artist","duration":200}""",
                    """{"type":"song","name":"Only Last.fm","artist":"Artist"}""",
                ),
                "listenbrainz" to songs(
                    """{"type":"song","name":"shared","artist":"ARTIST","mbid":"9d59b396-1f9d-4a70-8b17-6b1dd97d1c73"}""",
                    """{"type":"song","name":"Only LB","artist":"Other"}""",
                ),
            ),
        )
        val tracks = rig(source, lastFm = "listener", saved = null).repo.getRecommendedTracks().toList().last().tracks()

        assertEquals(listOf("Shared", "Only Last.fm", "Only LB"), tracks.map { it.title })
        assertEquals(listOf("lastfm", "lastfm", "listenbrainz"), tracks.map { it.source })
    }

    // ── Artists, and one fetch for both lists ────────────────────────

    @Test
    fun artistsAreTheArtistsOfTheSongsAndBothListsShareOneFetch() = runTest {
        val source = FakeSource(
            answers = mutableMapOf(
                "listenbrainz" to songs(
                    """{"type":"song","name":"One","artist":"Deafheaven"}""",
                    """{"type":"song","name":"Two","artist":"deafheaven"}""",
                    """{"type":"song","name":"Three","artist":"Anathema"}""",
                ),
            ),
        )
        val r = rig(source, saved = null)
        r.repo.getRecommendedTracks().toList()
        val artists = r.repo.getRecommendedArtists().toList().filterIsInstance<Resource.Success<List<RecommendedArtist>>>().last().data

        assertEquals(listOf("Deafheaven", "Anathema"), artists.map { it.name })
        assertEquals(listOf("listenbrainz", "listenbrainz"), artists.map { it.source })
        assertEquals(listOf("listenbrainz"), source.asked, "the second list reused the first one's fetch")

        // Past the window, the next load asks again.
        r.now += RecommendationsRepository.SHARE_WINDOW_MS
        r.repo.getRecommendedTracks().toList()
        assertEquals(listOf("listenbrainz", "listenbrainz"), source.asked)
    }

    @Test
    fun theSavedFileKeepsEachProvidersAnswer() = runTest {
        val source = FakeSource(answers = mutableMapOf("listenbrainz" to songs("""{"type":"song","name":"One","artist":"A"}""")))
        val r = rig(source, saved = null)
        r.repo.getRecommendedTracks().toList()
        val saved = r.writes.last()

        // A new launch reads it; ListenBrainz then fails and its rows stay.
        val failing = FakeSource(answers = mutableMapOf("listenbrainz" to null))
        val next = rig(failing, saved = saved)
        val emissions = next.repo.getRecommendedTracks().toList()
        assertEquals(listOf("One"), emissions.last().tracks().map { it.title })
        assertNull(emissions.firstOrNull { it is Resource.Error })
    }

    @Test
    fun theMessageNamesTheConnectedServices() {
        assertTrue(emptyRecommendationsMessage(true, false).startsWith("ListenBrainz hasn't made recommendations"))
        assertTrue(emptyRecommendationsMessage(true, false).contains("connect Last.fm"))
        assertTrue(emptyRecommendationsMessage(true, true).startsWith("Neither ListenBrainz nor Last.fm"))
        assertTrue(emptyRecommendationsMessage(false, true).startsWith("Last.fm doesn't have"))
    }

    private fun Resource<List<RecommendedTrack>>.tracks(): List<RecommendedTrack> =
        (this as Resource.Success).data
}
