package com.parachord.shared.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Desktop's `summarizeToolResults` vectors (`tests/chat/ai-chat-service.test.js`), plus the phones' keys. */
class ToolSummaryTest {

    private fun ok(vararg pairs: Pair<String, Any?>) = mapOf("success" to true) + pairs

    @Test
    fun `summarizes a play`() {
        assertEquals(
            "Now playing \"Creep\" by Radiohead.",
            ToolSummary.summarize(listOf(ToolRun("play", ok("track" to mapOf("artist" to "Radiohead", "title" to "Creep"))))),
        )
    }

    @Test
    fun `summarizes several tools`() {
        val s = ToolSummary.summarize(listOf(ToolRun("queue_add", ok("added" to 3)), ToolRun("shuffle", ok("shuffle" to true))))
        assertTrue("Added 3 tracks to queue" in s, s)
        assertTrue("Shuffle enabled" in s, s)
    }

    @Test
    fun `one of a thing is singular`() {
        assertEquals(
            "Added 1 track to queue. Found 1 result.",
            ToolSummary.summarize(listOf(ToolRun("queue_add", ok("added" to 1)), ToolRun("search", ok("results" to listOf(emptyMap<String, Any>()))))),
        )
    }

    @Test
    fun `a failure says what failed`() {
        assertTrue("Failed to play: Not found" in ToolSummary.summarize(listOf(ToolRun("play", mapOf("success" to false, "error" to "Not found")))))
    }

    @Test
    fun `nothing ran is Done`() {
        assertEquals("Done.", ToolSummary.summarize(emptyList()))
        assertEquals("Done.", ToolSummary.summarize(null))
    }

    @Test
    fun `the phones' result keys read too`() {
        assertEquals(
            "Added 2 tracks to queue. Could not find \"X\" by Y. Shuffle disabled. Created playlist \"Rainy\".",
            ToolSummary.summarize(
                listOf(
                    ToolRun("queue_add", ok("queued" to 2, "notFound" to listOf(mapOf("title" to "X", "artist" to "Y")))),
                    ToolRun("shuffle", ok("shuffleEnabled" to false)),
                    ToolRun("create_playlist", ok("name" to "Rainy")),
                ),
            ),
        )
    }
}
