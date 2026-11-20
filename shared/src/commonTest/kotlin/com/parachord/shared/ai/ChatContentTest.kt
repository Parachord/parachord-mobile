package com.parachord.shared.ai

import com.parachord.shared.ai.ChatBlock.Blank
import com.parachord.shared.ai.ChatBlock.CardLine
import com.parachord.shared.ai.ChatBlock.Header
import com.parachord.shared.ai.ChatBlock.ListItem
import com.parachord.shared.ai.ChatBlock.Line
import com.parachord.shared.ai.ChatInline.Bold
import com.parachord.shared.ai.ChatInline.Card
import com.parachord.shared.ai.ChatInline.Code
import com.parachord.shared.ai.ChatInline.Italic
import com.parachord.shared.ai.ChatInline.Link
import com.parachord.shared.ai.ChatInline.Text
import kotlin.test.Test
import kotlin.test.assertEquals

/** Desktop's `renderChatContent` (`app.js` ~L17573) and `convertPlainTextToCards` (~L3247). */
class ChatContentTest {

    private val creep = ChatCardRef.Track("Creep", "Radiohead", "Pablo Honey")
    private val karma = ChatCardRef.Track("Karma Police", "Radiohead", "")

    @Test
    fun `text after an inline card drops its leading punctuation`() {
        assertEquals(
            listOf(Text("Try "), Card(creep), Card(karma), Text("and more")),
            ChatContent.inlines("Try {{track|Creep|Radiohead|Pablo Honey}}, {{track|Karma Police|Radiohead}}, and more"),
        )
    }

    @Test
    fun `text after the last card drops its leading punctuation and asterisks`() {
        assertEquals(
            listOf(Card(creep), Text("It shares a mood")),
            ChatContent.inlines("{{track|Creep|Radiohead|Pablo Honey}}**. It shares a mood"),
        )
        assertEquals(listOf(Card(creep)), ChatContent.inlines("{{track|Creep|Radiohead|Pablo Honey}}."))
    }

    @Test
    fun `an orphaned bold marker before a card goes`() {
        assertEquals(
            listOf(Text("Listen to "), Card(creep)),
            ChatContent.inlines("Listen to **{{track|Creep|Radiohead|Pablo Honey}}"),
        )
    }

    @Test
    fun `a card inside bold is still a card`() {
        assertEquals(
            listOf(Text("Hear "), Card(ChatCardRef.Artist("Radiohead"))),
            ChatContent.inlines("Hear **{{artist|Radiohead}}**"),
        )
    }

    @Test
    fun `links bold italic and code`() {
        assertEquals(
            listOf(
                Text("See "), Link("docs", "https://example.com"), Text(" and "), Bold("this"),
                Text(" in "), Code("x = 1"),
            ),
            ChatContent.inlines("See [docs](https://example.com) and **this** in `x = 1`"),
        )
        assertEquals(listOf(Text("a "), Italic("soft"), Text(" word")), ChatContent.inlines("a *soft* word"))
    }

    @Test
    fun `an image link reads as a link`() {
        assertEquals(listOf(Link("cover", "https://x.test/a.jpg")), ChatContent.inlines("![cover](https://x.test/a.jpg)"))
        assertEquals(listOf(Link("a.jpg", "https://x.test/a.jpg")), ChatContent.inlines("![](https://x.test/a.jpg)"))
    }

    @Test
    fun `a bare url stops before sentence punctuation`() {
        assertEquals(
            listOf(Text("visit "), Link("https://X.com", "https://X.com"), Text(". Then")),
            ChatContent.inlines("visit https://X.com. Then"),
        )
    }

    @Test
    fun `a line after a card line drops its leading punctuation`() {
        assertEquals(
            listOf(CardLine(creep), Line(listOf(Text("It is a classic.")))),
            ChatContent.blocks("{{track|Creep|Radiohead|Pablo Honey}}\n. It is a classic."),
        )
    }

    @Test
    fun `only the line straight after a card line is stripped`() {
        assertEquals(
            listOf(CardLine(creep), Line(listOf(Text("ok"))), Line(listOf(Text(", kept")))),
            ChatContent.blocks("{{track|Creep|Radiohead|Pablo Honey}}\n, ok\n, kept"),
        )
    }

    @Test
    fun `headers lists and blank lines`() {
        assertEquals(
            listOf(
                Header(2, "Picks"),
                ListItem("1", listOf(Card(creep)), isCard = true),
                ListItem("2", listOf(Text("A "), Bold("note")), isCard = false),
                Blank,
                Line(listOf(Text("Done"))),
            ),
            ChatContent.blocks("## Picks\n1. {{track|Creep|Radiohead|Pablo Honey}}\n2. A **note**\n\nDone"),
        )
    }

    @Test
    fun `album artist and playlist cards`() {
        assertEquals(
            listOf(
                CardLine(ChatCardRef.Album("Kid A", "Radiohead")),
                CardLine(ChatCardRef.Artist("Radiohead")),
                CardLine(ChatCardRef.Playlist("Rainy Day", "pl-1", "12")),
            ),
            ChatContent.blocks("{{album|Kid A|Radiohead}}\n{{artist|Radiohead|}}\n{{playlist|Rainy Day|pl-1|12}}"),
        )
    }

    @Test
    fun `two cards on one line are two cards`() {
        // Desktop's whole-line card pattern is greedy and reads this line as one
        // card with mangled fields; the phones read it as two cards.
        assertEquals(
            listOf(Line(listOf(Card(creep), Text("and "), Card(karma)))),
            ChatContent.blocks("{{track|Creep|Radiohead|Pablo Honey}} and {{track|Karma Police|Radiohead}}"),
        )
    }

    @Test
    fun `message tracks lists the track cards with a title and an artist`() {
        assertEquals(
            listOf(creep, ChatCardRef.Track("Karma Police", "Radiohead", "")),
            ChatContent.messageTracks("{{track|Creep|Radiohead|Pablo Honey}} {{track|Karma Police|Radiohead}} {{track|Lonely|}}"),
        )
    }

    @Test
    fun `plain text cards are left alone when the model used cards`() {
        val text = "**Radiohead** {{track|Creep|Radiohead|}}"
        assertEquals(text, ChatContent.convertPlainTextToCards(text))
    }

    @Test
    fun `plain text bold names become artist cards and quoted songs track cards`() {
        assertEquals(
            "{{artist|Radiohead|}} — try {{track|Creep|Radiohead|}}",
            ChatContent.convertPlainTextToCards("**Radiohead** — try their song \"Creep\""),
        )
    }

    @Test
    fun `plain text album and track from album`() {
        assertEquals(
            "{{artist|Radiohead|}}: {{track|Airbag|Radiohead|OK Computer}} and {{album|Kid A|Radiohead|}}",
            ChatContent.convertPlainTextToCards("**Radiohead**: song \"Airbag\" from the album \"OK Computer\" and the album \"Kid A\""),
        )
    }

    @Test
    fun `plain text skips label words`() {
        assertEquals("**Note:** keep it", ChatContent.convertPlainTextToCards("**Note:** keep it"))
    }

    @Test
    fun `plain text quoted title by artist`() {
        assertEquals(
            "Try {{track|Creep|Radiohead|}} tonight",
            ChatContent.convertPlainTextToCards("Try \"Creep\" by Radiohead tonight"),
        )
    }
}
