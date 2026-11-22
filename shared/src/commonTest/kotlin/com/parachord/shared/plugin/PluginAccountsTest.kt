package com.parachord.shared.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #782, desktop parachord#1615: `signedInMap`, the sheet's order and the Plug-Ins tiles. */
class PluginAccountsTest {
    private fun plugin(id: String, type: String = "", vararg caps: String) =
        PluginManager.PluginInfo(id = id, name = id, version = "1.0.0", type = type, capabilities = caps.associateWith { true })

    @Test
    fun `a status reply wins for its own id over the host`() {
        val map = signedInMap(
            host = mapOf("spotify" to true, "amazonmusic" to true),
            statuses = mapOf("amazonmusic" to PluginStatusEntry(signedIn = false)),
        )
        assertEquals(mapOf("spotify" to true, "amazonmusic" to false), map)
    }

    @Test
    fun `no verdict leaves the host value or no entry`() {
        val map = signedInMap(
            host = mapOf("soundcloud" to false),
            statuses = mapOf(
                "soundcloud" to PluginStatusEntry(),
                "amazonmusic" to PluginStatusEntry.READ_FAILED,
            ),
        )
        assertEquals(mapOf("soundcloud" to false), map)
        assertNull(statusSignedIn(PluginStatusEntry.READ_FAILED))
        assertNull(statusSignedIn(null))
        assertEquals(true, statusSignedIn(PluginStatusEntry(signedIn = true)))
    }

    @Test
    fun `a plug-in that needs a sign-in puts its account first`() {
        assertTrue(accountFirst(plugin("spotify", "", "resolve")))
        assertTrue(accountFirst(plugin("soundcloud", "", "resolve")))
        assertTrue(accountFirst(plugin("applemusic", "", "resolve")))
        assertTrue(accountFirst(plugin("amazonmusic", "", "resolve", "status")))
    }

    @Test
    fun `a plug-in that needs no sign-in keeps Enable first`() {
        assertFalse(accountFirst(plugin("lastfm", "meta-service", "recommendations", "scrobbling")))
        assertFalse(accountFirst(plugin("listenbrainz", "meta-service", "recommendations", "scrobbling")))
        assertFalse(accountFirst(plugin("bandcamp", "", "resolve")))
        assertFalse(accountFirst(plugin("localfiles", "", "resolve")))
    }

    @Test
    fun `only an explicit false on an active plug-in reads signed out`() {
        val accounts = mapOf("amazonmusic" to false, "spotify" to true)
        assertTrue(pluginSignedOut("amazonmusic", active = true, accounts = accounts))
        assertFalse(pluginSignedOut("amazonmusic", active = false, accounts = accounts))
        assertFalse(pluginSignedOut("spotify", active = true, accounts = accounts))
        assertFalse(pluginSignedOut("lastfm", active = true, accounts = accounts))
        assertEquals("Sign in", SIGN_IN_TILE_LABEL)
    }

    @Test
    fun `signed-out tiles sit unnumbered with the not-playing ones and the rest number with no gap`() {
        val order = pluginTileOrder(
            onInOrder = listOf("spotify", "amazonmusic", "applemusic", "bandcamp", "subsonic"),
            notPlaying = listOf("subsonic"),
            accounts = mapOf("amazonmusic" to false, "spotify" to true),
        )
        assertEquals(listOf("spotify", "applemusic", "bandcamp"), order.numbered)
        assertEquals(listOf("amazonmusic", "subsonic"), order.unnumbered)
    }

    @Test
    fun `with no verdicts every playing plug-in is numbered`() {
        val order = pluginTileOrder(listOf("spotify", "amazonmusic"), emptyList(), emptyMap())
        assertEquals(listOf("spotify", "amazonmusic"), order.numbered)
        assertEquals(emptyList(), order.unnumbered)
    }
}
