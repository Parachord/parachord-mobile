package com.parachord.shared.ai

import com.parachord.shared.metadata.ImageEnrichmentService
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.metadata.TrackSearchResult
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.resolver.selectBestMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The cover on each chat card, following desktop's `ChatCard` (`app.js`
 * ~L17257) in its sources and order:
 * - **artist:** the artist-photo chain ([MetadataService.getArtistImage],
 *   desktop's `getArtistImage`);
 * - **album:** the album-art pipeline ([ImageEnrichmentService.lookupAlbumArt],
 *   desktop's `getAlbumArt`);
 * - **track:** the album-art pipeline for its album. A card with no album
 *   (common when the model answers in prose) first searches for the song to
 *   find its album, as desktop's `searchResolvers` + `pickTrackMatch` does;
 *   with no album found, the matched song's own art (desktop's
 *   `getTrackArt`, the catalog's song search).
 *
 * The search keeps only a real match ([selectBestMatch]), so a card never
 * shows another song's cover. It used to take the first result whatever it
 * was, searched only for the song (whose MusicBrainz rows carry no art) and
 * asked the full artist cascade for a photo, so many cards stayed blank.
 *
 * **Caching:** a cover found is kept for the session. A miss is kept for
 * [MISS_TTL_MS], then asked again: lower layers can turn a network failure
 * into a plain null. A lookup that threw is never kept, so the next showing
 * asks again. It used to keep every miss, failures included, for the session.
 */
class ChatCardEnricher(
    private val albumArt: suspend (albumTitle: String, artist: String) -> String?,
    private val artistImage: suspend (artistName: String) -> String?,
    private val trackSearch: suspend (title: String, artist: String) -> List<TrackSearchResult>,
    private val nowMs: () -> Long = { currentTimeMillis() },
) {
    /** The app's wiring. */
    constructor(
        metadataService: MetadataService,
        imageEnrichmentService: ImageEnrichmentService,
    ) : this(
        albumArt = { album, artist -> imageEnrichmentService.lookupAlbumArt(album, artist) },
        artistImage = { name -> metadataService.getArtistImage(name) },
        trackSearch = { title, artist -> metadataService.searchTracks("$artist $title", limit = 5) },
    )

    companion object {
        /** How long a cover that wasn't found stays not found. */
        const val MISS_TTL_MS = 30L * 60 * 1000
    }

    private class Entry(val url: String?, val at: Long)

    private val cache = mutableMapOf<String, Entry>()
    private val mutex = Mutex()

    /** The cover for a track card; [album] may be blank. */
    suspend fun getTrackArtwork(title: String, artist: String, album: String): String? {
        val key = "track:${artist.lowercase()}|${title.lowercase()}|${album.lowercase()}"
        return getOrFetch(key) {
            if (album.isNotBlank() && artist.isNotBlank()) return@getOrFetch albumArt(album, artist)
            if (title.isBlank() || artist.isBlank()) return@getOrFetch null
            val match = selectBestMatch(
                trackSearch(title, artist),
                targetTitle = title,
                targetArtist = artist,
                titleOf = { it.title },
                artistOf = { it.artist },
            )
            val foundAlbum = match?.album?.takeIf { it.isNotBlank() }
            (foundAlbum?.let { albumArt(it, artist) }) ?: match?.artworkUrl
        }
    }

    /** The cover for an album card. */
    suspend fun getAlbumArtwork(albumTitle: String, artist: String): String? {
        val key = "album:${artist.lowercase()}|${albumTitle.lowercase()}"
        return getOrFetch(key) {
            if (albumTitle.isBlank() || artist.isBlank()) null else albumArt(albumTitle, artist)
        }
    }

    /** The photo for an artist card. */
    suspend fun getArtistImage(artistName: String): String? {
        val key = "artist:${artistName.trim().lowercase()}"
        return getOrFetch(key) { if (artistName.isBlank()) null else artistImage(artistName) }
    }

    private suspend fun getOrFetch(key: String, fetch: suspend () -> String?): String? {
        mutex.withLock {
            val entry = cache[key]
            if (entry != null && (entry.url != null || nowMs() - entry.at < MISS_TTL_MS)) return entry.url
        }
        val result = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null // not cached: the next showing asks again
        }
        mutex.withLock { cache[key] = Entry(result, nowMs()) }
        return result
    }
}
