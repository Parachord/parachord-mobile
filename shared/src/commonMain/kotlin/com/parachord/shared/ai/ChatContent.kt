package com.parachord.shared.ai

/** A `{{type|…}}` card in a Shuffleupagus message. */
sealed class ChatCardRef {
    /** `{{track|title|artist|album}}`; [album] is blank when the model gave none. */
    data class Track(val title: String, val artist: String, val album: String) : ChatCardRef()
    /** `{{album|title|artist}}`. */
    data class Album(val title: String, val artist: String) : ChatCardRef()
    /** `{{artist|name}}`. */
    data class Artist(val name: String) : ChatCardRef()
    /** `{{playlist|name|id|trackCount}}`. */
    data class Playlist(val name: String, val id: String, val trackCount: String) : ChatCardRef()
}

/** A run inside one line of a message. */
sealed class ChatInline {
    data class Text(val text: String) : ChatInline()
    data class Card(val card: ChatCardRef) : ChatInline()
    /** `[text](url)`, `![alt](url)` and a bare `https://` URL (its text is the URL). */
    data class Link(val text: String, val url: String) : ChatInline()
    data class Bold(val text: String) : ChatInline()
    data class Italic(val text: String) : ChatInline()
    data class Code(val text: String) : ChatInline()
}

/** One line of a message, as desktop lays it out. */
sealed class ChatBlock {
    /** `#` to `######`; [text] is shown as is (desktop doesn't parse inline markdown in a header). */
    data class Header(val level: Int, val text: String) : ChatBlock()
    /** A line that is exactly one card. */
    data class CardLine(val card: ChatCardRef) : ChatBlock()
    /**
     * `1. …`. When the item is a card ([isCard]), desktop centres the number on
     * the card and packs the rows 4 px apart; a text item keeps the line's top
     * alignment and a 12 px gap.
     */
    data class ListItem(val number: String, val inlines: List<ChatInline>, val isCard: Boolean) : ChatBlock()
    /** An empty line: 8 px of space. */
    data object Blank : ChatBlock()
    data class Line(val inlines: List<ChatInline>) : ChatBlock()
}

/**
 * How a Shuffleupagus message is laid out, ported from desktop's
 * `renderChatContent` (`app.js` ~L17573) so both phones draw the same thing:
 * - **Cards win:** a `{{card}}` inside bold or italic is still a card.
 * - **Punctuation around cards goes.** Text right after a card drops its
 *   leading `[\s.,!?;:*]+` ("…{{card}}, {{card}}." doesn't leave lone commas),
 *   text right before a card drops a trailing `*`-run (an orphaned bold
 *   marker), and a line after a card line drops its leading `[\s.,!?;:]+`.
 * - Headers, numbered items (a card item centres its number), blank lines,
 *   links (an image link reads as a link), bare URLs (stopping before
 *   sentence punctuation), bold, italic and code.
 *
 * One difference: desktop's whole-line card pattern is greedy, so a line of
 * two cards ("{{a}} and {{b}}") read as one card with mangled fields; here a
 * card line is exactly one card, and that line is two cards.
 *
 * [convertPlainTextToCards] is desktop's `AIChatService.convertPlainTextToCards`
 * (~L3247): the fallback for a model that wrote names in prose.
 */
object ChatContent {

    private val CARD = Regex("""\{\{(track|artist|album|playlist)\|([^}]+)\}\}""")
    private val CARD_LINE = Regex("""^\{\{(track|artist|album|playlist)\|([^}]+)\}\}$""")
    private val HEADER = Regex("""^(#{1,6})\s+(.+)$""")
    private val LIST_ITEM = Regex("""^(\d+)\.\s+(.+)$""")
    private val STARTS_WITH_CARD = Regex("""^\s*\{\{""")
    private val AFTER_CARD_INLINE = Regex("""^[\s.,!?;:*]+""")
    private val AFTER_CARD_LINE = Regex("""^[\s.,!?;:]+""")
    private val ORPHAN_MARKER = Regex("""\*+\s*$""")

    private enum class Kind { CARD, IMAGE_LINK, LINK, BOLD, ITALIC, CODE, BARE_URL }

    /** In desktop's order: cards first, then image links, links, bold, italic, code, bare URLs. */
    private val PATTERNS = listOf(
        Kind.CARD to CARD,
        Kind.IMAGE_LINK to Regex("""!\[([^\]]*)\]\(([^)]+)\)"""),
        Kind.LINK to Regex("""\[([^\]]+)\]\(([^)]+)\)"""),
        Kind.BOLD to Regex("""\*\*([^*]+)\*\*"""),
        Kind.ITALIC to Regex("""\*([^*]+)\*"""),
        Kind.CODE to Regex("""`([^`]+)`"""),
        Kind.BARE_URL to Regex("""\bhttps?://\S+?(?=[.,;:!?)\]]*(?:\s|$))"""),
    )

    private class Hit(val kind: Kind, val match: MatchResult) {
        val start get() = match.range.first
        val end get() = match.range.last + 1
    }

    /** The message's lines, laid out. */
    fun blocks(content: String): List<ChatBlock> {
        if (content.isEmpty()) return emptyList()
        val blocks = mutableListOf<ChatBlock>()
        var previousLineWasCard = false
        for (line in content.split('\n')) {
            val processed = if (previousLineWasCard) line.replace(AFTER_CARD_LINE, "") else line
            previousLineWasCard = false
            val header = HEADER.find(processed)
            val cardLine = CARD_LINE.find(processed)
            val listItem = LIST_ITEM.find(processed)
            when {
                header != null -> blocks += ChatBlock.Header(header.groupValues[1].length, header.groupValues[2])
                cardLine != null -> {
                    val card = card(cardLine.groupValues[1], cardLine.groupValues[2])
                    if (card != null) blocks += ChatBlock.CardLine(card)
                    previousLineWasCard = true
                }
                listItem != null -> {
                    val itemText = listItem.groupValues[2]
                    blocks += ChatBlock.ListItem(listItem.groupValues[1], inlines(itemText), STARTS_WITH_CARD.containsMatchIn(itemText))
                }
                processed.isBlank() -> blocks += ChatBlock.Blank
                else -> blocks += ChatBlock.Line(inlines(processed))
            }
        }
        return blocks
    }

    /** One line's runs. */
    fun inlines(text: String): List<ChatInline> {
        val hits = PATTERNS.flatMap { (kind, regex) -> regex.findAll(text).map { Hit(kind, it) }.toList() }
            .sortedWith { a, b ->
                when {
                    a.start != b.start -> a.start - b.start
                    a.kind == Kind.CARD && b.kind != Kind.CARD -> -1
                    b.kind == Kind.CARD && a.kind != Kind.CARD -> 1
                    else -> 0
                }
            }

        // Drop overlaps; a card inside another match replaces it.
        val kept = mutableListOf<Hit>()
        for (hit in hits) {
            val overlapping = kept.firstOrNull { f ->
                (hit.start >= f.start && hit.start < f.end) ||
                    (hit.end > f.start && hit.end <= f.end) ||
                    (hit.start <= f.start && hit.end >= f.end)
            }
            if (overlapping == null) {
                kept += hit
            } else if (hit.kind == Kind.CARD && overlapping.kind != Kind.CARD) {
                kept.remove(overlapping)
                kept += hit
                kept.sortBy { it.start }
            }
        }

        val result = mutableListOf<ChatInline>()
        var pos = 0
        var lastWasCard = false
        for (hit in kept) {
            if (hit.start > pos) {
                var before = text.substring(pos, hit.start)
                if (lastWasCard) before = before.replace(AFTER_CARD_INLINE, "")
                if (hit.kind == Kind.CARD) before = before.replace(ORPHAN_MARKER, "")
                if (before.isNotEmpty()) result += ChatInline.Text(before)
            }
            lastWasCard = false
            val g = hit.match.groupValues
            when (hit.kind) {
                Kind.CARD -> {
                    card(g[1], g[2])?.let { result += ChatInline.Card(it) }
                    lastWasCard = true
                }
                Kind.IMAGE_LINK, Kind.LINK -> {
                    val url = g[2]
                    result += ChatInline.Link(g[1].ifEmpty { url.substringAfterLast('/') }, url)
                }
                Kind.BARE_URL -> result += ChatInline.Link(g[0], g[0])
                Kind.BOLD -> result += ChatInline.Bold(g[1])
                Kind.ITALIC -> result += ChatInline.Italic(g[1])
                Kind.CODE -> result += ChatInline.Code(g[1])
            }
            pos = hit.end
        }
        if (pos < text.length) {
            var remaining = text.substring(pos)
            if (lastWasCard) remaining = remaining.replace(AFTER_CARD_INLINE, "")
            if (remaining.isNotEmpty()) result += ChatInline.Text(remaining)
        }
        return result.ifEmpty { listOf(ChatInline.Text(text)) }
    }

    /**
     * The tracks a message lists (`{{track|title|artist|album}}` with a title
     * and an artist): desktop's `messageTracks`, which a playlist card the
     * model never saved opens as a temporary playlist.
     */
    fun messageTracks(content: String): List<ChatCardRef.Track> =
        Regex("""\{\{track\|([^}]+)\}\}""").findAll(content).mapNotNull { m ->
            val parts = m.groupValues[1].split('|')
            val title = parts.getOrElse(0) { "" }.trim()
            val artist = parts.getOrElse(1) { "" }.trim()
            if (title.isEmpty() || artist.isEmpty()) null
            else ChatCardRef.Track(title, artist, parts.getOrElse(2) { "" }.trim())
        }.toList()

    private fun card(type: String, fields: String): ChatCardRef? {
        val p = fields.split('|').map { it.trim() }
        fun at(i: Int) = p.getOrElse(i) { "" }
        return when (type) {
            "track" -> ChatCardRef.Track(at(0), at(1), at(2))
            "album" -> ChatCardRef.Album(at(0), at(1))
            "artist" -> ChatCardRef.Artist(at(0))
            "playlist" -> ChatCardRef.Playlist(at(0), at(1), at(2))
            else -> null
        }
    }

    // ── convertPlainTextToCards ─────────────────────────────────────

    private val ANY_CARD = Regex("""\{\{(track|artist|album|playlist)\|""")
    private val BOLD_NAME = Regex("""\*\*([^*]+?)\*\*""")
    private val LABEL_WORD = Regex("""^(Note|Warning|Important|Recommendations?|Here\b|Summary|Tip):?""", RegexOption.IGNORE_CASE)
    private val AGAIN = Regex("""\s*\(again!?\)\s*""", RegexOption.IGNORE_CASE)
    private val SONG_FROM_ALBUM = Regex(
        """(?:their\s+)?(?:song|track)\s+"([^"]+)"(?:\s+(?:from|off|on)\s+(?:the\s+)?(?:album|record|LP|EP)\s+"([^"]+)")?""",
        RegexOption.IGNORE_CASE,
    )
    private val ALBUM_QUOTED = Regex("""(?:the\s+)?(?:album|record|LP|EP)\s+"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val QUOTED = Regex(""""([^"]{2,80})"""")
    private val QUOTED_BY = Regex(""""([^"]{2,80})"\s+by\s+([A-Z][A-Za-z\s.'&-]+?)(?=[\s,.\n;)]|$)""")

    /**
     * A message's plain-text music references as cards, for a model that
     * wrote names instead of `{{cards}}`: a **bold** name is an artist, and
     * with an artist in context, `song "X"` (from `album "Y"`), `album "Y"`
     * and other quoted titles become its tracks and albums; `"X" by Artist`
     * works without context. A message that already has a card is left alone.
     */
    fun convertPlainTextToCards(content: String): String {
        if (content.isEmpty() || ANY_CARD.containsMatchIn(content)) return content
        var currentArtist: String? = null
        return content.split('\n').joinToString("\n") { line ->
            var result = line
            BOLD_NAME.find(result)?.let { bold ->
                val raw = bold.groupValues[1].trim()
                if (!LABEL_WORD.containsMatchIn(raw)) currentArtist = raw.replace(AGAIN, "").trim()
            }
            val artist = currentArtist
            if (artist != null) {
                result = SONG_FROM_ALBUM.replace(result) { m ->
                    "{{track|${m.groupValues[1]}|$artist|${m.groupValues[2]}}}"
                }
                result = ALBUM_QUOTED.replace(result) { m -> "{{album|${m.groupValues[1]}|$artist|}}" }
                result = BOLD_NAME.replace(result) { m ->
                    val name = m.groupValues[1]
                    if (LABEL_WORD.containsMatchIn(name.trim())) m.value
                    else "{{artist|${name.replace(AGAIN, "").trim()}|}}"
                }
                val before = result
                result = QUOTED.replace(result) { m ->
                    val title = m.groupValues[1]
                    if (before.contains("|$title|") || before.contains("|$title}")) m.value
                    else "{{track|$title|$artist|}}"
                }
            } else {
                result = BOLD_NAME.replace(result) { m ->
                    val name = m.groupValues[1]
                    if (LABEL_WORD.containsMatchIn(name.trim())) m.value else "{{artist|${name.trim()}|}}"
                }
            }
            QUOTED_BY.replace(result) { m ->
                if (m.value.contains("{{")) m.value
                else "{{track|${m.groupValues[1].trim()}|${m.groupValues[2].trim()}|}}"
            }
        }
    }
}
