package com.parachord.shared.metadata

import com.parachord.shared.api.LastFmClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Last.fm's artist search lists only artists it knows the MusicBrainz id of. */
class LastFmArtistSearchTest {

    @Test
    fun pagesScrobblersMadeFromBadTagsAreLeftOut() = runTest {
        val mock = MockEngine {
            respond(
                content = """{"results":{"artistmatches":{"artist":[""" +
                    """{"name":"Portishead","mbid":"8f6bd1e4-fbe1-4f50-aa9b-94c450ec0f11","listeners":"3443230"},""" +
                    """{"name":"Pörtishead","mbid":"","listeners":"21"},""" +
                    """{"name":"Portishead & Moloko","listeners":"67029"},""" +
                    """{"name":"Mazzy Star - Topic","mbid":"","listeners":"347"}""" +
                    """]}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val rows = LastFmProvider(LastFmClient(HttpClient(mock)), "KEY").searchArtists("portishead", 10)
        assertEquals(listOf("Portishead"), rows.map { it.name })
    }
}
