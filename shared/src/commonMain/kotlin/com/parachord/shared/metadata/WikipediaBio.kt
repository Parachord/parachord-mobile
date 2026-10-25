package com.parachord.shared.metadata

/**
 * A Wikipedia article as an artist bio. Port of desktop
 * `src/renderer/lib/wikipedia-bio.js` (parachord#1134, mobile #429); keep the
 * two in step.
 *
 * Mobile showed the whole plain-text article: for Wild Pink, 294 words with
 * the band-member and EP lists, empty "Studio albums" headings, "References"
 * and "External links", and the headings as raw `== History ==` markup.
 * Desktop showed the lead only (19 words). Both now take the whole article
 * and keep the lead plus the prose sections:
 *
 * - a lead of [LEAD_ENOUGH_WORDS] or more is the whole bio (a big artist's
 *   lead is already a full summary);
 * - otherwise prose paragraphs from the following sections are added, in
 *   article order, until the bio reaches [TARGET_WORDS] (whole paragraphs);
 * - sections that aren't a bio ([DROP_SECTION]: references, discography,
 *   members, ...) are skipped with all their subsections;
 * - inside a kept section only prose survives: at least [MIN_PROSE_WORDS]
 *   words, ending like a sentence. List items don't;
 * - headings are dropped; paragraphs are separated by a blank line.
 */
object WikipediaBio {
    const val LEAD_ENOUGH_WORDS = 150
    const val TARGET_WORDS = 400
    const val MIN_PROSE_WORDS = 4

    val DROP_SECTION = Regex(
        "^(?:" + listOf(
            "references", "external links", "see also", "notes", "notes and references", "further reading",
            "bibliography", "sources", "citations", "footnotes", "works cited",
            "discography", "selected discography", "filmography", "videography",
            "(?:band )?members", "personnel", "line-?up",
            "(?:concert )?tours", "tours and concerts",
            "accolades", "awards(?: and (?:nominations|honou?rs|accolades))?", "honou?rs",
            "timeline(?: of .*)?", "lists? of .*",
        ).joinToString("|") + ")$",
        RegexOption.IGNORE_CASE,
    )

    private val HEADING = Regex("^(={2,6})\\s*(.+?)\\s*\\1\\s*$")
    private val SENTENCE_END = Regex("[.!?…][\"'”’)\\]]*$")
    private val WORD = Regex("\\S+")

    data class Section(val level: Int, val title: String, val paragraphs: List<String>)
    data class Split(val lead: List<String>, val sections: List<Section>)

    fun wordCount(text: String): Int = WORD.findAll(text).count()

    fun isProse(paragraph: String): Boolean =
        SENTENCE_END.containsMatchIn(paragraph) && wordCount(paragraph) >= MIN_PROSE_WORDS

    /** The plain-text extract into its lead and its sections, blank lines dropped. */
    fun splitExtract(extract: String?): Split {
        val lead = mutableListOf<String>()
        val sections = mutableListOf<Triple<Int, String, MutableList<String>>>()
        for (raw in extract.orEmpty().split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val heading = HEADING.matchEntire(line)
            when {
                heading != null -> sections += Triple(heading.groupValues[1].length, heading.groupValues[2], mutableListOf())
                sections.isNotEmpty() -> sections.last().third += line
                else -> lead += line
            }
        }
        return Split(lead, sections.map { (level, title, paragraphs) -> Section(level, title, paragraphs) })
    }

    /** The bio text, or null when the article has nothing to show. */
    fun fromExtract(
        extract: String?,
        leadEnoughWords: Int = LEAD_ENOUGH_WORDS,
        targetWords: Int = TARGET_WORDS,
    ): String? {
        val (lead, sections) = splitExtract(extract)
        val paragraphs = lead.toMutableList()
        var words = wordCount(lead.joinToString(" "))

        if (words < leadEnoughWords) {
            var droppingBelowLevel = 0 // inside a dropped section: skip until a heading at this level or above
            for (section in sections) {
                if (words >= targetWords) break
                if (droppingBelowLevel != 0 && section.level > droppingBelowLevel) continue
                droppingBelowLevel = if (DROP_SECTION.matches(section.title)) section.level else 0
                if (droppingBelowLevel != 0) continue
                for (paragraph in section.paragraphs) {
                    if (words >= targetWords) break
                    if (!isProse(paragraph)) continue
                    paragraphs += paragraph
                    words += wordCount(paragraph)
                }
            }
        }
        return if (paragraphs.isEmpty()) null else paragraphs.joinToString("\n\n")
    }
}
