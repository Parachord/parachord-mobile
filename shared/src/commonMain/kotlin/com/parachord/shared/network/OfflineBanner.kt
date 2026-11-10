package com.parachord.shared.network

/**
 * The "No internet connection" banner both apps show at the top of the screen
 * while the device is offline: Android's `NetworkBanner`, iOS's
 * `PCOfflineBanner` (`RootView.swift`).
 */
object OfflineBanner {
    /**
     * The banner shows only after the device has been offline this long, so a
     * brief drop doesn't flash it. It hides as soon as the network is back.
     */
    const val SHOW_DELAY_MS = 2_000L

    const val MESSAGE = "No internet connection"
}
