package com.parachord.shared.plugin

/**
 * Plugin-supplied playback paths: ways a plugin plays that the host has to
 * run for it, rather than a service engine the app has built in (Spotify
 * Connect, MusicKit, the embeds, local files).
 *
 * - [WIDEVINE]: `capabilities.widevine` with `getPlaybackManifest` +
 *   `relayLicense` (ExoPlayer with Widevine; Android only).
 * - [FAIRPLAY]: the same pair, asked for FairPlay HLS by an iOS host
 *   (desktop #1627: Amazon Music 1.24.0; AVPlayer with an
 *   `AVContentKeySession`, parachord-mobile#794).
 * - [HTML5_STREAM]: `capabilities.html5Stream` (a URL the plugin builds at
 *   play time; ExoPlayer on Android, AVPlayer on iOS).
 * - [PLAIN_STREAM]: a Widevine plugin that also implements `getStreamUrl`
 *   (desktop parachord#1613: Amazon Music's purchases). Chosen by the method's
 *   presence, not `capabilities.html5Stream`, which such a plugin doesn't
 *   declare. Android plays it when the DRM stream didn't start; iOS, with no
 *   Widevine engine, plays it on its own.
 */
object HostPlayback {
    const val WIDEVINE = "widevine"
    const val FAIRPLAY = "fairplay"
    const val HTML5_STREAM = "html5Stream"
    const val PLAIN_STREAM = "plainStream"

    /** What Android runs. */
    val ANDROID: Set<String> = setOf(WIDEVINE, HTML5_STREAM, PLAIN_STREAM)

    /** What iOS runs: the stream URL paths and the DRM pair as FairPlay, through AVPlayer. */
    val IOS: Set<String> = setOf(FAIRPLAY, HTML5_STREAM, PLAIN_STREAM)
}

/** The plugin-supplied playback paths [plugin] declares ([HostPlayback]). */
fun pluginPlaybackPaths(plugin: PluginManager.PluginInfo): Set<String> = buildSet {
    val drm = plugin.capabilities[HostPlayback.WIDEVINE] == true && plugin.hasDrmPair
    if (drm) {
        add(HostPlayback.WIDEVINE)
        add(HostPlayback.FAIRPLAY)
    }
    if (plugin.capabilities[HostPlayback.HTML5_STREAM] == true) add(HostPlayback.HTML5_STREAM)
    if (drm && plugin.hasStreamUrl) add(HostPlayback.PLAIN_STREAM)
}

/**
 * A plugin whose only way to play is a plugin-supplied path this host can't
 * run ([hostPaths]): iOS with a Widevine plugin that has no `getStreamUrl`.
 * It still loads for search, covers and links, but nothing it finds can play
 * here, so it joins the "not playing" list. A plugin with no such path
 * (Spotify, Apple Music, the embeds) is left to the host's own engines.
 */
fun hostCannotPlay(plugin: PluginManager.PluginInfo, hostPaths: Set<String>): Boolean {
    val paths = pluginPlaybackPaths(plugin)
    return paths.isNotEmpty() && paths.none { it in hostPaths }
}

/**
 * Whether this host plays [plugin] by asking it for `getStreamUrl` first:
 * an `html5Stream` plugin, or a plugin whose plain stream the host runs
 * while it can't run its DRM pair at all. Where the host runs the pair
 * (Android's Widevine, iOS's FairPlay), the DRM stream goes first and the
 * plain stream is only its fallback ([com.parachord.shared.playback.drm.drmPlainStreamFallbackAllowed]).
 */
fun playsThroughStreamUrl(plugin: PluginManager.PluginInfo, hostPaths: Set<String>): Boolean {
    val paths = pluginPlaybackPaths(plugin)
    if (HostPlayback.HTML5_STREAM in paths && HostPlayback.HTML5_STREAM in hostPaths) return true
    return HostPlayback.PLAIN_STREAM in paths && HostPlayback.PLAIN_STREAM in hostPaths &&
        HostPlayback.WIDEVINE !in hostPaths && HostPlayback.FAIRPLAY !in hostPaths
}

/**
 * Whether this host plays [plugin]'s DRM pair as FairPlay HLS (iOS): the DRM
 * stream goes first, and its plain stream, if it has one, is the fallback
 * ([com.parachord.shared.playback.drm.drmPlainStreamFallbackAllowed]), as on Android.
 */
fun playsThroughFairPlay(plugin: PluginManager.PluginInfo, hostPaths: Set<String>): Boolean =
    HostPlayback.FAIRPLAY in pluginPlaybackPaths(plugin) && HostPlayback.FAIRPLAY in hostPaths

/** The ids of [plugins] that [hostCannotPlay], sorted. */
fun hostUnplayableIds(plugins: List<PluginManager.PluginInfo>, hostPaths: Set<String>): List<String> =
    plugins.filter { hostCannotPlay(it, hostPaths) }.map { it.id }.distinct().sorted()

/** The tile line and the "Play from X" hint for a plugin this host can't play from. */
const val HOST_CANNOT_PLAY_LABEL = "Search and links only"
const val HOST_CANNOT_PLAY_MESSAGE = "Parachord can’t play from it on this device yet"
