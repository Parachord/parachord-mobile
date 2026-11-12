package com.parachord.android.deeplink

import com.parachord.android.playback.PlaybackController
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * What a `parachord://` link's teardown does before it plays or listens along.
 */
class AndroidProtocolPlayTeardownTest {

    private val controller = mockk<PlaybackController>(relaxed = true)
    private val teardown = AndroidProtocolPlayTeardown(playbackController = controller)

    @Test
    fun `a listen-along link exits a spinoff and keeps the queue`() = runTest {
        // #613: the session sets the whole queue aside and gives it back,
        // as an in-app start does; clearing it left only the current track.
        teardown.prepareForListenAlongHandover()
        verify(exactly = 1) { controller.exitSpinoff() }
        verify(exactly = 0) { controller.clearQueue() }
    }

    @Test
    fun `a play link still clears the queue`() = runTest {
        teardown.prepareForNewPlayback(endListenAlongSilently = false)
        verify(exactly = 1) { controller.exitSpinoff() }
        verify(exactly = 1) { controller.clearQueue() }
    }
}
