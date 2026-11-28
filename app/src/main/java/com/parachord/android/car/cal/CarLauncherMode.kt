package com.parachord.android.car.cal

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Gates the CAL drive UI and launcher entry to AAOS cars with the media
 * templates host (`android.software.car.templates_host.media`).
 *
 * The manifest ships:
 * - `.ui.MainActivity` (`<activity-alias>` targeting `.ui.ParachordActivity`) —
 *   keeps the exact launcher `ComponentName` existing phone users already have
 *   pinned on their home screens, while letting AAOS disable the launcher
 *   entry without disabling `.ui.ParachordActivity` itself.
 * - `androidx.car.app.activity.CarAppActivity` and `.car.cal.CalCarAppService`
 *   both `enabled="false"` by default so phones (including projected Android
 *   Auto) never see a second launcher icon and never offer the AAOS-only CAL
 *   service.
 *
 * On an AAOS car with the templates host, this disables the phone launcher
 * alias and enables `CarAppActivity` + `CalCarAppService` once. The setting
 * persists in PackageManager; subsequent launches short-circuit without making
 * PackageManager writes.
 */
object CarLauncherMode {

    internal const val TEMPLATES_HOST_FEATURE = "android.software.car.templates_host.media"
    internal const val PHONE_LAUNCHER_ALIAS = "com.parachord.android.ui.MainActivity"
    internal const val CAR_APP_ACTIVITY = "androidx.car.app.activity.CarAppActivity"
    internal const val CAR_APP_SERVICE = "com.parachord.android.car.cal.CalCarAppService"

    /** Runs at process start; idempotent and skips PackageManager writes once applied. */
    fun apply(context: Context) {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(TEMPLATES_HOST_FEATURE)) return
        setEnabledIfNeeded(pm, ComponentName(context.packageName, PHONE_LAUNCHER_ALIAS), PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        setEnabledIfNeeded(pm, ComponentName(context.packageName, CAR_APP_SERVICE), PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        setEnabledIfNeeded(pm, ComponentName(context.packageName, CAR_APP_ACTIVITY), PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }

    private fun setEnabledIfNeeded(pm: PackageManager, component: ComponentName, desiredState: Int) {
        if (pm.getComponentEnabledSetting(component) != desiredState) {
            pm.setComponentEnabledSetting(component, desiredState, PackageManager.DONT_KILL_APP)
        }
    }
}
