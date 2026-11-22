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

    // #782, desktop parachord#1615: a plug-in that needs a sign-in never shows
    // as enabled until it's signed in. Display only; only false locks.
    private val amazonMusic = PluginManager.PluginInfo(
        id = "amazonmusic", name = "Amazon Music", version = "1.0.0",
        capabilities = mapOf("resolve" to true, "search" to true, "recommendations" to true, "status" to true),
    )
    private val spotifyNamed = PluginManager.PluginInfo(
        id = "spotify", name = "Spotify", version = "1.0.0", capabilities = mapOf("resolve" to true, "search" to true),
    )
    private val signedOut = PluginSwitchState(true, false, true, "Sign in to Amazon Music first")

    @Test
    fun `signed out the nested switches are off and locked saying so`() {
        assertEquals(signedOut, playbackSwitchState(amazonMusic, active = true, playbackOff = false, signedIn = false))
        assertEquals(signedOut, metadataSwitchState(amazonMusic, active = true, metadataOff = false, signedIn = false))
        assertEquals(signedOut, recommendationsSwitchState(amazonMusic, active = true, recommendationsOff = false, signedIn = false))
    }

    @Test
    fun `a choice switched off stays saved and shows again once signed in`() {
        assertEquals(signedOut, playbackSwitchState(amazonMusic, active = true, playbackOff = true, signedIn = false))
        val play = playbackSwitchState(amazonMusic, active = true, playbackOff = true, signedIn = true)
        assertFalse(play.checked); assertFalse(play.disabled)
        val meta = metadataSwitchState(amazonMusic, active = true, metadataOff = true, signedIn = true)
        assertFalse(meta.checked); assertFalse(meta.disabled)
        val recs = recommendationsSwitchState(amazonMusic, active = true, recommendationsOff = true, signedIn = true)
        assertFalse(recs.checked); assertFalse(recs.disabled)
    }

    @Test
    fun `signed in or no verdict the nested switches work as before`() {
        for (signedIn in listOf(true, null)) {
            val play = playbackSwitchState(amazonMusic, active = true, playbackOff = false, signedIn = signedIn)
            assertTrue(play.checked); assertFalse(play.disabled)
            val meta = metadataSwitchState(amazonMusic, active = true, metadataOff = false, signedIn = signedIn)
            assertTrue(meta.checked); assertFalse(meta.disabled)
            val recs = recommendationsSwitchState(amazonMusic, active = true, recommendationsOff = false, signedIn = signedIn)
            assertTrue(recs.checked); assertFalse(recs.disabled)
        }
    }

    @Test
    fun `a plan limit still decides Play from with its own message`() {
        val limit = PlaybackLimit(true, "Prime: no playback", message = "Your plan can’t play from Amazon Music.")
        assertEquals(
            PluginSwitchState(true, false, true, "Your plan can’t play from Amazon Music."),
            playbackSwitchState(amazonMusic, active = true, playbackOff = false, limit = limit, signedIn = false),
        )
    }

    @Test
    fun `a plug-in that is off shows none of them signed in or not`() {
        assertFalse(playbackSwitchState(amazonMusic, active = false, playbackOff = false, signedIn = false).shown)
        assertFalse(metadataSwitchState(amazonMusic, active = false, metadataOff = false, signedIn = false).shown)
        assertFalse(recommendationsSwitchState(amazonMusic, active = false, recommendationsOff = false, signedIn = false).shown)
    }

    @Test
    fun `signed out Enable is off and locked even while in the active list`() {
        assertEquals(
            PluginSwitchState(true, false, true, "Sign in to Amazon Music first"),
            enableSwitchState(amazonMusic, active = true, signedIn = false),
        )
        assertEquals(
            PluginSwitchState(true, false, true, "Sign in to Spotify first"),
            enableSwitchState(spotifyNamed, active = false, signedIn = false),
        )
    }

    @Test
    fun `signed in or no verdict Enable reads the active list as before`() {
        for (signedIn in listOf(true, null)) {
            assertEquals(PluginSwitchState(true, true, false, "Include it in search"), enableSwitchState(amazonMusic, active = true, signedIn = signedIn))
            assertEquals(PluginSwitchState(true, false, false, "Include it in search"), enableSwitchState(amazonMusic, active = false, signedIn = signedIn))
        }
    }
}
