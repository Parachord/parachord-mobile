package com.parachord.shared.metadata

import kotlinx.serialization.Serializable

@Serializable
data class SimilarArtist(
    val name: String,
    val imageUrl: String? = null,
)

@Serializable
data class ArtistInfo(
    val name: String,
    val mbid: String? = null,
    val imageUrl: String? = null,
    val bio: String? = null,
    val bioSource: String? = null,
    val bioUrl: String? = null,
    val tags: List<String> = emptyList(),
    val similarArtists: List<SimilarArtist> = emptyList(),
    val provider: String = "",
    /**
     * Search relevance from the source, 0–100 (desktop's `score`): MusicBrainz's
     * own for its rows, rank in the list for the other providers'. Null outside
     * search, or when the source gave none; [SearchRanking] reads null as 50.
     */
    val score: Int? = null,
)

@Serializable
data class TrackSearchResult(
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Long? = null,
    val artworkUrl: String? = null,
    val previewUrl: String? = null,
    val spotifyId: String? = null,
    val mbid: String? = null,
    val provider: String = "",
    /**
     * Search relevance from the source, 0–100 (desktop's `score`): MusicBrainz's
     * own for its rows, rank in the list for the other providers'. Null outside
     * search, or when the source gave none; [SearchRanking] reads null as 50.
     */
    val score: Int? = null,
    /** The first credited artist's MusicBrainz id: what its search popularity is looked up by ([SearchPopularity]). */
    val artistMbid: String? = null,
    /**
     * Popularity, 0–100, when a source has it: Spotify's own for its rows, the
     * artist's ListenBrainz listens for MusicBrainz's ([SearchPopularity]).
     * [SearchRanking] blends it in; null outside search or when unknown.
     */
    val popularity: Double? = null,
)

@Serializable
data class AlbumSearchResult(
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val year: Int? = null,
    val trackCount: Int? = null,
    val mbid: String? = null,
    val spotifyId: String? = null,
    val releaseType: String? = null,
    /** MusicBrainz secondary types (e.g. "Live", "Compilation", "Soundtrack").
     *  Lets the UI split Live releases into their own discography filter instead
     *  of lumping them under Studio Albums. */
    val secondaryTypes: List<String> = emptyList(),
    val provider: String = "",
    /**
     * Search relevance from the source, 0–100 (desktop's `score`): MusicBrainz's
     * own for its rows, rank in the list for the other providers'. Null outside
     * search, or when the source gave none; [SearchRanking] reads null as 50.
     */
    val score: Int? = null,
    /** MusicBrainz release-group id ([mbid] is the release): what its search popularity is looked up by. */
    val releaseGroupMbid: String? = null,
    /**
     * Popularity, 0–100: the release group's ListenBrainz listens, for
     * MusicBrainz's rows ([SearchPopularity]). Null outside search or when unknown.
     */
    val popularity: Double? = null,
)

@Serializable
data class AlbumDetail(
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val year: Int? = null,
    val releaseType: String? = null,
    val tracks: List<TrackSearchResult> = emptyList(),
    val provider: String = "",
    /**
     * Set when the track list came from a plugin's catalog (`albumSearch`)
     * because MusicBrainz and the other providers couldn't supply one: the
     * catalog's display name, for the page's "Track list from …" label.
     * Null for a normal provider answer. See [AlbumCatalog].
     */
    val catalogSource: String? = null,
)
