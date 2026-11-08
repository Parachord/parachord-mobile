package com.parachord.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [resolvePlaceholderTitleSubtitle], the pure helper behind
 * PlaybackService.silencePlaceholderFor.
 *
 * Background: when Android Auto dispatches a play command via onSetMediaItems,
 * it hands back a bare mediaId — NOT the browse tile's metadata. The synchronous
 * placeholder we set on the player therefore had empty title/subtitle, and
 * Auto's GH.MediaPlaybackMonitor rejects a session whose current item lacks
 * either ("Invalid metadata, no title and subtitle" → head unit reverts to
 * browse). This only surfaced when the phone was locked, because the real
 * metadata (set asynchronously by PlaybackController ~300-500ms later) lost the
 * race against GH's check under contended foreground-service promotion.
 *
 * The fix guarantees a valid title+subtitle synchronously: prefer whatever Auto
 * sent, then the cached browse-tile metadata (real playlist name), then a
 * generic non-blank fallback. There must never be an empty-metadata window.
 */
class PlaybackServicePlaceholderTest {

    @Test
    fun `source metadata is used when present`() {
        val (title, subtitle) = resolvePlaceholderTitleSubtitle(
            sourceTitle = "My Playlist",
            sourceSubtitle = "Jared",
            cachedTitle = "Cached Title",
            cachedSubtitle = "Cached Sub",
        )
        assertEquals("My Playlist", title)
        assertEquals("Jared", subtitle)
    }

    @Test
    fun `falls back to cached browse-tile metadata when source is empty`() {
        // Auto hands back a bare mediaId — source title/subtitle null.
        val (title, subtitle) = resolvePlaceholderTitleSubtitle(
            sourceTitle = null,
            sourceSubtitle = null,
            cachedTitle = "Road Trip Mix",
            cachedSubtitle = "Jared",
        )
        assertEquals("Road Trip Mix", title)
        assertEquals("Jared", subtitle)
    }

    @Test
    fun `generic fallback guarantees non-blank title and subtitle when nothing known`() {
        val (title, subtitle) = resolvePlaceholderTitleSubtitle(
            sourceTitle = null,
            sourceSubtitle = null,
            cachedTitle = null,
            cachedSubtitle = null,
        )
        // Both must be non-blank so GH.MediaPlaybackMonitor accepts the session.
        assert(title.isNotBlank()) { "title must be non-blank" }
        assert(subtitle.isNotBlank()) { "subtitle must be non-blank" }
    }

    @Test
    fun `blank strings are treated as missing`() {
        val (title, subtitle) = resolvePlaceholderTitleSubtitle(
            sourceTitle = "   ",
            sourceSubtitle = "",
            cachedTitle = "Real Name",
            cachedSubtitle = "Owner",
        )
        assertEquals("Real Name", title)
        assertEquals("Owner", subtitle)
    }

    @Test
    fun `mixed source and cache - title from source, subtitle from cache`() {
        val (title, subtitle) = resolvePlaceholderTitleSubtitle(
            sourceTitle = "Has Title",
            sourceSubtitle = null,
            cachedTitle = "Ignored",
            cachedSubtitle = "From Cache",
        )
        assertEquals("Has Title", title)
        assertEquals("From Cache", subtitle)
    }

    // ── mosaicFileNameFromStored (Auto FileProvider bridge) ──────────────

    @Test
    fun `mosaicFileNameFromStored extracts name from single-slash file URI`() {
        // The form java.io.File.toURI() actually produces today.
        val stored = "file:/data/user/0/com.parachord.android/files/playlist_mosaics/listenbrainz-abc.jpg"
        assertEquals("listenbrainz-abc.jpg", mosaicFileNameFromStored(stored))
    }

    @Test
    fun `mosaicFileNameFromStored strips cache-bust query`() {
        val stored = "file:///data/.../files/playlist_mosaics/hosted-xspf-7cd1.jpg?v=e2de321e"
        assertEquals("hosted-xspf-7cd1.jpg", mosaicFileNameFromStored(stored))
    }

    @Test
    fun `mosaicFileNameFromStored returns null for http art (not bridged)`() {
        assertEquals(null, mosaicFileNameFromStored("https://mosaic.scdn.co/300/abc"))
    }

    @Test
    fun `mosaicFileNameFromStored returns null for content art and null input`() {
        assertEquals(null, mosaicFileNameFromStored("content://media/external/audio/albumart/5"))
        assertEquals(null, mosaicFileNameFromStored(null))
        assertEquals(null, mosaicFileNameFromStored(""))
    }
}
