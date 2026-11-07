package com.parachord.shared.metadata

/**
 * Where a stored playlist mosaic URL points, and how to repair one that
 * points at a container that no longer exists.
 *
 * Mosaics are written as absolute `file://` URLs into the app's data
 * container (`<container>/…/playlist_mosaics/<playlistId>.jpg`) and saved in
 * `playlists.artworkUrl`. iOS can move an app's data container across
 * launches, reinstalls and updates: the files move with it, but a stored
 * absolute path keeps the old container's UUID and loads nothing. Android's
 * `filesDir` doesn't move, so there the repair is a no-op.
 *
 * Only the file NAME is meaningful in a stored mosaic URL; the directory is
 * whatever the platform's mosaic directory is today.
 */
object PlaylistMosaicPaths {
    const val DIR_NAME = "playlist_mosaics"
    private const val MARKER = "$DIR_NAME/"

    /** The mosaic file name (`<playlistId>.jpg`) in [stored], without any
     *  cache-bust query, or null when [stored] isn't a mosaic URL. */
    fun fileName(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        val idx = stored.indexOf(MARKER)
        if (idx < 0) return null
        return stored.substring(idx + MARKER.length).substringBefore('?').ifBlank { null }
    }

    /**
     * The covers a playlist's mosaic is built from: the first four distinct
     * track covers, in playlist order. The same rule decides what a mosaic
     * SHOULD show, so a stored mosaic can be checked against its tracks
     * without the network.
     */
    fun covers(trackArtworkUrls: List<String?>): List<String> =
        trackArtworkUrls.filterNotNull().filter { it.isNotBlank() }.distinct().take(4)

    /**
     * A short fingerprint of [covers], stored as the mosaic URL's `v` query.
     * When a playlist's covers change, the fingerprint no longer matches and
     * the mosaic is rebuilt; the new URL also makes image caches reload.
     * FNV-1a 64-bit, so it's identical on every platform.
     */
    fun coverSignature(covers: List<String>): String {
        var h = 0xcbf29ce484222325uL
        for (b in covers.joinToString("\n").encodeToByteArray()) {
            h = (h xor (b.toUByte().toULong())) * 0x100000001b3uL
        }
        return h.toString(16).padStart(16, '0')
    }

    /** The `v` query value of [url], or null. */
    fun version(url: String): String? =
        url.substringAfter('?', missingDelimiterValue = "").split('&')
            .firstOrNull { it.startsWith("v=") }?.removePrefix("v=")?.ifBlank { null }

    /** Whether [url] is a local file, in either form: iOS writes `file:///…`,
     *  Android's `File.toURI()` writes `file:/…` (one slash). */
    fun isLocalFile(url: String?): Boolean = url != null && url.startsWith("file:")

    /** The filesystem path of a local file URL (query dropped), or null for
     *  any other scheme. */
    fun filePath(url: String): String? {
        if (!isLocalFile(url)) return null
        val rest = url.removePrefix("file:")
        val path = if (rest.startsWith("//")) rest.removePrefix("//") else rest
        return path.substringBefore('?').ifBlank { null }
    }

    /**
     * [stored] moved onto [currentDir] (the platform's mosaic directory now),
     * keeping its cache-bust query. Null when [stored] isn't a mosaic URL.
     */
    fun rerooted(stored: String, currentDir: String): String? {
        val name = fileName(stored) ?: return null
        val query = stored.substringAfter('?', missingDelimiterValue = "")
        val base = "file://${currentDir.trimEnd('/')}/$name"
        return if (query.isEmpty()) base else "$base?$query"
    }

    /**
     * The URL to read [stored] from: [stored] itself while its file exists,
     * else the same mosaic in [currentDir] when that file exists, else
     * [stored] unchanged (the caller shows its placeholder; the launch repair
     * regenerates it). Non-`file://` URLs pass through.
     */
    fun resolveForRead(stored: String, currentDir: String?, exists: (path: String) -> Boolean): String =
        when (val r = repair(stored, currentDir, exists)) {
            is Repair.Rewrite -> r.url
            else -> stored
        }

    /** What the launch repair should do with a playlist's stored art. */
    sealed class Repair {
        /** Not a local file, or its file is there. */
        data object Keep : Repair()
        /** The file moved with the container: store [url] instead. */
        data class Rewrite(val url: String) : Repair()
        /** The file is gone: build the mosaic again. */
        data object Regenerate : Repair()
    }

    fun repair(stored: String, currentDir: String?, exists: (path: String) -> Boolean): Repair {
        val path = filePath(stored) ?: return Repair.Keep
        if (exists(path)) return Repair.Keep
        if (currentDir != null) {
            val moved = rerooted(stored, currentDir)
            if (moved != null && moved != stored) {
                val movedPath = filePath(moved)
                if (movedPath != null && exists(movedPath)) return Repair.Rewrite(moved)
            }
        }
        return Repair.Regenerate
    }
}
