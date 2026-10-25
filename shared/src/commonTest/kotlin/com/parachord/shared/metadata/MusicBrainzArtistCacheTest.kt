package com.parachord.shared.metadata

import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.auth.OAuthTokenRefresher
import com.parachord.shared.api.installSharedPlugins
import com.parachord.shared.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A failed MusicBrainz artist lookup must not be cached as "no such artist".
 * It was, for the life of the process, so one throttled or dropped request left
 * that artist's discography and bio blank until the app restarted.
 */
class MusicBrainzArtistCacheTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val noAuth = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val noRefresh = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }
    private val artistJson = """{"count":1,"offset":0,"artists":[{"id":"a74b1b7f-71a5-4011-9441-d0b5e4122711","name":"Radiohead"}]}"""
    private val noArtistJson = """{"count":0,"offset":0,"artists":[]}"""

    private fun kotlinx.coroutines.test.TestScope.provider(respondWith: () -> String?): Pair<MusicBrainzProvider, () -> Int> {
        var requests = 0
        val engine = MockEngine {
            requests++
            val body = respondWith() ?: error("connection reset")
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = MusicBrainzClient(
            HttpClient(engine) { installSharedPlugins(json, AppConfig(userAgent = "Parachord/test"), noAuth, noRefresh) },
            nowMs = { testScheduler.currentTime },
        )
        return MusicBrainzProvider(client) to { requests }
    }

    @Test
    fun aFailedLookupIsRetriedOnTheNextCall() = runTest {
        var down = true
        val (mb, _) = provider { if (down) null else artistJson }

        assertFailsWith<Exception> { mb.resolveArtist("Radiohead") }
        down = false
        assertEquals("Radiohead", mb.resolveArtist("Radiohead")?.name)
    }

    @Test
    fun anAnsweredLookupIsCached_includingNoSuchArtist() = runTest {
        val (mb, requests) = provider { noArtistJson }

        assertNull(mb.resolveArtist("Nobody At All"))
        assertNull(mb.resolveArtist("nobody at all "))
        assertEquals(1, requests(), "a genuine no-match is an answer and is cached")
    }
}
