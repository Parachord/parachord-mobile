package com.parachord.android.ui.screens.settings

import com.parachord.shared.settings.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTabIndexTest {
    @Test
    fun `a tab name opens its page and anything else opens Plug-Ins`() {
        assertEquals(0, settingsTabIndex(null))
        assertEquals(0, settingsTabIndex(SettingsTab.PLUGINS))
        assertEquals(1, settingsTabIndex(SettingsTab.SYNC))
        assertEquals(2, settingsTabIndex(SettingsTab.GENERAL))
        assertEquals(3, settingsTabIndex(SettingsTab.ABOUT))
        assertEquals(0, settingsTabIndex("nope"))
    }
}
