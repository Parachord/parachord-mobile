package com.parachord.shared.ui

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageHeroTest {

    @Test
    fun everyPageHasAPatternThatParses() {
        DesignTokens.Page.entries.forEach { page ->
            val pattern = PageHero.pattern(page) ?: return@forEach
            assertTrue(pattern.opacity > 0f && pattern.opacity <= 1f, page.name)
            assertTrue(pattern.tile.width > 0f && pattern.tile.width == pattern.tile.height, page.name)
            pattern.tile.parts.forEach { part -> assertTrue(part.commands.isNotEmpty(), "${page.name}: ${part.d}") }
        }
    }

    @Test
    fun searchIsTheNeutralHeroWithNoPattern() {
        assertNull(PageHero.pattern(DesignTokens.Page.SEARCH))
        assertEquals(listOf(0x374151, 0x4B5563, 0x374151), DesignTokens.Page.SEARCH.gradient)
    }

    @Test
    fun lineIsTheDescriptionThenTheCount() {
        assertEquals(
            "Top-rated albums from leading music publications · 12 albums",
            PageHero.line(DesignTokens.Page.CRITICAL_DARLINGS, 12),
        )
        assertEquals("1 concert", PageHero.line(DesignTokens.Page.CONCERTS, 1)?.substringAfter(" · "))
        assertEquals("Upcoming concerts from artists you love", PageHero.line(DesignTokens.Page.CONCERTS, 0))
        assertEquals("3 playlists", PageHero.line(DesignTokens.Page.PLAYLISTS, 3))
    }

    @Test
    fun aPageWithNothingToSayHasNoLine() {
        assertNull(PageHero.line(DesignTokens.Page.HOME))
        assertNull(PageHero.line(DesignTokens.Page.COLLECTION, 40))
        assertNull(PageHero.line(DesignTokens.Page.PLAYLISTS, null))
    }

    @Test
    fun aSelectedTabTakesItsPageColour() {
        val collection = DesignTokens.Page.COLLECTION
        assertEquals(collection.fill, PageHero.tabColor(collection, onDark = false))
        assertEquals(collection.color, PageHero.tabColor(collection, onDark = true))
        assertEquals(DesignTokens.accent.light, PageHero.tabColor(DesignTokens.Page.HOME, onDark = false))
        assertEquals(DesignTokens.accent.dark, PageHero.tabColor(DesignTokens.Page.SEARCH, onDark = true))
        // Text on the light tab bar reads at 4.5:1 or better.
        DesignTokens.Page.entries.forEach {
            val ratio = contrastOnWhite(PageHero.tabColor(it, onDark = false))
            assertTrue(ratio >= 4.5, "${it.name}: $ratio")
        }
    }

    @Test
    fun chartsLineCountsBothCharts() {
        assertEquals("What’s trending around the world · 50 albums · 1 track", PageHero.chartsLine(50, 1))
        assertEquals("What’s trending around the world", PageHero.chartsLine(0, 0))
    }

    private fun contrastOnWhite(rgb: Int): Double {
        fun ch(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
        val l = 0.2126 * ch(rgb shr 16 and 0xFF) + 0.7152 * ch(rgb shr 8 and 0xFF) + 0.0722 * ch(rgb and 0xFF)
        return 1.05 / (l + 0.05)
    }
}
