package com.parachord.android.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.FriendDao
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.Resource
import com.parachord.shared.repository.FriendsRepository
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Listeners' Follow and Play on [FriendsRepository], over fake HTTP and an in-memory database. */
class FriendsFollowTest {

    private val json = headersOf("Content-Type", "application/json")

    private object NoSecrets : SecureTokenStore {
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) {}
        override fun remove(key: String) {}
        override fun contains(key: String): Boolean = false
        override fun observe(key: String): Flow<String?> = flowOf(null)
    }

    private class Rig(val repo: FriendsRepository, val dao: FriendDao, val settings: SettingsStore, val requests: List<String>)

    private fun rig(handler: suspend MockRequestHandleScope.(path: String, query: String) -> HttpResponseData): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val dao = FriendDao(ParachordDb(driver))
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += request.url.host + request.url.encodedPath + "?" + request.url.encodedQuery
            handler(this, request.url.encodedPath, request.url.encodedQuery)
        }
        val client = HttpClient(engine)
        val settings = SettingsStore(NoSecrets, KvStore(MapSettings()))
        val repo = FriendsRepository(
            friendDao = dao,
            lastFmClient = LastFmClient(client),
            listenBrainzClient = ListenBrainzClient(client),
            metadataService = MetadataService(emptyList(), getDisabledProviders = { emptySet() }),
            settingsStore = settings,
            lastFmApiKey = "key",
        )
        return Rig(repo, dao, settings, requests)
    }

    @Test
    fun `follow adds the friend on the given service`() = runBlocking {
        // A bare name would be Last.fm through Add Friend; follow keeps the service it's given.
        val r = rig { _, _ -> respond("""{"payload":{"count":1,"listens":[]}}""", HttpStatusCode.OK, json) }
        r.settings.addDeletedFriendKey("listenbrainz:bob")
        val out = r.repo.follow("listenbrainz", "Bob")
        assertTrue(out is Resource.Success<*>)
        val saved = r.dao.getAllFriendsSync().single()
        assertEquals("listenbrainz", saved.service)
        assertEquals("Bob", saved.username)
        assertTrue(r.requests.any { "listenbrainz.org/1/user/Bob/listen-count" in it })
        assertFalse("listenbrainz:bob" in r.settings.getDeletedFriendKeys())
    }

    @Test
    fun `top tracks fall back to a month`() = runBlocking {
        val r = rig { path, query ->
            if ("recordings" in path && "range=week" in query) respond("""{"payload":{"recordings":[]}}""", HttpStatusCode.OK, json)
            else respond(
                """{"payload":{"recordings":[{"track_name":"Roads","artist_name":"Portishead","release_name":"Dummy","listen_count":3},{"track_name":"Glory Box","artist_name":"Portishead","listen_count":2}]}}""",
                HttpStatusCode.OK, json,
            )
        }
        val tracks = r.repo.listenerTopTracks("listenbrainz", "ann")
        assertEquals(listOf("Roads", "Glory Box"), tracks.map { it.title })
        assertEquals("Dummy", tracks.first().album)
        assertTrue(r.requests.any { "range=week" in it && "count=10" in it })
        assertTrue(r.requests.any { "range=month" in it })
    }

    @Test
    fun `top tracks throw when the service can't answer`() = runBlocking {
        val r = rig { _, _ -> respond("oops", HttpStatusCode.InternalServerError) }
        try {
            r.repo.listenerTopTracks("listenbrainz", "ann")
            fail("expected a failure")
        } catch (_: Exception) {
        }
    }
}
