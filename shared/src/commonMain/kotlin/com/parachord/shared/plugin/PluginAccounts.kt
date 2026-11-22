package com.parachord.shared.plugin

/**
 * What the app knows about each plug-in's sign-in (#782, desktop parachord#1615,
 * `PluginSetup.signedInMap` / app.js `pluginAccounts`), and the rules that
 * read it: the sheet's order and the Plug-Ins tiles. Pure.
 *
 * A plug-in that needs a sign-in never shows as enabled until it's signed in.
 * Only `false` counts as signed out; a plug-in not in the map (no verdict: a
 * status not read yet, a failed read, a host check not finished, or one that
 * needs no sign-in, Last.fm and ListenBrainz) is never locked. Display only:
 * nothing here changes a saved list.
 */

/** The plug-ins whose sign-in the app runs itself (desktop's host accounts, `settings.account.type == "sign-in"`). */
val HOST_SIGN_IN_PLUGIN_IDS: Set<String> = setOf("spotify", "soundcloud", "applemusic")

/** Port of `statusSignedIn`: a status reply's `signedIn`, or null (no reply, a failed read, no verdict). */
fun statusSignedIn(entry: PluginStatusEntry?): Boolean? =
    entry?.takeUnless { it.readFailed }?.signedIn

/**
 * Port of `signedInMap`: the host's own sign-ins ([host], id to signed in;
 * leave an id out while its startup check hasn't finished) with each status
 * plug-in's reply laid over them: a plug-in's reply wins for its own id.
 */
fun signedInMap(host: Map<String, Boolean>, statuses: Map<String, PluginStatusEntry>): Map<String, Boolean> {
    val out = host.toMutableMap()
    for ((id, entry) in statuses) {
        statusSignedIn(entry)?.let { out[id] = it }
    }
    return out
}

/**
 * The sheet puts the account (and its client keys) before Enable for a
 * plug-in that needs a sign-in: one whose sign-in the host runs
 * ([HOST_SIGN_IN_PLUGIN_IDS]), or one that signs in itself
 * (`capabilities.status`). Always, signed in or not, so nothing moves after
 * signing in. Everything else keeps Enable first (Last.fm's and
 * ListenBrainz's Scrobbling sign-in stays under it).
 */
fun accountFirst(plugin: PluginManager.PluginInfo): Boolean =
    plugin.id in HOST_SIGN_IN_PLUGIN_IDS || plugin.capabilities["status"] == true

/** A plug-in that's on but signed out: its tile reads as off and says [SIGN_IN_TILE_LABEL]. */
fun pluginSignedOut(id: String, active: Boolean, accounts: Map<String, Boolean>): Boolean =
    active && accounts[id] == false

/** A signed-out tile's line, in the warning colour, in place of "Setup required". */
const val SIGN_IN_TILE_LABEL = "Sign in"

/**
 * The Plug-Ins priority list (desktop's settings page): the plug-ins that are
 * on, in their saved order, split into the ones that play ([numbered], 1, 2,
 * … with no gap, draggable) and the ones that don't ([unnumbered]: a plan
 * block or "Play from" off in [notPlaying], or signed out). Every id keeps
 * its saved slot.
 */
data class PluginTileOrder(val numbered: List<String>, val unnumbered: List<String>)

fun pluginTileOrder(
    onInOrder: List<String>,
    notPlaying: Collection<String>,
    accounts: Map<String, Boolean>,
): PluginTileOrder {
    val unnumbered = { id: String -> id in notPlaying || accounts[id] == false }
    return PluginTileOrder(onInOrder.filterNot(unnumbered), onInOrder.filter(unnumbered))
}
