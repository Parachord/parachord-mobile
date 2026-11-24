package com.parachord.android.car

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether the car's UI restrictions are on (≈ moving) — the AAOS side of the
 * parked-only gate: the car home's settings gear, and the full app behind it,
 * are offered only while parked.
 *
 * `android.car` isn't in the public SDK (and `CarDrivingStateMonitor` is
 * system-only), so this reaches `CarUxRestrictionsManager` through
 * reflection: `Car.createCar` → `getCarManager("uxrestriction")` → the current
 * restrictions plus a listener proxy. `isRequiresDistractionOptimization` is
 * the signal third-party AAOS apps are told to use; it is on while the car
 * moves and off while parked.
 *
 * Anything unavailable — a phone, a device without the car service, a
 * refused registration — **fails open** (not restricted, the gear works): the
 * gate keeps a full app out of the driver's view, it isn't a safety
 * interlock, and a stuck-shut gear would hide Settings forever.
 */
class CarDrivingState(private val context: Context) {

    private val _restricted = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Connects once; idempotent, safe to call from any car screen's LaunchedEffect. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { connect() }
    }

    private fun connect() {
        try {
            val carClass = Class.forName("android.car.Car")
            // createCar blocks until the car service connects, and can wait forever
            // on a sick car service; the two-arg overload (API 30+) bounds the wait.
            // Older AAOS cars only have the one-arg form — fall back to it.
            val createCar = try {
                carClass.getMethod("createCar", Context::class.java, java.lang.Long.TYPE)
            } catch (_: NoSuchMethodException) {
                null
            }
            val car = if (createCar != null) {
                createCar.invoke(null, context, 10_000L)
            } else {
                carClass.getMethod("createCar", Context::class.java).invoke(null, context)
            }
            // Either way this ran off the main thread.
            val manager = carClass.getMethod("getCarManager", String::class.java)
                .invoke(car, "uxrestriction")
            readRestricted(
                manager.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(manager),
            )?.let { _restricted.value = it }
            val listenerClass = Class.forName(
                "android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener",
            )
            val listener = Proxy.newProxyInstance(context.classLoader, arrayOf(listenerClass)) { _, _, args ->
                // StateFlow is thread-safe; the manager calls back on its own thread.
                readRestricted(args.firstOrNull())?.let { _restricted.value = it }
                null
            }
            manager.javaClass
                .getMethod("registerCarUxRestrictionsListener", listenerClass)
                .invoke(manager, listener)
        } catch (e: Throwable) {
            // No car service, or it refused: fail open — the gear stays available.
            Log.w(TAG, "Car UX restrictions unavailable (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    /** `CarUxRestrictions.isRequiresDistractionOptimization`, or null when it can't be read. */
    private fun readRestricted(restrictions: Any?): Boolean? = try {
        if (restrictions == null) {
            null
        } else {
            restrictions.javaClass.getMethod("isRequiresDistractionOptimization")
                .invoke(restrictions) as? Boolean
        }
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val TAG = "CarDrivingState"
    }
}
