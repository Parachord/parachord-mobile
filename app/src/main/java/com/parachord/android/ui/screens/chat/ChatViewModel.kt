package com.parachord.android.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.ai.AiChatProvider
import com.parachord.android.ai.AiChatService
import com.parachord.android.ai.AiProviderConfig
import com.parachord.android.ai.AiProviderInfo
import com.parachord.android.ai.ChatCardEnricher
import com.parachord.android.ai.ChatMessage
import com.parachord.android.ai.ChatRole
import com.parachord.android.ai.providers.ChatGptProvider
import com.parachord.android.ai.providers.ClaudeProvider
import com.parachord.android.ai.providers.GeminiProvider
import com.parachord.android.data.store.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
class ChatViewModel constructor(
    private val chatService: AiChatService,
    private val settingsStore: SettingsStore,
    private val chatGptProvider: ChatGptProvider,
    private val claudeProvider: ClaudeProvider,
    private val geminiProvider: GeminiProvider,
    val cardEnricher: ChatCardEnricher,
    private val pluginManager: com.parachord.android.plugin.PluginManager,
) : ViewModel() {

    // Native providers + .axe-only AI providers (e.g., Ollama)
    private val providers: List<AiChatProvider> by lazy {
        val native = listOf(chatGptProvider, claudeProvider, geminiProvider)
        val nativeIds = native.map { it.id }.toSet()
        // Add AxeAiProvider wrappers for .axe AI plugins not already covered natively
        val axeProviders = pluginManager.plugins.value
            .filter { it.capabilities["chat"] == true || it.capabilities["generate"] == true }
            .filter { it.id !in nativeIds }
            .map { com.parachord.android.ai.providers.AxeAiProvider(it.id, it.name, pluginManager) }
        native + axeProviders
    }

    private val _selectedProviderId = MutableStateFlow<String?>(null)
    val selectedProviderId: StateFlow<String?> = _selectedProviderId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _progressText = MutableStateFlow<String?>(null)
    val progressText: StateFlow<String?> = _progressText.asStateFlow()

    val availableProviders: StateFlow<List<AiProviderInfo>> = combine(
        settingsStore.getAiProviderApiKeyFlow("chatgpt"),
        settingsStore.getAiProviderApiKeyFlow("claude"),
        settingsStore.getAiProviderApiKeyFlow("gemini"),
    ) { chatGptKey, claudeKey, geminiKey ->
        val native = listOf(
            AiProviderInfo("chatgpt", "ChatGPT", chatGptKey != null, ""),
            AiProviderInfo("claude", "Claude", claudeKey != null, ""),
            AiProviderInfo("gemini", "Google Gemini", geminiKey != null, ""),
        )
        // Add .axe-only AI providers (Ollama, etc.) — they don't require API keys
        // (configured = true when no auth required, or when key is stored)
        val nativeIds = native.map { it.id }.toSet()
        val axeProviders = pluginManager.plugins.value
            .filter { (it.capabilities["chat"] == true || it.capabilities["generate"] == true) && it.id !in nativeIds }
            .map { plugin ->
                val requiresAuth = plugin.capabilities["requiresAuth"] == true
                AiProviderInfo(plugin.id, plugin.name, isConfigured = !requiresAuth, currentModel = "")
            }
        native + axeProviders
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The provider whose saved messages are in [messages]. A queued prompt
     * waits for it: sending while the load is still running let the load
     * replace the shown prompt with the old conversation.
     */
    private val _loadedProviderId = MutableStateFlow<String?>(null)

    /** Whether this chat screen is on screen (not left in the back stack). */
    private val _chatOpen = MutableStateFlow(false)

    /**
     * The newest queued prompt this screen saw while it was open; closing
     * drops only that one (shared [com.parachord.shared.deeplink.PendingChatPrompt.generation]).
     */
    private var seenPromptGeneration = -1L

    init {
        // Load last used provider, or auto-select first configured one
        viewModelScope.launch {
            val saved = settingsStore.getSelectedChatProvider()
            availableProviders.collect { providersList ->
                if (_selectedProviderId.value == null) {
                    val target = if (saved != null) {
                        providersList.firstOrNull { it.id == saved && it.isConfigured }
                    } else null
                    val provider = target ?: providersList.firstOrNull { it.isConfigured }
                    provider?.let { selectProvider(it.id) }
                }
            }
        }
        // A queued prompt: a confirmed parachord://chat link, or Home's Ask
        // (DeepLinkViewModel.handleInAppChatPrompt). Sent whenever the chat is
        // open with a provider whose messages are loaded and no reply is in
        // flight (shared ChatPrompt.shouldSend), re-checked when any of those
        // change, as desktop's pending-prompt effect does.
        viewModelScope.launch {
            combine(
                chatService.pendingChatPrompt,
                _chatOpen,
                _loadedProviderId,
                _isLoading,
            ) { pending, open, providerId, loading -> PromptCheck(pending, open, providerId, loading) }
                .collect { check ->
                    if (check.open && check.pending != null) {
                        seenPromptGeneration = chatService.pendingChatPromptGeneration
                    }
                    chatService.takeSendableChatPrompt(check.open, check.providerId, check.loading)
                        ?.let { prompt -> sendMessage(prompt) }
                }
        }
    }

    private data class PromptCheck(val pending: String?, val open: Boolean, val providerId: String?, val loading: Boolean)

    /** The chat screen came on screen ([open]) or left it (covered, or closed). */
    fun setChatOpen(open: Boolean) {
        _chatOpen.value = open
    }

    fun selectProvider(providerId: String) {
        _selectedProviderId.value = providerId
        _loadedProviderId.value = null
        viewModelScope.launch {
            settingsStore.setSelectedChatProvider(providerId)
            val loaded = chatService.getDisplayMessages(providerId)
            if (_selectedProviderId.value != providerId) return@launch
            // A send already under way shows its own messages when its reply lands.
            if (!_isLoading.value) _messages.value = loaded
            _loadedProviderId.value = providerId
        }
    }

    fun sendMessage(text: String) {
        val providerId = _selectedProviderId.value ?: return
        val provider = providers.find { it.id == providerId } ?: return

        // Set before the coroutine starts, so the pending-prompt check can't
        // send a second prompt in between.
        _isLoading.value = true
        _progressText.value = null
        // Show user message immediately
        _messages.value = _messages.value + ChatMessage(role = ChatRole.USER, content = text)

        viewModelScope.launch {
            val config = AiProviderConfig(
                apiKey = settingsStore.getAiProviderApiKey(providerId) ?: "",
                model = settingsStore.getAiProviderModel(providerId),
            )

            chatService.sendMessage(
                provider = provider,
                config = config,
                userMessage = text,
                onProgress = { _progressText.value = it },
            )

            // Refresh from service (includes assistant response)
            if (_selectedProviderId.value == providerId) {
                _messages.value = chatService.getDisplayMessages(providerId)
            }
            _isLoading.value = false
            _progressText.value = null
        }
    }

    fun clearChat() {
        val providerId = _selectedProviderId.value ?: return
        viewModelScope.launch {
            chatService.clearHistory(providerId)
            _messages.value = emptyList()
        }
    }

    /**
     * The chat screen closed: a prompt it saw that never went out (no
     * provider was set up) is dropped rather than sent the next time chat
     * opens (shared ChatPrompt.shouldDrop, desktop's shouldDropPendingChatPrompt).
     * Only that prompt: one Home's Ask queued for the next chat screen
     * survives this screen being cleared late.
     */
    override fun onCleared() {
        if (seenPromptGeneration >= 0) chatService.dropPendingChatPrompt(seenPromptGeneration)
        super.onCleared()
    }
}
