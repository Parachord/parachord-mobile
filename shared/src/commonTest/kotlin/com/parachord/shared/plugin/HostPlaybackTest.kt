package com.parachord.shared.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostPlaybackTest {

    private fun plugin(id: String, caps: Map<String, Boolean>, drm: Boolean = false) =
        PluginManager.PluginInfo(id = id, name = id, version = "1.0.0", capabilities = caps, hasDrmPair = drm)

    private val amazon = plugin("amazonmusic", mapOf("resolve" to true, "stream" to true, "widevine" to true, "status" to true), drm = true)
    private val subsonic = plugin("subsonic", mapOf("resolve" to true, "html5Stream" to true))
    private val spotify = plugin("spotify", mapOf("resolve" to true, "stream" to true))
    private val widevineNoPair = plugin("half", mapOf("resolve" to true, "widevine" to true))

    @Test
    fun `a plugin's plugin-supplied paths`() {
        assertEquals(setOf(HostPlayback.WIDEVINE), pluginPlaybackPaths(amazon))
        assertEquals(setOf(HostPlayback.HTML5_STREAM), pluginPlaybackPaths(subsonic))
        assertEquals(emptySet(), pluginPlaybackPaths(spotify))
        // Widevine without getPlaybackManifest + relayLicense isn't a path.
        assertEquals(emptySet(), pluginPlaybackPaths(widevineNoPair))
    }

    @Test
    fun `iOS can't play a plugin whose only path is one it lacks`() {
        assertTrue(hostCannotPlay(amazon, HostPlayback.IOS))
        assertTrue(hostCannotPlay(subsonic, HostPlayback.IOS))
        assertFalse(hostCannotPlay(spotify, HostPlayback.IOS))
        assertEquals(listOf("amazonmusic", "subsonic"), hostUnplayableIds(listOf(spotify, subsonic, amazon), HostPlayback.IOS))
    }

    @Test
    fun `Android plays them all`() {
        assertEquals(emptyList(), hostUnplayableIds(listOf(spotify, subsonic, amazon), HostPlayback.ANDROID))
    }

    @Test
    fun `the tile and the Play from switch say so`() {
        assertEquals(TileStatus(HOST_CANNOT_PLAY_LABEL, HOST_CANNOT_PLAY_MESSAGE), tileStatus(emptyMap(), "amazonmusic", emptyList(), listOf("amazonmusic")))
        assertEquals(TileStatus(null, null), tileStatus(emptyMap(), "spotify", emptyList(), listOf("amazonmusic")))
        val s = playbackSwitchState(amazon, active = true, playbackOff = false, limit = null, hostCannotPlay = true)
        assertFalse(s.checked)
        assertTrue(s.disabled)
        assertEquals(HOST_CANNOT_PLAY_MESSAGE, s.hint)
    }
}
