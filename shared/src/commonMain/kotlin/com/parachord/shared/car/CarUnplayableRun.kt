package com.parachord.shared.car

/**
 * How many tracks in a row may fail with no playable source, in a car that
 * can't play the embeds, before playback stops on the last one with
 * [CarMessages.OPEN_PARACHORD_ON_PHONE] instead of skipping on.
 */
const val CAR_UNPLAYABLE_RUN_LIMIT = 3

/**
 * Counts tracks in a row that had no source that could play them, so a car
 * doesn't silently run through a whole queue it can't play (CarPlay with the
 * phone app in the background can't play Bandcamp or SoundCloud).
 *
 * Only failures count. Any track that starts resets the run, and so does
 * [reset]: the platform calls it when a car connects and on every explicit
 * play (a tap, not the queue moving on), so failures on the phone before the
 * car connected, or before the person picked something new, don't count.
 * Playback stops only while a car is connected and the phone app isn't in
 * front (`carWithoutPhone`: locked, behind another app, or never shown), when
 * nobody can act on the phone; otherwise the queue skips on as on the phone.
 */
class CarUnplayableRun(private val limit: Int = CAR_UNPLAYABLE_RUN_LIMIT) {
    var count: Int = 0
        private set

    /** A track started playing. */
    fun started() {
        count = 0
    }

    /** A car connected, or the person started something themselves. */
    fun reset() {
        count = 0
    }

    /**
     * A track failed with no playable source. True when playback should stop
     * on it: [carWithoutPhone] (a car is connected and the phone app isn't in
     * front) and this failure reaches [limit]. The run starts over after a
     * stop, so pressing play counts afresh.
     */
    fun failed(carWithoutPhone: Boolean): Boolean {
        count += 1
        if (carWithoutPhone && count >= limit) {
            count = 0
            return true
        }
        return false
    }
}
