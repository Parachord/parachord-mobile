package com.parachord.shared.plugin

import com.parachord.shared.settings.SettingsStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `config` the Last.fm and ListenBrainz plugins read, for
 * [PluginManager.resolverConfigProvider]: `{ "username": … }`, or `{}` when
 * the service isn't connected, so a disconnect reaches the plugin too.
 *
 * Only the username: `getRecommendations` reads public pages, and a plugin
 * has no use for the session key or token.
 */
suspend fun metaServicePluginConfigs(settingsStore: SettingsStore): Map<String, String> {
    fun config(username: String?) = buildJsonObject {
        username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
    }.toString()
    return mapOf(
        "lastfm" to config(settingsStore.getLastFmUsername()),
        "listenbrainz" to config(settingsStore.getListenBrainzUsername()),
    )
}
