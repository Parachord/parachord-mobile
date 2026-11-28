package com.parachord.android.playback

import android.app.Application
import androidx.media3.common.MediaMetadata
import com.parachord.shared.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaItemMappingTest {

    @Test fun `toAutoMediaItem populates metadata fields`() {
        val track = Track(
            id = "track-1",
            title = "Title",
            artist = "Artist",
            album = "Album",
            artworkUrl = "https://example.com/art.jpg",
            sourceUrl = "https://example.com/audio.mp3",
        )
        val item = track.toAutoMediaItem()
        assertEquals("track-1", item.mediaId)
        assertEquals("Title", item.mediaMetadata.title)
        assertEquals("Artist", item.mediaMetadata.artist)
        assertEquals("Album", item.mediaMetadata.albumTitle)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, item.mediaMetadata.mediaType)
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals(false, item.mediaMetadata.isBrowsable)
        assertEquals("https://example.com/art.jpg", item.mediaMetadata.artworkUri.toString())
    }

    @Test fun `toAutoMediaItem omits artwork when null`() {
        val track = Track(id = "t", title = "T", artist = "A", album = "B", artworkUrl = null)
        val item = track.toAutoMediaItem()
        assertNull(item.mediaMetadata.artworkUri)
    }

    @Test fun `toAutoMediaItem maps artwork URL through custom mapper`() {
        val track = Track(
            id = "t",
            title = "T",
            artist = "A",
            album = "B",
            artworkUrl = "https://example.com/art.jpg",
        )
        val item = track.toAutoMediaItem(
            artworkUriMapper = { url -> MediaArtworkProvider.uriFor("com.parachord.android", url) },
        )
        val expected = MediaArtworkProvider.uriFor("com.parachord.android", "https://example.com/art.jpg")
        assertEquals(expected, item.mediaMetadata.artworkUri)
        assertEquals("content", item.mediaMetadata.artworkUri?.scheme)
    }
}
