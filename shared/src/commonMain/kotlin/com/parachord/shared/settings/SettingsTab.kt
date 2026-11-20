package com.parachord.shared.settings

/**
 * The Settings tabs a link can open, by name, so both apps route the same
 * request to the same tab (Android `Routes.settings(tab = …)`). Each app maps
 * a name to its own tab; an unknown name opens Plug-Ins.
 */
object SettingsTab {
    const val PLUGINS = "plugins"
    /** Library sync and playlist sync. */
    const val SYNC = "sync"
    const val GENERAL = "general"
    const val ABOUT = "about"
}
