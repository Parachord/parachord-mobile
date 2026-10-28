package com.parachord.shared.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the MetaBrainz User-Agent contract (#375) for both platforms. The
 * Android wrapper has its own `UserAgentTest` in `:app`.
 */
class UserAgentTest {

    private val shape = Regex("""^Parachord/\S+ \( .+ \)$""")

    @Test
    fun `iOS carries the real app version and platform`() {
        assertEquals(
            "Parachord/0.9.10 ( iOS; https://parachord.com )",
            parachordUserAgent("0.9.10", "iOS"),
        )
    }

    @Test
    fun `Android shape is unchanged`() {
        assertEquals(
            "Parachord/0.9.9 ( Android; https://parachord.com )",
            parachordUserAgent("0.9.9", "Android"),
        )
    }

    @Test
    fun `blank version keeps the App-version shape`() {
        val ua = parachordUserAgent("  ", "iOS")
        assertTrue(shape.matches(ua), "was: $ua")
        assertTrue(ua.startsWith("Parachord/unknown "), "was: $ua")
    }

    @Test
    fun `never reintroduces the dead parachord app domain`() {
        assertFalse(parachordUserAgent("1.0", "iOS").contains("parachord.app"))
    }
}
