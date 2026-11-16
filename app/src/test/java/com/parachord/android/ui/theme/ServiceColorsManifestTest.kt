package com.parachord.android.ui.theme

import com.parachord.shared.ui.ServiceColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [ServiceColors.FALLBACK] answers before the plugins load, so it must say
 * what the bundled manifests say (both apps' copies).
 */
class ServiceColorsManifestTest {

    private fun manifestColors(dir: String): Map<String, Int> =
        File(dir).listFiles { f -> f.name.endsWith(".axe") }!!.associate { f ->
            val manifest = Json.parseToJsonElement(f.readText()).jsonObject["manifest"]!!.jsonObject
            manifest["id"]!!.jsonPrimitive.content to
                ServiceColors.parseHex(manifest["color"]?.jsonPrimitive?.content)!!
        }

    @Test
    fun fallbackMatchesAndroidBundledManifests() {
        manifestColors("src/main/assets/plugins").forEach { (id, color) ->
            assertEquals("fallback colour for $id", color, ServiceColors.FALLBACK[id])
        }
    }

    @Test
    fun fallbackMatchesIosBundledManifests() {
        manifestColors("../iosApp/Parachord/Resources/axe-plugins").forEach { (id, color) ->
            assertEquals("fallback colour for $id", color, ServiceColors.FALLBACK[id])
        }
    }
}
