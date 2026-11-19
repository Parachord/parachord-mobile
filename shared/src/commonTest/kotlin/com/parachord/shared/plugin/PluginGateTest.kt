package com.parachord.shared.plugin

import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSettingsApi::class)
class PluginGateTest {

    private class FakeSecureStore : SecureTokenStore {
        private val m = mutableMapOf<String, String>()
        override fun get(key: String): String? = m[key]
        override fun set(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun contains(key: String): Boolean = m.containsKey(key)
        override fun observe(key: String): Flow<String?> = flowOf(m[key])
    }

    private fun store() = SettingsStore(FakeSecureStore(), KvStore(MapSettings()))

    private fun on(
        id: String = "spotify",
        isMetaService: Boolean = false,
        needsAccount: Boolean = false,
        connected: Boolean = false,
        active: List<String> = emptyList(),
        disabledPlugins: Set<String> = emptySet(),
        disabledMeta: Set<String> = emptySet(),
    ) = pluginIsOn(id, isMetaService, needsAccount, connected, active, disabledPlugins, disabledMeta)

    @Test
    fun `a disabled plugin is off`() {
        assertFalse(on(connected = true, active = listOf("spotify"), disabledPlugins = setOf("spotify")))
        assertFalse(on(connected = true, active = listOf("spotify"), disabledMeta = setOf("spotify")))
        assertFalse(on(isMetaService = true, disabledPlugins = setOf("spotify")))
    }

    @Test
    fun `iOS - the active list decides`() {
        assertFalse(on(id = "applemusic", connected = true, active = listOf("spotify")))
        assertTrue(on(id = "spotify", connected = true, active = listOf("spotify")))
    }

    @Test
    fun `Android - an account plugin needs a connection`() {
        assertFalse(on(needsAccount = true, connected = false))
        assertTrue(on(needsAccount = true, connected = true))
    }

    @Test
    fun `a plugin with no account is on when nothing disables it`() {
        assertTrue(on(needsAccount = false, connected = false))
    }

    @Test
    fun `a meta-service is on unless disabled`() {
        assertTrue(on(id = "lastfm", isMetaService = true, needsAccount = true, active = listOf("spotify")))
    }

    @Test
    fun `needsAccount follows requiresAuth`() {
        assertTrue(needsAccount(PluginManager.PluginInfo("a", "A", "1", requiresAuth = true)))
        assertFalse(needsAccount(PluginManager.PluginInfo("a", "A", "1")))
    }

    @Test
    fun `the recommendations off-list defaults to empty and round-trips`() = runTest {
        val s = store()
        assertEquals(emptySet(), s.getRecommendationsOffResolvers())
        s.setRecommendationsOffResolvers(setOf("spotify", "bandcamp"))
        assertEquals(setOf("spotify", "bandcamp"), s.getRecommendationsOffResolvers())
        s.setRecommendationsOffResolvers(emptySet())
        assertEquals(emptySet(), s.getRecommendationsOffResolvers())
    }

    @Test
    fun `Last_fm's config carries the API key`() = runTest {
        val cfg = metaServicePluginConfigs(store(), lastFmApiKey = "abc123")
        val lastfm = Json.parseToJsonElement(cfg.getValue("lastfm")).jsonObject
        assertEquals("abc123", lastfm["apiKey"]?.jsonPrimitive?.content)
        val lb = Json.parseToJsonElement(cfg.getValue("listenbrainz")).jsonObject
        assertNull(lb["apiKey"])
    }

    @Test
    fun `not when there is none`() = runTest {
        for (key in listOf("", "  ")) {
            val cfg = metaServicePluginConfigs(store(), lastFmApiKey = key)
            val lastfm = Json.parseToJsonElement(cfg.getValue("lastfm")).jsonObject
            assertNull(lastfm["apiKey"])
        }
    }
}
