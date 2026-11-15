package com.parachord.shared.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesignTokensTest {

    @AfterTest
    fun reset() = ServiceColors.update(emptyMap())

    @Test
    fun parsesManifestHex() {
        assertEquals(0x1DB954, ServiceColors.parseHex("#1DB954"))
        assertEquals(0x6366F1, ServiceColors.parseHex("6366f1"))
        assertEquals(0xFFFFFF, ServiceColors.parseHex("#fff"))
        assertNull(ServiceColors.parseHex(""))
        assertNull(ServiceColors.parseHex("#12345"))
        assertNull(ServiceColors.parseHex("red"))
    }

    @Test
    fun manifestColourWinsOverFallback() {
        assertEquals(0x6366F1, ServiceColors.color("localfiles"))
        ServiceColors.update(mapOf("localfiles" to "#123456", "newservice" to "#abcdef", "bad" to "nope"))
        assertEquals(0x123456, ServiceColors.color("localfiles"))
        assertEquals(0xABCDEF, ServiceColors.color("NewService"))
        assertEquals(ServiceColors.DEFAULT, ServiceColors.color("bad"))
        assertEquals(0x1DB954, ServiceColors.color("spotify")) // fallback still answers
    }

    @Test
    fun unknownServiceIsBrandPurple() {
        assertNull(ServiceColors.colorOrNull("nothing"))
        assertNull(ServiceColors.colorOrNull(null))
        assertEquals(DesignTokens.FILL_ACCENT, ServiceColors.color("nothing"))
        assertEquals(0xFA243C, ServiceColors.color("Apple Music"))
    }

    @Test
    fun fillAccentIsTheLightAccentInBothThemes() {
        assertEquals(DesignTokens.accent.light, DesignTokens.FILL_ACCENT)
        assertEquals(0x8B5CF6, DesignTokens.accentSurface.dark)
        assertEquals(0.15f, DesignTokens.accentSurface.darkAlpha)
        DesignTokens.Page.entries.forEach { assertEquals(it.fill, it.fill and 0xFFFFFF) }
        assertEquals(DesignTokens.FILL_ACCENT, DesignTokens.Page.HOME.fill)
    }

    @Test
    fun pageColoursMatchDesktop() {
        assertEquals(0x3B82F6, DesignTokens.Page.HISTORY.color)
        assertEquals(0x10C9B4, DesignTokens.Page.CONCERTS.color)
        assertEquals(0xF97316, DesignTokens.Page.POP_OF_THE_TOPS.color)
        assertEquals(0x22C55E, DesignTokens.Page.FRESH_DROPS.color)
        // red → rose: desktop stopped it starting in Recommendations' amber
        assertEquals(listOf(0xEF4444, 0xDC2626, 0xBE123C), DesignTokens.Page.CRITICAL_DARLINGS.gradient)
        assertEquals(listOf(0x0EA5E9, 0x3B82F6, 0x4F46E5), DesignTokens.Page.HISTORY.gradient)
    }

    @Test
    fun noTwoHeroesStartTheSame() {
        // desktop's tests/renderer/page-colors.test.js checks colour families;
        // the first stops being distinct is the part we can check exactly.
        val starts = DesignTokens.Page.entries.map { it.gradient.first() }
        assertEquals(starts.size, starts.toSet().size)
    }

    @Test
    fun oneReleaseTypeMap() {
        assertEquals(0x6366F1, DesignTokens.releaseTypeColor("Album"))
        assertEquals(0xA855F7, DesignTokens.releaseTypeColor("ep"))
        assertEquals(0xDB2777, DesignTokens.releaseTypeColor("single"))
        assertEquals(0x9CA3AF, DesignTokens.releaseTypeColor(null))
    }
}
