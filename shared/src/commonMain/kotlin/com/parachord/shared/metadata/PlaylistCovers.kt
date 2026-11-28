package com.parachord.shared.metadata

/**
 * A playlist's own cover against the 2×2 mosaic of its tracks (#852, desktop
 * parachord#1394, `docs/architecture/album-art.md` "Playlist covers"). The
 * cover the provider gave the playlist (a sync service's image, a link's, an
 * XSPF's `<image>`) is the playlist's identity and wins; the mosaic is the
 * fallback, built when there's no cover and shown when one fails to load.
 *
 * One column holds either, so what `Playlist.artworkUrl` is decides:
 * - blank: build the mosaic;
 * - a local file: the mosaic;
 * - a service's stock art ([isStockCover]): no cover of its own, so the mosaic;
 * - one of the playlist's track covers: a stand-in set before this rule (a
 *   one-cover "mosaic", a first track's cover at import), so the mosaic;
 * - anything else: the playlist's own cover ([isOwnCover]).
 */
object PlaylistCovers {

    /**
     * Art a service gives a playlist that has no cover of its own: Spotify's
     * generated 2×2 (`mosaic.scdn.co`, the same tracks as ours, which follows
     * local edits) and Apple Music's stock image (`AM.PDCXS…`). Desktop leaves
     * Spotify's out at sync for the same reason.
     */
    fun isStockCover(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        return "mosaic.scdn.co" in u || "/am.pdcxs" in u
    }

    /** Whether [art] is the playlist's own cover, not the mosaic, stock art or a [trackCovers] stand-in. */
    fun isOwnCover(art: String?, trackCovers: Collection<String?> = emptyList()): Boolean =
        !art.isNullOrBlank() && !PlaylistMosaicPaths.isLocalFile(art) && !isStockCover(art) && art !in trackCovers

    /**
     * The art a pull from the playlist's source leaves on it. The source's own
     * cover replaces whatever is there (a mosaic built before the cover was
     * kept included). Stock art says it has none, so the mosaic comes back:
     * an existing mosaic stays, anything else goes and the mosaic is built.
     * No art at all says nothing (a service that sends no covers), so the
     * playlist keeps its art.
     */
    fun coverAfterPull(existing: String?, remote: String?): String? = when {
        remote.isNullOrBlank() -> existing
        isStockCover(remote) -> existing?.takeIf { PlaylistMosaicPaths.isLocalFile(it) }
        else -> remote
    }

    /**
     * The art a hosted XSPF's update leaves on its playlist: the document's
     * `<image>`, else the playlist's own cover it already had (a document
     * without one keeps it, as desktop's re-import does), else null, and the
     * mosaic is rebuilt from the new tracks.
     */
    fun coverAfterHostedUpdate(existing: String?, image: String?, trackCovers: Collection<String?>): String? =
        image ?: existing.takeIf { isOwnCover(it, trackCovers) }

    /** The file name the mosaic composer writes for [playlistId] (`playlist_mosaics/<id>.jpg`). */
    fun mosaicFileName(playlistId: String): String = "$playlistId.jpg"
}
