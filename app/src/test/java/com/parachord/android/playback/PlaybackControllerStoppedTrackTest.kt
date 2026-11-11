package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.handlers.ExternalPlaybackHandler
import com.parachord.android.playback.handlers.PlaybackAction
import com.parachord.shared.playback.AdvanceTrigger
import com.parachord.shared.playback.LoadOnPlay
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A track nothing holds (restored, idle-stopped, failed) and a track the
 * queue is skipping past: Previous, Play, a seek, the play-start foreground
 * and the repeat-all "played" flag, on the real [PlaybackController].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerStoppedTrackTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var f: PlaybackControllerFixture
    private val controller get() = f.controller
    private val state get() = f.stateHolder.state.value

    private fun track(id: String) = TrackEntity(id = id, title = id.uppercase(), artist = "X", resolver = "localfiles", sourceUrl = "file:///$id.mp3")

    private val a = track("a")
    private val b = track("b")
    private val c = track("c")
    /** No source: the router has nothing for it. */
    private val n = track("n")

    private val handler = mockk<ExternalPlaybackHandler>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        f = PlaybackControllerFixture()
        coEvery { f.router.route(any()) } answers {
            if (firstArg<TrackEntity>().id == "n") null else PlaybackAction.ExternalPlayback(handler)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun run() = dispatcher.scheduler.runCurrent()
    private fun pastTheGap() {
        dispatcher.scheduler.advanceTimeBy(PlaybackController.AUTO_SKIP_DELAY_MS + 100)
        run()
    }
    private fun routed(id: String) = coVerify { f.router.route(match { it.id == id }) }
    private fun routedTimes(id: String, times: Int) = coVerify(exactly = times) { f.router.route(match { it.id == id }) }

    // ── A. Previous on a stopped track ─────────────────────────────────

    @Test
    fun `previous on a restored track past three seconds restarts it without playing`() {
        controller.restoreQueuePaused(listOf(b, c), null)
        controller.seekTo(120_000)
        assertEquals(120_000L, controller.pendingLoad?.positionMs)

        controller.skipPrevious()
        run()

        assertEquals("b", state.currentTrack?.id)
        assertFalse(state.isPlaying)
        assertEquals(0L, state.position)
        assertEquals(LoadOnPlay.Why.RESTORED, controller.pendingLoad?.why)
        assertEquals(0L, controller.pendingLoad?.positionMs)
        assertFalse(controller.isPlayStarting)
        routedTimes("b", 0)
        // Nothing holds it, so nothing is sought.
        coVerify(exactly = 0) { handler.seekTo(any()) }
        // The next launch doesn't bring 2:00 back.
        verify { f.queuePersistence.savePositionNow("b", 0L, any()) }
    }

    @Test
    fun `previous on a restored track at the top goes to the previous track`() {
        controller.restoreQueuePaused(listOf(b, c), null)
        f.queueManager.pushToHistory(a)

        controller.skipPrevious()
        run()

        routed("a")
        assertEquals("a", state.currentTrack?.id)
        assertNull(controller.pendingLoad)
    }

    // ── B. Play in the auto-skip gap ───────────────────────────────────

    @Test
    fun `play during the auto skip gap does nothing`() {
        controller.playQueue(listOf(a, n, b))
        run()
        assertTrue(state.isPlaying)
        // The external handler is gone once the no-source track stops it.
        every { f.router.activeExternalHandler } returns null

        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        run()
        assertEquals("n", state.currentTrack?.id)

        controller.togglePlayPause()
        assertTrue(controller.onSessionPlayPause(play = true))
        run()
        routedTimes("n", 1)

        pastTheGap()
        routedTimes("n", 1)
        routedTimes("b", 1)
        assertEquals("b", state.currentTrack?.id)
    }

    @Test
    fun `next during the auto skip gap still moves on once`() {
        controller.playQueue(listOf(a, n, b, c))
        run()
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        run()

        controller.skipNext()
        run()
        pastTheGap()

        routedTimes("b", 1)
        routedTimes("c", 0)
        assertEquals("b", state.currentTrack?.id)
    }

    @Test
    fun `previous during the auto skip gap goes to the track before even with a stale external position`() {
        controller.playQueue(listOf(a, n, b))
        run()
        every { f.router.activeExternalHandler } returns null
        // A stale Spotify position, which would read as "restart the track".
        every { f.router.getSpotifyHandler().getPosition() } returns 90_000L
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        run()
        assertEquals("n", state.currentTrack?.id)

        controller.skipPrevious()
        run()
        pastTheGap()

        routedTimes("a", 2)
        assertEquals("a", state.currentTrack?.id)
        routedTimes("b", 0)
    }

    @Test
    fun `previous during the gap with nothing before the track still lets the skip run`() {
        controller.playQueue(listOf(a, n, b))
        run()
        every { f.router.activeExternalHandler } returns null
        every { f.router.getSpotifyHandler().getPosition() } returns 90_000L
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        run()
        // n is first in its queue: nothing before it.
        f.queueManager.setQueue(listOf(n, b), 0, null, false)
        assertFalse(f.queueManager.hasPrevious)

        controller.skipPrevious()
        run()
        assertEquals("n", state.currentTrack?.id)
        pastTheGap()

        routedTimes("n", 1)
        routedTimes("b", 1)
        assertEquals("b", state.currentTrack?.id)
    }

    @Test
    fun `a tapped play still resolving after the gap is not skipped past`() {
        controller.playQueue(listOf(a, n, b))
        run()
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        run()
        val gate = CompletableDeferred<Unit>()
        coEvery { f.router.route(match { it.id == "c" }) } coAnswers {
            gate.await()
            PlaybackAction.ExternalPlayback(handler)
        }

        controller.playTrack(c)
        run()
        pastTheGap()
        gate.complete(Unit)
        run()

        routedTimes("c", 1)
        routedTimes("b", 0)
        assertEquals("c", state.currentTrack?.id)
    }

    // ── C. The play-start foreground across an auto-skip ───────────────

    @Test
    fun `a foreground taken for a skipped track is kept for the next one`() {
        // A local track was playing (nothing external); Next from the car,
        // with no Parachord screen showing, promotes as the play starts.
        f.queueManager.setQueue(listOf(a, n, b), 0, null, false)
        f.stateHolder.update { copy(currentTrack = a, isPlaying = true) }

        controller.skipNext()
        run()
        assertEquals("n", state.currentTrack?.id)
        assertTrue("released before the skip", controller.holdsForegroundForPlay)

        pastTheGap()
        // b's route (external playback) took it over.
        assertEquals("b", state.currentTrack?.id)
        assertFalse(controller.holdsForegroundForPlay)
    }

    @Test
    fun `a foreground kept for a skip is released at the end of the queue`() {
        f.queueManager.setQueue(listOf(a, n), 0, null, false)
        f.stateHolder.update { copy(currentTrack = a, isPlaying = true) }

        controller.skipNext()
        run()
        assertTrue(controller.holdsForegroundForPlay)

        pastTheGap()
        assertNull(state.currentTrack)
        assertFalse(controller.holdsForegroundForPlay)
    }

    @Test
    fun `a foreground taken for a track playback stops on is released`() {
        f.queueManager.setQueue(listOf(a, b), 0, null, false)
        f.stateHolder.update { copy(currentTrack = a, isPlaying = true) }
        // A tap: not advancing, so playback stops on it.
        controller.playTrack(n)
        run()
        assertEquals("n", state.currentTrack?.id)
        assertFalse(controller.holdsForegroundForPlay)
    }

    // ── D. playedSinceWrap ─────────────────────────────────────────────

    @Test
    fun `a play that finishes after a new queue does not mark the new queue as played`() {
        val gate = CompletableDeferred<Unit>()
        coEvery { handler.play(match { it.id == "a" }) } coAnswers { gate.await() }
        controller.playTrack(a)
        run()
        assertTrue(controller.isPlayStarting)

        // A new queue while a's play is still starting.
        controller.playQueue(listOf(n))
        gate.complete(Unit)
        run()

        routed("n")
        assertFalse(controller.queuePlayedSinceWrap)
    }

    @Test
    fun `a play that starts marks its own queue as played`() {
        controller.playQueue(listOf(a, b))
        run()
        assertTrue(controller.queuePlayedSinceWrap)
    }

    // ── E. A track that failed and stays on screen (#607) ──────────────

    @Test
    fun `a failed track leaves external playback and a seek moves where Play starts`() {
        controller.playQueue(listOf(a, b))
        run()
        // A tap on a track nothing can play: playback stops on it.
        controller.playTrack(n)
        run()
        assertEquals("n", state.currentTrack?.id)
        // The session's seek comes to the controller, which moves the slot.
        assertTrue(controller.ownsSessionSeek)
        assertEquals(LoadOnPlay.Why.FAILED, controller.pendingLoad?.why)

        controller.seekTo(30_000)
        run()

        assertEquals(30_000L, controller.pendingLoad?.positionMs)
        assertEquals(30_000L, state.position)
        assertFalse(state.isPlaying)
        coVerify(exactly = 0) { handler.seekTo(any()) }
        routedTimes("n", 1)
    }
}
