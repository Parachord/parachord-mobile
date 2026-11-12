package com.parachord.android.deeplink

import android.net.Uri
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.shared.deeplink.DeepLinkAction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * `parachord://control/pause` while paused must not resume, and `resume` or
 * `play` while playing must not pause, as on desktop (`app.js` `case "control"`).
 */
class DeepLinkViewModelControlTest {

    private val testDispatcher = StandardTestDispatcher()

    private val deepLinkHandler = mockk<DeepLinkHandler>()
    private val playbackController = mockk<PlaybackController>(relaxed = true)
    private val stateHolder = PlaybackStateHolder()
    private val uri = mockk<Uri>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val lockedLinkNotifier = mockk<LockedLinkNotifier>(relaxed = true)

    private fun viewModel() = DeepLinkViewModel(
        deepLinkHandler = deepLinkHandler,
        externalLinkResolver = mockk(relaxed = true),
        playbackController = playbackController,
        playbackStateHolder = stateHolder,
        queueManager = mockk(relaxed = true),
        resolverManager = mockk(relaxed = true),
        resolverScoring = mockk(relaxed = true),
        playlistImportManager = mockk(relaxed = true),
        chatService = mockk(relaxed = true),
        protocolPlayHandler = mockk(relaxed = true),
        protocolPlayTeardown = mockk(relaxed = true),
        playRadioDispatcher = mockk(relaxed = true),
        listenAlongDispatcher = mockk(relaxed = true),
        friendsRepository = mockk(relaxed = true),
        pluginManager = mockk(relaxed = true),
        trackResolverCache = mockk(relaxed = true),
        lockedLinkNotifier = lockedLinkNotifier,
    )

    private fun kotlinx.coroutines.test.TestScope.openControlLink(action: String, isPlaying: Boolean) {
        stateHolder.update { copy(isPlaying = isPlaying) }
        every { deepLinkHandler.parse(uri) } returns DeepLinkAction.Control(action)
        viewModel().handleUri(uri)
        advanceUntilIdle()
    }

    @Test
    fun `pause while playing pauses`() = runTest(testDispatcher) {
        openControlLink("pause", isPlaying = true)
        verify(exactly = 1) { playbackController.togglePlayPause() }
    }

    @Test
    fun `pause while paused does nothing`() = runTest(testDispatcher) {
        openControlLink("pause", isPlaying = false)
        verify(exactly = 0) { playbackController.togglePlayPause() }
    }

    @Test
    fun `resume while paused resumes`() = runTest(testDispatcher) {
        openControlLink("resume", isPlaying = false)
        verify(exactly = 1) { playbackController.togglePlayPause() }
    }

    @Test
    fun `resume while playing does nothing`() = runTest(testDispatcher) {
        openControlLink("resume", isPlaying = true)
        verify(exactly = 0) { playbackController.togglePlayPause() }
    }

    @Test
    fun `play while paused resumes`() = runTest(testDispatcher) {
        openControlLink("play", isPlaying = false)
        verify(exactly = 1) { playbackController.togglePlayPause() }
    }

    @Test
    fun `play while playing does nothing`() = runTest(testDispatcher) {
        openControlLink("play", isPlaying = true)
        verify(exactly = 0) { playbackController.togglePlayPause() }
    }

    @Test
    fun `skip still skips whatever the state`() = runTest(testDispatcher) {
        openControlLink("skip", isPlaying = false)
        verify(exactly = 1) { playbackController.skipNext() }
        verify(exactly = 0) { playbackController.togglePlayPause() }
    }

    @Test
    fun `a play link with the phone locked asks to unlock and plays nothing`() = runTest(testDispatcher) {
        every { lockedLinkNotifier.isLocked() } returns true
        val action = DeepLinkAction.Play("Ramones", "Judy Is a Punk")
        every { deepLinkHandler.parse(uri) } returns action
        viewModel().handleUri(uri)
        advanceUntilIdle()
        verify(exactly = 1) { lockedLinkNotifier.notifyUnlockToPlay(uri, action) }
        verify(exactly = 0) { playbackController.noteLinkPlayRequested() }
        verify(exactly = 0) { playbackController.playTrack(any(), any()) }
    }

    @Test
    fun `a control link with the phone locked still works`() = runTest(testDispatcher) {
        every { lockedLinkNotifier.isLocked() } returns true
        openControlLink("skip", isPlaying = true)
        verify(exactly = 1) { playbackController.skipNext() }
        verify(exactly = 0) { lockedLinkNotifier.notifyUnlockToPlay(any(), any()) }
    }
}
