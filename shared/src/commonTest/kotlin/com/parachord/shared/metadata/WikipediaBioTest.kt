package com.parachord.shared.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of desktop tests/renderer/wikipedia-bio.test.js (parachord#1134, mobile #429). */
class WikipediaBioTest {
    private fun words(n: Int, end: String = ".") = List(n) { "w$it" }.joinToString(" ") + end
    private fun count(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() }

    // Same shape as Wild Pink's plain-text extract (2026-10-25): a one-sentence
    // lead, a History section, then member and discography lists and the link
    // sections, some of them empty.
    private val shortLeadArticle = listOf(
        "The Band is an American indie rock band from New York City.",
        "", "",
        "== History ==",
        "The Band began in 2015, releasing an EP titled 2 Songs. They signed to a label in 2016.",
        "In 2020, the band contributed to a benefit compilation.",
        "", "",
        "== Band members ==",
        "", "",
        "=== Current ===",
        "Jane Doe (vocals, guitar)",
        "John Roe (bass)",
        "", "",
        "=== Former ===",
        "A. N. Other",
        "", "",
        "== Discography ==",
        "", "",
        "=== Studio albums ===",
        "", "",
        "=== EPs ===",
        "2 Songs (2015, self-released)",
        "Good Life (2015, Texas Is Funny)",
        "", "",
        "== References ==",
        "", "",
        "=== External links ===",
        "The Band on Bandcamp",
        "The Band on Facebook",
    ).joinToString("\n")

    @Test fun aShortLeadGetsTheHistoryProseAndNothingAfterIt() {
        assertEquals(
            listOf(
                "The Band is an American indie rock band from New York City.",
                "The Band began in 2015, releasing an EP titled 2 Songs. They signed to a label in 2016.",
                "In 2020, the band contributed to a benefit compilation.",
            ).joinToString("\n\n"),
            WikipediaBio.fromExtract(shortLeadArticle),
        )
    }

    @Test fun noHeadingMarkupListItemsOrLinkStubsSurvive() {
        val bio = WikipediaBio.fromExtract(shortLeadArticle)!!
        assertFalse(bio.contains("=="))
        for (junk in listOf("Band members", "Jane Doe", "Good Life (2015", "Bandcamp", "References", "Studio albums")) {
            assertFalse(bio.contains(junk), junk)
        }
    }

    @Test fun aLongLeadIsTheWholeBio() {
        val lead = listOf(words(80), words(80))
        val article = (lead + listOf("", "== History ==", words(200))).joinToString("\n")
        assertEquals(lead.joinToString("\n\n"), WikipediaBio.fromExtract(article))
    }

    @Test fun sectionsAreAddedInOrderUntilTheTarget_inWholeParagraphs() {
        val article = listOf(
            words(20),
            "== Early life ==", words(150),
            "== Career ==", words(150), words(150), words(150),
            "== Legacy ==", words(150),
        ).joinToString("\n")
        val bio = WikipediaBio.fromExtract(article)!!
        // 20 + 150 + 150 = 320 < 400, so one more paragraph; 470 >= 400 stops.
        assertEquals(listOf(20, 150, 150, 150), bio.split("\n\n").map(::count))
        assertEquals(470, count(bio))
    }

    @Test fun aDroppedSectionTakesItsSubsections_aSiblingAfterItIsReadAgain() {
        val article = listOf(
            words(10),
            "== Discography ==",
            "=== Studio albums ===", "Album One is the first album by the band and it is fine.",
            "== Musical style ==", "Their sound mixes folk with rock and a lot of reverb.",
            "=== Influences ===", "They cite many bands from the nineties as influences.",
        ).joinToString("\n")
        assertEquals(
            listOf(
                words(10),
                "Their sound mixes folk with rock and a lot of reverb.",
                "They cite many bands from the nineties as influences.",
            ).joinToString("\n\n"),
            WikipediaBio.fromExtract(article),
        )
    }

    @Test fun nullForAnEmptyArticle_theLeadAloneWhenNothingElseIsProse() {
        assertNull(WikipediaBio.fromExtract(""))
        assertNull(WikipediaBio.fromExtract(null))
        assertEquals("Short lead.", WikipediaBio.fromExtract("Short lead.\n== References ==\nSome Ref"))
    }

    @Test fun limitsAreAdjustable() {
        val article = listOf(words(10), "== History ==", words(50), words(50)).joinToString("\n")
        assertEquals(60, count(WikipediaBio.fromExtract(article, targetWords = 30)!!))
        assertEquals(10, count(WikipediaBio.fromExtract(article, leadEnoughWords = 5)!!))
    }

    @Test fun splitExtractSeparatesTheLeadFromTheSectionsAndTheirLevels() {
        val (lead, sections) = WikipediaBio.splitExtract(shortLeadArticle)
        assertEquals(listOf("The Band is an American indie rock band from New York City."), lead)
        assertEquals(
            listOf(
                "2:History", "2:Band members", "3:Current", "3:Former", "2:Discography",
                "3:Studio albums", "3:EPs", "2:References", "3:External links",
            ),
            sections.map { "${it.level}:${it.title}" },
        )
    }

    @Test fun isProseWantsASentenceEndingAndAFewWords() {
        assertTrue(WikipediaBio.isProse("The band broke up in 2019."))
        assertTrue(WikipediaBio.isProse("The compilation was titled \"Bernie Speaks With the Community.\""))
        assertFalse(WikipediaBio.isProse("Jane Doe (vocals, guitar)"))
        assertFalse(WikipediaBio.isProse("Good Life (2015, Texas Is Funny)"))
        assertFalse(WikipediaBio.isProse("Yes."))
    }

    @Test fun theSectionsThatAreNotABio() {
        for (title in listOf(
            "References", "External links", "See also", "Notes", "Further reading", "Discography",
            "Band members", "Members", "Personnel", "Concert tours", "Accolades", "Awards and nominations",
            "Timeline of official members", "Filmography", "Videography", "Sources",
        )) assertTrue(WikipediaBio.DROP_SECTION.matches(title), title)
        for (title in listOf("History", "Career", "Early life", "Musical style", "Legacy", "Personal life", "Artistry")) {
            assertFalse(WikipediaBio.DROP_SECTION.matches(title), title)
        }
    }
}
