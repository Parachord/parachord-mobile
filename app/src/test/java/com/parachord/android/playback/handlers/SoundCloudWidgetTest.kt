package com.parachord.android.playback.handlers

import com.parachord.android.data.db.entity.TrackEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SoundCloud through its widget (desktop parachord#1209). */
class SoundCloudWidgetTest {

    private fun track(sourceUrl: String? = null, soundcloudId: String? = null) = TrackEntity(
        id = "t", title = "Desos House Music", artist = "Desos",
        resolver = "soundcloud", sourceUrl = sourceUrl, soundcloudId = soundcloudId,
    )

    @Test
    fun `the widget loads the track page, else the API URL built from the id`() {
        val page = "https://soundcloud.com/desos/house-music-original-mix"
        assertEquals(page, SoundCloudPlaybackHandler.widgetLink(track(page, "293")))
        assertEquals(
            "https://api.soundcloud.com/tracks/293",
            SoundCloudPlaybackHandler.widgetLink(track("https://example.com/x.mp3", "293")),
        )
        assertNull(SoundCloudPlaybackHandler.widgetLink(track()))
    }

    @Test
    fun `Open in SoundCloud offers only a real page, never an API URL`() {
        assertEquals(
            "https://soundcloud.com/desos/house",
            SoundCloudPlaybackHandler.pageLink(track("https://soundcloud.com/desos/house")),
        )
        assertNull(SoundCloudPlaybackHandler.pageLink(track("https://api.soundcloud.com/tracks/293")))
        assertNull(SoundCloudPlaybackHandler.pageLink(track("https://evil.com/soundcloud.com")))
    }

    @Test
    fun `plays without a SoundCloud sign-in`() {
        val handler = SoundCloudPlaybackHandler(mockk(relaxed = true))
        assertTrue(handler.canHandle(track(soundcloudId = "293")))
        assertFalse(handler.canHandle(track(soundcloudId = "293").copy(resolver = "spotify")))
    }

    @Test
    fun `a track the widget refuses is a failed source, with its page to open`() = runTest {
        val bridge = mockk<SoundCloudWidgetBridge>(relaxed = true)
        coEvery { bridge.load(any(), any()) } returns SoundCloudWidgetBridge.LoadResult.Refused("unplayable")
        val handler = SoundCloudPlaybackHandler(bridge)
        try {
            handler.play(track("https://soundcloud.com/desos/house", "293"))
            fail("expected a refusal")
        } catch (e: EmbedPlaybackRefusedException) {
            assertEquals("SoundCloud", e.serviceName)
            assertEquals("https://soundcloud.com/desos/house", e.pageUrl)
        }
    }

    @Test
    fun `a load another play superseded is not the track's failure`() = runTest {
        val bridge = mockk<SoundCloudWidgetBridge>(relaxed = true)
        coEvery { bridge.load(any(), any()) } returns SoundCloudWidgetBridge.LoadResult.Refused("superseded")
        SoundCloudPlaybackHandler(bridge).play(track(soundcloudId = "293"))
    }

    @Test
    fun `relayed events parse, and junk is ignored`() {
        assertEquals(
            SoundCloudWidgetBridge.WidgetEvent(3, "progress", 12.5, null),
            SoundCloudWidgetBridge.parseEvent("""{"gen":3,"type":"progress","value":12.5}"""),
        )
        assertEquals(
            SoundCloudWidgetBridge.WidgetEvent(4, "failed", null, "unplayable"),
            SoundCloudWidgetBridge.parseEvent("""{"gen":4,"type":"failed","value":"unplayable"}"""),
        )
        assertEquals(
            SoundCloudWidgetBridge.WidgetEvent(0, "pageReady", null, null),
            SoundCloudWidgetBridge.parseEvent("""{"gen":0,"type":"pageReady","value":null}"""),
        )
        assertNull(SoundCloudWidgetBridge.parseEvent("not json"))
        assertNull(SoundCloudWidgetBridge.parseEvent("""{"type":"play"}"""))
    }

    @Test
    fun `the widget's iframe may use DRM, or encrypted-only tracks never start`() {
        // Gradle runs unit tests from the app module's directory.
        val module = java.io.File("src/main/assets/js/soundcloud-widget-player.js").readText()
        assertTrue(module.contains("'autoplay; encrypted-media'"))
    }

    @Test
    fun `Android and iOS carry the same widget page and module`() {
        // Gradle runs unit tests from the app module's directory.
        for (name in listOf("soundcloud-widget.html", "soundcloud-widget-player.js")) {
            val android = java.io.File("src/main/assets/js/$name")
            val ios = java.io.File("../iosApp/Parachord/Resources/$name")
            assertTrue("missing $android", android.isFile)
            assertTrue("missing $ios", ios.isFile)
            assertEquals("$name differs between the apps", android.readText(), ios.readText())
        }
    }

    @Test
    fun `only our page and SoundCloud may load in the widget's WebView`() {
        assertTrue(SoundCloudWidgetBridge.isAllowedHost("w.soundcloud.com"))
        assertTrue(SoundCloudWidgetBridge.isAllowedHost("cf-media.sndcdn.com"))
        assertTrue(SoundCloudWidgetBridge.isAllowedHost("appassets.androidplatform.net"))
        assertFalse(SoundCloudWidgetBridge.isAllowedHost("notsoundcloud.com"))
        assertFalse(SoundCloudWidgetBridge.isAllowedHost("example.com"))
    }
}
