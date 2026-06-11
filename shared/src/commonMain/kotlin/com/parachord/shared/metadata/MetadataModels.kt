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
)
