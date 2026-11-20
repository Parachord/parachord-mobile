package com.parachord.shared.home

import com.parachord.shared.plugin.PluginManager.PluginConfigField
import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSettingsApi::class)
class GettingStartedTest {
    private fun plugin(id: String, resolve: Boolean = true, stream: Boolean = true, embed: Boolean = false, auth: Boolean = false, fields: List<PluginConfigField> = emptyList(), authType: String? = null) =
        PluginInfo(
            id = id, name = id, version = "1",
            capabilities = mapOf("resolve" to resolve, "stream" to stream, "embedPlayer" to embed),
            requiresAuth = auth, configFields = fields, authType = authType,
        )

    private val subsonic = plugin("subsonic", auth = true, fields = listOf(
        PluginConfigField("serverUrl", "text", "Server"),
        PluginConfigField("username", "text", "Username"),
        PluginConfigField("password", "password", "Password"),
    ))
    private val registry = listOf(
        plugin("spotify", auth = true), plugin("applemusic", auth = true), plugin("soundcloud", auth = true), subsonic,
        plugin("bandcamp", stream = false, embed = true), plugin("localfiles"),
        plugin("youtube", stream = false),
    )

    // The shipped manifests' field flags (app/src/main/assets/plugins).
    private val spotifyShipped = plugin("spotify", auth = true, fields = listOf(PluginConfigField("clientId", "text", "Client ID", required = true)))
    private val appleShipped = plugin("applemusic", auth = true, fields = listOf(
        PluginConfigField("developerToken", "text", "MusicKit developer token", advanced = true, hasDefault = true),
        PluginConfigField("storefront", "text", "Storefront (Country)", hasDefault = true),
    ))
    private val soundcloudShipped = plugin("soundcloud", auth = true, fields = listOf(
        PluginConfigField("clientId", "text", "Client ID", advanced = true),
        PluginConfigField("clientSecret", "password", "Client Secret", advanced = true),
    ))

    @Test
    fun `shipped manifests - only Spotify and Subsonic need setup`() {
        assertFalse(settingsMissing(appleShipped, null))
        assertFalse(settingsMissing(soundcloudShipped, null))
        val needs = listOf(spotifyShipped, appleShipped, soundcloudShipped, subsonic)
            .filter { settingsMissing(it, null) || requiredSetting(it) != null }.map { it.id }.sorted()
        assertEquals(listOf("spotify", "subsonic"), needs)
        assertEquals(RequiredSetting("clientId", "Client ID"), requiredSetting(spotifyShipped))
        assertNull(requiredSetting(subsonic))
    }

    @Test
    fun `a custom auth type lands in services`() {
        val custom = plugin("plex", auth = false, authType = "custom")
        assertTrue(stepOnePlugins(registry + custom).services.any { it.id == "plex" })
        assertTrue(stepOnePlugins(registry + plugin("free", authType = "none")).free.any { it.id == "free" })
    }

    @Test
    fun `a limit without a label reads the default and a setup tile is not connected`() {
        val amazon = plugin("amazonmusic", auth = true)
        assertEquals(
            ServiceState("limited", "Can\u2019t play on this plan"),
            serviceState(amazon, mapOf("amazonmusic" to true), playbackLimits = mapOf("amazonmusic" to PlaybackLimit(true))),
        )
        assertFalse(serviceConnected(listOf(subsonic), setupNeeded = mapOf("subsonic" to true), playbackResolvers = listOf("subsonic")))
    }

    @Test
    fun `step 1 comes from the registry and never offers YouTube`() {
        val p = stepOnePlugins(registry)
        assertEquals(listOf("spotify", "applemusic", "soundcloud", "subsonic"), p.services.map { it.id })
        assertEquals(listOf("bandcamp"), p.free.map { it.id })
        assertEquals("localfiles", p.folder?.id)
        assertFalse((p.services + p.free).any { it.id == "youtube" })
    }

    @Test
    fun `a new service plug-in appears without a code change`() {
        assertTrue(stepOnePlugins(registry + plugin("newservice", auth = true)).services.any { it.id == "newservice" })
    }

    @Test
    fun `settings missing until one field is saved`() {
        assertTrue(settingsMissing(subsonic, null))
        assertTrue(settingsMissing(subsonic, mapOf("serverUrl" to "  ")))
        assertFalse(settingsMissing(subsonic, mapOf("serverUrl" to "http://nas:4533")))
        assertFalse(settingsMissing(plugin("bandcamp"), null))
        assertEquals(ServiceState("setup", "Set up"), serviceState(subsonic, setupNeeded = mapOf("subsonic" to true), playbackResolvers = listOf("subsonic")))
    }

    @Test
    fun `a required setting not saved yet reads Add your field`() {
        val spotify = registry[0]
        assertEquals(
            ServiceState("setup", "Add your Client ID"),
            serviceState(spotifyShipped, accounts = mapOf("spotify" to false), setupNeeded = mapOf("spotify" to true)),
        )
        assertEquals(
            "connected",
            serviceState(spotify, accounts = mapOf("spotify" to true), setupNeeded = mapOf("spotify" to true), playbackResolvers = listOf("spotify")).state,
        )
    }

    @Test
    fun `a tile is connected only when signed in and playing and a plan limit shows its label`() {
        val spotify = registry[0]
        assertEquals("connected", serviceState(spotify, mapOf("spotify" to true), playbackResolvers = listOf("spotify")).state)
        assertEquals("connect", serviceState(spotify, mapOf("spotify" to true), playbackResolvers = emptyList()).state)
        assertEquals("connect", serviceState(spotify, mapOf("spotify" to false), playbackResolvers = listOf("spotify")).state)
        assertEquals(
            ServiceState("limited", "Prime: no playback"),
            serviceState(plugin("amazonmusic", auth = true), mapOf("amazonmusic" to true), playbackLimits = mapOf("amazonmusic" to PlaybackLimit(true, "Prime: no playback"))),
        )
        assertEquals("connected", serviceState(subsonic, emptyMap(), playbackResolvers = listOf("subsonic")).state)
        assertTrue(serviceConnected(listOf(spotify), mapOf("spotify" to true), playbackResolvers = listOf("spotify")))
        assertFalse(serviceConnected(listOf(spotify)))
    }

    @Test
    fun `scrobbler connected once any account name is saved`() {
        assertTrue(scrobblerConnected(listOf(null, "jh")))
        assertFalse(scrobblerConnected(listOf(null, "")))
        assertFalse(scrobblerConnected(emptyList()))
    }

    @Test
    fun `each step is done on its own rule and current is the first not done`() {
        assertEquals("connect", gettingStartedSteps().current)
        assertEquals("music", gettingStartedSteps(folderAdded = true).current)
        assertEquals("listen", gettingStartedSteps(connected = true, scrobbling = true).current)
        assertEquals("listen", gettingStartedSteps(connected = true, hasMusic = true).current)
        assertTrue(gettingStartedSteps(connected = true, hasMusic = true, played = true).allDone)
        assertEquals(listOf(false, false, true), gettingStartedSteps(played = true).steps.map { it.done })
        assertEquals(1, gettingStartedSteps(played = true).done)
    }

    @Test
    fun `a saved state wins and skipped steps offer the pill`() {
        assertEquals(GettingStartedState(false, true, false), initialGettingStarted(GettingStartedState(false, true), false, false))
        val open = gettingStartedSteps()
        val done = gettingStartedSteps(connected = true, hasMusic = true, played = true)
        assertTrue(gettingStartedLeft(GettingStartedState(active = false, skipped = true), open))
        assertFalse(gettingStartedLeft(GettingStartedState(active = false, skipped = false), open))
        assertFalse(gettingStartedLeft(GettingStartedState(active = false, skipped = true), done))
        assertTrue(showGettingStarted(GettingStartedState(active = true), open))
        assertFalse(showGettingStarted(GettingStartedState(active = false), open))
        assertFalse(showGettingStarted(GettingStartedState(active = true), done))
        assertFalse(showGettingStarted(null, open))
    }

    @Test
    fun `no saved state with a collection is inactive and with nothing is active`() {
        assertFalse(initialGettingStarted(null, connectDone = false, musicDone = true).active)
        assertFalse(initialGettingStarted(null, connectDone = true, musicDone = false).active)
        assertTrue(initialGettingStarted(null, connectDone = false, musicDone = false).active)
    }

    private class FakeSecureStore : SecureTokenStore {
        private val m = mutableMapOf<String, String>()
        override fun get(key: String): String? = m[key]
        override fun set(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun contains(key: String): Boolean = m.containsKey(key)
        override fun observe(key: String): Flow<String?> = flowOf(m[key])
    }

    @Test
    fun `the saved state and the dismissed set round-trip through SettingsStore`() = runTest {
        val backing = MapSettings()
        val s = SettingsStore(FakeSecureStore(), KvStore(backing))
        assertNull(s.getHomeGettingStarted())
        assertEquals(emptySet(), s.getHomeSetupDismissed())
        val state = GettingStartedState(active = false, played = true, skipped = true)
        s.setHomeGettingStarted(state)
        s.setHomeSetupDismissed(setOf("getting-started"))
        val again = SettingsStore(FakeSecureStore(), KvStore(backing))
        assertEquals(state, again.getHomeGettingStarted())
        s.setHomeGettingStarted(GettingStartedState())
        assertEquals("""{"active":false,"played":false,"skipped":false}""", backing.getStringOrNull(SettingsStore.HOME_GETTING_STARTED))
        s.setHomeGettingStarted(state)
        assertEquals(setOf("getting-started"), again.getHomeSetupDismissed())
        backing.putString(SettingsStore.HOME_GETTING_STARTED, "not json")
        assertNull(again.getHomeGettingStarted())
    }
}
