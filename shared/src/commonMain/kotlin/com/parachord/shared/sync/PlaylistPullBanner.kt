package com.parachord.shared.sync

/**
 * The playlist page's "Updated on <service>" banner and its Update action.
 *
 * Only a playlist's pull source may replace its tracks, as on desktop, where
 * the sync banner exists only for a playlist with a `syncedFrom` (the service
 * it was imported from). Android offered the banner for any playlist with a
 * Spotify copy, so a ListenBrainz playlist mirrored to Spotify got "Updated on
 * Spotify" when its mirror changed, and Update replaced its 84 tracks with the
 * mirror's, which was empty (2026-11-22). The emptied playlist was then flagged
 * as edited, ready to be pushed to ListenBrainz on the next sync.
 */
object PlaylistPullBanner {

    /**
     * Whether the page may offer a pull from [providerId]: only when that
     * service is the playlist's pull source. [sourceProviderId] is the
     * playlist's `sync_playlist_source` row; with none, the provider its id
     * names ([impliedPlaylistProvider]).
     */
    fun offersPull(sourceProviderId: String?, playlistId: String, providerId: String): Boolean =
        (sourceProviderId ?: impliedPlaylistProvider(playlistId)) == providerId

    /**
     * Whether a pull may replace the playlist's tracks: only when it brought
     * some back, or the playlist is already empty. An empty answer for a
     * playlist that has tracks is far more often a failed or partial read than
     * a playlist emptied on purpose, and replacing on it loses everything.
     */
    fun acceptsPulledTracks(localCount: Int, remoteCount: Int): Boolean =
        remoteCount > 0 || localCount == 0

    /** Shown when an Update refused an empty answer. */
    fun emptyPullMessage(serviceName: String): String =
        "$serviceName returned no tracks for this playlist, so nothing was changed."
}
