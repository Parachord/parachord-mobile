package com.parachord.shared.plugin

import com.parachord.shared.settings.SettingsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `config` the Last.fm and ListenBrainz plugins read, for
 * [PluginManager.resolverConfigProvider]: `{ "username": … }`, or `{}` when
 * the service isn't connected, so a disconnect reaches the plugin too.
 *
 * The username, plus Last.fm's API key ([lastFmApiKey], when non-blank): the
 * plugin's `getRecommendations` reads public pages with it. A plugin has no use
 * for the session key or token.
 */
suspend fun metaServicePluginConfigs(
    settingsStore: SettingsStore,
    lastFmApiKey: String = "",
): Map<String, String> {
    fun config(username: String?, apiKey: String = "") = buildJsonObject {
        username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
        apiKey.takeIf { it.isNotBlank() }?.let { put("apiKey", it) }
    }.toString()
    return mapOf(
        "lastfm" to config(settingsStore.getLastFmUsername(), lastFmApiKey),
        "listenbrainz" to config(settingsStore.getListenBrainzUsername()),
    )
}

/**
 * The [PluginManager.resolverConfigProvider] map built from [layers], later
 * layers winning field by field: a plugin's config is one JSON object, so a
 * layer adds or replaces fields and never drops the ones before it. A plugin
 * with `settings.configurable` fields (listenbrainz.axe's username and token,
 * lastfm.axe's username and key) sends `{}` while they're unset, and a plain
 * map `+` let that empty object erase the connected account the host supplies:
 * ListenBrainz then had no username, so it sent no recommendations and no
 * similar listeners. A layer value that isn't a JSON object replaces the one
 * before it whole.
 */
fun mergeResolverConfigs(vararg layers: Map<String, String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (layer in layers) for ((id, config) in layer) {
        val before = out[id]?.let(::jsonObjectOrNull)
        val next = jsonObjectOrNull(config)
        out[id] = if (before != null && next != null) JsonObject(before + next).toString() else config
    }
    return out
}

private fun jsonObjectOrNull(raw: String): JsonObject? = try {
    Json.parseToJsonElement(raw) as? JsonObject
} catch (e: Exception) {
    null
}
