package com.parachord.shared.search

import com.parachord.shared.metadata.AlbumSearchResult
import com.parachord.shared.metadata.ArtistInfo
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.model.Album
import com.parachord.shared.model.Track

/** One row of a search's results merged into a single list. */
sealed interface MergedSearchItem {
    /** A library track (a local file or a saved track) — plays instantly. */
    data class Song(val track: Track) : MergedSearchItem

    /** A remote track (MusicBrainz and friends) — resolves the moment it plays. */
    data class RemoteSong(val result: TrackSearchResult) : MergedSearchItem

    /** An album in the library. */
    data class LibraryAlbum(val album: Album) : MergedSearchItem

    /** An album found remotely. */
    data class RemoteAlbum(val album: AlbumSearchResult) : MergedSearchItem

    /** An artist. */
    data class Artist(val artist: ArtistInfo) : MergedSearchItem
}

/**
 * A search's results merged into ONE list, the way the AAOS car's search shows
 * them (a car screen is a list of taps, not a page of sections): an album row
 * first (the "play this record" row), then a song, then an artist, round-robin.
 * The same song shows once — the library copy wins (it plays instantly and
 * covers local files) — and a remote album the library also has is left out.
 * Each list is capped so the merged list stays drivable; the caps are counts
 * of each kind, not of the merged list.
 */
fun mergeSearchResults(
    state: SearchState,
    libraryAlbums: Int = 4,
    remoteAlbums: Int = 6,
    artists: Int = 4,
    songs: Int = 14,
): List<MergedSearchItem> {
    val albums = buildList {
        val seenAlbums = mutableSetOf<String>()
        state.localAlbums.take(libraryAlbums).forEach { album ->
            val key = "${album.title.lowercase().trim()}|${album.artist.lowercase().trim()}"
            if (seenAlbums.add(key)) add(MergedSearchItem.LibraryAlbum(album))
        }
        var addedRemote = 0
        for (album in state.remoteAlbums) {
            if (addedRemote >= remoteAlbums) break
            val key = "${album.title.lowercase().trim()}|${album.artist.lowercase().trim()}"
            if (seenAlbums.add(key)) {
                add(MergedSearchItem.RemoteAlbum(album))
                addedRemote++
            }
        }
    }
    val artistRows = state.artists.take(artists).map(MergedSearchItem::Artist)
    val songRows = buildList {
        val seen = mutableSetOf<String>()
        (state.localTracks.map(MergedSearchItem::Song) + state.remoteTracks.map(MergedSearchItem::RemoteSong)).forEach { item ->
            val (title, artist) = when (item) {
                is MergedSearchItem.Song -> item.track.title to item.track.artist
                is MergedSearchItem.RemoteSong -> item.result.title to item.result.artist
                else -> return@forEach
            }
            if (seen.add("${title.lowercase().trim()}|${artist.lowercase().trim()}")) add(item)
        }
    }.take(songs)
    return buildList {
        var i = 0
        while (i < albums.size || i < songRows.size || i < artistRows.size) {
            albums.getOrNull(i)?.let { add(it) }
            songRows.getOrNull(i)?.let { add(it) }
            artistRows.getOrNull(i)?.let { add(it) }
            i++
        }
    }
}
