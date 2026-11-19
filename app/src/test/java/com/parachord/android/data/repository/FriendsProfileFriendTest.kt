package com.parachord.android.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.FriendDao
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.Friend
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** [FriendsRepository.profileFriend] and the hidden-listening report, over an in-memory database. */
class FriendsProfileFriendTest {

    private object NoSecrets : SecureTokenStore {
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) {}
        override fun remove(key: String) {}
        override fun contains(key: String): Boolean = false
        override fun observe(key: String): Flow<String?> = flowOf(null)
    }

    private class Rig(val repo: FriendsRepository, val dao: FriendDao, val driver: JdbcSqliteDriver)

    private fun rig(handler: MockRequestHandleScope.(String) -> HttpResponseData = { error("unscripted $it") }): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
        val dao = FriendDao(ParachordDb(driver))
        val client = HttpClient(MockEngine { request -> handler(this, request.url.toString()) })
        val repo = FriendsRepository(
            friendDao = dao,
            lastFmClient = LastFmClient(client),
            listenBrainzClient = ListenBrainzClient(client),
            metadataService = MetadataService(emptyList(), getDisabledProviders = { emptySet() }),
            settingsStore = SettingsStore(NoSecrets, KvStore(MapSettings())),
            lastFmApiKey = "key",
        )
        return Rig(repo, dao, driver)
    }

    private val alice = Friend(id = "friend-1", username = "Alice", service = "listenbrainz", displayName = "Alice B", addedAt = 1L)

    @Test
    fun `a saved id gives that row`() = runBlocking {
        val r = rig().also { it.dao.upsert(alice) }
        assertEquals(alice.id, r.repo.profileFriend("friend-1")?.id)
    }

    @Test
    fun `a transient id matching a saved friend gives the saved row`() = runBlocking {
        val r = rig().also { it.dao.upsert(alice) }
        val f = r.repo.profileFriend("transient:listenbrainz:alice")!!
        assertEquals("friend-1", f.id)
        assertFalse(f.transient)
    }

    @Test
    fun `no match gives an unsaved friend`() = runBlocking {
        val r = rig().also { it.dao.upsert(alice) }
        val f = r.repo.profileFriend("transient:lastfm:alice")!!
        assertTrue(f.transient)
        assertEquals("alice", f.username)
    }

    @Test
    fun `an unknown plain id is null`() = runBlocking {
        assertNull(rig().repo.profileFriend("nope"))
    }

    @Test
    fun `a failing saved-list read gives an unsaved friend`() = runBlocking {
        val r = rig()
        r.driver.close()
        val f = r.repo.profileFriend("transient:lastfm:bob")!!
        assertTrue(f.transient)
        assertEquals("bob", f.username)
    }

    @Test
    fun `a hidden Last_fm profile is reported for someone not followed`() = runBlocking {
        val r = rig { respond("""{"error":17,"message":"Login required"}""", HttpStatusCode.Forbidden, headersOf("Content-Type", "application/json")) }
        var hidden: Boolean? = null
        val out = r.repo.getFriendRecentTracks("bob", "lastfm") { hidden = it }.toList()
        assertEquals(true, hidden)
        assertTrue(out.last() is Resource.Success)
    }
}
