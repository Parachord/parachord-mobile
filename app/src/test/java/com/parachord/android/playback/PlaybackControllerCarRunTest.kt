package com.parachord.android.playback

import android.app.Activity
import com.parachord.android.app.CurrentActivityHolder
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.handlers.EmbedPlaybackRefusedException
import com.parachord.android.playback.handlers.ExternalPlaybackHandler
import com.parachord.android.playback.handlers.PlaybackAction
import com.parachord.shared.car.CarMessages
import com.parachord.shared.playback.AdvanceTrigger
import com.parachord.shared.playback.LoadOnPlay
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Android Auto stops a queue after three unplayable tracks in a row while no
 * Parachord screen is showing (the shared `CarUnplayableRun`, as CarPlay
 * does), on the real [PlaybackController].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerCarRunTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var f: PlaybackControllerFixture
    private val controller get() = f.controller
    private val state get() = f.stateHolder.state.value

    /** "n…" tracks have no source; "e…" tracks are refused by their embed; others play. */
    private fun track(id: String) = TrackEntity(id = id, title = id.uppercase(), artist = "X", resolver = "soundcloud", sourceUrl = "https://example.com/$id")

    private val a = track("a")
    private val b = track("b")
    private val c = track("c")
    private val d = track("d")
    private val n1 = track("n1")
    private val n2 = track("n2")
    private val n3 = track("n3")
    private val n4 = track("n4")
    private val n5 = track("n5")
    private val n6 = track("n6")
    private val e1 = track("e1")
    private val e2 = track("e2")

    private val handler = mockk<ExternalPlaybackHandler>(relaxed = true)
    private val refusing = mockk<ExternalPlaybackHandler>(relaxed = true)
    private val screen = mockk<Activity>(relaxed = true)
    private var carController = Any()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        f = PlaybackControllerFixture()
        coEvery { f.router.route(any()) } answers {
            val id = firstArg<TrackEntity>().id
            when {
                id.startsWith("n") -> null
                id.startsWith("e") -> PlaybackAction.ExternalPlayback(refusing)
                else -> PlaybackAction.ExternalPlayback(handler)
            }
        }
        coEvery { refusing.play(any()) } throws EmbedPlaybackRefusedException("SoundCloud", "https://soundcloud.com/x", "blocked")
        // No other source for a refused track.
        coEvery { f.resolverScoring.selectBest(any(), any()) } returns null
        // No Parachord screen showing.
        CurrentActivityHolder.onActivityPaused(screen)
        // Android Auto's own state can't be read (as on an emulator).
        controller.carConnectionState = { stateAsked++; carState }
    }

    /** What Android Auto's car-connection provider answers (null: unreadable). */
    private var carState: Int? = null
    private var stateAsked = 0

    @After
    fun tearDown() {
        CurrentActivityHolder.onActivityPaused(screen)
        Dispatchers.resetMain()
    }

    private fun run() = dispatcher.scheduler.runCurrent()
    private fun pastTheGap() {
        dispatcher.scheduler.advanceTimeBy(PlaybackController.AUTO_SKIP_DELAY_MS + 100)
        run()
    }
    private fun pastAllGaps() = repeat(8) { pastTheGap() }
    private fun routedTimes(id: String, times: Int) = coVerify(exactly = times) { f.router.route(match { it.id == id }) }

    private fun carConnects() {
        carController = Any()
        controller.carConnections.connected(CarConnections.ANDROID_AUTO_PACKAGE, carController)
    }
    private fun carDisconnects() = controller.carConnections.disconnected(carController)

    /** Plays [tracks] from the first, then lets it end so the queue moves on. */
    private fun playThenAdvance(vararg tracks: TrackEntity) {
        controller.playQueue(tracks.toList())
        run()
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        run()
        pastAllGaps()
    }

    private fun assertStoppedOn(id: String) {
        assertEquals(id, state.currentTrack?.id)
        assertFalse(state.isPlaying)
        assertEquals(CarMessages.OPEN_PARACHORD_ON_PHONE, state.playbackError)
        assertTrue(controller.needsLoadOnPlay)
        assertEquals(LoadOnPlay.Why.FAILED, controller.pendingLoad?.why)
    }

    @Test
    fun `the third unplayable track in a row stops in the car with the phone app not in front`() {
        carConnects()
        playThenAdvance(a, n1, n2, n3, b)

        routedTimes("n3", 1)
        routedTimes("b", 0)
        assertStoppedOn("n3")
    }

    @Test
    fun `with the phone app in front the queue keeps skipping`() {
        carConnects()
        CurrentActivityHolder.onActivityResumed(screen)
        playThenAdvance(a, n1, n2, n3, b)

        routedTimes("b", 1)
        assertEquals("b", state.currentTrack?.id)
    }

    @Test
    fun `a track that plays starts the run over`() {
        carConnects()
        playThenAdvance(a, n1, n2, c, n3, n4, d)
        // c played, then its end moves on: n3, n4, d.
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        run()
        pastAllGaps()

        routedTimes("d", 1)
        assertEquals("d", state.currentTrack?.id)
    }

    @Test
    fun `a tap starts the run over`() {
        carConnects()
        controller.playQueue(listOf(a, n1, n2, b))
        run()
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        run()
        // n1 failed and its skip ran; n2 failed and its skip is waiting.
        pastTheGap()
        assertEquals("n2", state.currentTrack?.id)

        // A tap: a no-source track first, so only the reset can start the run over.
        controller.playQueue(listOf(n3, n4, n5, c))
        run()
        assertEquals("n3", state.currentTrack?.id)
        controller.skipNext()
        run()
        pastAllGaps()

        routedTimes("c", 1)
        assertEquals("c", state.currentTrack?.id)
    }

    @Test
    fun `not in the car nothing stops`() {
        playThenAdvance(a, n1, n2, n3, n4, b)

        routedTimes("b", 1)
        assertEquals("b", state.currentTrack?.id)
    }

    @Test
    fun `an embed refused track counts toward the run`() {
        val notices = mutableListOf<PlaybackNotice>()
        val collector = CoroutineScope(dispatcher)
        collector.launch { controller.notices.collect { notices += it } }
        carConnects()
        playThenAdvance(a, e1, n1, e2, b)

        routedTimes("b", 0)
        assertStoppedOn("e2")
        // The phone's "Open in SoundCloud" snackbar isn't shown for the car's stop.
        assertTrue(notices.isEmpty())
        collector.cancel()
    }

    @Test
    fun `a new car connection starts the run over`() {
        carConnects()
        controller.playQueue(listOf(a, n1, n2, n3, n4, b))
        run()
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        run()
        pastTheGap()
        // n2 failed (the second) and its skip is waiting.
        assertEquals("n2", state.currentTrack?.id)
        carDisconnects()
        carConnects()
        pastAllGaps()

        routedTimes("b", 1)
        assertEquals("b", state.currentTrack?.id)
    }

    @Test
    fun `after the stop Play retries the track and the run starts over`() {
        carConnects()
        playThenAdvance(a, n1, n2, n3, n4, n5, n6, b)
        assertStoppedOn("n3")

        controller.togglePlayPause()
        run()
        pastAllGaps()
        routedTimes("n3", 2)
        // Play's own play isn't the queue moving on: it stops on the track, for Play again.
        assertEquals("n3", state.currentTrack?.id)
        assertTrue(controller.needsLoadOnPlay)

        // Three more failures are needed before the next stop.
        controller.skipNext()
        run()
        pastAllGaps()
        routedTimes("n4", 1)
        routedTimes("n5", 1)
        routedTimes("b", 0)
        assertStoppedOn("n6")
    }

    @Test
    fun `the stop does not keep the foreground for a skip`() {
        carConnects()
        // Nothing external plays first, so the user's Next takes the
        // play-start foreground and the skips keep it for the next track.
        controller.playQueue(listOf(n1, n2, n3, n4, b))
        run()
        controller.skipNext()
        run()
        assertTrue(controller.holdsForegroundForPlay)
        pastAllGaps()

        assertStoppedOn("n4")
        assertFalse(controller.holdsForegroundForPlay)
        routedTimes("b", 0)
        // No skip waits on it: Play loads it again.
        controller.togglePlayPause()
        run()
        routedTimes("n4", 2)
    }

    // ── Android Auto's own car-connection state ─────────────────────

    @Test
    fun `after the car is gone the queue skips again`() {
        // Tracked still (no disconnect reported), but Android Auto says no car.
        carConnects()
        carState = 0
        playThenAdvance(a, n1, n2, n3, n4, b)

        routedTimes("b", 1)
        assertEquals("b", state.currentTrack?.id)
        assertNotEquals(CarMessages.OPEN_PARACHORD_ON_PHONE, state.playbackError)
        assertNull(controller.carConnections.current)
    }

    @Test
    fun `a provider that cannot be read falls back to the tracked connection`() {
        carConnects()
        carState = null
        playThenAdvance(a, n1, n2, n3, b)

        assertTrue(stateAsked > 0)
        routedTimes("b", 0)
        assertStoppedOn("n3")
    }

    @Test
    fun `android auto projecting keeps the stop`() {
        carConnects()
        carState = 2
        playThenAdvance(a, n1, n2, n3, b)

        routedTimes("b", 0)
        assertStoppedOn("n3")
    }

    @Test
    fun `the provider is not asked when no car was ever connected`() {
        playThenAdvance(a, n1, n2, n3, b)

        assertEquals(0, stateAsked)
        routedTimes("b", 1)
    }

    // ── What counts ──────────────────────────────────────────────────

    @Test
    fun `a spinoff pool track that auto skips counts toward the run`() {
        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
        try {
            carConnects()
            controller.playQueue(listOf(a, b))
            run()
            val scope = CoroutineScope(dispatcher)
            scope.launch {
                controller.startSpinoffFromPool(listOf(n1, n2, n3, c), "Spinoff", "X", "A", kickStartFirstTrack = false)
            }
            run()
            assertTrue(state.spinoffMode)
            controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
            run()
            pastAllGaps()

            routedTimes("c", 0)
            routedTimes("b", 0)
            assertStoppedOn("n3")
            scope.cancel()
        } finally {
            unmockkStatic(Toast::class)
        }
    }

    @Test
    fun `a track a listener drives does not count and still skips`() {
        // Listen Along's tracks: n1 and n2. Counted, n3 would be the third.
        controller.drivesTrack = { it.id == "n1" || it.id == "n2" }
        carConnects()
        playThenAdvance(a, n1, n2, n3, n4, b)

        routedTimes("n1", 1)
        routedTimes("n2", 1)
        routedTimes("b", 1)
        assertEquals("b", state.currentTrack?.id)
        assertNotEquals(CarMessages.OPEN_PARACHORD_ON_PHONE, state.playbackError)
    }
}

