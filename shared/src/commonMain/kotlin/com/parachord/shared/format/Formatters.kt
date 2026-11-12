package com.parachord.shared.format

import com.parachord.shared.platform.currentTimeMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * How long ago something happened, as desktop's `formatTimeAgo` writes it
 * (#593): "Just now", "5m ago", "3h ago", "2d ago", then the date: "Mar 4",
 * or "Mar 4, 2025" in an earlier year. Months are English, as on desktop.
 * Used for listens, friends' last plays and a playlist's last update.
 */
object RelativeTime {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** [timestampMs] in milliseconds; 0 or less is unknown, an empty string. Dates in the device's time zone. */
    fun ago(timestampMs: Long): String = ago(timestampMs, currentTimeMillis())

    fun ago(timestampMs: Long, nowMs: Long): String =
        ago(timestampMs, nowMs, TimeZone.currentSystemDefault())

    internal fun ago(timestampMs: Long, nowMs: Long, timeZone: TimeZone): String {
        if (timestampMs <= 0) return ""
        val diff = nowMs - timestampMs
        return when {
            diff < MINUTE -> "Just now"
            diff < HOUR -> "${diff / MINUTE}m ago"
            diff < DAY -> "${diff / HOUR}h ago"
            diff < 7 * DAY -> "${diff / DAY}d ago"
            else -> {
                val date = Instant.fromEpochMilliseconds(timestampMs).toLocalDateTime(timeZone).date
                val thisYear = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(timeZone).year
                val day = "${MONTHS[date.monthNumber - 1]} ${date.dayOfMonth}"
                if (date.year == thisYear) day else "$day, ${date.year}"
            }
        }
    }

    /** The same, for a timestamp in seconds (Last.fm and ListenBrainz listens). */
    fun agoSeconds(timestampSeconds: Long): String = agoSeconds(timestampSeconds, currentTimeMillis())

    fun agoSeconds(timestampSeconds: Long, nowMs: Long): String = ago(timestampSeconds * 1000, nowMs)
}

/** "1 play", "1234 plays": the full number, as desktop and iOS show it (#593). */
fun playCountLabel(count: Int): String = if (count == 1) "1 play" else "$count plays"

/** The period History and a friend's profile open on: desktop's `7day` (#593). */
const val DEFAULT_HISTORY_PERIOD = "7day"
