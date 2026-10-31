package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A ListenBrainz friend's Top Albums tab showed only letter placeholders:
 * [ListenBrainzClient.getUserTopReleases] dropped the `caa_release_mbid` the
 * stats payload carries for each release. The cover now comes from that id,
 * with no extra request (desktop searches MusicBrainz per album, which takes
 * 50+ seconds under mobile's 1 req/s MusicBrainz throttle).
 */
class ListenBrainzTopReleasesArtworkTest {

    private fun client(body: String) = ListenBrainzClient(
        HttpClient(MockEngine { _ ->
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
    )

    @Test
    fun `release with a caa_release_mbid gets its Cover Art Archive url`() = runTest {
        val body = """
            {
              "payload": {
                "releases": [
                  {
                    "release_name": "Fetch the Bolt Cutters",
                    "artist_name": "Fiona Apple",
                    "listen_count": 42,
                    "caa_id": 39250165476,
                    "caa_release_mbid": "ed286309-6e64-48e3-835b-61aaf86cdb86"
                  }
                ]
              }
            }
        """.trimIndent()

        val releases = client(body).getUserTopReleases("jbwharris")

        assertEquals(1, releases.size)
        assertEquals(
            "https://coverartarchive.org/release/ed286309-6e64-48e3-835b-61aaf86cdb86/front-250",
            releases[0].artworkUrl,
        )
        assertEquals(42, releases[0].listenCount)
    }

    @Test
    fun `release without a caa_release_mbid has no artwork`() = runTest {
        val body = """
            {
              "payload": {
                "releases": [
                  { "release_name": "No Cover", "artist_name": "Someone", "listen_count": 3 },
                  { "release_name": "Blank Cover", "artist_name": "Someone", "listen_count": 2, "caa_release_mbid": "  " },
                  { "release_name": "Null Cover", "artist_name": "Someone", "listen_count": 1, "caa_release_mbid": null }
                ]
              }
            }
        """.trimIndent()

        val releases = client(body).getUserTopReleases("jbwharris")

        assertEquals(3, releases.size)
        releases.forEach { assertNull(it.artworkUrl, "${it.releaseName} should have no artwork") }
    }
}
