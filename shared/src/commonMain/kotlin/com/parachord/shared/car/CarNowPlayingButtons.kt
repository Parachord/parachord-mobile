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
 *
 * While the pool is being built ([spinoffLoading], 15 s or more) Spinoff
 * already shows as running: the car has no other sign that the tap took, and
 * an unchanged button read as a tap that did nothing (#551). A tap then is
 * ignored, as it is on the phone.
 */
fun carNowPlayingButtons(
    loved: Boolean?,
    spinoffMode: Boolean,
    spinoffAvailable: Boolean?,
    spinoffLoading: Boolean = false,
): List<CarNowPlayingButton> {
    if (loved == null) return emptyList()
    val heart = if (loved) CarNowPlayingButton.UNLOVE else CarNowPlayingButton.LOVE
    val spinoff = when {
        spinoffMode || spinoffLoading -> CarNowPlayingButton.END_SPINOFF
        spinoffAvailable == false -> null
        else -> CarNowPlayingButton.SPINOFF
    }
    return listOfNotNull(heart, spinoff)
}
