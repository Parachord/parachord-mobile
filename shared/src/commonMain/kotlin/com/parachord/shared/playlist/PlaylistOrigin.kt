package com.parachord.shared.playlist

import com.parachord.shared.playback.resolverDisplayName
import com.parachord.shared.sync.impliedPlaylistProvider
import com.parachord.shared.sync.linkImportOrigin

/**
 * Where a playlist came from and where it syncs, for its page (desktop's
 * playlist page: "Source: X", then a badge per service it syncs to).
 *
 * The source is the service it's pulled from (its sync source, else the
 * provider its id names), a service it was imported from by link, "A playlist
 * link" for a hosted XSPF, or "An imported file"; a playlist made in Parachord
 * has none. The badges are the services it pushes to: every mirror but the
 * one it's pulled from, so a pulled playlist doesn't name its source twice.
 * Desktop shows no badges at all on a pulled playlist; mobile shows its push
 * mirrors.
 */
object PlaylistOrigin {
    const val HOSTED_LABEL = "A playlist link"
    const val FILE_LABEL = "An imported file"

    /** The provider a playlist is pulled from: its sync source, else the one its id names. */
    fun pullProvider(playlistId: String, syncSourceProvider: String?): String? =
        syncSourceProvider ?: impliedPlaylistProvider(playlistId)

    /** What the source line names, or null for a playlist made in Parachord. */
    fun label(playlistId: String, sourceUrl: String?, pullProvider: String?): String? {
        pullProvider?.let { return resolverDisplayName(it) }
        linkImportOrigin(playlistId)?.let { return resolverDisplayName(it) }
        if (sourceUrl != null || playlistId.startsWith("hosted-xspf-")) return HOSTED_LABEL
        if (playlistId.startsWith("imported-xspf-")) return FILE_LABEL
        return null
    }

    /** The line under the playlist's title. */
    fun sourceLine(label: String): String = "Source: $label"

    /** The services it pushes to: [mirrors] (provider -> remote id) without [pullProvider]. */
    fun pushMirrors(mirrors: Map<String, String>, pullProvider: String?): Map<String, String> =
        if (pullProvider == null) mirrors else mirrors - pullProvider
}
