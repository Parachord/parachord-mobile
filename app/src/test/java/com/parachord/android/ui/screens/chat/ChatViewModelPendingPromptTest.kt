package com.parachord.android.ui.screens.chat

import com.parachord.android.ai.providers.ChatGptProvider
import com.parachord.android.ai.providers.ClaudeProvider
import com.parachord.android.ai.providers.GeminiProvider
import com.parachord.android.data.store.SettingsStore
import com.parachord.shared.ai.AiChatResponse
import com.parachord.shared.ai.AiChatService
import com.parachord.shared.ai.ChatHistoryStore
import com.parachord.shared.ai.ChatMessage
import com.parachord.shared.ai.ChatRole
import com.parachord.shared.ai.tools.DjToolExecutor
import com.parachord.shared.model.ChatMessageRecord
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Home's Ask queues a prompt and opens the chat. The chat used to send it
 * while it was still loading the provider's messages, and the load replaced
 * the shown prompt with the old conversation. It now sends once the messages
 * are loaded, and only while its screen is open.
 */
class ChatViewModelPendingPromptTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    /** Saved history that takes a moment to read, like the database. */
    private class SlowStore : ChatHistoryStore {
        override suspend fun deleteOlderThan(cutoffMillis: Long) {}
        override suspend fun getByProvider(providerId: String): List<ChatMessageRecord> {
            delay(200)
            return listOf(ChatMessageRecord(providerId = providerId, role = "USER", content = "earlier", timestamp = Long.MAX_VALUE / 2))
        }
        override suspend fun insert(record: ChatMessageRecord) {}
        override suspend fun clearByProvider(providerId: String) {}
        override suspend fun clearAll() {}
    }

    private val chatService = AiChatService(
        toolExecutor = object : DjToolExecutor {
            override suspend fun execute(name: String, args: Map<String, Any?>): Map<String, Any?> = emptyMap()
        },
        systemPrompt = { "system" },
        historyStore = SlowStore(),
        json = Json,
    )

    private fun viewModel(): ChatViewModel {
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.getAiProviderApiKeyFlow("chatgpt") } returns flowOf("key")
        every { settings.getAiProviderApiKeyFlow("claude") } returns flowOf(null)
        every { settings.getAiProviderApiKeyFlow("gemini") } returns flowOf(null)
        coEvery { settings.getSelectedChatProvider() } returns null
        coEvery { settings.setSelectedChatProvider(any()) } coAnswers { delay(50) }
        coEvery { settings.getAiProviderApiKey(any()) } returns "key"
        coEvery { settings.getAiProviderModel(any()) } returns ""

        val chatGpt = mockk<ChatGptProvider>()
        every { chatGpt.id } returns "chatgpt"
        every { chatGpt.name } returns "ChatGPT"
        coEvery { chatGpt.chat(any(), any(), any()) } coAnswers {
            val messages = firstArg<List<ChatMessage>>()
            AiChatResponse(content = "reply to " + messages.last().content)
        }
        val claude = mockk<ClaudeProvider>(relaxed = true).also { every { it.id } returns "claude" }
        val gemini = mockk<GeminiProvider>(relaxed = true).also { every { it.id } returns "gemini" }
        val plugins = mockk<com.parachord.android.plugin.PluginManager>(relaxed = true)
        every { plugins.plugins } returns MutableStateFlow(emptyList())

        return ChatViewModel(chatService, settings, chatGpt, claude, gemini, mockk(relaxed = true), plugins)
    }

    @Test
    fun `an Ask prompt shows with its reply after the old conversation`() = runTest(testDispatcher) {
        chatService.setPendingChatPrompt("Make me a playlist", fromApp = true)
        val vm = viewModel()
        // availableProviders is WhileSubscribed: the screen collects it.
        backgroundScope.launch { vm.availableProviders.collect {} }
        vm.setChatOpen(true)
        advanceUntilIdle()

        assertEquals(
            listOf("earlier", "Make me a playlist", "reply to Make me a playlist"),
            vm.messages.value.filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }.map { it.content },
        )
        assertEquals(null, chatService.pendingChatPrompt.value)
    }

    @Test
    fun `a prompt queued while the chat is open is sent too`() = runTest(testDispatcher) {
        val vm = viewModel()
        // availableProviders is WhileSubscribed: the screen collects it.
        backgroundScope.launch { vm.availableProviders.collect {} }
        vm.setChatOpen(true)
        advanceUntilIdle()

        chatService.setPendingChatPrompt("one more", fromApp = true)
        advanceUntilIdle()

        assertEquals("reply to one more", vm.messages.value.last().content)
    }

    @Test
    fun `a chat screen that is not open leaves the prompt for the one that is`() = runTest(testDispatcher) {
        val vm = viewModel()
        // availableProviders is WhileSubscribed: the screen collects it.
        backgroundScope.launch { vm.availableProviders.collect {} }
        advanceUntilIdle()

        chatService.setPendingChatPrompt("for the new screen", fromApp = true)
        advanceUntilIdle()

        assertEquals("for the new screen", chatService.pendingChatPrompt.value)
        assertEquals(listOf("earlier"), vm.messages.value.map { it.content })
    }
}
