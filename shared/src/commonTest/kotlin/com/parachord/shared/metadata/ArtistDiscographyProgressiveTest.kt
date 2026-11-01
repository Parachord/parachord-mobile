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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #478 (desktop parachord#1188): the artist page's discography shows as each
 * provider answers, and a known MusicBrainz id skips the artist name search.
 */
class ArtistDiscographyProgressiveTest {

    private class Provider(
        override val name: String,
        val delayMs: Long,
        val titles: List<String>,
        val fails: Boolean = false,
    ) : MetadataProvider {
        override val priority = 0
        override suspend fun isAvailable() = true
        override suspend fun searchTracks(query: String, limit: Int) = emptyList<TrackSearchResult>()
        override suspend fun searchAlbums(query: String, limit: Int) = emptyList<AlbumSearchResult>()
        override suspend fun searchArtists(query: String, limit: Int) = emptyList<ArtistInfo>()
        override suspend fun getArtistInfo(artistName: String): ArtistInfo? = null
        override suspend fun getArtistAlbums(artistName: String, limit: Int): List<AlbumSearchResult> {
            delay(delayMs)
            if (fails) error("$name is down")
            return titles.map { AlbumSearchResult(it, artistName, artworkUrl = "https://coverartarchive.org/$it", provider = name) }
        }
    }

    private fun service(
        vararg providers: Provider,
        enrich: (suspend (String, List<AlbumSearchResult>) -> List<AlbumSearchResult>)? = null,
    ) = MetadataService(providers.toList(), getDisabledProviders = { emptySet() }, enrichAlbumArtwork = enrich)

    @Test
    fun fastProviderAlbumsShowBeforeMusicBrainzBrowses() = runTest {
        val svc = service(
            Provider("musicbrainz", delayMs = 4_000, titles = listOf("Castlemania", "Floating Coffin")),
            Provider("spotify", delayMs = 300, titles = listOf("Floating Coffin")),
        )
        val at = mutableListOf<Long>()
        val emissions = mutableListOf<List<String>>()
        svc.getArtistAlbumsProgressively("Thee Oh Sees").collect {
            at += testScheduler.currentTime
            emissions += it.map { a -> a.title }
        }
        assertEquals(listOf(300L, 4_000L), at)
        assertEquals(listOf(listOf("Floating Coffin"), listOf("Castlemania", "Floating Coffin")), emissions)
        assertEquals(emissions.last(), svc.getArtistAlbums("Thee Oh Sees").map { it.title })
    }

    @Test
    fun theListShowsBeforeTheArtworkEnrichment() = runTest {
        val svc = service(
            Provider("musicbrainz", delayMs = 0, titles = listOf("Castlemania")),
            enrich = { _, albums ->
                delay(5_000)
                albums.map { it.copy(artworkUrl = "https://itunes.example/${it.title}") }
            },
        )
        val at = mutableListOf<Long>()
        val art = mutableListOf<String?>()
        svc.getArtistAlbumsProgressively("Thee Oh Sees").collect {
            at += testScheduler.currentTime
            art += it.single().artworkUrl
        }
        assertEquals(listOf(0L, 5_000L), at)
        assertEquals(listOf<String?>("https://coverartarchive.org/Castlemania", "https://itunes.example/Castlemania"), art)
    }

    @Test
    fun anEmptyDiscographyWithAFailedProviderStillThrows() = runTest {
        val svc = service(
            Provider("musicbrainz", delayMs = 100, titles = emptyList(), fails = true),
            Provider("spotify", delayMs = 50, titles = emptyList()),
        )
        assertFailsWith<DiscographyUnavailableException> { svc.getArtistAlbumsProgressively("X").toList() }
    }

    // ── known MBID ─────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }
    private val noAuth = object : AuthTokenProvider {
        override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = null
        override suspend fun invalidate(realm: AuthRealm) {}
    }
    private val noRefresh = object : OAuthTokenRefresher {
        override suspend fun refresh(realm: AuthRealm): AuthCredential.BearerToken? = null
    }
    private val searchedMbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
    private val knownMbid = "f5f5e4e4-1111-4222-8333-944455556666"

    private fun kotlinx.coroutines.test.TestScope.musicBrainz(paths: MutableList<String>): MusicBrainzProvider {
        val engine = MockEngine { request ->
            paths += request.url.encodedPath + "?" + request.url.encodedQuery
            val body = if (request.url.encodedPath.endsWith("/artist/")) {
                """{"artists":[{"id":"$searchedMbid","name":"Wire"}]}"""
            } else {
                """{"release-groups":[{"id":"rg-1","title":"Pink Flag","primary-type":"Album","artist-credit":[{"name":"Wire"}]}]}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = MusicBrainzClient(
            HttpClient(engine) { installSharedPlugins(json, AppConfig(userAgent = "Parachord/test"), noAuth, noRefresh) },
            nowMs = { testScheduler.currentTime },
        )
        return MusicBrainzProvider(client)
    }

    @Test
    fun aKnownMbidSkipsTheArtistNameSearch() = runTest {
        val paths = mutableListOf<String>()
        val svc = MetadataService(listOf(musicBrainz(paths)), getDisabledProviders = { emptySet() })

        val albums = svc.getArtistAlbums("Wire", mbid = knownMbid)

        assertEquals(listOf("Pink Flag"), albums.map { it.title })
        assertTrue(paths.none { it.contains("/artist/?") }, "no name search: $paths")
        assertTrue(paths.single().contains(knownMbid), "the browse uses the known id: $paths")
    }

    @Test
    fun aKnownMbidReplacesANamesakeTheNameSearchFound() = runTest {
        val paths = mutableListOf<String>()
        val mb = musicBrainz(paths)
        val svc = MetadataService(listOf(mb), getDisabledProviders = { emptySet() })

        assertEquals(searchedMbid, mb.resolveArtist("Wire")?.id)
        svc.getArtistAlbums("Wire", mbid = knownMbid)

        assertEquals(knownMbid, mb.resolveArtist("Wire")?.id)
        assertTrue(paths.last().contains(knownMbid), "browsed the known artist, not the namesake: $paths")
    }

    @Test
    fun anInvalidMbidIsIgnored() = runTest {
        val paths = mutableListOf<String>()
        val svc = MetadataService(listOf(musicBrainz(paths)), getDisabledProviders = { emptySet() })

        svc.getArtistAlbums("Wire", mbid = "not-an-mbid")

        assertTrue(paths.first().contains("/artist/?"), "falls back to the name search: $paths")
    }
}
