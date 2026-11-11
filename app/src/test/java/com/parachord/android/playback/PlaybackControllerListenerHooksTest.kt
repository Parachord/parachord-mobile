package com.parachord.android.playback

import com.parachord.shared.playback.LoadOnPlay
import com.parachord.shared.playback.failedLoad
import com.parachord.shared.playback.idleTimeoutLoad
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.shared.playback.AdvanceTrigger
import com.parachord.shared.playback.QueueManager
import com.parachord.shared.playback.RepeatMode
import io.mockk.mockk
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
 * [PlaybackController]'s side of the listener hooks (Listen Along): when a
 * hook says it handled something, the controller doesn't act on it as well.
 * Nothing launched on the main dispatcher runs here, so a play the controller
 * starts shows as [PlaybackController.isPlayStarting].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerListenerHooksTest {

    private val stateHolder = PlaybackStateHolder()
    private val queueManager = QueueManager()
    private lateinit var controller: PlaybackController

    private val a = TrackEntity(id = "a", title = "A", artist = "X")
    private val b = TrackEntity(id = "b", title = "B", artist = "X")
    private val c = TrackEntity(id = "c", title = "C", artist = "X")

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        controller = PlaybackController(
            context = mockk(relaxed = true),
            stateHolder = stateHolder,
            router = mockk(relaxed = true),
            queueManager = queueManager,
            queuePersistence = mockk(relaxed = true),
            scrobbleManager = mockk(relaxed = true),
            imageEnrichment = mockk(relaxed = true),
            mbidEnrichment = mockk(relaxed = true),
            lastFmClient = mockk(relaxed = true),
            resolverManager = mockk(relaxed = true),
            resolverScoring = mockk(relaxed = true),
            trackResolverCache = mockk(relaxed = true),
            widgetUpdater = mockk(relaxed = true),
            settingsStore = mockk(relaxed = true),
            networkMonitor = mockk(relaxed = true),
        )
        // The queue is a, b; a is current and has just ended.
        val current = queueManager.setQueue(listOf(a, b), 0, null, false)
        stateHolder.update { copy(currentTrack = current, isPlaying = true, upNext = listOf(b)) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun upNextIds() = queueManager.snapshot.value.upNext.map { it.id }

    @Test
    fun `a handled track end neither advances nor wraps nor replays`() {
        controller.onOwnTrackEnded = { true }
        for (mode in RepeatMode.values()) {
            controller.setRepeatMode(mode)
            controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
            assertEquals("a", stateHolder.state.value.currentTrack?.id)
            assertEquals(listOf("b"), upNextIds())
            assertFalse("$mode started a play", controller.isPlayStarting)
        }
        // Held on screen, not playing.
        assertFalse(stateHolder.state.value.isPlaying)
    }

    @Test
    fun `an unhandled track end advances`() {
        controller.onOwnTrackEnded = { false }
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        assertTrue(controller.isPlayStarting)
        assertEquals(emptyList<String>(), upNextIds())
    }

    @Test
    fun `a handled skip doesn't skip again`() {
        val actions = mutableListOf<UserPlaybackAction>()
        controller.onUserAction = { actions += it; it == UserPlaybackAction.SKIP }
        controller.setRepeatMode(RepeatMode.ALL)
        controller.skipNext()
        assertEquals(listOf(UserPlaybackAction.SKIP), actions)
        assertEquals("a", stateHolder.state.value.currentTrack?.id)
        assertEquals(listOf("b"), upNextIds())
        assertFalse(controller.isPlayStarting)
    }

    @Test
    fun `a handled resume doesn't load or resume the track`() {
        // A queue given back paused: Play would load its first track.
        controller.restoreQueuePaused(listOf(b, c), null)
        assertTrue(controller.needsLoadOnPlay)
        controller.onUserAction = { it == UserPlaybackAction.RESUME }
        controller.togglePlayPause()
        assertTrue("the resume was consumed by the load", controller.needsLoadOnPlay)
        assertFalse(controller.isPlayStarting)
        assertTrue(controller.onSessionPlayPause(play = true))
    }

    @Test
    fun `a held track ignores the session's play even when the listener doesn't answer`() {
        controller.onOwnTrackEnded = { true }
        controller.skipNextInternal(AdvanceTrigger.TRACK_ENDED)
        controller.onUserAction = { false }
        assertTrue(controller.onSessionPlayPause(play = true))
        // Once the listener lets go, play is the player's again.
        controller.releaseListenerHold()
        assertFalse(controller.onSessionPlayPause(play = true))
    }

    @Test
    fun `a queue given back paused shows its first track paused and Play loads it`() {
        controller.restoreQueuePaused(listOf(b, c), null)
        val state = stateHolder.state.value
        assertEquals("b", state.currentTrack?.id)
        assertFalse(state.isPlaying)
        assertEquals(listOf("c"), state.upNext.map { it.id })
        assertTrue(controller.needsLoadOnPlay)
        assertFalse(controller.isPlayStarting)
    }

    @Test
    fun `the idle timeout keeps a slot already set for the track`() {
        val failed = failedLoad(a, skipReselect = false, failedAtMs = 0, durationMs = 0)
        assertEquals(failed, idleTimeoutLoad(failed, a, positionMs = 42_000))
        // Another track's slot, or none, becomes an idle stop where it was paused.
        val stale = failedLoad(b, skipReselect = false, failedAtMs = 0, durationMs = 0)
        val idle = idleTimeoutLoad(stale, a, positionMs = 42_000)
        assertEquals(LoadOnPlay(a, 42_000, skipReselect = true, why = LoadOnPlay.Why.IDLE_STOPPED), idle)
        assertEquals(idle, idleTimeoutLoad(null, a, positionMs = 42_000))
        assertNull(idleTimeoutLoad(failed, null, positionMs = 0))
    }
}
