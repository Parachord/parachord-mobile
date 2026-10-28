package com.parachord.shared.repository

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.Resource
import com.parachord.shared.model.RecommendedTrack
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop parachord#1121: with no ListenBrainz recommendation playlist, For You
 * showed the user's own top tracks of the month as "recommendations". Now
 * nothing comes from ListenBrainz, the page says why, and an empty answer
 * replaces the saved list, while a failed request still keeps it.
 */
class RecommendationsEmptyTest {

    private class FakeSecureStore : SecureTokenStore {
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) {}
        override fun remove(key: String) {}
        override fun contains(key: String): Boolean = false
        override fun observe(key: String): Flow<String?> = flowOf(null)
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** Last saved recommendations: the user's own top track, from the old fallback. */
    private val savedCache =
        """{"tracks":[{"title":"My Top Song","artist":"Me","source":"listenbrainz"}],"artists":[]}"""

    private class Rig(val repo: RecommendationsRepository, val paths: MutableList<String>, val writes: MutableList<String>)

    private fun rig(createdForStatus: HttpStatusCode, createdForBody: String = """{"playlists":[]}"""): Rig {
        val paths = mutableListOf<String>()
        val writes = mutableListOf<String>()
        val engine = MockEngine { req ->
            paths += req.url.encodedPath
            when {
                req.url.encodedPath.endsWith("/playlists/createdfor") ->
                    respond(createdForBody, createdForStatus, jsonHeaders)
                req.url.encodedPath.contains("/stats/user/") ->
                    respond("""{"payload":{"recordings":[{"track_name":"My Top Song","artist_name":"Me"}]}}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        val settings = SettingsStore(FakeSecureStore(), KvStore(MapSettings(SettingsStore.LISTENBRAINZ_USERNAME to "listener")))
        val repo = RecommendationsRepository(
            httpClient = HttpClient(engine),
            listenBrainzClient = ListenBrainzClient(HttpClient(engine)),
            settingsStore = settings,
            metadataService = MetadataService(emptyList(), getDisabledProviders = { emptySet() }),
            cacheRead = { savedCache },
            cacheWrite = { writes += it },
        )
        return Rig(repo, paths, writes)
    }

    @Test
    fun noRecommendationPlaylist_showsNothingFromListenBrainz_andSaysWhy() = runTest {
        val r = rig(HttpStatusCode.OK)
        val emissions = r.repo.getRecommendedTracks().toList()

        // The user's own top tracks are never asked for.
        assertFalse(r.paths.any { it.contains("/stats/user/") }, "asked for: ${r.paths}")
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
    }

    @Test
    fun aFailedRequest_keepsTheSavedList() = runTest {
        val r = rig(HttpStatusCode.ServiceUnavailable)
        val emissions = r.repo.getRecommendedTracks().toList()

        assertEquals(Resource.Error("Failed to load recommendations"), emissions.last())
        assertFalse(emissions.any { it == Resource.Success(emptyList<RecommendedTrack>()) })
        assertTrue(r.writes.isEmpty(), "a failure must not overwrite the saved list: ${r.writes}")
    }

    @Test
    fun theMessageNamesTheConnectedServices() {
        assertTrue(emptyRecommendationsMessage(true, false).startsWith("ListenBrainz hasn't made recommendations"))
        assertTrue(emptyRecommendationsMessage(true, false).contains("connect Last.fm"))
        assertTrue(emptyRecommendationsMessage(true, true).startsWith("Neither ListenBrainz nor Last.fm"))
        assertTrue(emptyRecommendationsMessage(false, true).startsWith("Last.fm doesn't have"))
    }
}
