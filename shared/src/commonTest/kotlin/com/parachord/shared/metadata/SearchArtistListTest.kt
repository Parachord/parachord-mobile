package com.parachord.shared.metadata

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Search's Artists list, built like desktop's (publishArtists / catalogArtistsFromResults). */
class SearchArtistListTest {

    private fun track(artist: String, provider: String) = TrackSearchResult("Song", artist, provider = provider)
    private fun album(artist: String, provider: String) = AlbumSearchResult("Album", artist, provider = provider)

    @Test
    fun theServicesRowsNameArtistsThatMatchTheQuery() {
        val named = SearchArtistList.namedByResults(
            "portishead",
            tracks = listOf(
                track("Portishead", "spotify"),
                track("Some Covers Band", "spotify"), // doesn't match the query
                track("The Portishead Experience", "spotify"),
            ),
            albums = listOf(album("Portishead", "applemusic"), album("Portishead Tribute", "bandcamp")),
            musicBrainz = emptyList(),
        )
        assertEquals(listOf("Portishead", "The Portishead Experience", "Portishead Tribute"), named.map { it.name })
        assertEquals(listOf(100, 95, 90), named.map { it.score })
    }

    @Test
    fun musicBrainzAndLastFmRowsDontNameArtists_andMusicBrainzsArtistsArentRepeated() {
        val named = SearchArtistList.namedByResults(
            "mazzy star",
            tracks = listOf(
                track("Mazzy Star - Topic", "lastfm"), // a scrobbler-made page
                track("Mazzy Star Tribute", "musicbrainz"),
                track("Mazzy Star", "musicbrainz+spotify"), // merged: Spotify named it too
                track("Mazzy Star Tribute Band", "spotify"),
            ),
            albums = emptyList(),
            musicBrainz = listOf(ArtistInfo("Mazzy Star", provider = "musicbrainz")),
        )
        assertEquals(listOf("Mazzy Star Tribute Band"), named.map { it.name })
    }

    @Test
    fun accentsAndALeadingTheDontMatter() {
        val named = SearchArtistList.namedByResults(
            "the beatles",
            tracks = listOf(track("Beatles", "spotify"), track("Beyoncé", "spotify")),
            albums = emptyList(),
            musicBrainz = emptyList(),
        )
        assertEquals(listOf("Beatles"), named.map { it.name })
    }

    @Test
    fun theListCombinesEachCategoryAsItArrives() = runTest {
        val list = SearchArtistList("portishead")
        assertEquals(listOf("Portishead"), list.withTracks(listOf(track("Portishead", "spotify"))).map { it.name })
        val withMb = list.withMusicBrainz(listOf(ArtistInfo("Portishead", provider = "musicbrainz", score = 100, mbid = "x")))
        // MusicBrainz's row replaces the named one rather than repeating it.
        assertEquals(listOf("musicbrainz"), withMb.map { it.provider })
        val withAlbums = list.withAlbums(listOf(album("Portishead Tribute", "applemusic")))
        assertEquals(listOf("Portishead", "Portishead Tribute"), withAlbums.map { it.name })
    }

    private class Provider(override val name: String, val artists: List<String>) : MetadataProvider {
        var asked = 0
        override val priority = 0
        override suspend fun isAvailable() = true
        override suspend fun searchTracks(query: String, limit: Int) = emptyList<TrackSearchResult>()
        override suspend fun searchAlbums(query: String, limit: Int) = emptyList<AlbumSearchResult>()
        override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> {
            asked++
            return artists.map { ArtistInfo(it, provider = name) }
        }
        override suspend fun getArtistInfo(artistName: String): ArtistInfo? = null
        override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? = null
    }

    @Test
    fun searchAsksOnlyMusicBrainzForArtists() = runTest {
        val mb = Provider("musicbrainz", listOf("Portishead"))
        val lastFm = Provider("lastfm", listOf("Pörtishead"))
        val spotify = Provider("spotify", listOf("Portishead"))
        val artists = MetadataService(listOf(mb, lastFm, spotify), getDisabledProviders = { emptySet() })
            .searchArtistsProgressively("portishead").last()
        assertEquals(listOf("Portishead"), artists.map { it.name })
        assertEquals(listOf(1, 0, 0), listOf(mb.asked, lastFm.asked, spotify.asked))
    }
}
