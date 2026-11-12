package com.parachord.shared.metadata

import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.MusicBrainzClient
import com.parachord.shared.api.SpotifyClient
import com.parachord.shared.settings.SettingsStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The metadata cascade's providers, built once for both apps (#595). Each
 * platform used to list them itself.
 *
 * Priority order (lower first, later ones fill gaps): MusicBrainz 0,
 * Wikipedia 5, Last.fm 10, Discogs 15, Spotify 20, then the Apple Music
 * catalog's artist art (25), which needs the developer token.
 *
 * @param knownArtistMbid an artist's MBID without asking MusicBrainz, for
 *   Wikipedia (#429).
 */
class MetadataProviders(
    musicBrainzClient: MusicBrainzClient,
    httpClient: HttpClient,
    lastFmClient: LastFmClient,
    lastFmApiKey: String,
    spotifyClient: SpotifyClient,
    settingsStore: SettingsStore,
    appleMusicDeveloperToken: String,
    knownArtistMbid: suspend (String) -> String?,
) {
    val musicBrainz = MusicBrainzProvider(musicBrainzClient)
    val wikipedia = WikipediaProvider(musicBrainzClient, musicBrainz, httpClient, knownArtistMbid = knownArtistMbid)
    val lastFm = LastFmProvider(lastFmClient, lastFmApiKey)
    val discogs = DiscogsProvider(httpClient, settingsStore)
    val spotify = SpotifyProvider(spotifyClient, settingsStore)
    val appleMusicArtist = AppleMusicArtistProvider(httpClient, appleMusicDeveloperToken)

    /** Every provider, in priority order. */
    fun all(): List<MetadataProvider> =
        listOf(musicBrainz, wikipedia, lastFm, discogs, spotify, appleMusicArtist).sortedBy { it.priority }

    /**
     * Every provider but Spotify, for background enrichment: it fires one
     * search per album, and a burst on a cold launch re-trips the shared
     * Spotify key's rate limit. Spotify only fills gaps in art anyway.
     */
    fun withoutSpotify(): List<MetadataProvider> = all().filter { it !== spotify }
}

/**
 * Swaps a Cover Art Archive cover (which often 404s) for the iTunes album
 * art (600x600) when the iTunes Search finds the album by an artist whose
 * name contains [artistName]. A failed lookup keeps the album as it was.
 * The cascade's `enrichAlbumArtwork` step on both apps (#595).
 */
suspend fun upgradeCoverArtArchiveArtwork(
    appleMusicClient: AppleMusicClient,
    artistName: String,
    albums: List<AlbumSearchResult>,
): List<AlbumSearchResult> = coroutineScope {
    albums.map { album ->
        async {
            val url = album.artworkUrl
            if (url == null || !url.contains("coverartarchive.org")) return@async album
            try {
                val response = appleMusicClient.search(term = "$artistName ${album.title}", entity = "album", limit = 3)
                val match = response.results.firstOrNull { item ->
                    item.collectionName != null &&
                        item.artistName?.lowercase()?.contains(artistName.lowercase()) == true
                }
                val itunesArt = match?.artworkUrl100?.replace("100x100", "600x600")
                if (itunesArt != null) album.copy(artworkUrl = itunesArt) else album
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                album
            }
        }
    }.awaitAll()
}
