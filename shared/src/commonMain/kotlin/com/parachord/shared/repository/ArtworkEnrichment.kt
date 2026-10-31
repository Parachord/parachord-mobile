package com.parachord.shared.repository

import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.HistoryAlbum
import com.parachord.shared.model.HistoryTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The artwork fallback the history and friend lists share: items that came
 * back without a cover get one looked up through [MetadataService], at most
 * [limit] distinct lookups per list. Items sharing a key (the same song
 * listened to twice) share one lookup. Items that already have art, and
 * lookups that find nothing or fail, are left as they are.
 */
internal suspend fun <T> fillMissingArtwork(
    items: List<T>,
    artworkOf: (T) -> String?,
    keyOf: (T) -> String,
    lookup: suspend (T) -> String?,
    withArtwork: (T, String) -> T,
    limit: Int = ARTWORK_LOOKUP_LIMIT,
): List<T> = coroutineScope {
    val needed = items.filter { artworkOf(it) == null }
        .distinctBy(keyOf)
        .take(limit)
    if (needed.isEmpty()) return@coroutineScope items
    val found = needed.map { item ->
        async {
            val url = try {
                lookup(item)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            url?.let { keyOf(item) to it }
        }
    }.awaitAll().filterNotNull().toMap()
    items.map { item ->
        if (artworkOf(item) != null) item
        else found[keyOf(item)]?.let { withArtwork(item, it) } ?: item
    }
}

internal const val ARTWORK_LOOKUP_LIMIT = 15

internal fun artworkKey(artist: String, title: String) = "${artist.lowercase()}|${title.lowercase()}"

internal suspend fun MetadataService.fillTrackArtwork(tracks: List<HistoryTrack>): List<HistoryTrack> =
    fillMissingArtwork(
        tracks, { it.artworkUrl }, { artworkKey(it.artist, it.title) },
        { trackArtwork(it.artist, it.title) }, { t, url -> t.copy(artworkUrl = url) },
    )

internal suspend fun MetadataService.fillAlbumArtwork(albums: List<HistoryAlbum>): List<HistoryAlbum> =
    fillMissingArtwork(
        albums, { it.artworkUrl }, { artworkKey(it.artist, it.name) },
        { albumArtwork(it.artist, it.name) }, { a, url -> a.copy(artworkUrl = url) },
    )

/** Track cover via the metadata cascade's track search. */
internal suspend fun MetadataService.trackArtwork(artist: String, title: String): String? =
    searchTracks("$artist $title", limit = 1).firstOrNull()?.artworkUrl

/** Album cover via the metadata cascade's album search, preferring an exact title + artist match. */
internal suspend fun MetadataService.albumArtwork(artist: String, album: String): String? {
    val results = searchAlbums("$artist $album", limit = 3)
    val match = results.firstOrNull { it.title.equals(album, ignoreCase = true) && it.artist.equals(artist, ignoreCase = true) }
        ?: results.firstOrNull { it.title.equals(album, ignoreCase = true) }
        ?: results.firstOrNull()
    return match?.artworkUrl
}
