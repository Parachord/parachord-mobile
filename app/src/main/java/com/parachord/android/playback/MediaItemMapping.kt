package com.parachord.android.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.parachord.android.data.db.entity.TrackEntity

/**
 * Build a `MediaItem` from a [TrackEntity].
 *
 * Includes Android Auto browse-tree flags
 * (`MediaMetadata.MEDIA_TYPE_MUSIC` + `isPlayable=true` + `isBrowsable=false`)
 * required for Auto to render the synthetic-queue rows as tappable tracks.
 * The same `MediaItem` shape is used for ExoPlayer-side playback — the
 * flags are pure metadata and don't affect ExoPlayer's playback pipeline.
 *
 * - `mediaId` is the bare [TrackEntity.id] — matches the existing scheme
 *   that `PlaybackController.playFromQueue()` lookups expect.
 * - `artworkUri` is set only when [TrackEntity.artworkUrl] is non-null;
 *   Auto fetches over HTTPS for remote URIs and accepts `content://` for
 *   local files.
 */
fun TrackEntity.toAutoMediaItem(): MediaItem {
    val builder = MediaMetadata.Builder()
        .setTitle(title)
        .setSubtitle(artist)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setIsPlayable(true)
        .setIsBrowsable(false)
    artworkUrl?.let { builder.setArtworkUri(Uri.parse(it)) }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(sourceUrl ?: "")
        .setMediaMetadata(builder.build())
        .build()
}
