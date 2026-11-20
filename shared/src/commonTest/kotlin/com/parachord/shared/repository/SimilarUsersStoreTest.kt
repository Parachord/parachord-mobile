package com.parachord.shared.repository

import com.parachord.shared.model.Friend
import com.parachord.shared.recommendations.FriendExclusions
import com.parachord.shared.recommendations.LISTENERS_ROW_KEY
import com.parachord.shared.recommendations.SimilarUser
import com.parachord.shared.recommendations.buildRecommendationPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Listeners' people: desktop's `fetchSimilarUsers` and its cache, shared. */
class SimilarUsersStoreTest {

    private class FakeSource(var answer: suspend (String, String) -> String) : SimilarUsersSource {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun providers() = listOf(SimilarUsersProvider("listenbrainz", "ListenBrainz", isMetaService = true))
        override suspend fun fetch(providerId: String, configJson: String): String {
            calls += providerId to configJson
            return answer(providerId, configJson)
        }
    }

    private var now = 1_000_000_000L
    private var disk: String? = null
    private var saves = 0

    private fun store(
        source: SimilarUsersSource,
        scope: CoroutineScope,
        exclude: FriendExclusions = FriendExclusions(),
        config: String? = """{"username":"me"}""",
    ) = SimilarUsersStore(
        source = source,
        friends = { exclude },
        configFor = { config },
        providerIsOn = { true },
        load = { disk },
        save = { disk = it; saves++ },
        nowMs = { now },
        scope = scope,
    )

    private fun diskWith(users: String, fetchedAt: Long, shape: Int = SimilarUsersStore.SHAPE) =
        """{"providers":{"listenbrainz":{"users":$users,"fetchedAt":$fetchedAt,"account":"me","shape":$shape}}}"""

    @Test
    fun `canAsk only with an on provider whose config names an account`() = runTest {
        val source = FakeSource { _, _ -> "[]" }
        assertTrue(store(source, backgroundScope).canAsk())
        assertFalse(store(source, backgroundScope, config = null).canAsk())
        assertFalse(store(source, backgroundScope, config = "{}").canAsk())
    }

    @Test
    fun `an empty answer is cached as fresh`() = runTest {
        val source = FakeSource { _, _ -> "[]" }
        val s = store(source, backgroundScope)
        assertEquals(mapOf("listenbrainz" to emptyList<SimilarUser>()), s.usersFor(false))
        assertEquals(1, saves)
        now += 60_000
        assertEquals(mapOf("listenbrainz" to emptyList<SimilarUser>()), s.usersFor(false))
        assertEquals(1, source.calls.size)
        // And from disk on the next launch.
        val again = store(source, backgroundScope)
        again.usersFor(false)
        assertEquals(1, source.calls.size)
    }

    @Test
    fun `a failure keeps the old list and stores nothing`() = runTest {
        disk = diskWith("""[{"username":"ann","similarity":0.5}]""", fetchedAt = now - SimilarUsersStore.FRESH_MS - 1)
        val before = disk
        val source = FakeSource { _, _ -> throw IllegalStateException("offline") }
        val s = store(source, backgroundScope)
        assertEquals(listOf(SimilarUser("ann", 0.5)), s.usersFor(false)["listenbrainz"])
        assertEquals(1, source.calls.size)
        assertEquals(0, saves)
        assertEquals(before, disk)
    }

    @Test
    fun `an older shape is shown and fetched again`() = runTest {
        disk = diskWith("""[{"username":"ann"}]""", fetchedAt = now, shape = 2)
        val source = FakeSource { _, _ -> """[{"username":"bob","similarity":0.9}]""" }
        val s = store(source, backgroundScope)
        assertEquals(listOf(SimilarUser("ann")), s.cached()["listenbrainz"])
        assertEquals(listOf(SimilarUser("bob", 0.9)), s.usersFor(false)["listenbrainz"])
        s.usersFor(false)
        assertEquals(1, source.calls.size)
    }

    @Test
    fun `two callers share one fetch`() = runTest {
        val gate = CompletableDeferred<String>()
        val source = FakeSource { _, _ -> gate.await() }
        val s = store(source, backgroundScope)
        val a = async { s.usersFor(false) }
        val b = async { s.usersFor(true) }
        runCurrent()
        gate.complete("""[{"username":"ann"}]""")
        assertEquals(a.await(), b.await())
        assertEquals(1, source.calls.size)
    }

    @Test
    fun `no username is not asked`() = runTest {
        val source = FakeSource { _, _ -> "[]" }
        val s = store(source, backgroundScope, config = "{}")
        assertTrue(s.usersFor(true).isEmpty())
        val pending = store(source, backgroundScope, config = similarUsersConfig("lastfm", mapOf("lastfm" to """{"username":"me"}""")))
        assertTrue(pending.usersFor(true).isEmpty())
        assertEquals(0, source.calls.size)
    }

    @Test
    fun `excludeUsernames reaches the plugin`() = runTest {
        val source = FakeSource { _, _ -> "[]" }
        val exclude = friendExclusionsOf(
            friends = listOf(Friend(id = "f", username = "Ann", service = "listenbrainz", displayName = "Ann", addedAt = 0)),
            deletedFriendKeys = setOf("listenbrainz:bob", "lastfm:carl"),
            selfAccounts = emptyMap(),
        )
        store(source, backgroundScope, exclude = exclude).usersFor(false)
        val sent = Json.parseToJsonElement(source.calls.single().second).jsonObject
        assertEquals("me", sent["username"]?.jsonPrimitive?.content)
        assertEquals(setOf("ann", "bob"), sent["excludeUsernames"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `followed — removed — and yourself are excluded — in any case`() = runTest {
        val exclude = friendExclusionsOf(
            friends = listOf(Friend(id = "f", username = "ANN", service = "listenbrainz", displayName = "Ann", addedAt = 0)),
            deletedFriendKeys = setOf("listenbrainz:Bob"),
            selfAccounts = mapOf("listenbrainz" to "Me", "lastfm" to null),
        ).copy(friendServices = setOf("listenbrainz"))
        val users = mapOf(
            "listenbrainz" to listOf(SimilarUser("ann", 0.9), SimilarUser("BOB", 0.8), SimilarUser("me", 0.7), SimilarUser("Dee", 0.6)),
        )
        val row = buildRecommendationPage(similarUsers = users, exclude = exclude)
            .flatMap { it.rows }.firstOrNull { it.key == LISTENERS_ROW_KEY }
        assertEquals(listOf("Dee"), row?.listeners?.map { it.name })
    }

    @Test
    fun `Last_fm without a key is pending`() {
        val pending = Json.parseToJsonElement(similarUsersConfig("lastfm", mapOf("lastfm" to """{"username":"me"}"""))!!).jsonObject
        assertEquals("true", pending["credentialsPending"]?.jsonPrimitive?.content)
        val ready = Json.parseToJsonElement(similarUsersConfig("lastfm", mapOf("lastfm" to """{"username":"me","apiKey":"k"}"""))!!).jsonObject
        assertNull(ready["credentialsPending"])
    }
}
