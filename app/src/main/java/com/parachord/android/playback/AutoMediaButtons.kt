package com.parachord.android.playback

/** A custom button on Android Auto's now-playing screen and the system media controls. */
enum class AutoMediaButton { LOVE, UNLOVE, SPINOFF, END_SPINOFF }

/**
 * Which custom buttons to show, left to right: the heart, then Spinoff.
 *
 * [loved] is null when nothing is playing, and then there are no buttons.
 * Spinoff follows the phone's Now Playing button: offered unless the
 * availability check found nothing similar ([spinoffAvailable] false), and
 * while a spinoff runs it ends it. Unlike the phone, an unavailable Spinoff is
 * hidden, not dimmed: Android Auto doesn't reliably draw a disabled button.
 */
fun autoMediaButtons(
    loved: Boolean?,
    spinoffMode: Boolean,
    spinoffAvailable: Boolean?,
): List<AutoMediaButton> {
    if (loved == null) return emptyList()
    val heart = if (loved) AutoMediaButton.UNLOVE else AutoMediaButton.LOVE
    val spinoff = when {
        spinoffMode -> AutoMediaButton.END_SPINOFF
        spinoffAvailable == false -> null
        else -> AutoMediaButton.SPINOFF
    }
    return listOfNotNull(heart, spinoff)
}
