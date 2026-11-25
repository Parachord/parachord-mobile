package com.parachord.android.car.cal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Which launcher entry the car sees. The manifest ships two: the `PhoneLauncher`
 * alias (the full app — phones' only icon) and the CAL `CarAppActivity` (disabled
 * by default). On a car with the media templates host this flips them once per
 * process start: the car launcher — and the home-screen media card's tap-out,
 * which resolves the app's launcher intent — then open the CAL drive app, and
 * the full app stays reachable from the drive app's Settings row (parked; the
 * system blocks non-distraction-optimized activities while moving).
 *
 * The setting is per-device state in PackageManager, so it persists across
 * runs; phones and older AAOS cars (no templates host — the system Media Center
 * renders the shared browse tree) keep the phone alias.
 */
object CarLauncherMode {

    private const val TEMPLATES_HOST_FEATURE = "android.software.car.templates_host.media"
    private const val CAR_APP_ACTIVITY = "androidx.car.app.activity.CarAppActivity"

    /** Runs at every process start; idempotent and cheap. */
    fun apply(context: Context) {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(TEMPLATES_HOST_FEATURE)) return
        val carActivity = ComponentName(context.packageName, CAR_APP_ACTIVITY)
        // Find the phone alias by resolving our own launcher intent; the
        // CarAppActivity is still disabled here, so the only hit is the alias.
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val alias = pm.queryIntentActivities(launcherIntent, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .firstOrNull { it.className != CAR_APP_ACTIVITY }
        if (alias != null) {
            pm.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        pm.setComponentEnabledSetting(
            carActivity,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
