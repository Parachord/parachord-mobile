package com.parachord.android.deeplink

import android.net.Uri
import com.parachord.android.ai.AiChatService
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.shared.deeplink.DeepLinkAction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Home's Ask sends its prompt without the external-link confirmation; a
 * `parachord://chat` link still confirms, and the in-app origin is set only by
 * the direct call (desktop's chat-origin.test.js).
 */
class DeepLinkViewModelChatOriginTest {

    private val testDispatcher = StandardTestDispatcher()
    private val deepLinkHandler = mockk<DeepLinkHandler>()
    private val chatService = mockk<AiChatService>(relaxed = true)
    private val uri = mockk<Uri>()

    @Before
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun viewModel() = DeepLinkViewModel(
        deepLinkHandler = deepLinkHandler,
        externalLinkResolver = mockk(relaxed = true),
        playbackController = mockk(relaxed = true),
        playbackStateHolder = PlaybackStateHolder(),
        queueManager = mockk(relaxed = true),
        resolverManager = mockk(relaxed = true),
        resolverScoring = mockk(relaxed = true),
        playlistImportManager = mockk(relaxed = true),
        chatService = chatService,
        protocolPlayHandler = mockk(relaxed = true),
        protocolPlayTeardown = mockk(relaxed = true),
        playRadioDispatcher = mockk(relaxed = true),
        listenAlongDispatcher = mockk(relaxed = true),
        friendsRepository = mockk(relaxed = true),
        pluginManager = mockk(relaxed = true),
        trackResolverCache = mockk(relaxed = true),
        lockedLinkNotifier = mockk(relaxed = true),
    )

    @Test
    fun `an in-app prompt is queued from the app and opens chat with no confirmation`() = runTest(testDispatcher) {
        val vm = viewModel()
        var event: DeepLinkNavEvent? = null
        val job = launch { event = vm.navEvents.first() }
        advanceUntilIdle()
        vm.handleInAppChatPrompt("Make me a playlist for right now")
        advanceUntilIdle()
        job.cancel()
        assertNull(vm.pendingConfirmation.value)
        verify(exactly = 1) { chatService.setPendingChatPrompt("Make me a playlist for right now", true) }
        assertEquals(DeepLinkNavEvent.Chat("Make me a playlist for right now"), event)
    }

    @Test
    fun `an in-app prompt is capped at 500 characters`() = runTest(testDispatcher) {
        viewModel().handleInAppChatPrompt("a".repeat(600))
        advanceUntilIdle()
        verify(exactly = 1) { chatService.setPendingChatPrompt("a".repeat(500), true) }
    }

    @Test
    fun `a blank in-app prompt does nothing`() = runTest(testDispatcher) {
        viewModel().handleInAppChatPrompt("   ")
        advanceUntilIdle()
        verify(exactly = 0) { chatService.setPendingChatPrompt(any(), any()) }
    }

    @Test
    fun `a chat link still confirms and queues only once confirmed and never as in-app`() = runTest(testDispatcher) {
        every { deepLinkHandler.parse(uri) } returns DeepLinkAction.NavigateChat("hello")
        val vm = viewModel()
        vm.handleUri(uri)
        advanceUntilIdle()
        assertNotNull(vm.pendingConfirmation.value)
        verify(exactly = 0) { chatService.setPendingChatPrompt(any(), any()) }

        vm.confirmPendingAction()
        advanceUntilIdle()
        verify(exactly = 1) { chatService.setPendingChatPrompt("hello", false) }
        verify(exactly = 0) { chatService.setPendingChatPrompt(any(), true) }
    }
}
