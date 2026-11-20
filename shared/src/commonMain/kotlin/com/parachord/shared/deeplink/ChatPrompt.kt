package com.parachord.shared.deeplink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Home's Ask Shuffleupagus box and the `parachord://chat` link, ported from
 * desktop's `protocol-url.js` (`buildChatPromptUrl`,
 * `shouldSendPendingChatPrompt`, `shouldDropPendingChatPrompt`).
 *
 * A prompt the user typed or approved in the app (Ask) is queued with
 * `AiChatService.setPendingChatPrompt(prompt, fromApp = true)` and sent
 * without the "An external link wants to send this message to chat" dialog.
 * The in-app origin comes only from that direct call (Android
 * `DeepLinkViewModel.handleInAppChatPrompt`, iOS
 * `IosContainer.askShuffleupagus`), never from a URL: a `parachord://chat`
 * link always confirms.
 */
object ChatPrompt {
    /** The `parachord://chat` prompt limit, in UTF-16 units (desktop's `.slice(0, 500)`). */
    const val MAX_CHARS = 500

    /**
     * The prompt cut to [MAX_CHARS]; a cut that splits a surrogate pair drops
     * the lone high half (it can't be encoded).
     */
    fun cap(prompt: String?): String {
        var text = (prompt ?: "").take(MAX_CHARS)
        if (text.isNotEmpty() && text.last().isHighSurrogate()) text = text.dropLast(1)
        return text
    }

    /** `parachord://chat?prompt=<prompt>`, capped and encoded as `encodeURIComponent` does. */
    fun url(prompt: String?): String = "parachord://chat?prompt=" + encodeUriComponent(cap(prompt))

    /**
     * Whether a queued prompt can be sent now: the chat screen is open with a
     * provider and no reply is in flight.
     */
    fun shouldSend(pending: String?, chatOpen: Boolean, providerId: String?, loading: Boolean): Boolean =
        !pending.isNullOrBlank() && chatOpen && !providerId.isNullOrEmpty() && !loading

    /**
     * Whether a queued prompt is dropped: only when the chat screen goes from
     * open to closed (closed before the prompt went out). One still opening,
     * or never opened, keeps it.
     */
    fun shouldDrop(wasOpen: Boolean, chatOpen: Boolean): Boolean = wasOpen && !chatOpen

    private const val HEX = "0123456789ABCDEF"

    private fun isUnreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.!~*'()"

    private fun encodeUriComponent(text: String): String = buildString {
        for (b in text.encodeToByteArray()) {
            val c = (b.toInt() and 0xFF)
            if (c < 0x80 && isUnreserved(c.toChar())) {
                append(c.toChar())
            } else {
                append('%'); append(HEX[c shr 4]); append(HEX[c and 0x0F])
            }
        }
    }
}

/**
 * The one queued chat prompt and where it came from. The origin is set only
 * with the prompt, by the caller that queued it, and cleared with it.
 */
class PendingChatPrompt {
    private val _prompt = MutableStateFlow<String?>(null)
    val prompt: StateFlow<String?> = _prompt.asStateFlow()

    /** True when the queued prompt came from the app (Home's Ask). */
    var fromApp: Boolean = false
        private set

    fun set(prompt: String?, fromApp: Boolean) {
        this.fromApp = prompt != null && fromApp
        _prompt.value = prompt
    }

    /** The queued prompt, whatever its origin, cleared. */
    fun consume(): String? {
        val value = _prompt.value
        set(null, false)
        return value
    }

    /** The queued prompt if it came from the app, cleared; null (and kept) otherwise. */
    fun consumeInApp(): String? = if (fromApp) consume() else null
}
