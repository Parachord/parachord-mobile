package com.parachord.shared.metadata

/**
 * Interface for metadata providers in the cascading lookup chain.
 *
 * Each provider wraps a single external API (MusicBrainz, Last.fm, Spotify).
 * The MetadataService tries providers in priority order — if one fails or
 * returns incomplete data, the next provider fills in the gaps. This mirrors
 * the cascading provider pattern from the desktop Electron app.
 */
interface MetadataProvider {

    /** Human-readable name of this provider (e.g. "musicbrainz", "lastfm", "spotify"). */
    val name: String

    /** Priority: lower = tried first. MusicBrainz=0, LastFm=10, Spotify=20. */
    val priority: Int

    /** Whether this provider is currently usable (has credentials, etc). */
    suspend fun isAvailable(): Boolean

    /** Search for tracks matching a query string. */
    suspend fun searchTracks(query: String, limit: Int = 20): List<TrackSearchResult>

    /** Search for albums matching a query string. */
    suspend fun searchAlbums(query: String, limit: Int = 10): List<AlbumSearchResult>

    /** Search for artists matching a query string. */
    suspend fun searchArtists(query: String, limit: Int = 10): List<ArtistInfo>

    /** Look up detailed artist information by name. */
    suspend fun getArtistInfo(artistName: String): ArtistInfo?

    /** Get an artist's top tracks (by popularity/play count). */
    suspend fun getArtistTopTracks(artistName: String, limit: Int = 10): List<TrackSearchResult> =
        emptyList()

    /** Get an artist's discography (albums and singles). */
    suspend fun getArtistAlbums(artistName: String, limit: Int = 50): List<AlbumSearchResult> =
        emptyList()

    /** Get the tracklist for a specific album. */
    suspend fun getAlbumTracks(albumTitle: String, artistName: String): AlbumDetail? = null

    /**
     * The caller already knows [artistName]'s MusicBrainz id (#478), e.g. from a
     * MusicBrainz search result. A provider that would search MusicBrainz by
     * name uses it instead: a turn of the 1 req/s queue saved, and no namesake.
     * Ignored by default.
     */
    suspend fun useArtistMbid(artistName: String, mbid: String) {}
}
