package com.parachord.shared.metadata

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/**
 * iOS implementation of the 2×2 album-art mosaic composite step that
 * `ImageEnrichmentService.enrichPlaylistArt` delegates to via the
 * `composeMosaic` suspend lambda.
 *
 * Mirrors Android's `composeMosaicAndroid` byte-for-byte:
 *   - 600×600 output, 4 tiles at 300×300 each (top-left, top-right,
 *     bottom-left, bottom-right)
 *   - JPEG 85% quality
 *   - Persisted at `<NSApplicationSupportDirectory>/playlist_mosaics/<id>.jpg`
 *   - Returns the resulting `file://` URL the UI can render through
 *     SwiftUI `AsyncImage`, or null if the composite failed
 *
 * Pad/dedup rules also mirror Android: an empty download set or a
 * single image returns null (can't make a meaningful 2×2 from one
 * tile); 2 or 3 successful downloads cycle to fill the remaining
 * slots so the user gets *some* visual difference rather than four
 * identical tiles.
 *
 * Image fetching goes through the same Ktor client that other shared
 * HTTP traffic uses — no need for a separate iOS-flavoured Coil/
 * Kingfisher dep on the path to a one-shot composite.
 */
@OptIn(ExperimentalForeignApi::class)
class IosMosaicComposer(private val httpClient: HttpClient) {

    companion object {
        private const val MOSAIC_SIZE = 600.0
        private const val JPEG_QUALITY = 0.85
    }

    /** `<Application Support>/playlist_mosaics` in TODAY's data container.
     *  Resolved on every call, never cached: iOS can move the container
     *  across launches and updates, and a stored absolute path then points at
     *  the old one ([PlaylistMosaicPaths]). */
    fun mosaicDir(): String? {
        val supportDirUrls = NSFileManager.defaultManager.URLsForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomains = NSUserDomainMask,
        )
        val supportPath = (supportDirUrls.firstOrNull() as? NSURL)?.path ?: return null
        return "$supportPath/${PlaylistMosaicPaths.DIR_NAME}"
    }

    fun fileExists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    suspend fun compose(playlistId: String, urls: List<String>): String? {
        val tileSize = MOSAIC_SIZE / 2.0

        // Download each URL → UIImage. Failed/decode-failed slots are
        // dropped silently; we'll pad/cycle from the survivors below.
        val downloaded: List<UIImage> = urls.mapNotNull { url ->
            try {
                val bytes = httpClient.get(url).readBytes()
                val nsData = bytes.toNSData() ?: return@mapNotNull null
                UIImage.imageWithData(nsData)
            } catch (_: Exception) {
                null
            }
        }

        // Match Android's gate: empty or single-survivor → no mosaic.
        if (downloaded.isEmpty() || downloaded.size == 1) return null

        val tiles: List<UIImage> = when {
            downloaded.size >= 4 -> downloaded.take(4)
            else -> {
                // Pad by cycling the survivor list so 2-survivor input
                // gives a visually-alternating mosaic (A,B,A,B), 3-survivor
                // input gives (A,B,C,A), etc. Matches Android exactly.
                val padded = downloaded.toMutableList()
                while (padded.size < 4) padded.add(downloaded[padded.size % downloaded.size])
                padded
            }
        }

        // Composite via UIGraphicsImageRenderer (modern Core Graphics path,
        // automatic display-scale handling, no manual UIGraphics begin/end
        // image-context dance).
        //
        // Pin `scale = 1.0` instead of the default `mainScreen.scale` so
        // the rendered bitmap matches the documented 600×600 contract
        // byte-for-byte regardless of the device's `nativeScale`. Without
        // this on a 2× iPad you'd get a 1200×1200 file — sharper on
        // Retina but inconsistent with Android's 600×600 and 4× the
        // disk-cache footprint.
        val format = UIGraphicsImageRendererFormat().apply { scale = 1.0 }
        val renderer = UIGraphicsImageRenderer(
            size = CGSizeMake(MOSAIC_SIZE, MOSAIC_SIZE),
            format = format,
        )
        val positions = listOf(
            0.0 to 0.0,           // top-left
            tileSize to 0.0,      // top-right
            0.0 to tileSize,      // bottom-left
            tileSize to tileSize, // bottom-right
        )
        val mosaicImage: UIImage = renderer.imageWithActions { _ ->
            tiles.forEachIndexed { i, tile ->
                val (x, y) = positions[i]
                tile.drawInRect(CGRectMake(x, y, tileSize, tileSize))
            }
        }

        // Encode to JPEG. KMP's UIKit bindings don't expose the modern
        // `UIImage.jpegData(compressionQuality:)` instance method, but the
        // global C function `UIImageJPEGRepresentation` it wraps is bound
        // and returns the same `NSData?`.
        val jpegData: NSData = UIImageJPEGRepresentation(mosaicImage, JPEG_QUALITY)
            ?: return null

        // Resolve <App Support>/playlist_mosaics/, mkdir -p equivalent,
        // then atomically write.
        val mosaicDir = mosaicDir() ?: return null
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = mosaicDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val filePath = "$mosaicDir/$playlistId.jpg"
        val saved = jpegData.writeToFile(filePath, atomically = true)
        return if (saved) "file://$filePath" else null
    }

    /**
     * `ByteArray` → `NSData` without a malloc'd copy. Pins the array
     * for the duration of the `create(bytes:length:)` call which
     * internally copies into a fresh CFData/NSData.
     */
    private fun ByteArray.toNSData(): NSData? = if (this.isEmpty()) {
        null
    } else {
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = this.size.toULong())
        }
    }
}
