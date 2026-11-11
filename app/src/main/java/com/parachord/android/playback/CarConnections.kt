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
 *
 * A tracked controller alone can outlive the car: Media3 never reports a
 * disconnect for a legacy `MediaBrowserCompat` connection, which Android Auto
 * may use. So the controller asks [confirmed] at the moment it matters, which
 * lets Android Auto's own car-connection state ([CAR_CONNECTION_NOT_CONNECTED])
 * veto a stale "in the car".
 *
 * Controllers are keys in a set, so they need stable `equals` / `hashCode`
 * (Media3's `ControllerInfo` has them).
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

    /** The session is gone (the service was destroyed), or the car is: nothing is connected. */
    fun clear() {
        live.clear()
    }

    /**
     * The car connection's number when the controller is about to decide
     * whether the car stops the queue: [current], unless Android Auto's own
     * car-connection state ([projectionState], null when it can't be read)
     * says no car is connected; then the tracked controllers are cleared, so
     * the next Android Auto connect is a new connection. [projectionState] is
     * asked only while a controller is tracked, so a phone that never met a
     * car never asks.
     */
    suspend fun confirmed(projectionState: suspend () -> Int?): Int? {
        val connection = current ?: return null
        if (!stillInCar(tracked = true, projectionState = projectionState())) {
            clear()
            return null
        }
        return connection
    }

    companion object {
        /** Android Auto's package (gearhead), which connects to the session from the phone. */
        const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    }
}

/**
 * Android Auto's car-connection state: the provider AndroidX's `CarConnection`
 * reads (`androidx.car.app.connection.CarConnectionTypeLiveData`), at
 * [CAR_CONNECTION_URI], column [CAR_CONNECTION_STATE_COLUMN]. 0 is no car,
 * 1 a native (Android Automotive) car, 2 Android Auto projection.
 */
internal const val CAR_CONNECTION_AUTHORITY = "androidx.car.app.connection"
internal const val CAR_CONNECTION_URI = "content://$CAR_CONNECTION_AUTHORITY"
internal const val CAR_CONNECTION_STATE_COLUMN = "CarConnectionState"
internal const val CAR_CONNECTION_NOT_CONNECTED = 0

/**
 * Whether the app is in the car: a tracked Android Auto controller ([tracked])
 * that Android Auto's car-connection state doesn't contradict. The state can
 * only veto: [CAR_CONNECTION_NOT_CONNECTED] means no car; unknown (null: no
 * provider, unreadable) or connected (1, 2) leaves the tracked answer.
 */
internal fun stillInCar(tracked: Boolean, projectionState: Int?): Boolean =
    tracked && projectionState != CAR_CONNECTION_NOT_CONNECTED
