package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.drm.PluginDrmPlaybackException
import com.parachord.android.playback.handlers.ExternalPlaybackHandler
import com.parachord.android.playback.handlers.PlaybackAction
import com.parachord.android.playback.handlers.PlaybackStartFailedException
import com.parachord.android.resolver.trackKey
import com.parachord.shared.playback.PlaybackMessages
import com.parachord.shared.playback.drm.PluginDrmError
import com.parachord.shared.resolver.ResolvedSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A manual source switch ([PlaybackController.switchSource]) whose pick can't
 * start leaves the playback as it was (shared failedSwitchKeepsPlayback).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerSourceSwitchTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var f: PlaybackControllerFixture
    private val controller get() = f.controller
    private val state get() = f.stateHolder.state.value

    private val unison = TrackEntity(
        id = "unison", title = "Unison", artist = "Björk", resolver = "applemusic", appleMusicId = "123",
    )

    /** Apple Music, playing. */
    private val am = mockk<ExternalPlaybackHandler>(relaxed = true)

    /** The pick's engine, for an external pick. */
    private val sc = mockk<ExternalPlaybackHandler>(relaxed = true)

    private val notices = mutableListOf<PlaybackNotice>()
    private lateinit var collector: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        f = PlaybackControllerFixture()
        // Apple Music plays Unison.
        coEvery { f.router.route(any()) } returns PlaybackAction.ExternalPlayback(am)
        every { f.router.activeExternalHandler } returns am
        controller.playTrack(unison)
        run()
        assertEquals("applemusic", state.currentTrack?.resolver)
        // Resolved since (after the play, so it routes as it is).
        every { f.trackResolverCache.trackSources } returns MutableStateFlow(
            mapOf(
                trackKey(unison.title, unison.artist) to listOf(
                    ResolvedSource(url = "am", sourceType = "stream", resolver = "applemusic", appleMusicId = "123", confidence = 0.95),
                    ResolvedSource(url = "amz", sourceType = "stream", resolver = "amazonmusic", confidence = 0.95),
                    ResolvedSource(url = "sc", sourceType = "stream", resolver = "soundcloud", soundcloudId = "9", confidence = 0.95),
                ),
            ),
        )
        collector = CoroutineScope(dispatcher)
        collector.launch { controller.notices.collect { notices += it } }
        run()
    }

    @After
    fun tearDown() {
        if (::collector.isInitialized) collector.cancel()
        Dispatchers.resetMain()
    }

    private fun run() = dispatcher.scheduler.runCurrent()

    private fun assertStillOnAppleMusic(playing: Boolean = true) {
        assertEquals("unison", state.currentTrack?.id)
        assertEquals("applemusic", state.currentTrack?.resolver)
        assertEquals(playing, state.isPlaying)
        assertNull(state.playbackError)
        assertNull(state.pendingSourcePick)
        assertNull(controller.pendingLoad)
        assertTrue(!state.isStartingPlay)
    }

    @Test
    fun `a pick whose manifest fails leaves the old engine playing untouched`() {
        coEvery { f.router.route(any(), false) } throws
            PluginDrmPlaybackException("amazonmusic", PluginDrmError(message = "getPlaybackManifest timed out", budgetExpired = true))

        controller.switchSource("amazonmusic")
        run()

        assertStillOnAppleMusic()
        coVerify(exactly = 0) { am.stop() }
        coVerify(exactly = 0) { am.pause() }
        assertEquals(
            listOf(PlaybackMessages.couldNotSwitchTo("amazonmusic", "applemusic", playing = true)),
            notices.map { it.message },
        )
    }

    @Test
    fun `a pick with no source to route to leaves the old engine playing`() {
        coEvery { f.router.route(any(), false) } returns null

        controller.switchSource("amazonmusic")
        run()

        assertStillOnAppleMusic()
        coVerify(exactly = 0) { am.stop() }
        assertEquals(1, notices.size)
    }

    @Test
    fun `an external pick that can't start resumes the engine it paused`() {
        coEvery { f.router.route(any(), false) } answers {
            every { f.router.activeExternalHandler } returns sc
            PlaybackAction.ExternalPlayback(sc)
        }
        coEvery { sc.play(any()) } throws PlaybackStartFailedException("offline")

        controller.switchSource("soundcloud")
        run()

        assertStillOnAppleMusic()
        coVerify { am.pause() }
        coVerify { am.resume() }
        coVerify(exactly = 0) { am.stop() }
        coVerify { sc.stop() }
        coVerify { f.router.restoreActiveExternalHandler(am) }
    }

    @Test
    fun `an external pick that starts stops the old engine`() {
        coEvery { f.router.route(any(), false) } returns PlaybackAction.ExternalPlayback(sc)

        controller.switchSource("soundcloud")
        run()

        assertEquals("soundcloud", state.currentTrack?.resolver)
        coVerify { am.stop() }
        coVerify(exactly = 0) { am.resume() }
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a local pick that fails after the old engine let go plays the old source again`() {
        // The local player plays the pick (no MediaController here, so it fails).
        coEvery { f.router.route(any(), false) } returns PlaybackAction.ExoPlayerItem(mockk(relaxed = true))

        controller.switchSource("amazonmusic")
        run()

        coVerify { am.stop() }
        // Played again from Apple Music.
        coVerify(atLeast = 2) { am.play(match { it.resolver == "applemusic" }) }
        assertEquals("applemusic", state.currentTrack?.resolver)
        assertNull(state.playbackError)
        assertNull(controller.pendingLoad)
        assertEquals(1, notices.size)
    }
}
