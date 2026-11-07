package com.parachord.android.deeplink

import android.content.Intent

/**
 * Decides whether `MainActivity.onCreate` should act on the intent it was
 * created with. A link must be handled once: Android hands the same launch
 * intent back on every recreate (rotation, dark-mode switch, restore after
 * process death) and when the person reopens the task from Recents. Replaying
 * a `parachord://play` link then would restart that song over whatever is
 * playing and end a running spinoff. New links arriving while the activity is
 * alive come through `onNewIntent` and are always handled.
 */
object LaunchIntentPolicy {

    /**
     * @param isRestoring `savedInstanceState != null` — the activity is being
     *   recreated and already handled its launch intent the first time.
     * @param intentFlags the launch intent's flags; Recents sets
     *   [Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY] when it re-delivers the
     *   original intent to a new activity instance.
     */
    fun shouldHandleOnCreate(isRestoring: Boolean, intentFlags: Int): Boolean {
        if (isRestoring) return false
        if (intentFlags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        return true
    }
}
