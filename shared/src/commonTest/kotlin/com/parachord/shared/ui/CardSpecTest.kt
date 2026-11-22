package com.parachord.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardSpecTest {

    @Test
    fun cardIsTheArtPlusItsPadding() {
        assertEquals(160, CardSpec.cardWidth(140))
    }

    @Test
    fun rankReadsAsDesktopsBadge() {
        assertEquals("#3", CardSpec.rankLabel(3))
    }

    @Test
    fun releaseTypePillTakesTheTypesColourAt90() {
        val pill = CardPill.releaseType("ep")!!
        assertEquals("EP", pill.text)
        assertEquals(DesignTokens.releaseTypeColor("ep"), pill.color)
        assertEquals(0.9f, pill.alpha)
        assertNull(CardPill.releaseType(null))
    }

    @Test
    fun comingPillIsTheFillAccent() {
        assertEquals(CardPill("Coming Dec 5", DesignTokens.FILL_ACCENT), CardPill.coming("Coming Dec 5"))
        assertNull(CardPill.coming(" "))
        assertNull(CardPill.coming(null))
    }
}
