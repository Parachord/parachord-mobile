package com.parachord.shared.metadata

import com.parachord.shared.api.AppleMusicClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The iTunes cover upgrade both apps run in the metadata cascade (#595). */
class MetadataWiringTest {
    private val caa = "https://coverartarchive.org/release-group/x/front-500"

    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK, seen: MutableList<String> = mutableListOf()) =
        AppleMusicClient(HttpClient(MockEngine { req ->
            seen += req.url.toString()
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }))

    private val hit = """{"resultCount":1,"results":[{"collectionName":"OK Computer","artistName":"Radiohead",
        "artworkUrl100":"https://is1.mzstatic.com/a/100x100bb.jpg"}]}"""

    @Test fun `a Cover Art Archive cover becomes the iTunes cover`() = runTest {
        val out = upgradeCoverArtArchiveArtwork(client(hit), "Radiohead", listOf(AlbumSearchResult("OK Computer", "Radiohead", caa)))
        assertEquals("https://is1.mzstatic.com/a/600x600bb.jpg", out.single().artworkUrl)
    }

    @Test fun `other covers are left alone and not looked up`() = runTest {
        val seen = mutableListOf<String>()
        val albums = listOf(AlbumSearchResult("A", "Radiohead", "https://i.scdn.co/image/x"), AlbumSearchResult("B", "Radiohead", null))
        assertEquals(albums, upgradeCoverArtArchiveArtwork(client(hit, seen = seen), "Radiohead", albums))
        assertEquals(0, seen.size)
    }

    @Test fun `another artist's album or a failed lookup keeps the cover`() = runTest {
        val album = listOf(AlbumSearchResult("OK Computer", "Radiohead", caa))
        assertEquals(album, upgradeCoverArtArchiveArtwork(client(hit), "Portishead", album))
        assertEquals(album, upgradeCoverArtArchiveArtwork(client("busy", HttpStatusCode.TooManyRequests), "Radiohead", album))
    }
}
