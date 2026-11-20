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
}
