package com.parachord.shared.home

import com.parachord.shared.format.TimeAgo
import com.parachord.shared.model.Playlist
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/** The playlist half of desktop's `home-model.js`. */

private const val DAY_MS = 86_400_000L
private const val BADGE_WINDOW_MS = 7 * DAY_MS

/** When a playlist's tracks last changed: `tracksChangedAt`, else `lastModified`, else `createdAt`, else 0. */
fun playlistFreshness(p: Playlist): Long =
    p.tracksChangedAt ?: p.lastModified.takeIf { it > 0 } ?: p.createdAt.takeIf { it > 0 } ?: 0L

/** The [limit] playlists whose tracks changed most recently; ties keep input order. */
fun freshestPlaylists(list: List<Playlist>, limit: Int = 6): List<Playlist> =
    list.sortedByDescending { playlistFreshness(it) }.take(limit)

/** "Δ N" when a change in the last 7 days added tracks, else null. */
fun changeBadge(p: Playlist, nowMs: Long): String? {
    if (p.lastChangeAdded <= 0) return null
    val at = p.tracksChangedAt ?: return null
    if (nowMs - at > BADGE_WINDOW_MS) return null
    return "Δ ${p.lastChangeAdded}"
}

fun whenPhrase(atMs: Long, nowMs: Long, tz: TimeZone = TimeZone.currentSystemDefault()): String {
    val d = Instant.fromEpochMilliseconds(atMs).toLocalDateTime(tz)
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz)
    val days = d.date.daysUntil(today.date)
    if (days <= 0) {
        return when {
            d.hour < 12 -> "this morning"
            d.hour < 18 -> "this afternoon"
            else -> "this evening"
        }
    }
    if (days == 1) return "yesterday"
    return "$days days ago"
}

fun whenPhraseShort(atMs: Long, nowMs: Long, tz: TimeZone = TimeZone.currentSystemDefault()): String {
    val p = whenPhrase(atMs, nowMs, tz)
    return if (p.startsWith("this ")) "today" else p
}

fun freshChangeLine(p: Playlist, nowMs: Long, tz: TimeZone = TimeZone.currentSystemDefault()): String? {
    if (changeBadge(p, nowMs) == null) return null
    val n = p.lastChangeAdded
    return "${p.name} picked up $n new ${if (n == 1) "track" else "tracks"} ${whenPhrase(p.tracksChangedAt!!, nowMs, tz)}"
}

/** A playlist card's line under its name: "Refreshed 2h ago". */
data class PlaylistSubtitle(val refreshed: String)

fun playlistSubtitle(p: Playlist, nowMs: Long): PlaylistSubtitle =
    PlaylistSubtitle("Refreshed ${TimeAgo.ago(playlistFreshness(p), nowMs)}")
