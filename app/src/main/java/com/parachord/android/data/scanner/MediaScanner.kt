package com.parachord.android.data.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.shared.resolver.validateIsrc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class ScanProgress(
    val isScanning: Boolean = false,
    val tracksFound: Int = 0,
    val albumsFound: Int = 0,
)

class MediaScanner constructor(
    private val context: Context,
    private val repository: LibraryRepository,
) {
    private companion object {
        // ISRC tag reads (#238): MetadataRetriever opens an extractor per file, so
        // bound concurrency for large libraries; per-file timeout so a slow/broken
        // file can't stall the whole scan.
        const val ISRC_READ_CONCURRENCY = 4
        const val ISRC_READ_TIMEOUT_MS = 3000L
    }
    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    /**
     * Scans the device's MediaStore for audio files and inserts them into Room.
     * Returns the number of tracks found.
     */
    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        _progress.value = ScanProgress(isScanning = true)

        val tracks = mutableListOf<TrackEntity>()
        val albumMap = mutableMapOf<String, AlbumEntity>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "Unknown"
                val artist = cursor.getString(artistCol) ?: "Unknown"
                val album = cursor.getString(albumCol)
                val albumId = cursor.getLong(albumIdCol)
                val duration = cursor.getLong(durationCol)
                val filePath = cursor.getString(dataCol)

                val contentUri = ContentUris.withAppendedId(collection, mediaId)

                // Build album artwork URI — only use it if the content actually exists
                val artworkUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId,
                )
                val validatedArtworkUrl = validateContentUri(artworkUri)

                val albumIdStr = "local-album-$albumId"

                tracks.add(
                    TrackEntity(
                        id = "local-$mediaId",
                        title = title,
                        artist = artist,
                        album = album,
                        albumId = albumIdStr,
                        duration = duration,
                        artworkUrl = validatedArtworkUrl,
                        sourceType = "local",
                        sourceUrl = contentUri.toString(),
                        resolver = "localfiles",
                    )
                )

                if (album != null && albumIdStr !in albumMap) {
                    albumMap[albumIdStr] = AlbumEntity(
                        id = albumIdStr,
                        title = album,
                        artist = artist,
                        artworkUrl = validatedArtworkUrl,
                    )
                }

                _progress.value = ScanProgress(
                    isScanning = true,
                    tracksFound = tracks.size,
                    albumsFound = albumMap.size,
                )
            }
        }

        // Capture each file's ISRC (ID3 TSRC) via Media3's metadata extractor —
        // no new dependency (media3-exoplayer is already on the classpath for
        // playback). #238 / desktop parachord#895. MediaStore exposes no ISRC, so
        // this reads the tag per file. Bounded concurrency: MetadataRetriever
        // spins up an extractor per file, so a large library must not fan out
        // unbounded; each read has a timeout so an unreadable file can't stall the
        // scan. validateIsrc drops malformed tags. Files with no TSRC → null.
        val tracksToInsert = if (tracks.isEmpty()) tracks else coroutineScope {
            val gate = Semaphore(ISRC_READ_CONCURRENCY)
            tracks.map { t ->
                async {
                    val isrc = t.sourceUrl?.let { url -> gate.withPermit { readIsrcTag(Uri.parse(url)) } }
                    if (isrc != null) t.copy(isrc = isrc) else t
                }
            }.awaitAll()
        }

        // Batch insert into Room
        if (tracksToInsert.isNotEmpty()) {
            repository.addTracks(tracksToInsert)
        }
        if (albumMap.isNotEmpty()) {
            repository.addAlbums(albumMap.values.toList())
        }

        _progress.value = ScanProgress(
            isScanning = false,
            tracksFound = tracks.size,
            albumsFound = albumMap.size,
        )

        tracks.size
    }

    /**
     * Read the ID3 `TSRC` (ISRC) tag from a local audio file via Media3's
     * metadata extractor. Returns the canonical, [validateIsrc]-normalized ISRC,
     * or null when the file has no TSRC frame / is unreadable / times out.
     * ID3-centric (where TSRC overwhelmingly lives); Vorbis/FLAC/MP4 ISRC would
     * need a broader tag library (see #238). Best-effort — never throws.
     */
    @OptIn(UnstableApi::class)
    private fun readIsrcTag(uri: Uri): String? = try {
        val trackGroups = MetadataRetriever.retrieveMetadata(context, MediaItem.fromUri(uri))
            .get(ISRC_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        var raw: String? = null
        loop@ for (g in 0 until trackGroups.length) {
            val group = trackGroups.get(g)
            for (f in 0 until group.length) {
                val metadata = group.getFormat(f).metadata ?: continue
                for (e in 0 until metadata.length()) {
                    val entry = metadata.get(e)
                    if (entry is TextInformationFrame && entry.id.equals("TSRC", ignoreCase = true)) {
                        raw = entry.values.firstOrNull()
                        break@loop
                    }
                }
            }
        }
        validateIsrc(raw)
    } catch (_: Exception) {
        null
    }

    /**
     * Check if a content:// album art URI actually resolves to image data.
     * Returns the URI string if valid, null if the content is missing/empty.
     */
    private fun validateContentUri(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.read(); uri.toString() }
        } catch (_: Exception) {
            null
        }
    }
}
