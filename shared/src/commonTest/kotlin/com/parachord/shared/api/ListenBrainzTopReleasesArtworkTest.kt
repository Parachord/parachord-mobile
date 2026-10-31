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
 * A ListenBrainz friend's Top Albums and Recent tabs showed only letter
 * placeholders: [ListenBrainzClient.getUserTopReleases] and
 * [ListenBrainzClient.getRecentListens] dropped the Cover Art Archive ids the
 * payloads carry. The cover now comes from `caa_release_mbid`, only when
 * `caa_id` says the archive has a front image, with no extra request.
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

    @Test
    fun `release mbid without a caa_id has no artwork`() = runTest {
        // caa_id is what says the archive holds a front image (desktop parachord#1181).
        val body = """
            {
              "payload": {
                "releases": [
                  { "release_name": "No Front", "artist_name": "Someone", "listen_count": 3,
                    "caa_release_mbid": "ed286309-6e64-48e3-835b-61aaf86cdb86" },
                  { "release_name": "Null Front", "artist_name": "Someone", "listen_count": 2,
                    "caa_release_mbid": "ed286309-6e64-48e3-835b-61aaf86cdb86", "caa_id": null }
                ]
              }
            }
        """.trimIndent()

        val releases = client(body).getUserTopReleases("jbwharris")

        assertEquals(2, releases.size)
        releases.forEach { assertNull(it.artworkUrl, "${it.releaseName} should have no artwork") }
    }

    @Test
    fun `listen gets the cover from its mbid_mapping`() = runTest {
        val body = """
            {
              "payload": {
                "listens": [
                  {
                    "listened_at": 1761930000,
                    "track_metadata": {
                      "artist_name": "Ministry",
                      "track_name": "Every Day Is Halloween",
                      "mbid_mapping": {
                        "caa_id": 42062579451,
                        "caa_release_mbid": "91bf554a-ded6-45d5-ab42-f40bc8b0e2db"
                      }
                    }
                  },
                  {
                    "listened_at": 1761929000,
                    "track_metadata": {
                      "artist_name": "Unmapped",
                      "track_name": "No Mapping"
                    }
                  },
                  {
                    "listened_at": 1761928000,
                    "track_metadata": {
                      "artist_name": "Mapped",
                      "track_name": "No Front Image",
                      "mbid_mapping": { "caa_release_mbid": "b5aaf2be-7bab-3037-863a-08c962a6e84a" }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val listens = client(body).getRecentListens("jbwharris")

        assertEquals(3, listens.size)
        assertEquals(
            "https://coverartarchive.org/release/91bf554a-ded6-45d5-ab42-f40bc8b0e2db/front-250",
            listens[0].artworkUrl,
        )
        assertNull(listens[1].artworkUrl)
        assertNull(listens[2].artworkUrl)
    }
}
