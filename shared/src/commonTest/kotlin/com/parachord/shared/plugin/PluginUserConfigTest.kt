package com.parachord.shared.plugin

import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PluginUserConfig]: a plugin's `settings.configurable` fields — where each
 * value lives (text in the plain store, passwords in the secure one), the
 * `config` JSON the plugin receives, and the post-save refresh.
 */
class PluginUserConfigTest {

    private class FakeSecureStore : SecureTokenStore {
        val m = mutableMapOf<String, String>()
        override fun get(key: String): String? = m[key]
        override fun set(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun contains(key: String): Boolean = m.containsKey(key)
        override fun observe(key: String): Flow<String?> = flowOf(m[key])
    }

    private val serverUrlField = PluginManager.PluginConfigField("serverUrl", "text", "Server URL", description = "Base URL")
    private val usernameField = PluginManager.PluginConfigField("username", "text", "Username")
    private val passwordField = PluginManager.PluginConfigField("password", "password", "Password")

    private fun config(plugins: () -> List<PluginManager.PluginInfo>): Triple<PluginUserConfig, MapSettings, FakeSecureStore> {
        val backing = MapSettings()
        val secure = FakeSecureStore()
        return PluginUserConfig(plugins = plugins, kv = KvStore(backing), secureStore = secure) to backing to secure
    }

    private fun subsonicPlugin() = PluginManager.PluginInfo(
        id = "subsonic", name = "Subsonic", version = "0.3.3",
        configFields = listOf(serverUrlField, usernameField, passwordField),
        requiresAuth = true,
    )

    @Test
    fun `save splits text from passwords across the two stores`() = runTest {
        val (userConfig, backing, secure) = config { listOf(subsonicPlugin()) }
        userConfig.save(
            "subsonic",
            mapOf("serverUrl" to "https://nav.example", "username" to "test", "password" to "hunter2"),
        )
        // Text fields land in the plain KvStore as JSON…
        assertEquals(
            """{"serverUrl":"https://nav.example","username":"test"}""",
            backing.getString("plugin_config_subsonic"),
        )
        // …and the password only in the secure store.
        assertEquals("hunter2", secure.m[PluginUserConfig.secretKey("subsonic", "password")])
        assertTrue("password" !in backing.keys)
    }

    @Test
    fun `a blank password clears the secret, a blank text field is dropped`() = runTest {
        val (userConfig, backing, secure) = config { listOf(subsonicPlugin()) }
        userConfig.save("subsonic", mapOf("serverUrl" to "https://nav.example", "username" to "test", "password" to "hunter2"))
        userConfig.save("subsonic", mapOf("serverUrl" to "", "username" to "test", "password" to ""))
        assertEquals("""{"username":"test"}""", backing.getString("plugin_config_subsonic"))
        assertEquals(null, secure.m[PluginUserConfig.secretKey("subsonic", "password")])
    }

    @Test
    fun `the plugin's config JSON omits unset fields and hides nothing set`() = runTest {
        val (userConfig, _, secure) = config { listOf(subsonicPlugin()) }
        assertEquals("{}", userConfig.configJson("subsonic"))
        userConfig.save("subsonic", mapOf("serverUrl" to "https://nav.example", "username" to "test", "password" to "hunter2"))
        assertEquals(
            """{"serverUrl":"https://nav.example","username":"test","password":"hunter2"}""",
            userConfig.configJson("subsonic"),
        )
        // The password round-trips from the secure store.
        assertEquals("hunter2", secure.m[PluginUserConfig.secretKey("subsonic", "password")])
    }

    @Test
    fun `pluginConfigs covers only plugins with configurable fields`() = runTest {
        val plain = PluginManager.PluginInfo(id = "lastfm", name = "Last.fm", version = "1.0")
        val (userConfig, _, _) = config { listOf(subsonicPlugin(), plain) }
        userConfig.save("subsonic", mapOf("serverUrl" to "https://nav.example", "username" to "test", "password" to "hunter2"))
        assertEquals(setOf("subsonic"), userConfig.pluginConfigs().keys)
    }

    @Test
    fun `saving fires the refresh hook, unknown fields never reach a store`() = runTest {
        val saved = mutableListOf<String>()
        val backing = MapSettings()
        val userConfig = PluginUserConfig(
            plugins = { listOf(subsonicPlugin()) },
            kv = KvStore(backing),
            secureStore = FakeSecureStore(),
            onConfigSaved = { saved.add(it) },
        )
        userConfig.save("subsonic", mapOf("serverUrl" to "https://nav.example", "hackerKey" to "nope"))
        assertEquals(listOf("subsonic"), saved)
        assertEquals("""{"serverUrl":"https://nav.example"}""", backing.getString("plugin_config_subsonic"))
    }
}
