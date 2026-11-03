package com.parachord.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The widget's links (#495), shared by both apps. */
class SoundCloudWidgetTest {
    private val page = "https://soundcloud.com/desos/house-music-original-mix"

    @Test
    fun theWidgetLoadsTheTrackPage_elseTheApiUrlFromTheId() {
        assertEquals(page, SoundCloudWidget.widgetLink(url = null, soundcloudUrl = page, soundcloudId = "293"))
        assertEquals(page, SoundCloudWidget.widgetLink(url = page, soundcloudUrl = null, soundcloudId = null))
        assertEquals(
            "https://api.soundcloud.com/tracks/293",
            SoundCloudWidget.widgetLink(url = "https://example.com/x.mp3", soundcloudUrl = null, soundcloudId = "293"),
        )
        assertEquals(
            "https://api.soundcloud.com/tracks/293",
            SoundCloudWidget.widgetLink(url = "https://api.soundcloud.com/tracks/293", soundcloudUrl = null, soundcloudId = null),
        )
        assertNull(SoundCloudWidget.widgetLink(url = null, soundcloudUrl = null, soundcloudId = " "))
    }

    @Test
    fun aPreviewSaysSo_otherRefusalsPointToSoundCloud() {
        assertEquals(
            "SoundCloud only lets Parachord play a preview of \"Shake It Off\".",
            SoundCloudWidget.refusedMessage("SoundCloud", "Shake It Off", "preview"),
        )
        assertEquals(
            "SoundCloud only plays \"Roads\" on its own site.",
            SoundCloudWidget.refusedMessage("SoundCloud", "Roads", "unplayable"),
        )
    }

    @Test
    fun openInSoundCloudOffersOnlyARealPage() {
        assertEquals(page, SoundCloudWidget.pageLink(url = "https://api.soundcloud.com/tracks/293", soundcloudUrl = page))
        assertNull(SoundCloudWidget.pageLink(url = "https://api.soundcloud.com/tracks/293", soundcloudUrl = null))
        assertNull(SoundCloudWidget.pageLink(url = "https://evil.com/soundcloud.com", soundcloudUrl = null))
        assertNull(SoundCloudWidget.pageLink(url = "https://soundcloud.com.evil.com/x", soundcloudUrl = null))
        assertNull(SoundCloudWidget.pageLink(url = "http://soundcloud.com/a/b", soundcloudUrl = null))
    }
}
