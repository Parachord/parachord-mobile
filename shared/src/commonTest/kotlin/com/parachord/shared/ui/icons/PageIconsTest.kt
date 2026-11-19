package com.parachord.shared.ui.icons

import kotlin.test.Test
import kotlin.test.assertEquals

class PageIconsTest {

    /** Desktop's `PAGE_ICONS` (src/renderer/lib/page-icons.js), by desktop's page key. */
    private val desktop = mapOf(
        "home" to "Home",
        "search" to "Search",
        "playlists" to "ListMusic",
        "library" to "Library",
        "history" to "RotateCcw",
        "new-releases" to "Droplet",
        "recommendations" to "Radar",
        "discover" to "ListOrdered",
        "critics-picks" to "Trophy",
        "concerts" to "Ticket",
        "settings" to "Settings",
    )

    private val pageKey = mapOf(
        PageIcon.HOME to "home",
        PageIcon.SEARCH to "search",
        PageIcon.PLAYLISTS to "playlists",
        PageIcon.COLLECTION to "library",
        PageIcon.HISTORY to "history",
        PageIcon.FRESH_DROPS to "new-releases",
        PageIcon.RECOMMENDATIONS to "recommendations",
        PageIcon.POP_OF_THE_TOPS to "discover",
        PageIcon.CRITICAL_DARLINGS to "critics-picks",
        PageIcon.CONCERTS to "concerts",
        PageIcon.SETTINGS to "settings",
    )

    @Test
    fun everyPageDrawsDesktopsIcon() {
        assertEquals(PageIcon.entries.toSet(), pageKey.keys)
        PageIcon.entries.forEach { page ->
            assertEquals(desktop.getValue(pageKey.getValue(page)), page.icon.name, "$page")
        }
    }
}
