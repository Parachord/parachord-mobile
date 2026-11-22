package com.parachord.shared.plugin

import com.parachord.shared.home.PlaybackLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Desktop's vectors from `tests/renderer/plugin-switch.test.js` (#1505), ported. */
class PluginSwitchTest {
    private fun plugin(id: String, type: String = "", vararg caps: String) =
        PluginManager.PluginInfo(id = id, name = id, version = "1.0.0", type = type, capabilities = caps.associateWith { true })

    // The manifests' capabilities, as far as these switches read them.
    private val spotify = plugin("spotify", "", "resolve", "search")
    private val youtube = plugin("youtube", "", "resolve", "search")
    private val amazon = plugin("amazonmusic", "", "resolve", "search", "recommendations", "status")
    private val lastfm = plugin("lastfm", "meta-service", "recommendations", "similarUsers", "scrobbling", "metadata")
    private val listenbrainz = plugin("listenbrainz", "meta-service", "recommendations", "similarUsers", "scrobbling")
    private val librefm = plugin("librefm", "meta-service", "scrobbling")
    private val wikipedia = plugin("wikipedia", "meta-service", "biography", "artistImages")
    private val chatgpt = plugin("chatgpt", "meta-service", "chat", "generate")
    private val ollama = plugin("ollama", "meta-service", "chat")
    private val bandsintown = plugin("bandsintown", "meta-service", "concerts")
    private val achordion = plugin("achordion", "meta-service")

    @Test
    fun `the Enable hint says what the plug-in brings`() {
        assertEquals("Include it in search", enableSwitchHint(spotify))
        assertEquals("AI playlists and Shuffleupagus chat", enableSwitchHint(chatgpt))
        assertEquals("Shuffleupagus chat", enableSwitchHint(ollama))
        assertEquals("Upcoming concerts and ticket links", enableSwitchHint(bandsintown))
        assertEquals("Recommendations, similar listeners and your listening history", enableSwitchHint(lastfm))
        assertEquals("Recommendations, similar listeners and your listening history", enableSwitchHint(listenbrainz))
        assertEquals("Scrobbling, a log of what you play", enableSwitchHint(librefm))
        assertEquals("Artist biographies and photos", enableSwitchHint(wikipedia))
        assertEquals("", enableSwitchHint(achordion))
        assertEquals("Enable Amazon Music", enableSwitchLabel("Amazon Music"))
    }

    @Test
    fun `Play from is shown only for a music plug-in that is on`() {
        assertTrue(playbackSwitchState(spotify, active = true, playbackOff = false).shown)
        assertFalse(playbackSwitchState(spotify, active = false, playbackOff = false).shown)
        assertFalse(playbackSwitchState(lastfm, active = true, playbackOff = false).shown)
    }

    @Test
    fun `Play from is on by default and off is search covers and links only`() {
        assertEquals(PluginSwitchState(true, true, false, "Plays tracks, in your priority order"), playbackSwitchState(youtube, active = true, playbackOff = false))
        val off = playbackSwitchState(youtube, active = true, playbackOff = true)
        assertFalse(off.checked)
        assertFalse(off.disabled)
    }

    @Test
    fun `a plan limit switches Play from off and locks it saying why`() {
        val limit = PlaybackLimit(true, "Prime: no playback", message = "Playing Amazon tracks in Parachord needs Music Unlimited.")
        assertEquals(
            PluginSwitchState(true, false, true, "Playing Amazon tracks in Parachord needs Music Unlimited."),
            playbackSwitchState(amazon, active = true, playbackOff = false, limit = limit),
        )
        // An unblocked limit changes nothing.
        assertTrue(playbackSwitchState(amazon, active = true, playbackOff = false, limit = PlaybackLimit(false)).checked)
    }

    @Test
    fun `Recommendations from is shown for an on plug-in that offers them music or not`() {
        assertTrue(recommendationsSwitchState(amazon, active = true, recommendationsOff = false).shown)
        assertTrue(recommendationsSwitchState(lastfm, active = true, recommendationsOff = false).shown)
        assertFalse(recommendationsSwitchState(spotify, active = true, recommendationsOff = false).shown)
        assertFalse(recommendationsSwitchState(amazon, active = false, recommendationsOff = false).shown)
        assertTrue(recommendationsSwitchState(amazon, active = true, recommendationsOff = false).checked)
        assertFalse(recommendationsSwitchState(amazon, active = true, recommendationsOff = true).checked)
    }

    @Test
    fun `Use metadata from is shown for an enabled music plug-in and on by default`() {
        assertEquals(PluginSwitchState(true, true, false, "Album covers and artist photos"), metadataSwitchState(spotify, active = true, metadataOff = false))
        assertFalse(metadataSwitchState(spotify, active = true, metadataOff = true).checked)
        assertFalse(metadataSwitchState(wikipedia, active = true, metadataOff = false).shown)
        assertFalse(metadataSwitchState(spotify, active = false, metadataOff = false).shown)
    }

    // #782 (desktop parachord#1614 proposed): signed out, the nested switches are disabled.
    private val signInHint = "Sign in to use this"

    @Test
    fun `signed out the three switches are disabled and keep the stored on`() {
        assertEquals(PluginSwitchState(true, true, true, signInHint), playbackSwitchState(amazon, active = true, playbackOff = false, signedIn = false))
        assertEquals(PluginSwitchState(true, true, true, signInHint), metadataSwitchState(amazon, active = true, metadataOff = false, signedIn = false))
        assertEquals(PluginSwitchState(true, true, true, signInHint), recommendationsSwitchState(amazon, active = true, recommendationsOff = false, signedIn = false))
    }

    @Test
    fun `signed out the switches keep the stored off`() {
        assertEquals(PluginSwitchState(true, false, true, signInHint), playbackSwitchState(amazon, active = true, playbackOff = true, signedIn = false))
        assertEquals(PluginSwitchState(true, false, true, signInHint), metadataSwitchState(amazon, active = true, metadataOff = true, signedIn = false))
        assertEquals(PluginSwitchState(true, false, true, signInHint), recommendationsSwitchState(amazon, active = true, recommendationsOff = true, signedIn = false))
    }

    @Test
    fun `signed out a plug-in that is off still hides its switches`() {
        assertFalse(playbackSwitchState(amazon, active = false, playbackOff = false, signedIn = false).shown)
        assertFalse(metadataSwitchState(amazon, active = false, metadataOff = false, signedIn = false).shown)
        assertFalse(recommendationsSwitchState(amazon, active = false, recommendationsOff = false, signedIn = false).shown)
    }

    @Test
    fun `a plan limit still wins over signed out for Play from`() {
        val limit = PlaybackLimit(true, "Prime: no playback", message = "Playing Amazon tracks in Parachord needs Music Unlimited.")
        assertEquals(
            PluginSwitchState(true, false, true, "Playing Amazon tracks in Parachord needs Music Unlimited."),
            playbackSwitchState(amazon, active = true, playbackOff = false, limit = limit, signedIn = false),
        )
    }

    @Test
    fun `signed in or unknown changes nothing`() {
        for (signedIn in listOf(true, null)) {
            assertEquals(playbackSwitchState(amazon, true, false), playbackSwitchState(amazon, true, false, signedIn = signedIn))
            assertEquals(playbackSwitchState(amazon, true, true), playbackSwitchState(amazon, true, true, signedIn = signedIn))
            assertEquals(metadataSwitchState(amazon, true, false), metadataSwitchState(amazon, true, false, signedIn = signedIn))
            assertEquals(metadataSwitchState(amazon, true, true), metadataSwitchState(amazon, true, true, signedIn = signedIn))
            assertEquals(recommendationsSwitchState(amazon, true, false), recommendationsSwitchState(amazon, true, false, signedIn = signedIn))
            assertEquals(recommendationsSwitchState(amazon, true, true), recommendationsSwitchState(amazon, true, true, signedIn = signedIn))
        }
        assertFalse(playbackSwitchState(amazon, true, false, signedIn = null).disabled)
    }

    @Test
    fun `a failed status read has no sign-in verdict`() {
        assertEquals(null, PluginStatusEntry.READ_FAILED.signedIn)
        assertFalse(metadataSwitchState(amazon, true, false, signedIn = PluginStatusEntry.READ_FAILED.signedIn).disabled)
    }
}
