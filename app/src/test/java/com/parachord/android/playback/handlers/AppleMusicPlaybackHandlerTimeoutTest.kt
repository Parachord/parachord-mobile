package com.parachord.android.playback.handlers

import com.parachord.shared.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

/**
 * A play that hangs (the network dropped mid-start) must give up and say so,
 * not retry or open the Apple Music sign-in: the hang held the playback lock
 * until the app was killed, with a spinner and no prev/next (#449).
 */
class AppleMusicPlaybackHandlerTimeoutTest {

    private val track = Track(
        id = "t1", title = "I Know!", artist = "Swapmeet", album = "Mount Zero",
        resolver = "applemusic", appleMusicId = "1880091921",
    )

    private fun bridge(timedOut: Boolean) = mockk<MusicKitWebBridge>(relaxed = true) {
        every { configured } returns MutableStateFlow(true)
        every { authorized } returns MutableStateFlow(true)
        coEvery { play(any()) } returns false
        every { lastPlayTimedOut } returns timedOut
    }

    @Test fun `a hung play throws PlaybackStartTimeoutException without retrying or re-authorizing`() = runTest {
        val bridge = bridge(timedOut = true)
        try {
            AppleMusicPlaybackHandler(bridge).play(track)
            fail("expected PlaybackStartTimeoutException")
        } catch (_: PlaybackStartTimeoutException) {
        }
        coVerify(exactly = 1) { bridge.play("1880091921") }
        coVerify(exactly = 0) { bridge.authorize() }
    }

    @Test fun `an ordinary failure still retries (DRM handshake) and doesn't throw`() = runTest {
        val bridge = bridge(timedOut = false)
        AppleMusicPlaybackHandler(bridge).play(track)
        coVerify(atLeast = 2) { bridge.play("1880091921") }
    }
}
