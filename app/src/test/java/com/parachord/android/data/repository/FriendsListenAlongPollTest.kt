package com.parachord.android.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.FriendDao
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.Friend
import com.parachord.shared.playback.FriendTrack
import com.parachord.shared.playback.ListenAlongPoll
import com.parachord.shared.repository.FriendsRepository
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * [FriendsRepository.pollListenAlong] over fake HTTP and an in-memory database: a failed
 * request is `Failed`, never "not listening".
 */
class FriendsListenAlongPollTest {

    private val json = headersOf("Content-Type", "application/json")
    private val now get() = System.currentTimeMillis() / 1000

    private object NoSecrets : SecureTokenStore {
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) {}
        override fun remove(key: String) {}
        override fun contains(key: String): Boolean = false
        override fun observe(key: String): Flow<String?> = flowOf(null)
    }

    private class Rig(val repo: FriendsRepository, val dao: FriendDao)

    private fun rig(handler: suspend MockRequestHandleScope.(host: String, query: String) -> HttpResponseData?): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val dao = FriendDao(ParachordDb(driver))
        val engine = MockEngine { request ->
            handler(this, request.url.host, request.url.encodedPath + "?" + request.url.encodedQuery)
                ?: error("unscripted ${request.url}")
        }
        val client = HttpClient(engine)
        val repo = FriendsRepository(
            friendDao = dao,
            lastFmClient = LastFmClient(client),
            listenBrainzClient = ListenBrainzClient(client),
            metadataService = MetadataService(emptyList(), getDisabledProviders = { emptySet() }),
            settingsStore = SettingsStore(NoSecrets, KvStore(MapSettings())),
            lastFmApiKey = "key",
        )
        return Rig(repo, dao)
    }

    private fun transient(service: String) = Friend(
        id = "transient:$service:u", username = "u", service = service, displayName = "u",
        addedAt = 0, transient = true,
    )

    private fun saved(service: String) = Friend(
        id = "$service-u", username = "u", service = service, displayName = "U", addedAt = 0,
    )

    private fun ListenBrainzHost(body: String) = """{"payload":{"listens":$body}}"""

    private val lbListen =
        """[{"listened_at":1,"track_metadata":{"artist_name":"Portishead","track_name":"Roads","release_name":"Dummy"}}]"""

    private fun lastFmBody(nowPlaying: Boolean, scrobbledAgo: Long = 60): String {
        val np = if (nowPlaying)
            """{"name":"Roads","artist":{"#text":"Portishead","name":"Portishead"},"album":{"#text":"Dummy"},"@attr":{"nowplaying":"true"},"image":[]},""" else ""
        val uts = now - scrobbledAgo
        return """{"recenttracks":{"track":[$np{"name":"Glory Box","artist":{"#text":"Portishead","name":"Portishead"},"album":{"#text":"Dummy"},"date":{"uts":"$uts"},"image":[]}]}}"""
    }

    // ---- transient ListenBrainz ----

    @Test
    fun `transient ListenBrainz now playing is listening`() = runBlocking {
        val r = rig { _, _ -> respond(ListenBrainzHost(lbListen), HttpStatusCode.OK, json) }
        val out = r.repo.pollListenAlong(transient("listenbrainz"))
        assertEquals(ListenAlongPoll.Listening(FriendTrack("Roads", "Portishead", "Dummy", null)), out.poll)
        assertNotNull(out.friend)
    }

    @Test
    fun `transient ListenBrainz empty answer is not listening`() = runBlocking {
        val r = rig { _, _ -> respond(ListenBrainzHost("[]"), HttpStatusCode.OK, json) }
        assertEquals(ListenAlongPoll.NotListening, r.repo.pollListenAlong(transient("listenbrainz")).poll)
    }

    private fun lbListenAt(ts: Long) =
        """[{"listened_at":$ts,"track_metadata":{"artist_name":"Portishead","track_name":"Roads","release_name":"Dummy"}}]"""

    @Test
    fun `transient ListenBrainz between songs uses the latest listen as a saved friend does`() = runBlocking {
        // #610: a station's playing-now is empty between songs; its latest
        // listen a minute ago keeps it on air, the same answer a saved friend gets.
        val r = rig { _, query ->
            if ("playing-now" in query) respond(ListenBrainzHost("[]"), HttpStatusCode.OK, json)
            else respond(ListenBrainzHost(lbListenAt(now - 60)), HttpStatusCode.OK, json)
        }
        val listening = ListenAlongPoll.Listening(FriendTrack("Roads", "Portishead", "Dummy", null))
        assertEquals(listening, r.repo.pollListenAlong(transient("listenbrainz")).poll)
        val f = saved("listenbrainz")
        r.dao.upsert(f)
        assertEquals(listening, r.repo.pollListenAlong(f).poll)
    }

    @Test
    fun `transient ListenBrainz with an old latest listen is not listening`() = runBlocking {
        val r = rig { _, query ->
            if ("playing-now" in query) respond(ListenBrainzHost("[]"), HttpStatusCode.OK, json)
            else respond(ListenBrainzHost(lbListenAt(now - 3600)), HttpStatusCode.OK, json)
        }
        assertEquals(ListenAlongPoll.NotListening, r.repo.pollListenAlong(transient("listenbrainz")).poll)
    }

    @Test
    fun `transient ListenBrainz HTTP 500 is failed`() = runBlocking {
        val r = rig { _, _ -> respondError(HttpStatusCode.InternalServerError) }
        val out = r.repo.pollListenAlong(transient("listenbrainz"))
        assertEquals(ListenAlongPoll.Failed, out.poll)
        assertNull(out.friend)
    }

    @Test
    fun `transient ListenBrainz transport error is failed`() = runBlocking {
        val r = rig { _, _ -> throw IOException("down") }
        assertEquals(ListenAlongPoll.Failed, r.repo.pollListenAlong(transient("listenbrainz")).poll)
    }

    // ---- transient Last.fm ----

    @Test
    fun `transient Last-fm now playing is listening`() = runBlocking {
        val r = rig { _, _ -> respond(lastFmBody(true), HttpStatusCode.OK, json) }
        assertEquals(
            ListenAlongPoll.Listening(FriendTrack("Roads", "Portishead", "Dummy", null)),
            r.repo.pollListenAlong(transient("lastfm")).poll,
        )
    }

    @Test
    fun `transient Last-fm with nothing now playing and no recent scrobble is not listening`() = runBlocking {
        val r = rig { _, _ -> respond(lastFmBody(false, scrobbledAgo = 3600), HttpStatusCode.OK, json) }
        assertEquals(ListenAlongPoll.NotListening, r.repo.pollListenAlong(transient("lastfm")).poll)
    }

    @Test
    fun `transient Last-fm between songs counts its last scrobble as a saved friend does`() = runBlocking {
        // #610: no now-playing row, but a scrobble a minute ago: on air for 10 minutes.
        val r = rig { _, _ -> respond(lastFmBody(false, scrobbledAgo = 60), HttpStatusCode.OK, json) }
        assertEquals(
            ListenAlongPoll.Listening(FriendTrack("Glory Box", "Portishead", "Dummy", null)),
            r.repo.pollListenAlong(transient("lastfm")).poll,
        )
        val f = saved("lastfm")
        r.dao.upsert(f)
        assertEquals(r.repo.pollListenAlong(transient("lastfm")).poll, r.repo.pollListenAlong(f).poll)
    }

    @Test
    fun `transient Last-fm now-playing flag with no recent scrobble is not listening`() = runBlocking {
        // Last.fm leaves the flag on a row for hours after someone stops.
        val r = rig { _, _ -> respond(lastFmBody(true, scrobbledAgo = 7200), HttpStatusCode.OK, json) }
        assertEquals(ListenAlongPoll.NotListening, r.repo.pollListenAlong(transient("lastfm")).poll)
    }

    @Test
    fun `transient Last-fm HTTP 500 is failed`() = runBlocking {
        val r = rig { _, _ -> respondError(HttpStatusCode.InternalServerError) }
        assertEquals(ListenAlongPoll.Failed, r.repo.pollListenAlong(transient("lastfm")).poll)
    }

    @Test
    fun `transient Last-fm transport error is failed`() = runBlocking {
        val r = rig { _, _ -> throw IOException("down") }
        assertEquals(ListenAlongPoll.Failed, r.repo.pollListenAlong(transient("lastfm")).poll)
    }

    @Test
    fun `transient Last-fm hidden profile is not listening`() = runBlocking {
        val r = rig { _, _ ->
            respond("""{"error":17,"message":"Login: User required to be logged in"}""", HttpStatusCode.Forbidden, json)
        }
        assertEquals(ListenAlongPoll.NotListening, r.repo.pollListenAlong(transient("lastfm")).poll)
    }

    // ---- saved ----

    @Test
    fun `saved friend whose refresh fails is failed and the cache is left alone`() = runBlocking {
        val r = rig { _, _ -> respondError(HttpStatusCode.InternalServerError) }
        val f = saved("listenbrainz")
        r.dao.upsert(f.copy(cachedTrackName = "Old", cachedTrackArtist = "Old", cachedTrackTimestamp = now))
        val out = r.repo.pollListenAlong(f)
        assertEquals(ListenAlongPoll.Failed, out.poll)
        assertNull(out.friend)
        assertEquals("Old", r.dao.getFriendById(f.id)?.cachedTrackName)
    }

    @Test
    fun `saved friend refreshed and on air is listening`() = runBlocking {
        val r = rig { _, _ ->
            respond(ListenBrainzHost("""[{"listened_at":${now},"track_metadata":{"artist_name":"Portishead","track_name":"Roads","release_name":"Dummy"}}]"""), HttpStatusCode.OK, json)
        }
        val f = saved("listenbrainz")
        r.dao.upsert(f)
        val out = r.repo.pollListenAlong(f)
        assertEquals(ListenAlongPoll.Listening(FriendTrack("Roads", "Portishead", "Dummy", null)), out.poll)
        assertEquals(f.id, out.friend?.id)
    }

    @Test
    fun `saved friend refreshed and not on air is not listening`() = runBlocking {
        val r = rig { _, _ ->
            respond(ListenBrainzHost("""[{"listened_at":1,"track_metadata":{"artist_name":"Portishead","track_name":"Roads"}}]"""), HttpStatusCode.OK, json)
        }
        val f = saved("listenbrainz")
        r.dao.upsert(f)
        val out = r.repo.pollListenAlong(f)
        assertEquals(ListenAlongPoll.NotListening, out.poll)
        assertNotNull(out.friend)
    }

    @Test
    fun `saved friend whose row was deleted is not listening`() = runBlocking {
        val r = rig { _, _ -> respond(ListenBrainzHost("[]"), HttpStatusCode.OK, json) }
        val out = r.repo.pollListenAlong(saved("listenbrainz"))
        assertEquals(ListenAlongPoll.NotListening, out.poll)
        assertNull(out.friend)
    }

    @Test
    fun `saved Last-fm friend with a hidden profile is not listening`() = runBlocking {
        val r = rig { _, _ ->
            respond("""{"error":17,"message":"Login: User required to be logged in"}""", HttpStatusCode.Forbidden, json)
        }
        val f = saved("lastfm")
        r.dao.upsert(f.copy(cachedTrackName = "Stale", cachedTrackArtist = "Stale", cachedTrackTimestamp = now))
        val out = r.repo.pollListenAlong(f)
        assertEquals(ListenAlongPoll.NotListening, out.poll)
        assertEquals(true, out.friend?.hidesListening)
    }

    @Test
    fun `saved Last-fm friend with a failed request is failed`() = runBlocking {
        val r = rig { _, _ -> throw IOException("down") }
        val f = saved("lastfm")
        r.dao.upsert(f)
        assertEquals(ListenAlongPoll.Failed, r.repo.pollListenAlong(f).poll)
    }
}
