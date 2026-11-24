package com.parachord.android.car

import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether Parachord runs on a car's own Android (Android Automotive, e.g. a
 * Volvo EX90) rather than a phone: [MainActivity][com.parachord.android.ui.MainActivity]
 * swaps its root to the car UI ([CarRoot][com.parachord.android.ui.car.CarRoot])
 * when it is. Android Auto (projected from the phone) needs no check — the
 * car connects to the same `PlaybackService` either way.
 *
 * A cached read: the package manager's answer can't change while the process
 * lives.
 */
object CarEnvironment {

    @Volatile
    private var automotive: Boolean? = null

    fun isAutomotive(context: Context): Boolean =
        automotive ?: context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
            .also { automotive = it }
}
