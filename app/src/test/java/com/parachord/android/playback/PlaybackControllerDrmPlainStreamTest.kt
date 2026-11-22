package com.parachord.android.playback

import androidx.media3.common.MediaItem
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.drm.PluginDrmPlaybackException
import com.parachord.android.playback.handlers.PlaybackAction
import com.parachord.android.playback.handlers.PluginStreamHandler
import com.parachord.shared.playback.PLAY_START_FAILED_MESSAGE
import com.parachord.shared.playback.drm.PluginDrmError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A plugin DRM play that doesn't start plays the plugin's plain stream
 * (`getStreamUrl`) on the same source (desktop's drmFallback, parachord#1613),
 * never after a plan refusal; with no plain stream, the DRM failure's own
 * message stands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerDrmPlainStreamTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var f: PlaybackControllerFixture
    private val stream = mockk<PluginStreamHandler>(relaxed = true)
    private val item = mockk<MediaItem>(relaxed = true)

    private val purchase = TrackEntity(id = "p1", title = "Bought", artist = "Band", resolver = "amazonmusic")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        f = PlaybackControllerFixture()
        every { f.router.getPluginStreamHandler() } returns stream
        every { stream.hasDrmPlainStream("amazonmusic") } returns true
        coEvery { f.router.plainStreamAction(any(), any()) } returns PlaybackAction.ExoPlayerItem(item)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun run() = dispatcher.scheduler.runCurrent()

    /** The first route's manifest fails; any later route (another source) finds nothing. */
    private fun manifestFails(reason: String) {
        var routes = 0
        coEvery { f.router.route(any()) } answers {
            if (routes++ == 0) throw PluginDrmPlaybackException("amazonmusic", PluginDrmError(reason = reason)) else null
        }
    }

    @Test
    fun `a DRM start that failed plays the plain stream`() {
        manifestFails("load-failed")
        coEvery { stream.plainStreamItem(any(), "amazonmusic") } returns item

        f.controller.playTrack(purchase)
        run()

        coVerify(exactly = 1) { stream.plainStreamItem(any(), "amazonmusic") }
        coVerify(exactly = 1) { f.router.plainStreamAction(item, true) }
    }

    @Test
    fun `no plain stream keeps the DRM failure's message`() {
        manifestFails("load-failed")
        coEvery { stream.plainStreamItem(any(), "amazonmusic") } returns null

        f.controller.playTrack(purchase)
        run()

        coVerify(exactly = 1) { stream.plainStreamItem(any(), "amazonmusic") }
        coVerify(exactly = 0) { f.router.plainStreamAction(any(), any()) }
        assertEquals(PLAY_START_FAILED_MESSAGE, f.stateHolder.state.value.playbackError)
    }

    @Test
    fun `a plan refusal never asks for the plain stream`() {
        manifestFails("plan-ineligible")

        f.controller.playTrack(purchase)
        run()

        coVerify(exactly = 0) { stream.plainStreamItem(any(), any()) }
    }

    @Test
    fun `a plugin without getStreamUrl fails as before`() {
        every { stream.hasDrmPlainStream("amazonmusic") } returns false
        manifestFails("load-failed")

        f.controller.playTrack(purchase)
        run()

        coVerify(exactly = 0) { stream.plainStreamItem(any(), any()) }
        assertEquals(PLAY_START_FAILED_MESSAGE, f.stateHolder.state.value.playbackError)
    }
}
