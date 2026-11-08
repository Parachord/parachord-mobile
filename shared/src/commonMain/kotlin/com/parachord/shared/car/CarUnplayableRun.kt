package com.parachord.shared.car

/**
 * How many tracks in a row may fail with no playable source, while a car is
 * connected, before playback stops on the last one with
 * [CarMessages.OPEN_PARACHORD_ON_PHONE] instead of skipping on.
 */
const val CAR_UNPLAYABLE_RUN_LIMIT = 3

/**
 * Counts tracks in a row that had no source that could play them, so a car
 * doesn't silently run through a whole queue it can't play (CarPlay with the
 * phone app in the background can't play Bandcamp or SoundCloud).
 *
 * Only failures count; any track that starts resets the run. With no car
 * connected the run never stops playback, so the phone keeps skipping as before.
 */
class CarUnplayableRun(private val limit: Int = CAR_UNPLAYABLE_RUN_LIMIT) {
    var count: Int = 0
        private set

    /** A track started playing. */
    fun started() {
        count = 0
    }

    /**
     * A track failed with no playable source. True when playback should stop
     * on it: a car is connected and this failure reaches [limit]. The run starts
     * over after a stop, so pressing play counts afresh.
     */
    fun failed(carConnected: Boolean): Boolean {
        count += 1
        if (carConnected && count >= limit) {
            count = 0
            return true
        }
        return false
    }
}
