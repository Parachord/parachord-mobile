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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** An album credited to two artists, searched by the credit a service wrote (#843). */
class MusicBrainzJointCreditTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val noAuth = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val noRefresh = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    private val queries = mutableListOf<String>()

    private fun hit(id: String, title: String, vararg artists: String) =
        """{"id":"$id","title":"$title","artist-credit":[${artists.joinToString { """{"name":"$it"}""" }}],"score":100}"""

    /** MusicBrainz answering each search from [answers] (by query; nothing for any other) and any release lookup. */
    private fun TestScope.provider(answers: Map<String, List<String>>): MusicBrainzProvider {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            val body = if (path.endsWith("/release/")) {
                val query = req.url.parameters["query"]!!
                queries += query
                """{"releases":[${answers[query].orEmpty().joinToString()}]}"""
            } else {
                val id = path.substringAfterLast('/')
                """{"id":"$id","title":"$id","artist-credit":[{"name":"X"}],"media":[{"tracks":[{"id":"t","title":"Song"}]}]}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = MusicBrainzClient(
            HttpClient(engine) { installSharedPlugins(json, AppConfig(userAgent = "Parachord/test"), noAuth, noRefresh) },
            nowMs = { testScheduler.currentTime },
        )
        return MusicBrainzProvider(client)
    }

    private val whole = "release:\"Watch the Throne\" AND artist:\"Jay-Z, Kanye West\""
    private val byName = "release:\"Watch the Throne\" AND artist:\"Jay-Z\" AND artist:\"Kanye West\""

    @Test
    fun aJointCreditThatFindsNothingIsSearchedByItsNames() = runTest {
        val mb = provider(mapOf(byName to listOf(hit("wtt", "Watch the Throne", "Jay‐Z", "Kanye West"))))
        assertEquals("wtt", mb.getAlbumTracks("Watch the Throne", "Jay-Z, Kanye West")?.title)
        assertEquals(listOf(whole, byName), queries)
    }

    @Test
    fun onlyAHitCreditedToTheSameActIsTaken() = runTest {
        val mb = provider(
            mapOf(
                byName to listOf(
                    hit("three", "Watch the Throne", "Jay‐Z", "Kanye West", "Beyoncé", "Frank Ocean", "Otis Redding"),
                    hit("wtt", "Watch the Throne", "Kanye West", "Jay‐Z"),
                ),
            ),
        )
        assertEquals("wtt", mb.getAlbumTracks("Watch the Throne", "Jay-Z, Kanye West")?.title)

        val none = provider(mapOf(byName to listOf(hit("other", "Watch the Throne", "Various Artists"))))
        assertNull(none.getAlbumTracks("Watch the Throne", "Jay-Z, Kanye West"))
    }

    @Test
    fun anActWhoseNameCarriesASeparatorIsFoundByTheFirstSearch() = runTest {
        val query = "release:\"Igor\" AND artist:\"Tyler, The Creator\""
        val mb = provider(mapOf(query to listOf(hit("igor", "Igor", "Tyler, The Creator"))))
        assertEquals("igor", mb.getAlbumTracks("Igor", "Tyler, The Creator")?.title)
        assertEquals(listOf(query), queries)
    }

    @Test
    fun aSingleNameThatFindsNothingIsNotSearchedAgain() = runTest {
        assertNull(provider(emptyMap()).getAlbumTracks("Nope", "Portishead"))
        assertEquals(1, queries.size)
    }
}
