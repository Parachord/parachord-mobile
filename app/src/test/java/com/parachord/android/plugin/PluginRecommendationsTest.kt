package com.parachord.android.plugin

import com.parachord.shared.plugin.JsRuntime
import com.parachord.shared.plugin.PluginFileAccess
import com.parachord.shared.plugin.PluginManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #606 (desktop parachord#1309): Recommendations come from the plugins'
 * `getRecommendations`, through the resolver-loader. The bundled loader and
 * plugins on both apps must carry it, and a plugin that throws must throw
 * here too, so a failed request is never read as a miss.
 */
class PluginRecommendationsTest {

    // Gradle runs unit tests from the app module's directory.
    private val androidJs = File("src/main/assets/js")
    private val iosResources = File("../iosApp/Parachord/Resources")
    private val androidPlugins = File("src/main/assets/plugins")
    private val iosPlugins = File("../iosApp/Parachord/Resources/axe-plugins")

    @Test
    fun `Android and iOS carry the same resolver-loader, with the recommendation calls`() {
        val android = File(androidJs, "resolver-loader.js").readText()
        assertEquals(android, File(iosResources, "resolver-loader.js").readText())
        for (method in listOf("getRecommendationResolvers()", "async getRecommendations(resolverId", "async expandRecommendationSection(resolverId")) {
            assertTrue("resolver-loader.js lacks $method", android.contains(method))
        }
    }

    @Test
    fun `the bundled Last_fm and ListenBrainz plugins recommend, on both apps`() {
        for (id in listOf("lastfm", "listenbrainz")) {
            val android = File(androidPlugins, "$id.axe").readText()
            assertEquals("$id.axe differs between the apps", android, File(iosPlugins, "$id.axe").readText())
            val axe = Json.parseToJsonElement(android).jsonObject
            assertTrue("$id declares the capability", axe.getValue("capabilities").jsonObject.getValue("recommendations").jsonPrimitive.boolean)
            assertTrue("$id implements getRecommendations", axe.getValue("implementation").jsonObject.containsKey("getRecommendations"))
        }
    }

    /**
     * Stands in for the WebView: plugins "load", and an async call's result
     * key answers with [answer], JSON-quoted as `evaluateJavascript` returns
     * a string.
     */
    private class FakeRuntime(var answer: String) : JsRuntime {
        override val ready: StateFlow<Boolean> = MutableStateFlow(true)
        val setups = mutableListOf<String>()
        override suspend fun initialize() {}
        override fun teardown() {}
        override suspend fun evaluate(script: String): String? = when {
            script.trim() == "window.__lastPluginResult" -> "\"ok\""
            script.startsWith("window.__resolveResults['") -> JsonPrimitive(answer).toString()
            else -> { setups += script; null }
        }
    }

    private fun manager(runtime: JsRuntime): PluginManager {
        val files = mockk<PluginFileAccess>(relaxed = true) {
            every { listBundledPlugins() } returns listOf("bandcamp.axe", "lastfm.axe", "listenbrainz.axe")
            every { readBundledPlugin(any()) } answers { File(androidPlugins, firstArg<String>()).readText() }
            every { listCachedPlugins() } returns emptyList()
        }
        return PluginManager(files, runtime, appVersion = "0.9.10", platform = "android")
    }

    @Test
    fun `only plugins that declare the capability are providers, in name order`() = runBlocking {
        assertEquals(
            listOf("lastfm" to "Last.fm", "listenbrainz" to "ListenBrainz"),
            manager(FakeRuntime("[]")).recommendationResolvers(),
        )
    }

    @Test
    fun `a provider's sections come back as the JSON the plugin answered`() = runBlocking {
        val sections = """[{"items":[{"type":"song","name":"He said \"hi\"","artist":"Sigur Rós","duration":262}]}]"""
        val runtime = FakeRuntime(sections)

        assertEquals(sections, manager(runtime).getRecommendations("listenbrainz"))
        assertTrue(
            "asked through the loader: ${runtime.setups}",
            runtime.setups.any { it.contains("await window.__resolverLoader.getRecommendations('listenbrainz')") },
        )
    }

    @Test
    fun `a miss is an empty answer`() = runBlocking {
        assertEquals("[]", manager(FakeRuntime("[]")).getRecommendations("lastfm"))
    }

    @Test
    fun `a plugin that threw throws here, so a failed request is not a miss`() {
        val pm = manager(FakeRuntime("error:Failed to fetch Last.fm recommendations: 500"))
        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { pm.getRecommendations("lastfm") } }
        assertTrue(thrown.message.orEmpty().contains("Failed to fetch Last.fm recommendations: 500"))
    }
}
