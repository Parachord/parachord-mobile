package com.parachord.android.plugin

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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shared HTTP client doesn't throw on error statuses, and plugin sync
 * didn't check them, so a 404/429/5xx error page was saved over the cached
 * `.axe`. The sync also stamped its 24h timestamp even when downloads failed.
 */
class PluginSyncErrorResponseTest {
    private val manifest = """{"plugins":[{"id":"bandcamp","version":"9.9.9"}]}"""

    private fun service(pluginStatus: HttpStatusCode, fileAccess: PluginFileAccess, stamped: MutableList<Long>): PluginSyncService {
        val engine = MockEngine { req ->
            if (req.url.encodedPath.endsWith("manifest.json")) respond(manifest, HttpStatusCode.OK)
            else respond(if (pluginStatus.value < 300) "// bandcamp.axe" else "429: Too Many Requests", pluginStatus)
        }
        val manager = mockk<PluginManager>(relaxed = true) {
            every { allLoadedPlugins } returns MutableStateFlow(emptyList())
            every { compareSemver(any(), any()) } returns 1
        }
        return PluginSyncService(
            httpClient = HttpClient(engine),
            pluginManager = manager,
            fileAccess = fileAccess,
            getLastSyncTimestamp = { 0L },
            setLastSyncTimestamp = { stamped += it },
        )
    }

    @Test
    fun `an error response is not saved as the plugin and the sync retries next launch`() = runBlocking {
        val files = mockk<PluginFileAccess>(relaxed = true)
        val stamped = mutableListOf<Long>()

        val result = service(HttpStatusCode.TooManyRequests, files, stamped).sync()

        assertEquals(listOf("bandcamp"), result.failed)
        verify(exactly = 0) { files.writeCachedPlugin(any(), any()) }
        assertNull("a failed sync must not start the 24h wait", stamped.firstOrNull())
    }

    @Test
    fun `a good download is saved and stamps the sync time`() = runBlocking {
        val files = mockk<PluginFileAccess>(relaxed = true)
        val stamped = mutableListOf<Long>()

        val result = service(HttpStatusCode.OK, files, stamped).sync()

        assertEquals(listOf("bandcamp"), result.added)
        verify(exactly = 1) { files.writeCachedPlugin("bandcamp.axe", "// bandcamp.axe") }
        assertEquals(1, stamped.size)
    }
}
