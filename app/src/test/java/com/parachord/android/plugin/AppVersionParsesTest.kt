package com.parachord.android.plugin

import com.parachord.android.BuildConfig
import com.parachord.shared.plugin.PluginCompat
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Each app hands `PluginManager` its version for plug-in `minAppVersion`
 * floors, and [PluginCompat] skips every floor when that version isn't
 * `x.y.z`. iOS reported "0.2" (its `MARKETING_VERSION`), so every
 * `minAppVersion.ios` was silently ignored (#770). A two-part version now
 * fails here instead.
 */
class AppVersionParsesTest {

    @Test
    fun iosMarketingVersionsAreThreePart() {
        val pbxproj = File("../iosApp/Parachord.xcodeproj/project.pbxproj").readText()
        val versions = Regex("MARKETING_VERSION = ([^;]+);").findAll(pbxproj).map { it.groupValues[1].trim() }.toList()
        assertTrue("no MARKETING_VERSION found", versions.isNotEmpty())
        versions.forEach { v ->
            assertNotNull("iOS MARKETING_VERSION '$v' isn't x.y.z", PluginCompat.parseVersion(v))
        }
    }

    @Test
    fun androidVersionNameParses() {
        assertNotNull(
            "Android versionName '${BuildConfig.VERSION_NAME}' isn't x.y.z",
            PluginCompat.parseVersion(BuildConfig.VERSION_NAME),
        )
    }
}
