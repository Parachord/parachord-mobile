package com.parachord.shared.ui.icons

/**
 * Each page's icon in the tab bar and the side menu, on both apps. A port of
 * desktop's `lib/page-icons.js` (`PAGE_ICONS`): the same page draws the same
 * icon on every platform. Change it on desktop first. Draw one with
 * Android's `ParachordIcon(PageIcon.X.icon, …)` or iOS's
 * `PCIcon(icon: PageIcon.x.icon)`.
 */
enum class PageIcon(val icon: AppIcon) {
    HOME(AppIcon.Home),
    SEARCH(AppIcon.Search),
    PLAYLISTS(AppIcon.ListMusic),
    COLLECTION(AppIcon.Library),
    HISTORY(AppIcon.RotateCcw),
    FRESH_DROPS(AppIcon.Droplet),
    RECOMMENDATIONS(AppIcon.Radar),
    POP_OF_THE_TOPS(AppIcon.ListOrdered),
    CRITICAL_DARLINGS(AppIcon.Trophy),
    CONCERTS(AppIcon.Ticket),
    SETTINGS(AppIcon.Settings),
}
