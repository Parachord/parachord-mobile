package com.parachord.shared.deeplink

import com.parachord.shared.sync.encodeUriComponent

/**
 * The tab and period of `parachord://history/{tab?}?period=` and
 * `parachord://friend/{id}/{tab?}?period=`, in both directions. A port of
 * desktop's `src/renderer/lib/friend-link.js` (parachord#1178) plus its
 * history handler, so a link copied on one platform opens the same view on
 * another (#470).
 */
enum class HistoryLinkTab(val segment: String) {
    RECENT("recent"),
    TOP_TRACKS("top-tracks"),
    TOP_ALBUMS("top-albums"),
    TOP_ARTISTS("top-artists");

    /** The top tabs take a period; Recent is the latest plays and has none. */
    val isTop: Boolean get() = this != RECENT

    companion object {
        fun fromSegment(segment: String?): HistoryLinkTab? = entries.firstOrNull { it.segment == segment }
    }
}

/** Same values as the period pickers (desktop `historyPeriodOptions`). */
val HISTORY_PERIODS = listOf("7day", "1month", "3month", "6month", "12month", "overall")

/**
 * A link's view target. `tab == null` lets the view pick its default tab;
 * `period == null` keeps the view's current period.
 */
data class HistoryLinkTarget(val tab: HistoryLinkTab?, val period: String?)

/**
 * Reads a friend link's raw tab segment and `?period=`. Unknown values are
 * dropped, and a period applies only to the top tabs.
 */
fun parseFriendLink(tabSegment: String?, period: String?): HistoryLinkTarget {
    val tab = HistoryLinkTab.fromSegment(tabSegment)
    return HistoryLinkTarget(tab, period.takeIf { tab?.isTop == true && it in HISTORY_PERIODS })
}

/**
 * Builds the link for a friend's view. Every top tab writes its period, the
 * default included: a link without one would open at whatever period the
 * recipient last picked. Recent never writes one.
 */
fun buildFriendLink(id: String, tab: HistoryLinkTab?, period: String?): String {
    val segment = tab?.let { "/${it.segment}" } ?: ""
    val query = if (tab?.isTop == true && period in HISTORY_PERIODS) "?period=$period" else ""
    return "parachord://friend/${encodeUriComponent(id)}$segment$query"
}

/**
 * Reads a history link's raw tab segment and `?period=`. Unlike a friend
 * link, desktop's history handler applies a known period whatever the tab.
 */
fun parseHistoryLink(tabSegment: String?, period: String?): HistoryLinkTarget =
    HistoryLinkTarget(HistoryLinkTab.fromSegment(tabSegment), period.takeIf { it in HISTORY_PERIODS })
