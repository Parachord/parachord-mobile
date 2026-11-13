package com.parachord.android.playback.handlers

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.resolver.ResolvedSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PluginStreamHandler]: the host side of `capabilities.html5Stream` (plan
 * parachord-mobile#664) — the credentialed stream URL built at play time and
 * never persisted, the placeholder-URL/cache track id, and the
 * settings-shaped failures.
 */
class PluginStreamHandlerTest {

    private lateinit var pluginManager: PluginManager
    private lateinit var trackResolverCache: TrackResolverCache
    private lateinit var handler: PluginStreamHandler

    private val plugin = PluginManager.PluginInfo(
        id = "subsonic", name = "Subsonic", version = "0.3.3",
        capabilities = mapOf("resolve" to true, "stream" to true, "html5Stream" to true),
        requiresAuth = true,
    )

    @Before
    fun setup() {
        pluginManager = mockk(relaxed = true)
        trackResolverCache = mockk(relaxed = true)
        every { pluginManager.plugins } returns MutableStateFlow(listOf(plugin))
        every { pluginManager.html5StreamPlugin("subsonic") } returns plugin
        every { pluginManager.html5StreamPlugin(null) } returns null
        every { trackResolverCache.trackSources } returns MutableStateFlow(emptyMap())
        coEvery { trackResolverCache.awaitLoaded() } returns Unit
        handler = PluginStreamHandler(pluginManager, trackResolverCache)
    }

    private fun track(sourceUrl: String? = "subsonic:track:mf3") = TrackEntity(
        id = "t1", title = "Pyramid Song", artist = "Radiohead", album = "Amnesiac",
        resolver = "subsonic", sourceUrl = sourceUrl, duration = 185000,
    )

    @Test
    fun `routes by capability, never by resolver id`() {
        assertTrue(handler.canHandle(track()))
        every { pluginManager.html5StreamPlugin("subsonic") } returns null
        assertTrue(!handler.canHandle(track()))
    }

    @Test
    fun `builds the MediaItem from a getStreamUrl call at play time`() = runTest {
        var askedTrackJson: String? = null
        coEvery { pluginManager.getStreamUrl("subsonic", any()) } answers {
            askedTrackJson = secondArg()
            "https://nav.example/rest/stream.view?id=mf3&u=test&t=tok&s=salt"
        }
        val item = handler.createMediaItem(track())
        assertEquals("t1", item.mediaId)
        // The track JSON handed to the plugin: its own result shape, with the
        // `<resolverId>Id` key and the names for its matchers.
        val trackJson = askedTrackJson
        assertTrue("track JSON: $trackJson", trackJson!!.contains(""""subsonicId":"mf3""""))
        assertTrue(trackJson.contains(""""title":"Pyramid Song""""))
        assertTrue(trackJson.contains(""""artist":"Radiohead""""))
        assertTrue(trackJson.contains(""""duration":185"""))
    }

    @Test
    fun `a track id missing from the placeholder and the cache is a settings failure`() = runTest {
        coEvery { pluginManager.getStreamUrl(any(), any()) } returns "https://nav.example/s"
        try {
            handler.createMediaItem(track(sourceUrl = null))
            throw AssertionError("expected PluginStreamStartException")
        } catch (e: PluginStreamStartException) {
            assertEquals("Check Subsonic's settings to play this.", e.userMessage)
        }
    }

    @Test
    fun `no URL from the plugin is a settings failure, and so is an http URL`() = runTest {
        coEvery { pluginManager.getStreamUrl("subsonic", any()) } returns null
        try {
            handler.createMediaItem(track())
            throw AssertionError("expected PluginStreamStartException")
        } catch (e: PluginStreamStartException) {
            assertEquals("Check Subsonic's settings to play this.", e.userMessage)
        }
        coEvery { pluginManager.getStreamUrl("subsonic", any()) } returns "http://192.168.0.13:4533/rest/stream.view?id=mf3"
        try {
            handler.createMediaItem(track())
            throw AssertionError("expected PluginStreamStartException")
        } catch (_: PluginStreamStartException) {
            // https-only while the app denies cleartext (PluginStreamUrl.isPlayable)
        }
    }

    @Test
    fun `a failed getStreamUrl call is a settings failure carrying the cause`() = runTest {
        coEvery { pluginManager.getStreamUrl("subsonic", any()) } throws IllegalStateException("Subsonic is unreachable")
        try {
            handler.createMediaItem(track())
            throw AssertionError("expected PluginStreamStartException")
        } catch (e: PluginStreamStartException) {
            assertEquals("Check Subsonic's settings to play this.", e.userMessage)
            assertEquals("Subsonic is unreachable", e.cause?.message)
        }
    }

    @Test
    fun `the plugin track id falls back to the cached source when the placeholder URL is gone`() = runTest {
        every { trackResolverCache.trackSources } returns MutableStateFlow(
            mapOf(
                "pyramid song|radiohead" to listOf(
                    ResolvedSource(
                        url = "subsonic:track:mf3", sourceType = "subsonic", resolver = "subsonic",
                        pluginTrackId = "mf3",
                    ),
                ),
            ),
        )
        coEvery { pluginManager.getStreamUrl("subsonic", any()) } returns "https://nav.example/rest/stream.view?id=mf3"
        val item = handler.createMediaItem(track(sourceUrl = null))
        assertEquals("t1", item.mediaId)
    }
}
