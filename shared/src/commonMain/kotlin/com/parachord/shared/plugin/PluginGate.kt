package com.parachord.shared.plugin

/**
 * A plugin answered that it isn't ready yet (its error carried `notReady: true`,
 * e.g. not signed in). Not an outage: callers keep what they have and don't
 * report a failure.
 */
class PluginNotReadyException(message: String) : IllegalStateException(message)

/** The plugin needs an account (sign-in or config) before it can answer. */
fun needsAccount(plugin: PluginManager.PluginInfo): Boolean = plugin.requiresAuth

/**
 * Whether a plugin counts as on, one rule for both apps (Android keeps
 * `active_resolvers` empty and gates on connection; iOS and desktop keep it
 * as the enable list). In order: in either off-list is off; a meta-service is
 * on; a set active list decides; else on unless it needs an account that
 * isn't connected.
 */
fun pluginIsOn(
    id: String,
    isMetaService: Boolean,
    needsAccount: Boolean,
    connected: Boolean,
    activeResolvers: Collection<String>,
    disabledPlugins: Set<String>,
    disabledMetaProviders: Set<String>,
): Boolean {
    if (id in disabledPlugins || id in disabledMetaProviders) return false
    if (isMetaService) return true
    if (activeResolvers.isNotEmpty()) return id in activeResolvers
    return !needsAccount || connected
}
