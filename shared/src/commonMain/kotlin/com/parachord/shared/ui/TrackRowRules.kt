package com.parachord.shared.ui

import com.parachord.shared.resolver.ResolvedSource
import com.parachord.shared.resolver.ResolverBadge
import com.parachord.shared.resolver.ResolverBadges

/**
 * The rules behind a track row, one set for both apps (#760). Desktop draws
 * every list with one `TrackRow` (`components/track-lists.js`): number, title,
 * artist, the source icons, then the duration as the last column, the icons
 * right up against it. The phones draw the same row with the cover at the left
 * and the artist under the title (Android `TrackRow`, iOS `PCTrackRow`); what
 * the row shows is decided here.
 */
object TrackRowRules {

    /**
     * The length a row shows, in ms: a port of desktop's `trackDuration`
     * (`lib/track-duration.js`).
     *
     * 1. the track's own length, when it's positive;
     * 2. else the length of one of its sources: the highest-confidence playable
     *    source that has one, ties going to the user's source order (a resolver
     *    not in it goes last);
     * 3. else null: nothing knows the length, and the row shows nothing rather
     *    than "0:00".
     *
     * A track imported from a link (a hosted XSPF with no durations) or a
     * metadata-only track (Last.fm, ListenBrainz, the DJ) has no length until a
     * source resolves; desktop showed "0:00" for it until this rule.
     */
    fun durationMs(trackDurationMs: Long?, sources: List<ResolvedSource>?, order: List<String>): Long? {
        if (trackDurationMs != null && trackDurationMs > 0) return trackDurationMs
        if (sources.isNullOrEmpty()) return null
        fun rank(id: String) = order.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }
        return sources
            .withIndex()
            .filter { (_, s) -> !s.noMatch && (s.matchedDurationMs ?: 0) > 0 }
            .sortedWith(
                compareByDescending<IndexedValue<ResolvedSource>> { it.value.confidence ?: 0.0 }
                    .thenBy { rank(it.value.resolver) }
                    .thenBy { it.index },
            )
            .firstOrNull()?.value?.matchedDurationMs
    }

    /**
     * [durationMs]'s step 1 for a caller that has already worked out the
     * sources' length ([sourceDurationMs], `durationMs(null, sources, order)`):
     * the track's own length when it's positive, else the sources'.
     */
    fun pickDurationMs(trackDurationMs: Long?, sourceDurationMs: Long?): Long? = when {
        trackDurationMs != null && trackDurationMs > 0 -> trackDurationMs
        sourceDurationMs != null && sourceDurationMs > 0 -> sourceDurationMs
        else -> null
    }

    /** "m:ss" for a length in ms; "" when it's unknown (never "0:00"). Desktop's `formatDuration`. */
    fun formatDuration(ms: Long?): String {
        if (ms == null || ms <= 0) return ""
        val total = ms / 1000
        val secs = total % 60
        return "${total / 60}:${if (secs < 10) "0" else ""}$secs"
    }

    /** The text the row's duration column shows: [durationMs], formatted. */
    fun durationLabel(trackDurationMs: Long?, sources: List<ResolvedSource>?, order: List<String>): String =
        formatDuration(durationMs(trackDurationMs, sources, order))

    /**
     * A row's state, desktop's `sourceRowState`:
     * - [TrackRowState.RESOLVING]: nothing has come back yet ([sources] is
     *   null): the icon column shimmers, and the row isn't dimmed.
     * - [TrackRowState.UNPLAYABLE]: the resolve came back and no icon shows (no
     *   match anywhere, or only services that don't play): the row is dimmed to
     *   [UNPLAYABLE_ALPHA], with no icons.
     * - [TrackRowState.PLAYABLE]: at least one icon.
     *
     * [badges] is what `ResolverBadges.badges` gave for [sources].
     */
    fun state(sources: List<ResolvedSource>?, badges: List<ResolverBadge>): TrackRowState = when {
        badges.isNotEmpty() -> TrackRowState.PLAYABLE
        sources == null -> TrackRowState.RESOLVING
        else -> TrackRowState.UNPLAYABLE
    }

    /** The row's opacity when it can't play (desktop's `opacity-60`). */
    const val UNPLAYABLE_ALPHA = 0.6f

    /** The track-number column's width, in dp / pt (an album's tracklist). */
    const val NUMBER_COLUMN = 28

    /** The cover's size, in dp / pt (the phones' rows have one; desktop's don't). */
    const val COVER = 44

    /** The source icon's size, in dp / pt. */
    const val SOURCE_ICON = 20

    /** The gap between two source icons, in dp / pt. */
    const val SOURCE_ICON_GAP = 4

    /** The widest the icon column gets, in dp / pt (desktop's 140px). */
    const val SOURCE_COLUMN_MAX = 140

    /** The duration column's width, in dp / pt: room for "59:59" at 12 sp. */
    const val DURATION_COLUMN = 40

    /**
     * The icon column's width, in dp / pt: room for one icon per service the
     * user can play from, at least two, at most [SOURCE_COLUMN_MAX]. Desktop's
     * `sourceColumnWidth`. Every row of a list gets the same width, so the
     * durations after it line up and the icons always touch them; the spare
     * room is to the left of the icons.
     */
    fun sourceColumnWidth(playableServices: Int): Int =
        minOf(SOURCE_COLUMN_MAX, maxOf(2, playableServices) * (SOURCE_ICON + SOURCE_ICON_GAP) - SOURCE_ICON_GAP)
}

/** See [TrackRowRules.state]. */
enum class TrackRowState { RESOLVING, UNPLAYABLE, PLAYABLE }

/** Whether a row in this state is drawn dimmed. */
val TrackRowState.dimmed: Boolean get() = this == TrackRowState.UNPLAYABLE

/** Convenience: the row's state straight from its sources and the badge settings. */
fun trackRowState(
    sources: List<ResolvedSource>?,
    order: List<String>,
    active: List<String> = emptyList(),
    disabled: List<String> = emptyList(),
    notPlaying: List<String> = emptyList(),
): TrackRowState = TrackRowRules.state(
    sources,
    sources?.let { ResolverBadges.badges(it, order, active, disabled, notPlaying) } ?: emptyList(),
)
