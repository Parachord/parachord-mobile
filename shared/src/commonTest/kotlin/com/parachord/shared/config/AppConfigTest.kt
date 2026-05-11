package com.parachord.shared.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppConfigTest {
    @Test
    fun userAgent_defaultsToEmptyString() {
        val config = AppConfig()
        assertEquals("", config.userAgent)
    }

    @Test
    fun isDebug_defaultsToFalse() {
        val config = AppConfig()
        assertFalse(config.isDebug)
    }

    @Test
    fun userAgent_canBeOverridden_inParachordFormat() {
        val config = AppConfig(userAgent = "Parachord/0.5.0 (Android; https://parachord.app)")
        assertEquals("Parachord/0.5.0 (Android; https://parachord.app)", config.userAgent)
    }

    @Test
    fun isDebug_canBeOverridden() {
        val config = AppConfig(isDebug = true)
        assertEquals(true, config.isDebug)
    }

    @Test
    fun achordionBearerToken_defaultIsEmpty() {
        assertEquals("", AppConfig().achordionBearerToken)
    }
}
