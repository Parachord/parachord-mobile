package com.parachord.android.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plug-in host contract (desktop parachord#1561): both apps run desktop's
 * resolver-loader, which builds each plugin a `this.host` from the supplier in
 * the app's JS bootstrap. The supplier block is the same text on both apps.
 */
class PluginHostContractTest {

    // Gradle runs unit tests from the app module's directory.
    private val bootstrapHtml = File("src/main/assets/js/bootstrap.html")
    private val iosRuntime = File("../shared/src/iosMain/kotlin/com/parachord/shared/plugin/IosJsRuntime.kt")
    private val loader = File("src/main/assets/js/resolver-loader.js")

    private fun block(file: File, name: String): String {
        val text = file.readText()
        val start = text.indexOf("// <$name>")
        val end = text.indexOf("// </$name>")
        assertTrue("${file.name} has the $name block", start >= 0 && end > start)
        return text.substring(start, end).lines().joinToString("\n") { it.trim() }
    }

    private fun supplierBlock(file: File): String = block(file, "plugin-host-supplier")

    @Test
    fun `both apps supply the same webSession bridge`() {
        assertEquals(block(bootstrapHtml, "web-session-bridge"), block(iosRuntime, "web-session-bridge"))
        assertTrue(block(iosRuntime, "web-session-bridge").contains("window.__parachordBridge_webSession = {"))
    }

    @Test
    fun `both apps carry the same plugin DRM bridge`() {
        assertEquals(block(bootstrapHtml, "plugin-drm"), block(iosRuntime, "plugin-drm"))
        assertTrue(block(iosRuntime, "plugin-drm").contains("accept === 'hls-fairplay'"))
    }

    @Test
    fun `the loader is desktop's — it builds this_host`() {
        val js = loader.readText()
        assertTrue(js.contains("static buildPluginHost(supplier, axe)"))
        assertTrue(js.contains("constructor(options = {})"))
    }

    @Test
    fun `both apps hand the loader the same host supplier`() {
        assertEquals(supplierBlock(bootstrapHtml), supplierBlock(iosRuntime))
        assertTrue(bootstrapHtml.readText().contains("new ResolverLoader({ host: window.__parachordHostSupplier })"))
        assertTrue(iosRuntime.readText().contains("new ResolverLoader({ host: window.__parachordHostSupplier })"))
    }

    @Test
    fun `each app names its own platform`() {
        assertTrue(bootstrapHtml.readText().contains("window.__parachordPlatform = 'android';"))
        assertTrue(iosRuntime.readText().contains("window.__parachordPlatform = 'ios';"))
    }
}
