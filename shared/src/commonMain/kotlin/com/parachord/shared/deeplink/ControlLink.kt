package com.parachord.shared.deeplink

/**
 * Whether a `parachord://control/<action>` link should toggle playback.
 *
 * Both platforms only expose a toggle, so a bare toggle would make `pause`
 * resume a paused track. As on desktop (`app.js` `case "control"`), `pause`
 * acts only while playing, and `resume` and `play` only while paused. Any other
 * action, such as `skip`, doesn't toggle.
 */
fun controlLinkShouldToggle(action: String, isPlaying: Boolean): Boolean = when (action) {
    "pause" -> isPlaying
    "resume", "play" -> !isPlaying
    else -> false
}
