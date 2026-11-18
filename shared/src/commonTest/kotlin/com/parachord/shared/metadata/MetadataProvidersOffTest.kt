package com.parachord.shared.metadata

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spotify and Apple Music are asked for metadata only while their plug-ins are on (#701). */
class MetadataProvidersOffTest {
    @Test fun `an empty active list leaves every service on`() {
        assertEquals(setOf("discogs"), metadataProvidersOff(setOf("discogs"), emptyList(), emptySet()))
    }

    @Test fun `a service missing from the active list is off`() {
        assertEquals(
            setOf("spotify"),
            metadataProvidersOff(emptySet(), listOf("applemusic", "bandcamp", "localfiles"), emptySet()),
        )
        assertEquals(
            setOf("spotify", "applemusic"),
            metadataProvidersOff(emptySet(), listOf("bandcamp", "localfiles"), emptySet()),
        )
    }

    @Test fun `a disabled plug-in is off even with an empty active list`() {
        assertEquals(setOf("spotify"), metadataProvidersOff(emptySet(), emptyList(), setOf("spotify")))
    }

    @Test fun `services that are on stay on`() {
        assertEquals(
            emptySet(),
            metadataProvidersOff(emptySet(), listOf("spotify", "applemusic"), setOf("bandcamp")),
        )
    }

    // ── Through MetadataService ─────────────────────────────────────────

    private class CountingProvider(override val name: String, override val priority: Int) : MetadataProvider {
        val calls = mutableListOf<String>()
        override suspend fun isAvailable() = true // a saved sign-in
        override suspend fun searchTracks(query: String, limit: Int): List<TrackSearchResult> { calls += "tracks"; return emptyList() }
        override suspend fun searchAlbums(query: String, limit: Int): List<AlbumSearchResult> { calls += "albums"; return emptyList() }
        override suspend fun searchArtists(query: String, limit: Int): List<ArtistInfo> { calls += "artists"; return emptyList() }
        override suspend fun getArtistInfo(artistName: String): ArtistInfo? { calls += "info"; return null }
        override suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? = null
        override suspend fun getArtistAlbums(artistName: String, limit: Int) = emptyList<AlbumSearchResult>()
    }

    private suspend fun ask(active: List<String>, disabledPlugins: Set<String> = emptySet()): CountingProvider {
        val spotify = CountingProvider("spotify", 20)
        val service = MetadataService(
            providers = listOf(CountingProvider("musicbrainz", 0), spotify),
            getDisabledProviders = { metadataProvidersOff(emptySet(), active, disabledPlugins) },
        )
        service.searchArtists("Built to Spill") // MusicBrainz only, by design
        service.searchTracks("Carry the Zero")
        service.searchAlbums("Keep It Like a Secret")
        service.getArtistImage("Built to Spill")
        return spotify
    }

    @Test fun `Spotify turned off is never asked — signed in or not`() = runTest {
        assertTrue(ask(active = listOf("applemusic", "localfiles")).calls.isEmpty())
        assertTrue(ask(active = emptyList(), disabledPlugins = setOf("spotify")).calls.isEmpty())
    }

    @Test fun `Spotify turned on is asked as before`() = runTest {
        val calls = ask(active = listOf("spotify", "localfiles")).calls
        assertTrue(listOf("tracks", "albums", "info").all { it in calls }, "asked: $calls")
        assertTrue(ask(active = emptyList()).calls.isNotEmpty())
    }
}
