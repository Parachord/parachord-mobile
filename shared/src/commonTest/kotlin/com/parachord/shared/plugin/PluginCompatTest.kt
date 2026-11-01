package com.parachord.shared.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #484: the same rules as desktop's `main-process/plugin-compat.js` (parachord#1197). */
class PluginCompatTest {

    private fun field(json: String): JsonElement = Json.parseToJsonElement(json)

    @Test
    fun parsesXyzAndDropsAPreReleaseOrBuildSuffix() {
        assertEquals(listOf(0, 9, 12), PluginCompat.parseVersion("0.9.12"))
        assertEquals(listOf(0, 9, 12), PluginCompat.parseVersion("0.9.12-beta.1"))
        assertEquals(listOf(1, 0, 0), PluginCompat.parseVersion("1.0.0+build.5"))
    }

    @Test
    fun rejectsAnythingThatIsNotXyz() {
        for (bad in listOf("0.9", "0.9.x", "v0.9.12", "", null)) {
            assertNull(PluginCompat.parseVersion(bad), "$bad")
        }
    }

    @Test
    fun comparesNumericallyNotAsStrings() {
        assertTrue(PluginCompat.compareVersions("0.9.10", "0.9.9") > 0)
        assertTrue(PluginCompat.compareVersions("0.10.0", "0.9.12") > 0)
        assertTrue(PluginCompat.compareVersions("0.9.11", "0.9.12") < 0)
        assertEquals(0, PluginCompat.compareVersions("0.9.12", "0.9.12"))
        assertEquals(0, PluginCompat.compareVersions("0.9.12", "junk"))
    }

    @Test
    fun readsTheFloorForThePlatform() {
        val floors = field("""{"desktop":"0.9.12","android":"1.4.0"}""")
        assertEquals("0.9.12", PluginCompat.minAppVersion(floors, "desktop"))
        assertEquals("1.4.0", PluginCompat.minAppVersion(floors, "android"))
    }

    @Test
    fun aPlatformWithNoEntryOrNoFieldHasNoFloor() {
        assertNull(PluginCompat.minAppVersion(field("""{"desktop":"0.9.12"}"""), "ios"))
        assertNull(PluginCompat.minAppVersion(null, "android"))
    }

    @Test
    fun aMalformedFloorIsNoFloor() {
        assertNull(PluginCompat.minAppVersion(field("""{"android":"0.9"}"""), "android"))
        assertNull(PluginCompat.minAppVersion(field("""{"android":9}"""), "android"))
        assertNull(PluginCompat.minAppVersion(field("\"0.9.12\""), "android"))
        assertNull(PluginCompat.minAppVersion(field("""["0.9.12"]"""), "android"))
    }

    @Test
    fun anAppBelowTheFloorIsRefusedNamingTheFloor() {
        val floors = field("""{"android":"0.9.10"}""")
        assertEquals("0.9.10", PluginCompat.requiredAppVersion(floors, "0.9.9", "android"))
    }

    @Test
    fun anAppAtOrAboveTheFloorRunsIt() {
        val floors = field("""{"android":"0.9.10"}""")
        assertNull(PluginCompat.requiredAppVersion(floors, "0.9.10", "android"))
        assertNull(PluginCompat.requiredAppVersion(floors, "0.10.0", "android"))
    }

    @Test
    fun anotherPlatformsFloorDoesNotApply() {
        assertNull(PluginCompat.requiredAppVersion(field("""{"ios":"9.0.0","desktop":"9.0.0"}"""), "0.9.9", "android"))
    }

    @Test
    fun aPreReleaseOfTheFloorVersionCountsAsThatVersion() {
        assertNull(PluginCompat.requiredAppVersion(field("""{"android":"0.9.10"}"""), "0.9.10-beta.1", "android"))
    }

    @Test
    fun anAppThatCannotReportItsVersionIsNotRefused() {
        val floors = field("""{"android":"0.9.10"}""")
        assertNull(PluginCompat.requiredAppVersion(floors, "", "android"))
        assertNull(PluginCompat.requiredAppVersion(floors, null, "android"))
    }

    @Test
    fun thePlatformsAreDesktopAndroidAndIos() {
        assertEquals(listOf("desktop", "android", "ios"), PluginCompat.PLATFORMS)
    }
}
