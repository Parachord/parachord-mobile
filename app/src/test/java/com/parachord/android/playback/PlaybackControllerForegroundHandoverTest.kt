package com.parachord.android.playback

import android.app.Application
import android.content.Intent
import androidx.media3.common.MediaItem
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.handlers.ExternalPlaybackHandler
import com.parachord.android.playback.handlers.PlaybackAction
import com.parachord.shared.playback.AdvanceTrigger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A local play after a foreground the service holds (#614): with no Parachord
 * screen showing the controller doesn't tell the service to drop it
 * (`ACTION_EXTERNAL_PLAYBACK_STOP`) before ExoPlayer plays, whoever asked for
 * the play: the queue moving on, Listen Along's `playTrack` / `playQueue`, or
 * a person on the lock screen. With a screen showing it drops it as before.
 * Robolectric, so the intents the controller sends can be read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlaybackControllerForegroundHandoverTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var f: PlaybackControllerFixture
    private val controller get() = f.controller
    private val intents = mutableListOf<Intent>()

    private fun local(id: String) = TrackEntity(id = id, title = id.uppercase(), artist = "X", resolver = "localfiles", sourceUrl = "file:///$id.mp3")
    private fun external(id: String) = TrackEntity(id = id, title = id.uppercase(), artist = "X", resolver = "applemusic", appleMusicId = "1$id")

    private val a = local("a")
    private val b = local("b")
    private val e = external("e")
    private val handler = mockk<ExternalPlaybackHandler>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        f = PlaybackControllerFixture()
        every { f.context.packageName } returns "com.parachord.android.test"
        every { f.context.startService(any()) } answers { intents += firstArg<Intent>(); null }
        coEvery { f.router.route(any()) } answers {
            val t = firstArg<TrackEntity>()
            if (t.resolver == "localfiles") PlaybackAction.ExoPlayerItem(MediaItem.fromUri(t.sourceUrl!!))
            else PlaybackAction.ExternalPlayback(handler)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun run() = dispatcher.scheduler.runCurrent()
    private fun stops() = intents.count { it.action == PlaybackService.ACTION_EXTERNAL_PLAYBACK_STOP }

    /** A local track [current] is playing and ends with the foreground kept. */
    private fun endKept(current: TrackEntity, queue: List<TrackEntity>) {
        f.queueManager.setQueue(queue, queue.indexOf(current), null, false)
        f.stateHolder.update { copy(currentTrack = current, isPlaying = true) }
        assertTrue(controller.keepForegroundAtLocalTrackEnd())
    }

    @Test
    fun `the queue moving on to a local track keeps the held foreground`() {
        endKept(a, listOf(a, b))
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        run()
        assertEquals("b", f.stateHolder.state.value.currentTrack?.id)
        assertEquals(0, stops())
        assertFalse("ExoPlayer owns it from here", controller.holdsForegroundForPlay)
    }

    @Test
    fun `Listen Along playing a local track after a held end keeps the foreground`() {
        // The session handles the end (waits), then plays the friend's song,
        // which the user has as a local file.
        controller.drivesTrack = { it.id == "a" }
        controller.onOwnTrackEnded = { true }
        endKept(a, listOf(a))
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        run()
        assertTrue(controller.foregroundHeldForListener)

        controller.playTrack(b)
        run()
        assertEquals("b", f.stateHolder.state.value.currentTrack?.id)
        assertEquals(0, stops())
    }

    @Test
    fun `the suspended queue resuming on a local first track keeps the foreground`() {
        controller.drivesTrack = { it.id == "a" }
        controller.onOwnTrackEnded = { true }
        endKept(a, listOf(a))
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        run()

        // Listen Along ends and gives the user's queue back, playing.
        controller.playQueue(listOf(b, e))
        run()
        assertEquals("b", f.stateHolder.state.value.currentTrack?.id)
        assertEquals(0, stops())
    }

    @Test
    fun `a lock screen Next onto a local track after external playback keeps the foreground`() {
        controller.playQueue(listOf(e, b))
        run()
        assertTrue(controller.isExternalPlaybackActive)
        intents.clear()

        controller.skipNext()
        run()
        assertEquals("b", f.stateHolder.state.value.currentTrack?.id)
        assertFalse(controller.isExternalPlaybackActive)
        assertEquals(0, stops())
    }

    @Test
    fun `with a Parachord screen showing a local play drops the held foreground first`() {
        endKept(a, listOf(a, b))
        val screen = mockk<android.app.Activity>(relaxed = true)
        com.parachord.android.app.CurrentActivityHolder.onActivityResumed(screen)
        try {
            controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
            run()
            assertEquals("b", f.stateHolder.state.value.currentTrack?.id)
            assertEquals(1, stops())
        } finally {
            com.parachord.android.app.CurrentActivityHolder.onActivityPaused(screen)
        }
    }
}
