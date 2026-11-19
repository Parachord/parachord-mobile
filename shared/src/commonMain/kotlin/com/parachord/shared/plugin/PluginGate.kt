package com.parachord.shared.plugin

/**
 * A plugin answered that it isn't ready yet (its error carried `notReady: true`,
 * e.g. not signed in). Not an outage: callers keep what they have and don't
 * report a failure.
 */
class PluginNotReadyException(message: String) : IllegalStateException(message)
