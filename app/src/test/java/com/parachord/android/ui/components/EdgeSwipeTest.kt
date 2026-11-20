package com.parachord.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EdgeSwipeTest {
    private val w = 1000f
    private val h = 2000f
    private val zone = 60f
    private val band = 500f // band spans y 750..1250

    @Test fun `a touch at the left edge inside the band is the left edge`() =
        assertEquals(SwipeEdge.LEFT, swipeEdgeAt(10f, 1000f, w, h, zone, band))

    @Test fun `a touch at the right edge inside the band is the right edge`() =
        assertEquals(SwipeEdge.RIGHT, swipeEdgeAt(990f, 900f, w, h, zone, band))

    @Test fun `a touch away from the edges is neither`() =
        assertNull(swipeEdgeAt(500f, 1000f, w, h, zone, band))

    @Test fun `an edge touch above or below the band is left to Back`() {
        assertNull(swipeEdgeAt(10f, 700f, w, h, zone, band))
        assertNull(swipeEdgeAt(990f, 1300f, w, h, zone, band))
    }

    @Test fun `the bands are centred on each edge`() {
        val (left, right) = edgeSwipeBands(w, h, zone, band)
        assertEquals(0f, left.left); assertEquals(zone, left.right)
        assertEquals(750f, left.top); assertEquals(1250f, left.bottom)
        assertEquals(w - zone, right.left); assertEquals(w, right.right)
    }
}
