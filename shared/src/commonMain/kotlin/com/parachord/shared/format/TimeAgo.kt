package com.parachord.shared.format

import com.parachord.shared.platform.currentTimeMillis

/**
 * One relative-time phrase for Home: "just now", "18m ago", "3h ago", "5d ago",
 * "2mo ago", "1y ago". A port of desktop's `time-ago.js` (#1388). Separate from
 * [RelativeTime], which has its own ladder for listens.
 */
object TimeAgo {
    fun ago(atMs: Long, nowMs: Long = currentTimeMillis()): String {
        val diff = maxOf(0L, nowMs - atMs)
        val m = diff / 60_000
        if (m < 1) return "just now"
        if (m < 60) return "${m}m ago"
        val h = m / 60
        if (h < 24) return "${h}h ago"
        val d = h / 24
        if (d < 30) return "${d}d ago"
        val mo = d / 30
        if (mo < 12) return "${mo}mo ago"
        return "${d / 365}y ago"
    }
}
