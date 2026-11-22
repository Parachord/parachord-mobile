package com.parachord.shared.plugin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.parachord.shared.platform.Log

/**
 * The minimum app version a plugin declares (#484, desktop parachord#1197).
 *
 * The marketplace serves only the latest copy of each plugin, and every
 * installed app downloads it. A plugin that relies on a host feature an older
 * app lacks (a new `NativeBridge` call, a newer `JsBridge`) would break there,
 * so a plugin can declare the oldest app it runs on, per platform, in its
 * `.axe` manifest and its marketplace `manifest.json` entry:
 *
 *     "minAppVersion": { "desktop": "0.9.12", "android": "0.9.10", "ios": "1.0.0" }
 *
 * Each client reads only its own key. A platform with no entry, or a plugin
 * with no field, has no floor. An app below the floor keeps the copy it already
 * has (the bundled one, or an older cached one).
 *
 * A port of desktop's `main-process/plugin-compat.js`; keep the two in step.
 * It also holds desktop's host-bridge rule ([missingHostBridges]): a plugin
 * that requires a bridge this app doesn't supply isn't loaded.
 * `capabilities.mobile: false` is separate: it keeps a plugin off mobile
 * entirely, where this is a version floor for plugins that do run here.
 */
object PluginCompat {
    val PLATFORMS = listOf("desktop", "android", "ios")

    private val VERSION_RE = Regex("^\\d+\\.\\d+\\.\\d+$")

    /**
     * "0.9.12" → [0, 9, 12]. A pre-release or build suffix ("0.9.12-beta.1")
     * is dropped, so a pre-release counts as its release. Anything else is null.
     */
    fun parseVersion(version: String?): List<Int>? {
        if (version == null) return null
        val core = version.trim().split('-', '+').first()
        if (!VERSION_RE.matches(core)) return null
        return core.split('.').map { it.toIntOrNull() ?: return null }
    }

    /** >0 if [a] > [b], <0 if [a] < [b], 0 if equal or either is unparseable. */
    fun compareVersions(a: String?, b: String?): Int {
        val pa = parseVersion(a) ?: return 0
        val pb = parseVersion(b) ?: return 0
        for (i in 0 until 3) if (pa[i] != pb[i]) return pa[i].compareTo(pb[i])
        return 0
    }

    /**
     * The floor [minAppVersion] (a manifest's or catalog entry's raw field)
     * declares for [platform], or null when it declares none. A malformed value
     * is no floor: desktop's build step rejects one before it can ship.
     */
    fun minAppVersion(minAppVersion: JsonElement?, platform: String): String? {
        val map = minAppVersion as? JsonObject ?: return null
        val floor = (map[platform] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return floor?.takeIf { parseVersion(it) != null }
    }

    private var warnedUnparseable = false

    /**
     * An app version that isn't `x.y.z` skips every floor (never refused, as
     * desktop does), so say so once: iOS reported "0.2" and every
     * `minAppVersion.ios` was silently ignored (#770).
     */
    private fun warnUnparseableAppVersion(appVersion: String?, platform: String) {
        if (warnedUnparseable) return
        warnedUnparseable = true
        Log.w("PluginCompat", "App version '$appVersion' ($platform) isn't x.y.z; plug-in minAppVersion floors are skipped")
    }

    /**
     * The floor this app is below, or null when it can run the plugin. An app
     * that can't report its version is not refused on that account.
     */
    fun requiredAppVersion(minAppVersion: JsonElement?, appVersion: String?, platform: String): String? {
        val requires = minAppVersion(minAppVersion, platform) ?: return null
        if (parseVersion(appVersion) == null) {
            warnUnparseableAppVersion(appVersion, platform)
            return null
        }
        return requires.takeIf { compareVersions(appVersion, it) < 0 }
    }

    /**
     * The host bridges a plugin requires (`requires.hostBridges`, a top-level
     * `.axe` field, desktop parachord#1355), or null when it requires none. A
     * plugin that calls a host bridge (`webSession`, ...) declares it, and a
     * host that doesn't supply every one HIDES the plugin: never loaded, so
     * its URL patterns don't claim links it can't open and its resolves don't
     * throw `not-ready` forever. Absent or malformed is no requirement, as on
     * desktop (its build step rejects a malformed one at source).
     */
    fun requiredHostBridges(requires: JsonElement?): List<String>? = bridgeList(requires, "hostBridges")

    /**
     * The bridges a plugin uses when the host has them and works without
     * (`requires.optionalHostBridges`, the plug-in host contract, #1561). A
     * missing one never hides a plugin; the loader leaves it off `this.host`.
     */
    fun optionalHostBridges(requires: JsonElement?): List<String>? = bridgeList(requires, "optionalHostBridges")

    /** The required bridges [supplied] lacks; empty when the host can run the plugin. */
    fun missingHostBridges(requires: JsonElement?, supplied: Set<String>): List<String> =
        requiredHostBridges(requires)?.filter { it !in supplied } ?: emptyList()

    private fun bridgeList(requires: JsonElement?, field: String): List<String>? {
        val obj = requires as? JsonObject ?: return null
        val list = obj[field] as? kotlinx.serialization.json.JsonArray ?: return null
        val names = list.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (names.any { it.isNullOrEmpty() }) return null
        return names.filterNotNull()
    }
}

