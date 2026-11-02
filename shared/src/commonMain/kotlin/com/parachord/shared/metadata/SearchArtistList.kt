package com.parachord.shared.metadata

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Search's Artists list, built the way desktop's is (`publishArtists` and
 * `catalogArtistsFromResults`, search-catalog.js):
 *
 * - MusicBrainz's artist search ([MetadataService.searchArtistsProgressively]
 *   asks only MusicBrainz). There's no artist search on the services: Last.fm's
 *   returned pages scrobblers made from bad tags ("Mazzy Star - Topic",
 *   "Pörtishead"), and Spotify's isn't one desktop has.
 * - Plus the artists the services' track and album rows name ([namedByResults]).
 * - The two ranked together ([SearchRanking.rankArtists]).
 *
 * The three categories answer separately, so the screen hands each one in as it
 * arrives and shows what comes back. Rows without a photo get one from the
 * artist-photo chain.
 */
class SearchArtistList(private val query: String) {
    private val lock = Mutex()
    private var musicBrainz: List<ArtistInfo> = emptyList()
    private var tracks: List<TrackSearchResult> = emptyList()
    private var albums: List<AlbumSearchResult> = emptyList()

    suspend fun withMusicBrainz(artists: List<ArtistInfo>): List<ArtistInfo> = lock.withLock {
        musicBrainz = artists
        current()
    }

    suspend fun withTracks(rows: List<TrackSearchResult>): List<ArtistInfo> = lock.withLock {
        tracks = rows
        current()
    }

    suspend fun withAlbums(rows: List<AlbumSearchResult>): List<ArtistInfo> = lock.withLock {
        albums = rows
        current()
    }

    private fun current(): List<ArtistInfo> =
        SearchRanking.rankArtists(query, musicBrainz + namedByResults(query, tracks, albums, musicBrainz))

    companion object {
        /** Metadata providers whose rows aren't a service's catalog (desktop searches only the services). */
        private val NOT_SERVICES = setOf("musicbrainz", "lastfm", "wikipedia", "discogs")

        private fun fromService(provider: String): Boolean =
            provider.split('+').any { it.isNotBlank() && it !in NOT_SERVICES }

        /**
         * Artists named by the services' track rows, then album rows, whose name
         * matches the query (one contains the other, after
         * [AlbumCatalog.normalizeArtistName]), in the order the rows name them,
         * minus artists MusicBrainz returned. Each scores by its place
         * ([SearchRanking.rankScore]). Desktop `catalogArtistsFromResults`.
         */
        fun namedByResults(
            query: String,
            tracks: List<TrackSearchResult>,
            albums: List<AlbumSearchResult>,
            musicBrainz: List<ArtistInfo>,
        ): List<ArtistInfo> {
            val q = AlbumCatalog.normalizeArtistName(query)
            if (q.isEmpty()) return emptyList()
            val seen = musicBrainz.map { AlbumCatalog.normalizeArtistName(it.name) }.filter { it.isNotEmpty() }.toMutableSet()
            val out = mutableListOf<ArtistInfo>()
            fun consider(name: String, provider: String) {
                val key = AlbumCatalog.normalizeArtistName(name)
                if (key.isEmpty() || key in seen) return
                if (!key.contains(q) && !q.contains(key)) return
                seen += key
                out += ArtistInfo(name = name, provider = provider, score = SearchRanking.rankScore(out.size, 0))
            }
            tracks.filter { fromService(it.provider) }.forEach { consider(it.artist, it.provider) }
            albums.filter { fromService(it.provider) }.forEach { consider(it.artist, it.provider) }
            return out
        }
    }
}
