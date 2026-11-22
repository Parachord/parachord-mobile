package com.parachord.shared.metadata

import com.parachord.shared.api.MusicBrainzClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An album page's type badge. Spotify's album_type calls every EP a "single",
 * so MusicBrainz's type wins whenever it has one: Phoebe Bridgers' "Anything"
 * showed "EP" on Home's Fresh Drops card and "Single" on its album page.
 */
class AlbumReleaseTypeTest {

    private class Fake(
        override val name: String,
        override val priority: Int,
        private val type: String?,
        private val trackCount: Int = 3,
    ) : MetadataProvider {
        var fail = false
        override suspend fun isAvailable() = true
        override suspend fun searchTracks(query: String, limit: Int) = emptyList<TrackSearchResult>()
        override suspend fun searchAlbums(query: String, limit: Int) = emptyList<AlbumSearchResult>()
        override suspend fun searchArtists(query: String, limit: Int) = emptyList<ArtistInfo>()
        override suspend fun getArtistInfo(artistName: String): ArtistInfo? = null
        override suspend fun getArtistAlbums(artistName: String, limit: Int) = emptyList<AlbumSearchResult>()
        override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? {
            if (fail) error("busy")
            return AlbumDetail(
                albumTitle, artistName,
                tracks = (1..trackCount).map { TrackSearchResult("Song $it", artistName) },
                releaseType = type, provider = name,
            )
        }
    }

    private fun service(vararg providers: MetadataProvider) =
        MetadataService(providers = providers.toList(), getDisabledProviders = { emptySet() })

    @Test
    fun musicBrainzsTypeBeatsSpotifysWithTheSameTrackCount() = runTest {
        val s = service(Fake("spotify", 20, "single"), Fake("musicbrainz", 0, "ep"))
        assertEquals("ep", s.getAlbumTracks("Anything", "Phoebe Bridgers")!!.releaseType)
        assertEquals("ep", s.getAlbumTracksProgressively("Anything", "Phoebe Bridgers").toList().last().releaseType)
    }

    @Test
    fun spotifysTypeStillFillsInWhenMusicBrainzHasNone() = runTest {
        val s = service(Fake("spotify", 20, "single"), Fake("musicbrainz", 0, null))
        assertEquals("single", s.getAlbumTracks("Song", "Someone")!!.releaseType)
    }

    @Test
    fun aKnownReleaseGroupKeepsItsTypeWhenMusicBrainzDoesntAnswer() = runTest {
        // MusicBrainz busy: every request fails, so only Spotify answers.
        val busy = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val mb = MusicBrainzProvider(MusicBrainzClient(HttpClient(busy), nowMs = { testScheduler.currentTime }))
        val s = service(mb, Fake("spotify", 20, "single"))
        s.rememberReleaseGroups(listOf(KnownReleaseGroup("Anything", "Phoebe Bridgers", "rg-1", "ep")))

        assertEquals("ep", s.getAlbumTracks("Anything", "Phoebe Bridgers")!!.releaseType)
        assertEquals("ep", s.getAlbumTracksWithCatalogFallback("Anything", "Phoebe Bridgers").toList().last().releaseType)
    }
}

class FreshDropsReleaseGroupsTest {
    @Test
    fun aDropWithAnIdIsAKnownReleaseGroup() {
        val groups = com.parachord.shared.repository.knownReleaseGroups(listOf(
            com.parachord.shared.repository.FreshDrop(mbid = "rg-1", title = "Anything", artist = "Phoebe Bridgers", releaseType = "EP"),
            com.parachord.shared.repository.FreshDrop(mbid = null, title = "No id", artist = "Someone"),
        ))
        assertEquals(listOf(KnownReleaseGroup("Anything", "Phoebe Bridgers", "rg-1", "ep")), groups)
    }
}
