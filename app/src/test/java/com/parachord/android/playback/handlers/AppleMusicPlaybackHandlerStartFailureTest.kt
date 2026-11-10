package com.parachord.android.playback.handlers

import com.parachord.shared.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * An Apple Music play that doesn't start must say so. Offline, MusicKit's
 * `play()` fails at once ("Unable to complete request for
 * https://api.music.apple.com/…"); the handler retried, re-authorized, played
 * once more and returned as if it had started. The controller then polled a
 * track that wasn't playing, and Play didn't load it again once the network
 * was back. Same when MusicKit never loaded (an offline launch), or when
 * sign-in was required.
 */
class AppleMusicPlaybackHandlerStartFailureTest {

    private val track = Track(
        id = "t1", title = "15 Step", artist = "Radiohead", album = "In Rainbows",
        resolver = "applemusic", appleMusicId = "1109715066",
    )

    private fun bridge(
        configured: Boolean = true,
        configures: Boolean = true,
        configureFailure: MusicKitConfigureFailure? = null,
        authorized: Boolean = true,
        authorizes: Boolean = true,
        plays: List<Boolean> = listOf(false),
    ) = mockk<MusicKitWebBridge>(relaxed = true) {
        every { this@mockk.configured } returns MutableStateFlow(configured)
        every { this@mockk.authorized } returns MutableStateFlow(authorized)
        coEvery { configure() } returns configures
        every { lastConfigureFailure } returns configureFailure
        coEvery { authorize() } returns authorizes
        coEvery { play(any()) } returnsMany plays
        every { lastPlayTimedOut } returns false
    }

    private suspend fun failure(bridge: MusicKitWebBridge): PlaybackStartFailedException {
        try {
            AppleMusicPlaybackHandler(bridge).play(track)
        } catch (e: PlaybackStartFailedException) {
            return e
        }
        fail("expected PlaybackStartFailedException")
        throw AssertionError()
    }

    @Test fun `a play that keeps failing throws once the retries and re-authorization are spent`() = runTest {
        val bridge = bridge(plays = listOf(false))
        val e = failure(bridge)
        // First try, 3 retries, one more after re-authorizing.
        coVerify(exactly = 5) { bridge.play("1109715066") }
        coVerify(exactly = 1) { bridge.authorize() }
        assertFalse("the controller tells the user", e.alreadyReported)
    }

    @Test fun `a play that starts on a retry doesn't throw`() = runTest {
        val bridge = bridge(plays = listOf(false, true))
        AppleMusicPlaybackHandler(bridge).play(track)
        coVerify(exactly = 2) { bridge.play("1109715066") }
        coVerify(exactly = 0) { bridge.authorize() }
    }

    @Test fun `a play that starts after re-authorizing doesn't throw`() = runTest {
        val bridge = bridge(plays = listOf(false, false, false, false, true))
        AppleMusicPlaybackHandler(bridge).play(track)
        coVerify(exactly = 5) { bridge.play("1109715066") }
    }

    @Test fun `MusicKit that never loaded is a failed start the controller reports`() = runTest {
        val bridge = bridge(
            configured = false, configures = false,
            configureFailure = MusicKitConfigureFailure.NOT_LOADED,
        )
        val e = failure(bridge)
        assertFalse(e.alreadyReported)
        coVerify(exactly = 0) { bridge.play(any()) }
    }

    @Test fun `an expired developer token is a failed start its own message already reported`() = runTest {
        val bridge = bridge(
            configured = false, configures = false,
            configureFailure = MusicKitConfigureFailure.DEVELOPER_TOKEN_EXPIRED,
        )
        assertTrue(failure(bridge).alreadyReported)
    }

    @Test fun `sign-in required is a failed start, reported by the sign-in prompt`() = runTest {
        val bridge = bridge(authorized = false, authorizes = false)
        val e = failure(bridge)
        assertTrue(e.alreadyReported)
        verify(exactly = 1) { bridge.emitSignInRequired() }
        coVerify(exactly = 0) { bridge.play(any()) }
    }

    @Test fun `re-authorization that fails after the retries prompts sign-in and fails the start`() = runTest {
        val bridge = bridge(authorizes = false)
        val e = failure(bridge)
        assertTrue(e.alreadyReported)
        verify(exactly = 1) { bridge.emitSignInRequired() }
        coVerify(exactly = 4) { bridge.play("1109715066") }
    }

    @Test fun `a retry that hangs stops the retries and reports a timeout`() = runTest {
        val bridge = bridge(plays = listOf(false, false))
        // The first play fails outright; the first retry hangs (MusicKit is reset).
        every { bridge.lastPlayTimedOut } returnsMany listOf(false, true)
        try {
            AppleMusicPlaybackHandler(bridge).play(track)
            fail("expected PlaybackStartTimeoutException")
        } catch (_: PlaybackStartTimeoutException) {
        }
        coVerify(exactly = 2) { bridge.play("1109715066") }
        coVerify(exactly = 0) { bridge.authorize() }
    }

    @Test fun `the failure names what failed`() = runTest {
        val e = failure(bridge(configured = false, configures = false, configureFailure = MusicKitConfigureFailure.NOT_LOADED))
        assertEquals("Apple Music couldn't start: MusicKit didn't configure (NOT_LOADED)", e.message)
    }
}
