package com.parachord.android.playback.handlers

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.android.resolver.trackKey
import com.parachord.shared.playback.BandcampEmbedResolver
import com.parachord.shared.resolver.ResolvedSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** #513: Bandcamp plays in its embed, and a track it won't start is a failed source. */
class BandcampEmbedHandlerTest {

    private val page = "https://swapmeetband.bandcamp.com/track/i-know"
    private val embedUrl = "https://bandcamp.com/EmbeddedPlayer/track=123/"

    private lateinit var bridge: BandcampEmbedBridge
    private lateinit var resolver: BandcampEmbedResolver
    private lateinit var cache: TrackResolverCache
    private lateinit var handler: BandcampPlaybackHandler
    private val sources = MutableStateFlow<Map<String, List<ResolvedSource>>>(emptyMap())

    private fun track() = TrackEntity(id = "1", title = "I Know!", artist = "Swapmeet", resolver = "bandcamp", sourceUrl = page)

    @Before
    fun setup() {
        bridge = mockk(relaxed = true)
        resolver = mockk()
        cache = mockk(relaxed = true)
        every { cache.trackSources } returns sources
        coEvery { resolver.embedUrl(any(), any()) } returns embedUrl
        coEvery { bridge.load(any(), any()) } returns BandcampEmbedBridge.LoadResult.Started
        handler = BandcampPlaybackHandler(bridge, resolver, cache)
    }

    @Test
    fun `handles only Bandcamp tracks and needs no sign-in`() {
        assertTrue(handler.canHandle(track()))
        assertFalse(handler.canHandle(track().copy(resolver = "soundcloud")))
        assertTrue(handler.isConnected)
    }

    @Test
    fun `plays by the id the resolved source carries`() = runTest {
        sources.value = mapOf(
            trackKey("I Know!", "Swapmeet") to listOf(
                ResolvedSource(url = "x", sourceType = "spotify", resolver = "spotify"),
                ResolvedSource(url = page, sourceType = "bandcamp", resolver = "bandcamp", bandcampTrackId = "123"),
            ),
        )
        handler.volumeFraction = 0.7f
        handler.play(track())
        coVerify { resolver.embedUrl("123", page) }
        coVerify { bridge.load(embedUrl, 0.7f) }
    }

    @Test
    fun `with no resolved source the page is what's left to look up`() = runTest {
        handler.play(track())
        coVerify { resolver.embedUrl(null, page) }
    }

    @Test
    fun `no embed to be had is a refusal, with the page to open, and the browser isn't opened`() = runTest {
        coEvery { resolver.embedUrl(any(), any()) } returns null
        assertRefused(page, "unplayable")
        coVerify(exactly = 0) { bridge.load(any(), any()) }
    }

    @Test
    fun `a track the embed won't start is a refusal`() = runTest {
        coEvery { bridge.load(any(), any()) } returns BandcampEmbedBridge.LoadResult.Refused("unplayable")
        assertRefused(page, "unplayable")
    }

    @Test
    fun `a load another play superseded is not the track's failure`() = runTest {
        coEvery { bridge.load(any(), any()) } returns BandcampEmbedBridge.LoadResult.Refused("superseded")
        handler.play(track())
    }

    private suspend fun assertRefused(pageUrl: String, reason: String) {
        try {
            handler.play(track())
            fail("expected a refusal")
        } catch (e: EmbedPlaybackRefusedException) {
            assertEquals("Bandcamp", e.serviceName)
            assertEquals(pageUrl, e.pageUrl)
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun `script results are unwrapped, and what isn't a string is nothing`() {
        assertEquals("""{"t":1}""", BandcampEmbedBridge.unwrap(""""{\"t\":1}""""))
        assertNull(BandcampEmbedBridge.unwrap(null))
        assertNull(BandcampEmbedBridge.unwrap("null"))
        assertNull(BandcampEmbedBridge.unwrap("true"))
        assertNull(BandcampEmbedBridge.unwrap("{not json"))
    }

    @Test
    fun `Android and iOS carry the same control script, with desktop's surface`() {
        // Gradle runs unit tests from the app module's directory.
        val android = java.io.File("src/main/assets/js/bandcamp-embed-control.js")
        val ios = java.io.File("../iosApp/Parachord/Resources/bandcamp-embed-control.js")
        assertTrue("missing $android", android.isFile)
        assertTrue("missing $ios", ios.isFile)
        assertEquals("the control script differs between the apps", android.readText(), ios.readText())
        for (fn in listOf("bcStart", "bcState", "bcPlay", "bcPause", "bcSeek", "bcVolume")) {
            assertTrue("no $fn", android.readText().contains("function $fn("))
        }
    }
}
