package com.parachord.shared.plugin

import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The host side of a plugin's `settings.configurable` fields (Subsonic's
 * server URL / username / password; plan parachord-mobile#664): what the settings
 * screen edits, where each value lives, and the `config` JSON the plugin
 * receives through [PluginManager.resolverConfigProvider].
 *
 * Storage split (security C4 — encrypt credentials at rest):
 * - `text` fields → the plain [KvStore], as JSON under `plugin_config_<id>`,
 *   never the credential-bearing ones;
 * - `password` fields → [SecureTokenStore] (Keystore / Keychain), one key per
 *   secret, never in plain prefs or backups.
 *
 * The plugin's own `isConfigured` decides readiness — this class never
 * second-guesses a plugin's rules.
 */
class PluginUserConfig(
    /** The loaded plugins — a lambda so the Koin binding can take [PluginManager] lazily (no definition cycle, #37). */
    private val plugins: () -> List<PluginManager.PluginInfo>,
    private val kv: KvStore,
    private val secureStore: SecureTokenStore,
    /** Called with the plugin id after a save, so its cached `isConfigured` refreshes ([PluginManager.refreshConfiguredCache]). */
    private val onConfigSaved: suspend (String) -> Unit = {},
) {
    companion object {
        /** KvStore key holding a plugin's text fields as JSON `{field: value}`. */
        const val TEXT_KEY_PREFIX = "plugin_config_"

        /** SecureTokenStore key for one plugin secret. */
        fun secretKey(pluginId: String, field: String): String = "plugin_secret_${pluginId}_$field"

        private val json = Json { ignoreUnknownKeys = true }
    }

    /** The plugin's configurable fields, in manifest order. */
    fun fields(pluginId: String): List<PluginManager.PluginConfigField> =
        plugins().find { it.id == pluginId }?.configFields ?: emptyList()

    /** The value of one field (passwords from the secure store); "" when unset. */
    suspend fun value(pluginId: String, field: PluginManager.PluginConfigField): String =
        if (field.type == "password") {
            secureStore.get(secretKey(pluginId, field.key)).orEmpty()
        } else {
            textValues(pluginId)[field.key].orEmpty()
        }

    /**
     * Save [values] for the plugin's fields (unknown keys ignored; a blank
     * password clears the secret). Every save refreshes the plugin's
     * `isConfigured` answer.
     */
    suspend fun save(pluginId: String, values: Map<String, String>) {
        val fields = fields(pluginId).associateBy { it.key }
        val texts = textValues(pluginId).toMutableMap()
        for ((key, value) in values) {
            val field = fields[key] ?: continue
            if (field.type == "password") {
                if (value.isEmpty()) secureStore.remove(secretKey(pluginId, key))
                else secureStore.set(secretKey(pluginId, key), value)
            } else if (value.isBlank()) {
                texts.remove(key)
            } else {
                texts[key] = value
            }
        }
        kv.setString(TEXT_KEY_PREFIX + pluginId, buildJsonObject { texts.forEach { (k, v) -> put(k, v) } }.toString())
        configCacheMutex.withLock {
            configCache = null
            configGeneration++
        }
        onConfigSaved(pluginId)
    }

    /**
     * The plugin's config for [PluginManager.resolverConfigProvider]: the JSON
     * object the plugin receives as `config` — `{ "serverUrl": …, "username": …,
     * "password": … }` for Subsonic — omitting unset fields.
     */
    suspend fun configJson(pluginId: String): String = buildJsonObject {
        for (field in fields(pluginId)) {
            value(pluginId, field).takeIf { it.isNotEmpty() }?.let { put(field.key, it) }
        }
    }.toString()

    /**
     * Configs for every loaded plugin with configurable fields, for the
     * resolver config provider (merged after the per-service configs so a
     * plugin's own fields win). Cached — the provider runs before every plugin
     * call, and building this decrypts the secure store once per password
     * field — and the cache drops on [save] or a plugin-set change.
     */
    suspend fun pluginConfigs(): Map<String, String> {
        val plugins = plugins().filter { it.configFields.isNotEmpty() }
        val fingerprint = plugins.joinToString(";") { p -> p.id + ":" + p.configFields.joinToString(",") { it.key } }
        val generationAtBuild: Long
        configCacheMutex.withLock {
            val cached = configCache
            if (cached?.first == fingerprint) return cached.second
            generationAtBuild = configGeneration
        }
        val configs = plugins.associate { it.id to configJson(it.id) }
        configCacheMutex.withLock {
            // A save landed while this built: don't store the pre-save configs
            // over the save's cache drop — the next call builds them fresh.
            if (configGeneration == generationAtBuild) configCache = fingerprint to configs
        }
        return configs
    }

    private val configCacheMutex = Mutex()
    private var configCache: Pair<String, Map<String, String>>? = null
    /** Bumped by [save], so an in-flight [pluginConfigs] build doesn't cache stale configs over it. */
    private var configGeneration: Long = 0

    private suspend fun textValues(pluginId: String): Map<String, String> = try {
        val raw = kv.getStringOrNull(TEXT_KEY_PREFIX + pluginId) ?: return emptyMap()
        json.parseToJsonElement(raw).jsonObject.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }
}
