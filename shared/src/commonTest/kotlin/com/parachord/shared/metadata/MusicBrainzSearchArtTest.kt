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

/** MusicBrainz's search rows carry their album's Cover Art Archive covers. */
class MusicBrainzSearchArtTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val noAuth = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val noRefresh = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }

    private val recordings = """{"recordings":[{"id":"rec","title":"Fade Into You","score":100,""" +
        """"artist-credit":[{"name":"Mazzy Star"}],""" +
        """"releases":[{"id":"rel","title":"So Tonight That I Might See","release-group":{"id":"grp","primary-type":"Album"}}]}]}"""
    private val releases = """{"releases":[""" +
        """{"id":"rel","title":"So Tonight That I Might See","artist-credit":[{"name":"Mazzy Star"}],"score":100,"release-group":{"id":"grp","primary-type":"Album"}},""" +
        """{"id":"old","title":"Bootleg","artist-credit":[{"name":"Mazzy Star"}],"score":90}]}"""

    private fun kotlinx.coroutines.test.TestScope.provider(): MusicBrainzProvider {
        val engine = MockEngine { req ->
            val body = if (req.url.encodedPath.contains("/recording")) recordings else releases
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = MusicBrainzClient(
            HttpClient(engine) { installSharedPlugins(json, AppConfig(userAgent = "Parachord/test"), noAuth, noRefresh) },
            nowMs = { testScheduler.currentTime },
        )
        return MusicBrainzProvider(client)
    }

    @Test
    fun aTrackRowCarriesItsAlbumsCoversToCheck() = runTest {
        val row = provider().searchTracks("fade into you", 10).single()
        assertNull(row.artworkUrl) // unchecked, so not shown as the row's own
        assertEquals(
            listOf(
                MusicBrainzProvider.releaseGroupArtUrl("grp"),
                MusicBrainzProvider.coverArtUrl("rel"),
            ),
            row.artworkCandidates,
        )
    }

    @Test
    fun anAlbumRowShowsItsReleaseGroupsCover_thenTheReleasesAsAFallback() = runTest {
        val rows = provider().searchAlbums("mazzy star", 10)
        assertEquals(MusicBrainzProvider.releaseGroupArtUrl("grp"), rows[0].artworkUrl)
        assertEquals(listOf(MusicBrainzProvider.coverArtUrl("rel")), rows[0].artworkCandidates)
        // No release group in the answer: the release's own cover, as before.
        assertEquals(MusicBrainzProvider.coverArtUrl("old"), rows[1].artworkUrl)
        assertEquals(emptyList(), rows[1].artworkCandidates)
    }
}
