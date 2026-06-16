package com.parachord.android.data.metadata

// WikipediaProvider + DiscogsProvider live only in shared (no android-package
// typealias, unlike the other providers), so import them directly.
import com.parachord.shared.metadata.DiscogsProvider
import com.parachord.shared.metadata.WikipediaProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MetadataServiceTest {

    private lateinit var musicBrainz: MusicBrainzProvider
    private lateinit var wikipedia: WikipediaProvider
    private lateinit var lastFm: LastFmProvider
    private lateinit var discogs: DiscogsProvider
    private lateinit var spotify: SpotifyProvider
    private var disabledProviders: Set<String> = emptySet()
    private lateinit var service: MetadataService

    @Before
    fun setup() {
        musicBrainz = mockk()
        wikipedia = mockk()
        lastFm = mockk()
        discogs = mockk()
        spotify = mockk()
        disabledProviders = emptySet()

        coEvery { musicBrainz.priority } returns 0
        coEvery { musicBrainz.name } returns "musicbrainz"
        coEvery { musicBrainz.isAvailable() } returns true

        coEvery { wikipedia.priority } returns 5
        coEvery { wikipedia.name } returns "wikipedia"
        coEvery { wikipedia.isAvailable() } returns true

        coEvery { lastFm.priority } returns 10
        coEvery { lastFm.name } returns "lastfm"
        coEvery { lastFm.isAvailable() } returns true

        coEvery { discogs.priority } returns 15
        coEvery { discogs.name } returns "discogs"
        coEvery { discogs.isAvailable() } returns true

        coEvery { spotify.priority } returns 20
        coEvery { spotify.name } returns "spotify"
        coEvery { spotify.isAvailable() } returns false

        // Android `MetadataService` is now a typealias to the shared cascading
        // orchestrator — see `app/data/metadata/MetadataService.kt`. The shared
        // constructor takes `(providers, getDisabledProviders, enrichAlbumArtwork)`;
        // the test passes a null enrichment lambda since none of these tests
        // exercise the iTunes-search artwork-enrichment path.
        service = MetadataService(
            providers = listOf(musicBrainz, wikipedia, lastFm, discogs, spotify)
                .sortedBy { it.priority },
            getDisabledProviders = { disabledProviders },
            enrichAlbumArtwork = null,
        )
    }

    // -- searchTracks --

    @Test
    fun `searchTracks merges results from all available providers`() = runTest {
        val mbTrack = TrackSearchResult(title = "Song", artist = "Artist", provider = "musicbrainz", mbid = "mb1")
        val lfmTrack = TrackSearchResult(title = "Song", artist = "Artist", provider = "lastfm", artworkUrl = "http://img")

        coEvery { musicBrainz.searchTracks("test", 20) } returns listOf(mbTrack)
        coEvery { wikipedia.searchTracks("test", 20) } returns emptyList()
        coEvery { lastFm.searchTracks("test", 20) } returns listOf(lfmTrack)
        coEvery { discogs.searchTracks("test", 20) } returns emptyList()

        val results = service.searchTracks("test")
        assertEquals(1, results.size) // deduplicated
        // Merged: should have both mbid and artworkUrl
        assertNotNull(results[0].mbid)
        assertNotNull(results[0].artworkUrl)
    }

    @Test
    fun `searchTracks isolates provider failures`() = runTest {
        coEvery { musicBrainz.searchTracks(any(), any()) } throws RuntimeException("Network error")
        coEvery { wikipedia.searchTracks(any(), any()) } returns emptyList()
        coEvery { lastFm.searchTracks(any(), any()) } returns listOf(
            TrackSearchResult(title = "Song", artist = "Artist", provider = "lastfm")
        )
        coEvery { discogs.searchTracks(any(), any()) } returns emptyList()

        val results = service.searchTracks("test")
        assertEquals(1, results.size) // still returns lastfm result despite musicbrainz failure
    }

    @Test
    fun `searchTracks skips disabled providers`() = runTest {
        disabledProviders = setOf("musicbrainz")
        coEvery { wikipedia.searchTracks(any(), any()) } returns emptyList()
        coEvery { lastFm.searchTracks(any(), any()) } returns listOf(
            TrackSearchResult(title = "Song", artist = "Artist", provider = "lastfm")
        )
        coEvery { discogs.searchTracks(any(), any()) } returns emptyList()

        val results = service.searchTracks("test")
        assertEquals(1, results.size)
        assertTrue(results[0].provider.contains("lastfm"))
    }

    // -- searchAlbums deduplication --

    @Test
    fun `searchAlbums deduplicates by title and artist`() = runTest {
        val album1 = AlbumSearchResult(title = "Abbey Road", artist = "The Beatles", provider = "musicbrainz", mbid = "mb1")
        val album2 = AlbumSearchResult(title = "Abbey Road", artist = "The Beatles", provider = "lastfm", artworkUrl = "http://img")

        coEvery { musicBrainz.searchAlbums("test", 10) } returns listOf(album1)
        coEvery { wikipedia.searchAlbums("test", 10) } returns emptyList()
        coEvery { lastFm.searchAlbums("test", 10) } returns listOf(album2)
        coEvery { discogs.searchAlbums("test", 10) } returns emptyList()

        val results = service.searchAlbums("test")
        assertEquals(1, results.size)
        assertNotNull(results[0].mbid)
        assertNotNull(results[0].artworkUrl)
    }

    // -- getArtistInfo cascading --

    @Test
    fun `getArtistInfo merges fields from multiple providers`() = runTest {
        val mbInfo = ArtistInfo(name = "Radiohead", mbid = "mb1", provider = "musicbrainz")
        val lfmInfo = ArtistInfo(
            name = "Radiohead",
            imageUrl = "http://img",
            bio = "A band from Oxford",
            bioSource = "lastfm",
            tags = listOf("alternative", "rock"),
            provider = "lastfm"
        )

        coEvery { musicBrainz.getArtistInfo("Radiohead") } returns mbInfo
        coEvery { wikipedia.getArtistInfo("Radiohead") } returns null
        coEvery { lastFm.getArtistInfo("Radiohead") } returns lfmInfo
        coEvery { discogs.getArtistInfo("Radiohead") } returns null

        val result = service.getArtistInfo("Radiohead")
        assertNotNull(result)
        assertEquals("mb1", result?.mbid)
        assertEquals("http://img", result?.imageUrl)
        assertFalse(result?.tags.isNullOrEmpty())
    }

    @Test
    fun `getArtistInfo prefers Wikipedia bio over LastFm`() = runTest {
        val lfmInfo = ArtistInfo(
            name = "Radiohead", bio = "Last.fm bio", bioSource = "lastfm", provider = "lastfm"
        )
        val wikiInfo = ArtistInfo(
            name = "Radiohead", bio = "Wikipedia bio", bioSource = "wikipedia", provider = "wikipedia"
        )

        coEvery { musicBrainz.getArtistInfo("Radiohead") } returns null
        coEvery { wikipedia.getArtistInfo("Radiohead") } returns wikiInfo
        coEvery { lastFm.getArtistInfo("Radiohead") } returns lfmInfo
        coEvery { discogs.getArtistInfo("Radiohead") } returns null

        val result = service.getArtistInfo("Radiohead")
        assertEquals("Wikipedia bio", result?.bio)
        assertEquals("wikipedia", result?.bioSource)
    }

    @Test
    fun `getArtistInfo prefers Discogs bio over LastFm`() = runTest {
        val lfmInfo = ArtistInfo(
            name = "Radiohead", bio = "Last.fm bio", bioSource = "lastfm", provider = "lastfm"
        )
        val discogsInfo = ArtistInfo(
            name = "Radiohead", bio = "Discogs bio", bioSource = "discogs", provider = "discogs"
        )

        coEvery { musicBrainz.getArtistInfo("Radiohead") } returns null
        coEvery { wikipedia.getArtistInfo("Radiohead") } returns null
        coEvery { lastFm.getArtistInfo("Radiohead") } returns lfmInfo
        coEvery { discogs.getArtistInfo("Radiohead") } returns discogsInfo

        val result = service.getArtistInfo("Radiohead")
        assertEquals("Discogs bio", result?.bio)
        assertEquals("discogs", result?.bioSource)
    }

    @Test
    fun `getArtistInfo returns null when all providers fail`() = runTest {
        coEvery { musicBrainz.getArtistInfo(any()) } returns null
        coEvery { wikipedia.getArtistInfo(any()) } returns null
        coEvery { lastFm.getArtistInfo(any()) } returns null
        coEvery { discogs.getArtistInfo(any()) } returns null

        assertNull(service.getArtistInfo("Unknown Artist"))
    }

    // -- getAlbumTracks merging --

    @Test
    fun `getAlbumTracks uses result with most tracks as base`() = runTest {
        val mbTracks = listOf(
            TrackSearchResult(title = "Track 1", artist = "Artist", provider = "musicbrainz"),
            TrackSearchResult(title = "Track 2", artist = "Artist", provider = "musicbrainz"),
            TrackSearchResult(title = "Track 3", artist = "Artist", provider = "musicbrainz"),
        )
        val lfmTracks = listOf(
            TrackSearchResult(title = "Track 1", artist = "Artist", provider = "lastfm", artworkUrl = "http://img"),
        )

        coEvery { musicBrainz.getAlbumTracks("Album", "Artist") } returns
            AlbumDetail(title = "Album", artist = "Artist", tracks = mbTracks, provider = "musicbrainz")
        coEvery { wikipedia.getAlbumTracks("Album", "Artist") } returns null
        coEvery { lastFm.getAlbumTracks("Album", "Artist") } returns
            AlbumDetail(title = "Album", artist = "Artist", tracks = lfmTracks, provider = "lastfm")
        coEvery { discogs.getAlbumTracks("Album", "Artist") } returns null

        val result = service.getAlbumTracks("Album", "Artist")
        assertNotNull(result)
        assertEquals(3, result?.tracks?.size) // base = musicbrainz (3 tracks)
        // Track 1 should be enriched with artwork from lastfm
        assertEquals("http://img", result?.tracks?.first()?.artworkUrl)
    }
}
