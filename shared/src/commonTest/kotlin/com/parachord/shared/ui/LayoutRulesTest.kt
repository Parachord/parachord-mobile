package com.parachord.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutRulesTest {
    @Test fun `a phone keeps 140 cards and scrolls`() =
        assertEquals(140.0, LayoutRules.rowCardWidth(available = 350.0, cards = 6, gap = 16.0))

    @Test fun `an iPad row fills its width`() {
        // 11-inch landscape: 1180 wide, 20 gutters.
        val w = LayoutRules.rowCardWidth(available = 1140.0, cards = 6, gap = 16.0)
        assertEquals((1140.0 - 5 * 16.0) / 6, w)
    }

    @Test fun `cards stop growing at the maximum`() =
        assertEquals(240.0, LayoutRules.rowCardWidth(available = 3000.0, cards = 6, gap = 16.0))

    @Test fun `no cards or no width keeps the phone size`() {
        assertEquals(140.0, LayoutRules.rowCardWidth(available = 900.0, cards = 0, gap = 16.0))
        assertEquals(140.0, LayoutRules.rowCardWidth(available = 0.0, cards = 6, gap = 16.0))
    }

    @Test fun `a phone grid keeps two columns`() =
        assertEquals(2, LayoutRules.gridColumns(available = 350.0, gap = 12.0))

    @Test fun `an iPad grid adds columns`() {
        assertEquals(7, LayoutRules.gridColumns(available = 1140.0, gap = 12.0))
        assertEquals(4, LayoutRules.gridColumns(available = 780.0, gap = 12.0))
    }

    @Test fun `artist circles use their own minimum`() {
        assertEquals(3, LayoutRules.gridColumns(available = 350.0, gap = 12.0, minCard = LayoutRules.GRID_CIRCLE_MIN, minColumns = 3))
        assertEquals(9, LayoutRules.gridColumns(available = 1140.0, gap = 12.0, minCard = LayoutRules.GRID_CIRCLE_MIN, minColumns = 3))
    }

    @Test fun `wide starts at 700`() {
        assertFalse(LayoutRules.isWide(699.0))
        assertTrue(LayoutRules.isWide(700.0))
        assertEquals(2, LayoutRules.jumpBackInColumns(390.0))
        assertEquals(4, LayoutRules.jumpBackInColumns(1180.0))
    }

    @Test fun `halves sit side by side only when both fit three cards`() {
        assertFalse(LayoutRules.halvesSideBySide(820.0))
        assertTrue(LayoutRules.halvesSideBySide(1180.0))
    }
}
