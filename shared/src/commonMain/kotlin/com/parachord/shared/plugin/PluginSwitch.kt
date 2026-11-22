package com.parachord.shared.plugin

import com.parachord.shared.home.PlaybackLimit

/**
 * The switches in a plug-in's settings sheet (#707), a port of desktop's
 * `src/renderer/lib/plugin-switch.js` (parachord#1505). A music plug-in has
 * **Enable X** (`active_resolvers`) and, while it's on, up to three nested
 * switches: **Play from X** (`playback_off_resolvers`), **Use metadata from
 * X** (`metadata_off_resolvers`) and **Use recommendations from X**
 * (`recommendations_off_resolvers`, any plug-in with
 * `capabilities.recommendations`). Each list holds an id only while its switch
 * is off. Pure.
 */
data class PluginSwitchState(
    /** The row is drawn. */
    val shown: Boolean,
    val checked: Boolean,
    /** Can't be changed (a plan limit). */
    val disabled: Boolean,
    val hint: String,
)

private fun PluginManager.PluginInfo.cap(name: String) = capabilities[name] == true
private val PluginManager.PluginInfo.isMetaService get() = type == "meta-service"

/** "Enable X", the sheet's top switch. */
fun enableSwitchLabel(name: String): String = "Enable $name"
fun playbackSwitchLabel(name: String): String = "Play from $name"
fun metadataSwitchLabel(name: String): String = "Use metadata from $name"
fun recommendationsSwitchLabel(name: String): String = "Use recommendations from $name"

/**
 * Port of `hintFor`: the line under "Enable X". A music plug-in says what
 * Enable means (search); a meta-service says what it brings, from its
 * capabilities.
 */
fun enableSwitchHint(plugin: PluginManager.PluginInfo): String {
    if (!plugin.isMetaService) return "Include it in search"
    if (plugin.cap("concerts")) return "Upcoming concerts and ticket links"
    if (plugin.cap("chat") && plugin.cap("generate")) return "AI playlists and Shuffleupagus chat"
    if (plugin.cap("generate")) return "AI playlists"
    if (plugin.cap("chat")) return "Shuffleupagus chat"
    if (plugin.cap("recommendations")) {
        val parts = mutableListOf("Recommendations")
        if (plugin.cap("similarUsers")) parts += "similar listeners"
        parts += "your listening history"
        return parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
    if (plugin.cap("scrobbling")) return "Scrobbling, a log of what you play"
    if (plugin.cap("biography") && plugin.cap("artistImages")) return "Artist biographies and photos"
    if (plugin.cap("biography")) return "Artist biographies"
    if (plugin.cap("artistImages")) return "Artist photos"
    if (plugin.cap("metadata")) return "Artist info"
    return ""
}

/**
 * Port of `playbackSwitchState`: "Play from X", for a music plug-in that's on.
 * Off, it stays on for search, covers and links and leaves the priority
 * order. A plan limit ([limit] blocked) switches it off and locks it, saying why.
 */
fun playbackSwitchState(
    plugin: PluginManager.PluginInfo,
    active: Boolean,
    playbackOff: Boolean,
    limit: PlaybackLimit? = null,
    hostCannotPlay: Boolean = false,
): PluginSwitchState {
    val shown = !plugin.isMetaService && plugin.cap("resolve") && active
    if (limit != null && limit.blocked) {
        return PluginSwitchState(shown, checked = false, disabled = true, hint = limit.message ?: limit.label ?: "Your plan can’t play from it")
    }
    // Mobile addition: this host can't run the plugin's only way to play
    // (shared hostCannotPlay), so the switch is off and locked.
    if (hostCannotPlay) {
        return PluginSwitchState(shown, checked = false, disabled = true, hint = HOST_CANNOT_PLAY_MESSAGE)
    }
    return if (playbackOff) {
        PluginSwitchState(shown, checked = false, disabled = false, hint = "Off: found in search, played from other plug-ins")
    } else {
        PluginSwitchState(shown, checked = true, disabled = false, hint = "Plays tracks, in your priority order")
    }
}

/** Port of `metadataSwitchState`: "Use metadata from X", for a music plug-in that's on. */
fun metadataSwitchState(plugin: PluginManager.PluginInfo, active: Boolean, metadataOff: Boolean): PluginSwitchState {
    val shown = !plugin.isMetaService && plugin.cap("resolve") && active
    return if (metadataOff) {
        PluginSwitchState(shown, checked = false, disabled = false, hint = "Covers and artist photos come from other plug-ins")
    } else {
        PluginSwitchState(shown, checked = true, disabled = false, hint = "Album covers and artist photos")
    }
}

/**
 * Port of `recommendationsSwitchState`: "Use recommendations from X", for a
 * plug-in that's on and offers recommendations, music or not.
 */
fun recommendationsSwitchState(plugin: PluginManager.PluginInfo, active: Boolean, recommendationsOff: Boolean): PluginSwitchState {
    val shown = plugin.cap("recommendations") && active
    return if (recommendationsOff) {
        PluginSwitchState(shown, checked = false, disabled = false, hint = "Its picks are left out of Recommendations and Home")
    } else {
        PluginSwitchState(shown, checked = true, disabled = false, hint = "Its picks in Recommendations and on Home")
    }
}
