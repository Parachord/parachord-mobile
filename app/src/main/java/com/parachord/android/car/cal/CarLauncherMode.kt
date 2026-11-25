package com.parachord.android.car.cal

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Which launcher entry the car sees. The manifest ships two:
 * - `.ui.MainActivity` (`<activity-alias>` targeting `.ui.ParachordActivity`) —
 *   keeps the exact launcher `ComponentName` existing phone users already have
 *   pinned on their home screens, while letting the car disable the launcher
 *   entry without disabling `.ui.ParachordActivity` itself.
 * - `androidx.car.app.activity.CarAppActivity` (`enabled="false"` by default so
 *   phones never see a second icon).
 *
 * On a car with the media templates host (`android.software.car.templates_host.media`),
 * this flips them once: the car launcher — and the home-screen media card's
 * tap-out, which resolves the app's launcher intent — then open the CAL drive
 * app, and the full app stays reachable from the drive app's "Open Parachord"
 * row (parked; the system blocks non-distraction-optimized activities while
 * moving). Note: on a fresh cold AAOS install before the process has ever run,
 * the launcher briefly shows the alias until first launch flips it.
 *
 * The setting persists in PackageManager; subsequent launches short-circuit
 * without making PackageManager writes.
 */
object CarLauncherMode {

    internal const val TEMPLATES_HOST_FEATURE = "android.software.car.templates_host.media"
    internal const val PHONE_LAUNCHER_ALIAS = "com.parachord.android.ui.MainActivity"
    internal const val CAR_APP_ACTIVITY = "androidx.car.app.activity.CarAppActivity"

    /** Runs at process start; idempotent and skips PackageManager writes once applied. */
    fun apply(context: Context) {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(TEMPLATES_HOST_FEATURE)) return
        val alias = ComponentName(context.packageName, PHONE_LAUNCHER_ALIAS)
        val carActivity = ComponentName(context.packageName, CAR_APP_ACTIVITY)
        if (pm.getComponentEnabledSetting(alias) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            pm.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        if (pm.getComponentEnabledSetting(carActivity) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            pm.setComponentEnabledSetting(
                carActivity,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
