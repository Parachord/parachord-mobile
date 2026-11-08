package com.parachord.shared.car

/** A custom button on a car's now-playing screen. */
enum class CarNowPlayingButton { LOVE, UNLOVE, SPINOFF, END_SPINOFF }

/**
 * Which custom buttons to show, left to right: the heart, then Spinoff.
 *
 * [loved] is null when nothing is playing, and then there are no buttons.
 * Spinoff follows the phone's Now Playing button: offered unless the
 * availability check found nothing similar ([spinoffAvailable] false), and
 * while a spinoff runs it ends it. Unlike the phone, an unavailable Spinoff is
 * hidden, not dimmed: car screens don't reliably draw a disabled button.
 */
fun carNowPlayingButtons(
    loved: Boolean?,
    spinoffMode: Boolean,
    spinoffAvailable: Boolean?,
): List<CarNowPlayingButton> {
    if (loved == null) return emptyList()
    val heart = if (loved) CarNowPlayingButton.UNLOVE else CarNowPlayingButton.LOVE
    val spinoff = when {
        spinoffMode -> CarNowPlayingButton.END_SPINOFF
        spinoffAvailable == false -> null
        else -> CarNowPlayingButton.SPINOFF
    }
    return listOfNotNull(heart, spinoff)
}
