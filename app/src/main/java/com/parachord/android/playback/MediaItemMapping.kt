package com.parachord.android.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.parachord.android.data.db.entity.TrackEntity

/**
 * The Car App Library's immersive-audio extra (`androidx.car.app.mediaextensions.
 * MetadataExtras.KEY_IMMERSIVE_AUDIO`, a long set to
 * `METADATA_VALUE_ATTRIBUTE_PRESENT`, 1). We don't ship that library, so the
 * key is spelled here. It tells the car this track is immersive audio, so it
 * should leave out sound effects that would interfere ("Indicate audio format",
 * Android for Cars).
 */
const val KEY_IMMERSIVE_AUDIO = "androidx.car.app.mediaextensions.KEY_IMMERSIVE_AUDIO"
private const val METADATA_VALUE_ATTRIBUTE_PRESENT = 1L

internal fun immersiveAudioExtras(): android.os.Bundle =
    android.os.Bundle().apply { putLong(KEY_IMMERSIVE_AUDIO, METADATA_VALUE_ATTRIBUTE_PRESENT) }

/**
 * Build a `MediaItem` from a [TrackEntity]. [immersive] marks the playing
 * track as immersive audio for the car (a Dolby Atmos mix, [KEY_IMMERSIVE_AUDIO]).
 *
 * Includes Android Auto browse-tree flags
 * (`MediaMetadata.MEDIA_TYPE_MUSIC` + `isPlayable=true` + `isBrowsable=false`)
 * required for Auto to render the synthetic-queue rows as tappable tracks.
 * The same `MediaItem` shape is used for ExoPlayer-side playback — the
 * flags are pure metadata and don't affect ExoPlayer's playback pipeline.
 *
 * - `mediaId` is the bare [TrackEntity.id] — matches the existing scheme
 *   that `PlaybackController.playFromQueue()` lookups expect.
 * - `artworkUri` is set only when [TrackEntity.artworkUrl] is non-blank, mapped
 *   through [artworkUriMapper] (in production, [MediaArtworkProvider.uriFor]
 *   produces a local `content://` URI for AAOS / Android Auto renderers).
 */
fun TrackEntity.toAutoMediaItem(
    immersive: Boolean = false,
    artworkData: ByteArray? = null,
    artworkUriMapper: (String) -> Uri? = { Uri.parse(it) },
): MediaItem {
    val builder = MediaMetadata.Builder()
        .setTitle(title)
        .setSubtitle(artist)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setIsPlayable(true)
        .setIsBrowsable(false)
    artworkUrl?.takeIf { it.isNotBlank() }?.let { artworkUriMapper(it) }?.let { builder.setArtworkUri(it) }
    // Embedded artwork bytes populate METADATA_KEY_ALBUM_ART for controllers
    // that read the compat bitmap directly (Bluetooth AVRCP, legacy clients);
    // AAOS's car-media-common (Templates Host + CarLauncher) reads the local
    // content:// artworkUri supplied by MediaArtworkProvider instead.
    artworkData?.let { builder.setArtworkData(it) }
    if (immersive) builder.setExtras(immersiveAudioExtras())
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(sourceUrl ?: "")
        .setMediaMetadata(builder.build())
        .build()
}

/**
 * The cover Android Auto shows for a queued [track]: its own, else the art its
 * resolved sources carry ([trackArtwork] is `TrackResolverCache.trackArtwork`),
 * as the phone's track rows do. The playing track uses
 * [PlaybackState.effectiveArtwork], which puts the streaming service's cover
 * first. Null leaves the car's placeholder.
 */
internal fun carArtworkFor(track: TrackEntity, trackArtwork: Map<String, String>): String? =
    track.artworkUrl?.takeIf { it.isNotBlank() }
        ?: trackArtwork[com.parachord.android.resolver.trackKey(track.title, track.artist)]
