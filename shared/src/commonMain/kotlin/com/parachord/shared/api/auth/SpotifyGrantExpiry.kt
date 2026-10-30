package com.parachord.shared.api.auth

/**
 * Warn before Spotify's 6-month sign-in expiry (#352, desktop parachord#967).
 *
 * Since 2026-07-20 a Spotify refresh token dies 6 months after the user's
 * original authorization, and refreshing doesn't extend it, so every user is
 * signed out on a predictable date. Spotify doesn't expose the grant's issue
 * date: the client records it (`spotify_authorized_at`) on every successful
 * authorization-code exchange and warns in the last [WARNING_WINDOW_MS].
 *
 * The expiry itself is still handled reactively (a terminal `invalid_grant`
 * shows the "session expired" prompt); this only gets ahead of it.
 */
object SpotifyGrantExpiry {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Six months, rounded down so the warning never arrives late. */
    const val LIFETIME_MS = 182L * DAY_MS

    /** Warn this long before the expiry (desktop: 7 days). */
    const val WARNING_WINDOW_MS = 7L * DAY_MS

    /** A dismissed warning comes back at most once a day. */
    const val REMIND_INTERVAL_MS = DAY_MS

    /**
     * Whether to show the warning now. Never without a recorded
     * authorization (0): an install that connected before the timestamp was
     * recorded can't know its grant's age, and guessing "now" would warn far
     * too late. Those users hit the reactive prompt once, reconnect, and get a
     * real timestamp from then on (desktop's choice).
     */
    fun shouldWarn(authorizedAtMs: Long, dismissedAtMs: Long, nowMs: Long): Boolean {
        if (authorizedAtMs <= 0) return false
        if (nowMs < authorizedAtMs + LIFETIME_MS - WARNING_WINDOW_MS) return false
        return nowMs - dismissedAtMs >= REMIND_INTERVAL_MS
    }

    /** Whole days left before the grant expires, never below 0. */
    fun daysLeft(authorizedAtMs: Long, nowMs: Long): Int {
        val remaining = authorizedAtMs + LIFETIME_MS - nowMs
        return if (remaining <= 0) 0 else ((remaining + DAY_MS - 1) / DAY_MS).toInt()
    }

    /** The warning's text for [daysLeft] days. */
    fun message(daysLeft: Int): String = when (daysLeft) {
        0 -> "Your Spotify sign-in expires today. Reconnect now to avoid interruption."
        1 -> "Your Spotify sign-in expires tomorrow. Reconnect now to avoid interruption."
        else -> "Your Spotify sign-in expires in $daysLeft days. Reconnect now to avoid interruption."
    }
}
