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
import kotlin.test.assertNull

/**
 * #429: during a MusicBrainz outage the Wikipedia bio used to vanish, because
 * both the artist's MBID and its Wikidata id came from MusicBrainz. With the
 * MBID known on the device and Wikidata's own P434 search, it doesn't need
 * MusicBrainz at all.
 */
class WikipediaWithoutMusicBrainzTest {
    private val wildPink = "94fdf751-0644-4b78-9e72-dbdce539b5b7"
    private val json = headersOf("Content-Type", "application/json")
    private var musicBrainzRequests = 0

    private fun client() = HttpClient(MockEngine { req ->
        val url = req.url.toString()
        when {
            req.url.host == "musicbrainz.org" -> { musicBrainzRequests++; error("connection refused") }
            "list=search" in url -> respond("""{"query":{"search":[{"title":"Q25056425"}]}}""", HttpStatusCode.OK, json)
            "wbgetentities" in url -> respond(
                """{"entities":{"Q25056425":{"sitelinks":{"enwiki":{"title":"Wild Pink"}}}}}""", HttpStatusCode.OK, json,
            )
            "prop=extracts" in url -> respond(
                """{"query":{"pages":{"1":{"extract":"Wild Pink is an American rock band."}}}}""", HttpStatusCode.OK, json,
            )
            "page/summary" in url -> respond(
                """{"originalimage":{"source":"https://upload.wikimedia.org/wp.jpg"},""" +
                    """"content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Wild_Pink"}}}""",
                HttpStatusCode.OK, json,
            )
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }) {
        installSharedPlugins(
            Json { ignoreUnknownKeys = true }, AppConfig(userAgent = "Parachord/test"),
            object : AuthTokenProvider {
                override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
                override suspend fun invalidate(realm: AuthRealm) {}
            },
            object : OAuthTokenRefresher {
                override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
            },
        )
    }

    private fun kotlinx.coroutines.test.TestScope.provider(known: String?): WikipediaProvider {
        val http = client()
        val mb = MusicBrainzClient(http, nowMs = { testScheduler.currentTime })
        return WikipediaProvider(mb, MusicBrainzProvider(mb), http, knownArtistMbid = { known })
    }

    @Test
    fun aKnownMbidGetsTheWikipediaBioWithMusicBrainzDown() = runTest {
        val info = provider(known = wildPink).getArtistInfo("Wild Pink")
        assertEquals("Wild Pink is an American rock band.", info?.bio)
        assertEquals("wikipedia", info?.bioSource)
        assertEquals(0, musicBrainzRequests)
    }

    @Test
    fun withoutAKnownMbidItStillNeedsMusicBrainz() = runTest {
        assertNull(provider(known = null).getArtistInfo("Wild Pink"))
    }
}
