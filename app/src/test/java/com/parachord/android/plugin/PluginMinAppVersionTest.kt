package com.parachord.android.plugin

import com.parachord.shared.plugin.JsRuntime
import com.parachord.shared.plugin.PluginFileAccess
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.plugin.PluginSyncService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #484 (desktop parachord#1197): a plugin's `minAppVersion.android` keeps a
 * version this app is too old for from being downloaded or loaded.
 */
class PluginMinAppVersionTest {

    private val appVersion = "0.9.9"

    private fun axe(version: String, minAppVersion: String? = null): String {
        val floor = minAppVersion?.let { ""","minAppVersion":$it""" } ?: ""
        return """{"manifest":{"id":"bandcamp","name":"Bandcamp","version":"$version"$floor},"implementation":{}}"""
    }

    // ── Sync ──────────────────────────────────────────────────────────

    private fun sync(catalogEntry: String, axeBody: String, fetched: MutableList<String>, files: PluginFileAccess): PluginSyncService {
        val engine = MockEngine { req ->
            fetched += req.url.encodedPath
            if (req.url.encodedPath.endsWith("manifest.json")) respond("""{"plugins":[$catalogEntry]}""", HttpStatusCode.OK)
            else respond(axeBody, HttpStatusCode.OK)
        }
        val manager = mockk<PluginManager>(relaxed = true) {
            every { allLoadedPlugins } returns MutableStateFlow(listOf(PluginManager.PluginInfo("bandcamp", "Bandcamp", "1.0.0")))
            every { compareSemver(any(), any()) } returns 1
        }
        return PluginSyncService(
            httpClient = HttpClient(engine),
            pluginManager = manager,
            fileAccess = files,
            getLastSyncTimestamp = { 0L },
            setLastSyncTimestamp = {},
            appVersion = appVersion,
            platform = "android",
        )
    }

    @Test
    fun `a catalog entry this app is too old for is not downloaded`() = runBlocking {
        val files = mockk<PluginFileAccess>(relaxed = true)
        val fetched = mutableListOf<String>()

        val result = sync(
            """{"id":"bandcamp","version":"2.0.0","minAppVersion":{"android":"0.9.10"}}""",
            axe("2.0.0"), fetched, files,
        ).sync()

        assertEquals(listOf(PluginSyncService.Skipped("bandcamp", "2.0.0", "0.9.10")), result.skipped)
        assertTrue("only the catalog was fetched: $fetched", fetched.none { it.endsWith(".axe") })
        verify(exactly = 0) { files.writeCachedPlugin(any(), any()) }
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `a downloaded axe whose own floor is above this app is not saved`() = runBlocking {
        val files = mockk<PluginFileAccess>(relaxed = true)

        val result = sync(
            """{"id":"bandcamp","version":"2.0.0"}""",
            axe("2.0.0", """{"android":"0.9.10"}"""), mutableListOf(), files,
        ).sync()

        assertEquals(listOf(PluginSyncService.Skipped("bandcamp", "2.0.0", "0.9.10")), result.skipped)
        verify(exactly = 0) { files.writeCachedPlugin(any(), any()) }
        assertTrue(result.updated.isEmpty())
    }

    @Test
    fun `another platform's floor, or one this app meets, still downloads`() = runBlocking {
        val files = mockk<PluginFileAccess>(relaxed = true)
        val body = axe("2.0.0", """{"desktop":"9.0.0","ios":"9.0.0","android":"0.9.9"}""")

        val result = sync(
            """{"id":"bandcamp","version":"2.0.0","minAppVersion":{"desktop":"9.0.0","android":"0.9.9"}}""",
            body, mutableListOf(), files,
        ).sync()

        assertEquals(listOf("bandcamp"), result.updated)
        assertTrue(result.skipped.isEmpty())
        verify(exactly = 1) { files.writeCachedPlugin("bandcamp.axe", body) }
    }

    // ── Load ──────────────────────────────────────────────────────────

    private class OkRuntime : JsRuntime {
        override val ready: StateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun initialize() {}
        override suspend fun evaluate(script: String): String? = "\"ok\""
        override fun teardown() {}
    }

    private fun loadedVersion(bundled: String?, cached: String?): String? = runBlocking {
        val files = mockk<PluginFileAccess>(relaxed = true) {
            every { listBundledPlugins() } returns listOfNotNull(bundled?.let { "bandcamp.axe" })
            every { listCachedPlugins() } returns listOfNotNull(cached?.let { "bandcamp.axe" })
            bundled?.let { every { readBundledPlugin("bandcamp.axe") } returns it }
            cached?.let { every { readCachedPlugin("bandcamp.axe") } returns it }
        }
        val manager = PluginManager(files, OkRuntime(), appVersion = appVersion, platform = "android")
        manager.ensureInitialized()
        manager.allLoadedPlugins.value.singleOrNull { it.id == "bandcamp" }?.version
    }

    @Test
    fun `a cached copy this app is too old for is skipped and the bundled copy stays in use`() {
        assertEquals("1.0.0", loadedVersion(bundled = axe("1.0.0"), cached = axe("2.0.0", """{"android":"0.9.10"}""")))
    }

    @Test
    fun `a cached copy this app meets the floor of still wins`() {
        assertEquals("2.0.0", loadedVersion(bundled = axe("1.0.0"), cached = axe("2.0.0", """{"android":"0.9.9"}""")))
    }

    @Test
    fun `the bundled copy is never checked`() {
        assertEquals("1.0.0", loadedVersion(bundled = axe("1.0.0", """{"android":"9.9.9"}"""), cached = null))
    }
}
