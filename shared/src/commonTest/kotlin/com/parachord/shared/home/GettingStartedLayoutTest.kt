package com.parachord.shared.home

import com.parachord.shared.model.Playlist
import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.settings.SettingsTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The getting-started layout's drawing rules (desktop `home-getting-started.js`).
class GettingStartedLayoutTest {
    private fun status(connected: Boolean = false, music: Boolean = false, played: Boolean = false) =
        gettingStartedSteps(connected = connected, hasMusic = music, played = played)

    @Test
    fun `the steps and their titles follow desktop`() {
        assertEquals(listOf("connect", "music", "listen"), GETTING_STARTED_STEPS.map { it.id })
        assertEquals(
            listOf("Connect where you listen", "Bring in your music", "Start listening"),
            GETTING_STARTED_STEPS.map { it.title },
        )
    }

    @Test
    fun `the current step is open until another is picked`() {
        assertEquals("connect", openGettingStartedStep(null, status()))
        assertEquals("music", openGettingStartedStep(null, status(connected = true)))
        assertEquals("listen", openGettingStartedStep(null, status(connected = true, music = true)))
        assertEquals("connect", openGettingStartedStep("connect", status(connected = true)))
    }

    @Test
    fun `with every step done the first step opens`() {
        assertEquals("connect", openGettingStartedStep(null, status(true, true, true)))
    }

    @Test
    fun `a step reads Done — Next or Not yet`() {
        val s = status(connected = true)
        val (connect, music, listen) = s.steps
        assertEquals("Done", stepStateLine(connect, s.current))
        assertEquals("Next", stepStateLine(music, s.current))
        assertEquals("Not yet", stepStateLine(listen, s.current))
        assertEquals(StepMarkerLook.DONE, stepMarkerLook(connect, s.current))
        assertEquals(StepMarkerLook.CURRENT, stepMarkerLook(music, s.current))
        assertEquals(StepMarkerLook.NOT_YET, stepMarkerLook(listen, s.current))
    }

    @Test
    fun `a service tile reads its state`() {
        assertEquals("Connected", serviceTileDescription(ServiceState("connected", null)))
        assertTrue(serviceTileShowsBadge(ServiceState("connected", null)))
        assertEquals("Connect", serviceTileDescription(ServiceState("connect", null)))
        assertFalse(serviceTileShowsBadge(ServiceState("connect", null)))
        assertEquals("Add your Client ID", serviceTileDescription(ServiceState("setup", "Add your Client ID")))
        assertEquals("Can’t play on this plan", serviceTileDescription(ServiceState("limited", "Can’t play on this plan")))
    }

    @Test
    fun `a service tile's label names the service`() {
        assertEquals("Connect Spotify", serviceTileLabel("Spotify", ServiceState("connect", null)))
        assertEquals("Set up Spotify: add your client id", serviceTileLabel("Spotify", ServiceState("setup", "Add your Client ID")))
        assertEquals("Spotify: connected", serviceTileLabel("Spotify", ServiceState("connected", null)))
        assertEquals("Amazon: Prime only", serviceTileLabel("Amazon", ServiceState("limited", "Prime only")))
    }

    @Test
    fun `the music folder says whether it's on and asks for music`() {
        assertEquals("On · Add a folder", folderTileDescription(on = true, folderAdded = false))
        assertEquals("Off · Add a folder", folderTileDescription(on = false, folderAdded = false))
        assertEquals("On", folderTileDescription(on = true, folderAdded = true))
        assertEquals("Add a music folder", folderTileLabel(folderAdded = false))
        assertEquals("Your music folder", folderTileLabel(folderAdded = true))
    }

    @Test
    fun `a free plug-in reads On or Off`() {
        assertEquals("On", freeTileDescription(true))
        assertEquals("Off", freeTileDescription(false))
    }

    @Test
    fun `the view knows which plug-ins are on`() {
        val bandcamp = PluginInfo(id = "bandcamp", name = "Bandcamp", version = "1")
        val view = GettingStartedView(
            status = status(), services = emptyList(), free = listOf(bandcamp), folder = null,
            hasMusic = false, playbackResolvers = listOf("bandcamp"),
        )
        assertTrue(view.isOn(bandcamp))
        assertFalse(view.isOn(bandcamp.copy(id = "youtube")))
    }

    @Test
    fun `step 3's line depends on having music`() {
        assertEquals("Play your collection, or open a playlist you brought in.", listenStepLine(true))
        assertEquals("Play an album from Explore below, or search for a song.", listenStepLine(false))
    }

    @Test
    fun `explore keeps Critical Darlings and Pop of the Tops — even a failed one`() {
        val h = exploreHalves(criticalCount = 3, criticalLoading = false, chartsCount = 0, chartsLoading = false, chartsFailed = true)
        assertFalse(h.fresh)
        assertTrue(h.critical)
        assertTrue(h.charts)
        val none = exploreHalves(0, false, 0, false, false)
        assertFalse(none.critical || none.charts)
        assertTrue(exploreHalves(0, true, 0, true, false).let { it.critical && it.charts })
    }

    private fun meta(id: String, scrobbling: Boolean) = PluginInfo(
        id = id, name = id, version = "1", type = "meta-service", capabilities = mapOf("scrobbling" to scrobbling),
    )

    @Test
    fun `scrobbler plug-ins are the ones that scrobble`() {
        val list = listOf(meta("lastfm", true), meta("discogs", false), meta("librefm", true))
        assertEquals(listOf("lastfm", "librefm"), scrobblerPlugins(list).map { it.id })
    }

    @Test
    fun `scrobbler tiles pair each plug-in with its account`() {
        val tiles = scrobblerTiles(listOf(meta("lastfm", true), meta("librefm", true)), mapOf("lastfm" to true))
        assertEquals(listOf(true, false), tiles.map { it.second })
    }

    @Test
    fun `a scrobbler tile reads Connected or Connect`() {
        assertEquals("Connected", scrobblerTileDescription(true))
        assertEquals("Connect", scrobblerTileDescription(false))
        assertEquals("Last.fm: connected", scrobblerTileLabel("Last.fm", true))
        assertEquals("Connect Last.fm", scrobblerTileLabel("Last.fm", false))
    }

    @Test
    fun `a playlist tile has its title and track count`() {
        val p = Playlist(id = "p", name = "Daily Brew", trackCount = 1)
        assertEquals("Daily Brew", playlistTileTitle(p))
        assertEquals("1 track", playlistTileDescription(p))
        assertEquals("Untitled playlist", playlistTileTitle(p.copy(name = " ")))
        assertEquals("12 tracks", playlistTileDescription(p.copy(trackCount = 12)))
    }

    @Test
    fun `step 3 offers at most three playlists — freshest first`() {
        val list = (1..5).map { Playlist(id = "p$it", name = "P$it", createdAt = it.toLong()) }
        assertEquals(listOf("p5", "p4", "p3"), gettingStartedPlaylists(list).map { it.id })
    }

    @Test
    fun `Sync your library opens Settings on the Sync tab`() {
        assertEquals("sync", SYNC_LIBRARY_SETTINGS_TAB)
        assertEquals(SettingsTab.SYNC, SYNC_LIBRARY_SETTINGS_TAB)
    }

    @Test
    fun `a service still needing setup says so`() {
        assertEquals("Set up X: set up", serviceTileLabel("X", ServiceState("setup", null)))
    }
}
