package com.parachord.shared.plugin

/**
 * Plugin-supplied playback paths: ways a plugin plays that the host has to
 * run for it, rather than a service engine the app has built in (Spotify
 * Connect, MusicKit, the embeds, local files).
 *
 * - [WIDEVINE]: `capabilities.widevine` with `getPlaybackManifest` +
 *   `relayLicense` (ExoPlayer with Widevine; Android only).
 * - [HTML5_STREAM]: `capabilities.html5Stream` (a URL the plugin builds at
 *   play time; Android only so far).
 */
object HostPlayback {
    const val WIDEVINE = "widevine"
    const val HTML5_STREAM = "html5Stream"

    /** What Android runs. */
    val ANDROID: Set<String> = setOf(WIDEVINE, HTML5_STREAM)

    /** What iOS runs: neither yet (no Widevine, no html5Stream engine). */
    val IOS: Set<String> = emptySet()
}

/** The plugin-supplied playback paths [plugin] declares ([HostPlayback]). */
fun pluginPlaybackPaths(plugin: PluginManager.PluginInfo): Set<String> = buildSet {
    if (plugin.capabilities[HostPlayback.WIDEVINE] == true && plugin.hasDrmPair) add(HostPlayback.WIDEVINE)
    if (plugin.capabilities[HostPlayback.HTML5_STREAM] == true) add(HostPlayback.HTML5_STREAM)
}

/**
 * A plugin whose only way to play is a plugin-supplied path this host can't
 * run ([hostPaths]): iOS with Amazon Music (Widevine). It still loads for
 * search, covers and links, but nothing it finds can play here, so it joins
 * the "not playing" list. A plugin with no such path (Spotify, Apple Music,
 * the embeds) is left to the host's own engines.
 */
fun hostCannotPlay(plugin: PluginManager.PluginInfo, hostPaths: Set<String>): Boolean {
    val paths = pluginPlaybackPaths(plugin)
    return paths.isNotEmpty() && paths.none { it in hostPaths }
}

/** The ids of [plugins] that [hostCannotPlay], sorted. */
fun hostUnplayableIds(plugins: List<PluginManager.PluginInfo>, hostPaths: Set<String>): List<String> =
    plugins.filter { hostCannotPlay(it, hostPaths) }.map { it.id }.distinct().sorted()

/** The tile line and the "Play from X" hint for a plugin this host can't play from. */
const val HOST_CANNOT_PLAY_LABEL = "Search and links only"
const val HOST_CANNOT_PLAY_MESSAGE = "Parachord can’t play from it on this device yet"
