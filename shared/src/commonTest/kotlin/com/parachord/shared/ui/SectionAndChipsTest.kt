package com.parachord.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SectionAndChipsTest {

    // Desktop's tests/renderer/readable-text.test.js vectors.
    @Test
    fun lightAndMidBrandColoursGetDarkText() {
        assertEquals(ReadableText.DARK, ReadableText.on(0xFA243C)) // Apple Music
        assertEquals(ReadableText.DARK, ReadableText.on(0x25D1DA)) // Amazon Music
        assertEquals(ReadableText.DARK, ReadableText.on(0x1DB954)) // Spotify
    }

    @Test
    fun darkAndDeepColoursGetWhite() {
        assertEquals(ReadableText.LIGHT, ReadableText.on(0x333333))
        assertEquals(ReadableText.LIGHT, ReadableText.on(0x7C3AED))
        assertEquals(ReadableText.LIGHT, ReadableText.on(0x000000))
        assertEquals(ReadableText.LIGHT, ReadableText.on(null))
    }

    @Test
    fun idleChipIsNeutralWithItsDot() {
        assertEquals(FilterChipSpec.Look(null, null, 0x1DB954), FilterChipSpec.look(false, 0x1DB954))
        assertEquals(FilterChipSpec.Look(null, null, null), FilterChipSpec.look(false, null))
    }

    @Test
    fun pickedColouredChipFillsWithItsColour() {
        assertEquals(FilterChipSpec.Look(0x1DB954, ReadableText.DARK, null), FilterChipSpec.look(true, 0x1DB954))
        assertEquals(FilterChipSpec.Look(0x6366F1, ReadableText.LIGHT, null), FilterChipSpec.look(true, 0x6366F1))
    }

    @Test
    fun pickedNeutralChipFillsWithTheAccentOrThePagesFill() {
        assertEquals(FilterChipSpec.Look(DesignTokens.FILL_ACCENT, 0xFFFFFF, null), FilterChipSpec.look(true, null))
        val fresh = DesignTokens.Page.FRESH_DROPS
        assertEquals(FilterChipSpec.Look(fresh.fill, 0xFFFFFF, null), FilterChipSpec.look(true, null, fresh.fill))
    }

    @Test
    fun pageFillsCarryWhiteText() {
        DesignTokens.Page.entries.forEach { assertEquals(ReadableText.LIGHT, ReadableText.on(it.fill), it.name) }
    }

    @Test
    fun seeAllNamesThePage() {
        assertEquals("See all in Playlists", SectionHeaderSpec.seeAllLabel(DesignTokens.Page.PLAYLISTS))
    }
}
