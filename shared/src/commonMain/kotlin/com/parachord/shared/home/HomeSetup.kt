package com.parachord.shared.home

// The "Finish setting up" checklist model, a port of desktop's
// `src/renderer/lib/home-setup.js`. Mobile has no browser-extension item and
// no What's New (spec "What's New (out of scope)"), so the only item is the
// getting-started steps when they were skipped before they were done.

data class HomeSetupItem(val id: String, val label: String, val done: Boolean, val action: String)

/** `mode` is `setup` while an item is open, else `none`. */
data class HomeSetup(val items: List<HomeSetupItem>, val done: Int, val total: Int, val mode: String)

fun homeSetupItems(gettingStartedLeft: Boolean = false, dismissed: Set<String> = emptySet()): HomeSetup {
    val all = buildList {
        if (gettingStartedLeft) add(HomeSetupItem("getting-started", "Finish getting started", false, "getting-started"))
    }
    val items = all.filter { it.id !in dismissed }
    return HomeSetup(
        items = items,
        done = items.count { it.done },
        total = items.size,
        mode = if (items.any { !it.done }) "setup" else "none",
    )
}
