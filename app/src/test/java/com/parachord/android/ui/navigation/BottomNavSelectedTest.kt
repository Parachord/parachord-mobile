package com.parachord.android.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which bottom-bar tab shows as selected. */
class BottomNavSelectedTest {
    @Test
    fun `Collection is selected on its destination with arguments`() {
        assertTrue(bottomNavSelected(BottomNavItem.COLLECTION.route, sequenceOf(Routes.COLLECTION, null)))
    }

    @Test
    fun `a tab without arguments still matches exactly`() {
        assertTrue(bottomNavSelected(BottomNavItem.HOME.route, sequenceOf(Routes.HOME)))
        assertFalse(bottomNavSelected(BottomNavItem.HOME.route, sequenceOf(Routes.COLLECTION)))
    }

    @Test
    fun `the Add action is never selected`() {
        assertFalse(bottomNavSelected(BottomNavItem.ADD.route, sequenceOf(Routes.HOME, null)))
    }

    @Test
    fun `Collection links carry the tab and the songs' sort only when given`() {
        org.junit.Assert.assertEquals("collection", Routes.collection())
        org.junit.Assert.assertEquals("collection?tab=2", Routes.collection(2))
        org.junit.Assert.assertEquals("collection?tab=2&sort=RECENT", Routes.collection(2, "RECENT"))
        org.junit.Assert.assertEquals("collection?sort=RECENT", Routes.collection(0, "RECENT"))
        assertTrue(bottomNavSelected(BottomNavItem.COLLECTION.route, sequenceOf(Routes.collection(2, "RECENT"))))
    }
}
