package com.parachord.shared.ai

import com.parachord.shared.format.countOf

/** One DJ tool call the turn ran, and what it answered. */
data class ToolRun(val tool: String, val result: Map<String, Any?>)

/**
 * The chat's reply when Shuffleupagus ran DJ tools but wrote no text of its
 * own ("Added 3 tracks to queue. Shuffle enabled."): a port of desktop's
 * `src/renderer/lib/tool-summary.js`, in its words. Every turn ends with
 * something to show (desktop's `rawFinal = content || summarizeToolResults`).
 *
 * The phones' tools answer with a few different keys from desktop's, so each
 * count reads either: `queue_add`'s `queued` (desktop `added`), `shuffle`'s
 * `shuffleEnabled` (desktop `shuffle`), `create_playlist`'s `name` (desktop
 * `playlist.name`).
 */
object ToolSummary {

    /** Desktop's reply for a turn with no tool results. */
    const val DONE = "Done."

    fun summarize(runs: List<ToolRun>?): String {
        if (runs.isNullOrEmpty()) return DONE
        return runs.joinToString(". ") { (tool, result) ->
            if (result["success"] != true) return@joinToString "Failed to $tool: ${result["error"]}"
            when (tool) {
                "play" -> (result["track"] as? Map<*, *>)
                    ?.let { "Now playing \"${it["title"]}\" by ${it["artist"]}" } ?: "Started playback"
                "control" -> "Playback ${result["action"]}"
                "search" -> "Found ${countOf(((result["results"] as? List<*>)?.size) ?: 0, "result")}"
                "queue_add" -> {
                    val skipped = (result["notFound"] as? List<*>).orEmpty().mapNotNull { t ->
                        (t as? Map<*, *>)?.let { "\"${it["title"]}\" by ${it["artist"]}" }
                    }.joinToString(", ")
                    val added = number(result["added"]) ?: number(result["queued"]) ?: 0
                    "Added ${countOf(added, "track")} to queue" + if (skipped.isNotEmpty()) ". Could not find $skipped" else ""
                }
                "queue_clear" -> "Queue cleared"
                "create_playlist" -> "Created playlist \"${(result["playlist"] as? Map<*, *>)?.get("name") ?: result["name"]}\""
                "shuffle" -> "Shuffle ${if ((result["shuffle"] ?: result["shuffleEnabled"]) == true) "enabled" else "disabled"}"
                "collection_radio" -> "Started Collection Radio with ${countOf(number(result["trackCount"]) ?: 0, "track")}"
                else -> "$tool completed"
            }
        } + "."
    }

    private fun number(value: Any?): Int? = (value as? Number)?.toInt()
}
