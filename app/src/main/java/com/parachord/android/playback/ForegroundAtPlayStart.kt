package com.parachord.android.playback

/**
 * Whether a play should put PlaybackService in the foreground as it starts,
 * before the track is looked up and routed.
 *
 * Without a foreground service, Android 15+ cuts the network of an app that
 * isn't on screen (the `APP_BACKGROUND` firewall chain), so Apple Music and
 * every lookup fail and nothing plays. A play from Android Auto, a Bluetooth
 * button or the lock-screen controls lets the app start a foreground service
 * for about 10 seconds; the service used to be started only once the track was
 * routed, which an on-the-fly lookup or a slow source can push past that.
 *
 * - [userRequested]: the queue moving on by itself has no such window, so the
 *   start would be refused; and a local track there keeps Media3's own
 *   foreground.
 * - [appVisible]: with a Parachord screen showing, a later start is allowed
 *   anyway, and promoting first would only swap the notification needlessly.
 * - [externalPlayback]: the service is already in the foreground for it.
 */
internal fun shouldPromoteAtPlayStart(userRequested: Boolean, appVisible: Boolean, externalPlayback: Boolean): Boolean =
    userRequested && !appVisible && !externalPlayback
