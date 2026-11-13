package com.parachord.android.plugin

import com.parachord.shared.plugin.JsRuntime
import com.parachord.shared.plugin.PluginFileAccess
import com.parachord.shared.plugin.PluginManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * The `capabilities.html5Stream` host surface on the bundled subsonic.axe
 * (plan parachord-mobile#664): capability detection, `getStreamUrl` through the
 * unique-key poll with the config the host wrote, and the not-configured gate
 * that keeps an unset-up plugin out of resolution.
 */
class PluginStreamManagerTest {

    /** Stands in for the WebView: a plugin call's poll key answers with [answer], JSON-quoted as `evaluateJavascript` returns a string. */
    private class FakeRuntime(var answer: String, var configured: String = "true") : JsRuntime {
        val scripts = mutableListOf<String>()
        val setups = mutableListOf<String>()
        override val ready: StateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun initialize() {}
        override fun teardown() {}
        override suspend fun evaluate(script: String): String? = when {
            script.contains("r.isConfigured") -> "\"$configured\""
            script.trim() == "window.__lastPluginResult" -> "\"ok\""
            script.startsWith("window.__resolveResults['") -> "\"$answer\""
            else -> { setups += script; null }
        }
    }

    private val androidPlugins = File("src/main/assets/plugins")

    /** The bundled .axe files, the plugin set the app ships. */
    private val bundledPlugins: List<String> =
        androidPlugins.listFiles { f -> f.name.endsWith(".axe") }?.map { it.name } ?: emptyList()

    private fun manager(runtime: JsRuntime): PluginManager {
        val files = mockk<PluginFileAccess>(relaxed = true) {
            every { listBundledPlugins() } returns bundledPlugins
            every { readBundledPlugin(any()) } answers { File(androidPlugins, firstArg<String>()).readText() }
            every { listCachedPlugins() } returns emptyList()
        }
        return PluginManager(files, runtime, appVersion = "0.9.10", platform = "android")
    }

    @Test
    fun `the bundled subsonic plugin declares html5Stream and its fields`() = runBlocking {
        val pm = manager(FakeRuntime("[]"))
        pm.ensureInitialized()
        val subsonic = pm.html5StreamPlugin("subsonic")
        assertEquals("subsonic", subsonic?.id)
        assertEquals(
            listOf("serverUrl", "username", "password"),
            subsonic?.configFields?.map { it.key },
        )
        assertTrue(subsonic?.requiresAuth == true)
        // The other bundled plugins don't declare the capability.
        assertNull(pm.html5StreamPlugin("bandcamp"))
        assertNull(pm.html5StreamPlugin("soundcloud"))
    }

    @Test
    fun `getStreamUrl asks the plugin with the track JSON and its written config`() = runBlocking {
        val runtime = FakeRuntime("\"https://nav.example/rest/stream.view?id=mf3&u=test&t=tok&s=salt\"")
        val pm = manager(runtime)
        val url = pm.getStreamUrl("subsonic", """{"subsonicId":"mf3","title":"Pyramid Song"}""")
        assertEquals("https://nav.example/rest/stream.view?id=mf3&u=test&t=tok&s=salt", url)
        assertTrue(
            "asked through the loader with the plugin's config: ${runtime.setups}",
            runtime.setups.any {
                it.contains("getResolver('subsonic')") && it.contains("r.getStreamUrl(JSON.parse(atob(") && it.contains("r.config || {})")
            },
        )
    }

    @Test
    fun `a plugin that threw makes getStreamUrl throw, not answer no URL`() {
        val pm = manager(FakeRuntime("error:Subsonic is unreachable"))
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { pm.getStreamUrl("subsonic", """{"subsonicId":"mf3"}""") }
        }
        assertTrue(thrown.message.orEmpty().contains("Subsonic is unreachable"))
    }

    @Test
    fun `a not-configured plugin stays out of resolution`() = runBlocking {
        val runtime = FakeRuntime("[]", configured = "false")
        val pm = manager(runtime)
        pm.ensureInitialized()
        assertTrue(!pm.isPluginConfigured("subsonic"))
        // resolve() answers null without ever reaching the loader's resolve:
        // an unset-up plugin records no miss for any track.
        assertNull(pm.resolve("subsonic", "Radiohead", "Pyramid Song", null))
        assertTrue(
            "the plugin's resolve was never called: ${runtime.setups}",
            runtime.setups.none { it.contains("r.resolve(") },
        )
        // A plugin without the isConfigured function (bandcamp) counts as configured.
        assertTrue(pm.isPluginConfigured("bandcamp"))
    }
}
