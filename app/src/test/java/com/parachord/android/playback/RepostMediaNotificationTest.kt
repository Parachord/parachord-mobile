package com.parachord.android.playback

import android.app.ForegroundServiceStartNotAllowedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [repostMediaNotification] is the artwork re-post in PlaybackService's
 * notification provider. A play started from a link while the phone was locked
 * crashed the app: Media3 tried to start the service in the foreground from the
 * background, Android refused, and nothing caught the refusal.
 */
class RepostMediaNotificationTest {

    @Test
    fun `a refused foreground start does not escape`() {
        val posted = repostMediaNotification(externalForeground = false) {
            throw ForegroundServiceStartNotAllowedException("startForegroundService() not allowed")
        }
        assertFalse(posted)
    }

    @Test
    fun `the notification is posted when nothing refuses it`() {
        var calls = 0
        assertTrue(repostMediaNotification(externalForeground = false) { calls++ })
        assertEquals(1, calls)
    }

    @Test
    fun `nothing is posted through Media3 during external playback`() {
        var calls = 0
        assertFalse(repostMediaNotification(externalForeground = true) { calls++ })
        assertEquals(0, calls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `other failures still propagate`() {
        repostMediaNotification(externalForeground = false) { throw IllegalArgumentException("bug") }
    }
}
