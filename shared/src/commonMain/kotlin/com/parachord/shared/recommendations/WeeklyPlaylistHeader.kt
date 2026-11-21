package com.parachord.shared.recommendations

/*
 * A weekly playlist's header (Weekly Jams / Weekly Exploration), built from the
 * recommendation sections. A port of desktop's `weeklyHomeEntries`
 * (`src/renderer/lib/recommendation-model.js`): the weekly view looks its
 * playlist up there, never with a separate ListenBrainz request.
 */

/** What a weekly playlist's header shows. [blurb] is desktop's `description` (a Swift-safe name). */
data class WeeklyHeader(
    val id: String,
    val contextType: String,
    /** The plugin's label, e.g. "Weekly Jams". */
    val title: String,
    /** "This Week", "Last Week", … */
    val weekLabel: String,
    /** "Your favorite tracks from the past week, curated by ListenBrainz". */
    val blurb: String,
    /** The playlist's date as the plugin sent it (ISO), or null. */
    val date: String?,
    /** [date] as "Jun 15, 2026"; empty when there is none or it doesn't parse. */
    val dateLabel: String,
)

/** Desktop's `WEEKLY_DESCRIPTIONS`. */
val WEEKLY_DESCRIPTIONS: Map<String, String> = mapOf(
    "weekly-jam" to "Your favorite tracks from the past week",
    "weekly-exploration" to "New discoveries based on your listening habits",
)

/**
 * Every weekly playlist on the page, first appearance first, one per id. The
 * curator is the item's own source (rows are blended), else its resolver id,
 * else "your service".
 *
 * Pass the sections built **before** the history-mix exclusion
 * ([com.parachord.shared.repository.RecommendationPageState.weeklySections]):
 * Weekly Jams are also a history mix, so the page leaves them out.
 */
fun weeklyPlaylistHeaders(sections: List<RecommendationPageSection>): List<WeeklyHeader> {
    val out = mutableListOf<WeeklyHeader>()
    val seen = mutableSetOf<String>()
    for (section in sections) {
        for (row in section.rows) {
            for (item in row.items) {
                val w = item.weekly ?: continue
                val base = WEEKLY_DESCRIPTIONS[w.contextType] ?: continue
                if (!seen.add(w.id)) continue
                val source = row.sources.firstOrNull { it.id == item.resolverId }
                val curator = source?.name?.takeIf { it.isNotEmpty() }
                    ?: item.resolverId?.takeIf { it.isNotEmpty() }
                    ?: "your service"
                out += WeeklyHeader(
                    id = w.id,
                    contextType = w.contextType,
                    title = w.label,
                    weekLabel = w.weekLabel,
                    blurb = "$base, curated by $curator",
                    date = w.date?.takeIf { it.isNotEmpty() },
                    dateLabel = weeklyDateLabel(w.date.orEmpty()),
                )
            }
        }
    }
    return out
}

/** The header of the weekly playlist [id], or null when the page doesn't carry it. */
fun weeklyPlaylistHeader(sections: List<RecommendationPageSection>, id: String): WeeklyHeader? =
    weeklyPlaylistHeaders(sections).firstOrNull { it.id == id }

/** The header's kind label: "Weekly Jam" (singular, to parallel the other) or "Weekly Exploration". */
fun weeklyKindLabel(contextType: String): String =
    if (contextType == "weekly-jam") "Weekly Jam" else "Weekly Exploration"

/** The header's first line: "Weekly Jam · Jun 15, 2026", or the kind alone with no date. */
fun weeklyHeadline(kind: String, dateLabel: String): String =
    if (dateLabel.isNotEmpty()) "$kind · $dateLabel" else kind

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** An ISO date (optionally with a time) as "Jun 15, 2026"; empty for blank or unparseable input. */
fun weeklyDateLabel(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
        val d = kotlinx.datetime.LocalDate.parse(raw.take(10))
        "${MONTHS[d.monthNumber - 1]} ${d.dayOfMonth}, ${d.year}"
    } catch (e: Exception) {
        ""
    }
}
