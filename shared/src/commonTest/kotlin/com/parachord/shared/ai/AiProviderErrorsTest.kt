package com.parachord.shared.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Desktop's `describeProviderError` vectors (`tests/chat/ai-provider-errors.test.js`)
 * plus one per branch, and the OpenAI 429s the phones saw.
 */
class AiProviderErrorsTest {

    private fun describe(message: String) = AiProviderErrors.describe(message)

    @Test
    fun `desktop vectors`() {
        assertTrue("internet connection" in describe("Failed to fetch"))
        assertTrue("Invalid API key" in describe("401 Unauthorized"))
        assertTrue("AI model not found" in describe("404 Not Found"))
        assertTrue("Rate limit" in describe("429 Too Many Requests"))
    }

    @Test
    fun `the plugin wording wins`() {
        assertEquals("My server is asleep.", AiProviderErrors.describe("Failed to fetch", "My server is asleep."))
        assertEquals(AiProviderErrors.CONNECTION, AiProviderErrors.describe("Failed to fetch", ""))
    }

    @Test
    fun `an OpenAI insufficient quota 429 is a quota problem not a wait`() {
        val message = "ChatGPT API error (429): You exceeded your current quota, please check your plan and " +
            "billing details. For more information on this error, read the docs: " +
            "https://platform.openai.com/docs/guides/error-codes/api-errors."
        assertEquals(AiProviderErrors.QUOTA, describe(message))
    }

    @Test
    fun `a real OpenAI rate limit 429 is a wait`() {
        val message = "ChatGPT API error (429): Rate limit reached for gpt-4o-mini in organization org-abc on " +
            "requests per min (RPM): Limit 3 / Used 3 / Requested 1. Please try again in 20s."
        assertEquals(AiProviderErrors.RATE_LIMIT, describe(message))
    }

    @Test
    fun `a Gemini exhausted quota is a quota problem`() {
        assertEquals(AiProviderErrors.QUOTA, describe("Resource has been exhausted (e.g. check quota)."))
    }

    @Test
    fun `phone connection failures read as connection failures`() {
        assertEquals(AiProviderErrors.CONNECTION, describe("Unable to resolve host \"api.openai.com\": No address associated with hostname"))
        assertEquals(AiProviderErrors.CONNECTION, describe("failed to connect to api.anthropic.com/1.2.3.4 (port 443)"))
        assertEquals(AiProviderErrors.CONNECTION, describe("The Internet connection appears to be offline."))
        assertEquals(AiProviderErrors.CONNECTION, describe("ECONNREFUSED 127.0.0.1:11434"))
        assertEquals(AiProviderErrors.CONNECTION, describe("Request timeout has expired [url=https://api.openai.com/v1/chat/completions, request_timeout=60000 ms]"))
    }

    @Test
    fun `a bad key and a missing model`() {
        assertEquals(AiProviderErrors.INVALID_KEY, describe("ChatGPT API error (401): Incorrect API key provided"))
        assertEquals(AiProviderErrors.INVALID_KEY, describe("Invalid API key. Please check your Claude API key in settings."))
        assertEquals(AiProviderErrors.MODEL_NOT_FOUND, describe("ChatGPT API error (404): The model `gpt-9` does not exist"))
        assertEquals(AiProviderErrors.MODEL_NOT_FOUND, describe("models/gemini-9 is not found for API version v1beta"))
    }

    @Test
    fun `a content policy block`() {
        assertEquals(AiProviderErrors.CONTENT_POLICY, describe("Gemini blocked the response due to safety filters"))
        assertEquals(AiProviderErrors.CONTENT_POLICY, describe("Your request was rejected by our content policy"))
    }

    @Test
    fun `anything else is the provider message cut to 100 characters`() {
        assertEquals("Error: ChatGPT API error (500): boom", describe("ChatGPT API error (500): boom"))
        val long = "x".repeat(150)
        assertEquals("Error: " + "x".repeat(100) + "…", describe(long))
    }

    @Test
    fun `secrets never reach the message`() {
        val timeout = "Request timeout has expired [url=https://generativelanguage.googleapis.com/v1beta/models/" +
            "gemini-2.0-flash:generateContent?key=AIzaSyA1234567890abcdefghijk, request_timeout=60000 ms]"
        val shown = describe(timeout)
        assertFalse("AIza" in shown, shown)
        assertFalse("1234567890" in shown, shown)
    }

    @Test
    fun `redaction removes query keys bearer tokens and key literals`() {
        assertEquals(
            "GET https://x.test/v1?key=REDACTED&alt=json",
            AiProviderErrors.redactSecrets("GET https://x.test/v1?key=AIzaSyA1234567890abcdefghijk&alt=json"),
        )
        assertEquals("Authorization: Bearer REDACTED", AiProviderErrors.redactSecrets("Authorization: Bearer abc.def-123"))
        assertEquals("key REDACTED was rejected", AiProviderErrors.redactSecrets("key sk-proj-abcdefghijklmnop was rejected"))
        assertEquals("no secrets here", AiProviderErrors.redactSecrets("no secrets here"))
    }
}
