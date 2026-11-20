package com.parachord.shared.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Desktop's host-bridge rules (parachord#1355, #1561), with the vectors of its
 * `tests/main-process/plugin-compat.test.js`. A plugin that requires a bridge
 * this app doesn't supply is never loaded.
 */
class PluginHostBridgesTest {
    private fun requires(json: String): JsonElement? = (Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)["requires"]

    @Test
    fun `reads a top-level requires dot hostBridges`() {
        assertEquals(
            listOf("webSession", "amazonMusic"),
            PluginCompat.requiredHostBridges(requires("""{"requires":{"hostBridges":["webSession","amazonMusic"]}}""")),
        )
    }

    @Test
    fun `absent or malformed is no requirement`() {
        assertNull(PluginCompat.requiredHostBridges(requires("{}")))
        assertNull(PluginCompat.requiredHostBridges(requires("""{"requires":null}""")))
        assertNull(PluginCompat.requiredHostBridges(requires("""{"requires":"webSession"}""")))
        assertNull(PluginCompat.requiredHostBridges(requires("""{"requires":[]}""")))
        assertNull(PluginCompat.requiredHostBridges(requires("""{"requires":{"hostBridges":"webSession"}}""")))
        assertNull(PluginCompat.requiredHostBridges(requires("""{"requires":{"hostBridges":["webSession",42]}}""")))
        assertNull(PluginCompat.requiredHostBridges(requires("""{"requires":{"hostBridges":["webSession",""]}}""")))
    }

    @Test
    fun `a host supplying every required bridge loads the plugin`() {
        val r = requires("""{"requires":{"hostBridges":["amazonMusic","webSession"]}}""")
        assertEquals(emptyList(), PluginCompat.missingHostBridges(r, setOf("amazonMusic", "webSession", "widevine")))
        assertEquals(emptyList(), PluginCompat.missingHostBridges(r, setOf("webSession", "amazonMusic")))
    }

    @Test
    fun `a host missing any required bridge hides the plugin — naming the missing ones`() {
        val r = requires("""{"requires":{"hostBridges":["amazonMusic","webSession","somethingElse"]}}""")
        assertEquals(listOf("webSession", "somethingElse"), PluginCompat.missingHostBridges(r, setOf("amazonMusic")))
        assertEquals(listOf("amazonMusic", "webSession", "somethingElse"), PluginCompat.missingHostBridges(r, emptySet()))
    }

    @Test
    fun `no requirement loads everywhere`() {
        assertEquals(emptyList(), PluginCompat.missingHostBridges(null, emptySet()))
        assertEquals(emptyList(), PluginCompat.missingHostBridges(requires("{}"), emptySet()))
        assertEquals(emptyList(), PluginCompat.missingHostBridges(requires("""{"requires":{"hostBridges":"garbled"}}"""), emptySet()))
    }

    @Test
    fun `optional bridges are read the same way — and a missing one never hides a plugin`() {
        val r = requires("""{"requires":{"hostBridges":["webSession"],"optionalHostBridges":["widevine"]}}""")
        assertEquals(listOf("widevine"), PluginCompat.optionalHostBridges(r))
        assertNull(PluginCompat.optionalHostBridges(requires("""{"requires":{"optionalHostBridges":"widevine"}}""")))
        assertEquals(emptyList(), PluginCompat.missingHostBridges(r, setOf("webSession")))
    }
}
