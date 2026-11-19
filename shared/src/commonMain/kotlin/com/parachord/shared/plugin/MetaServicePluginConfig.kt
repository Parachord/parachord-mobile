package com.parachord.shared.plugin

import com.parachord.shared.settings.SettingsStore
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
