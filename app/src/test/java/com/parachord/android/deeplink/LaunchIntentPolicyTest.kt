package com.parachord.android.deeplink

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A link is handled once: on the create that a link launched, never again
 * when the activity is recreated (rotation, process restore) or relaunched
 * from Recents with the same intent.
 */
class LaunchIntentPolicyTest {

    @Test
    fun `fresh launch handles the link`() {
        assertTrue(LaunchIntentPolicy.shouldHandleOnCreate(isRestoring = false, intentFlags = 0))
    }

    @Test
    fun `fresh launch with ordinary flags handles the link`() {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertTrue(LaunchIntentPolicy.shouldHandleOnCreate(isRestoring = false, intentFlags = flags))
    }

    @Test
    fun `recreate after a configuration change does not replay the link`() {
        assertFalse(LaunchIntentPolicy.shouldHandleOnCreate(isRestoring = true, intentFlags = 0))
    }

    @Test
    fun `relaunch from Recents does not replay the link`() {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        assertFalse(LaunchIntentPolicy.shouldHandleOnCreate(isRestoring = false, intentFlags = flags))
    }

    @Test
    fun `restore from Recents after process death does not replay the link`() {
        assertFalse(
            LaunchIntentPolicy.shouldHandleOnCreate(
                isRestoring = true,
                intentFlags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
            ),
        )
    }
}
