package com.parachord.android.playback

/**
 * Whether Android Auto is connected to the media session, as plain values
 * for [PlaybackController]: [PlaybackService] reports each controller that
 * connects and disconnects, and the controller reads [current]. Keeps Media3's
 * session types out of the controller.
 *
 * Lives with the controller (a process-lifetime singleton), not the service,
 * so the connection number keeps going up when the service is destroyed and
 * created again: a reconnection after that is a new number, and the car's
 * unplayable run ([com.parachord.shared.car.CarUnplayableRun]) starts over.
 * Called on the main thread (Media3's session callbacks, the controller).
 */
class CarConnections {
    private val live = mutableSetOf<Any>()
    private var number = 0

    /**
     * The number of the car connection in progress while at least one Android
     * Auto controller is connected; null when none is. It goes up when the
     * first one connects, so Android Auto connecting its browser and its
     * controller (or reconnecting one of them mid-drive) is one connection.
     */
    val current: Int?
        get() = if (live.isEmpty()) null else number

    /** A controller from [packageName] connected; only Android Auto's count. */
    fun connected(packageName: String, controller: Any) {
        if (packageName != ANDROID_AUTO_PACKAGE) return
        if (live.isEmpty()) number++
        live += controller
    }

    /** A controller disconnected. */
    fun disconnected(controller: Any) {
        live -= controller
    }

    /** The session is gone (the service was destroyed): nothing is connected. */
    fun clear() {
        live.clear()
    }

    companion object {
        /** Android Auto's package (gearhead), which connects to the session from the phone. */
        const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    }
}
