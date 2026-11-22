package com.parachord.android.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Both apps bundle the same `.axe` plugins as an offline fallback
 * (`sync-plugins.yml` keeps them fresh from the marketplace). iOS has its own
 * copy, so a plugin added to one app only (subsonic.axe was, #671) never
 * loaded on the other until marketplace sync. Same set, byte for byte.
 */
class BundledPluginCopiesTest {

    // Gradle runs unit tests from the app module's directory.
    private val android = File("src/main/assets/plugins")
    private val ios = File("../iosApp/Parachord/Resources/axe-plugins")

    private fun axes(dir: File): Map<String, File> =
        (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".axe") }.associateBy { it.name }

    @Test
    fun `both apps bundle the same plugins, identical`() {
        val a = axes(android)
        val i = axes(ios)
        assertTrue("no bundled plugins found", a.isNotEmpty())
        assertEquals("bundled plugin sets differ", a.keys.sorted(), i.keys.sorted())
        for ((name, file) in a) {
            assertTrue("$name differs between the apps", file.readBytes().contentEquals(i.getValue(name).readBytes()))
        }
        assertTrue("subsonic.axe is bundled (#670)", "subsonic.axe" in i)
    }
}
