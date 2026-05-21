package com.parachord.android.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.parachord.shared.model.Track
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ForwardingPlayerSnapshotTest {

    private fun track(id: String) = Track(id = id, title = id, artist = "A", album = "B")

    @Test fun `getCurrentTimeline reflects last updateQueueSnapshot call`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        wrapper.updateQueueSnapshot(
            currentTrack = track("c1"),
            upNext = listOf(track("u1"), track("u2")),
        )
        val timeline = wrapper.currentTimeline
        assertEquals(3, timeline.windowCount)
        val window = Timeline.Window()
        timeline.getWindow(0, window)
        assertEquals("c1#0", window.uid)
        timeline.getWindow(1, window)
        assertEquals("u1#1", window.uid)
    }

    @Test fun `getCurrentMediaItemIndex is always 0 when current track present`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        wrapper.updateQueueSnapshot(track("current"), listOf(track("a"), track("b")))
        assertEquals(0, wrapper.currentMediaItemIndex)
    }

    @Test fun `hasNextMediaItem reflects upNext non-empty`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        wrapper.updateQueueSnapshot(track("c"), upNext = emptyList())
        assertEquals(false, wrapper.hasNextMediaItem())

        wrapper.updateQueueSnapshot(track("c"), upNext = listOf(track("u1")))
        assertEquals(true, wrapper.hasNextMediaItem())
    }

    @Test fun `hasPreviousMediaItem returns false in v1`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1")))
        assertEquals(false, wrapper.hasPreviousMediaItem())
    }

    @Test fun `seekTo with index larger than 0 routes to playFromQueue with offset`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1"), track("u2")))

        wrapper.seekTo(2, 0L)
        verify { controller.playFromQueue(1) }
    }

    @Test fun `seekTo with index 0 does not invoke playFromQueue`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1")))

        wrapper.seekTo(0, 5_000L)
        verify(exactly = 0) { controller.playFromQueue(any()) }
    }

    @Test fun `moveMediaItem subtracts 1 from indices when routing to controller`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1"), track("u2"), track("u3")))

        wrapper.moveMediaItem(/* fromIndex = */ 3, /* newIndex = */ 1)
        verify { controller.moveInQueue(2, 0) }
    }

    @Test fun `removeMediaItem subtracts 1 when routing to controller`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1"), track("u2")))

        wrapper.removeMediaItem(/* index = */ 2)
        verify { controller.removeFromQueue(1) }
    }

    @Test fun `updateQueueSnapshot fires onTimelineChanged on registered external listeners`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        val timelineSlot = slot<Timeline>()
        val reasonSlot = slot<Int>()
        val listener = mockk<Player.Listener>(relaxed = true) {
            every { onTimelineChanged(capture(timelineSlot), capture(reasonSlot)) } returns Unit
        }
        wrapper.addListener(listener)

        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1")))

        verify { listener.onTimelineChanged(any(), Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
        assertEquals(2, timelineSlot.captured.windowCount)
    }

    @Test fun `current track change fires onMediaItemTransition`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        val itemSlot = slot<MediaItem?>()
        val listener = mockk<Player.Listener>(relaxed = true) {
            every { onMediaItemTransition(captureNullable(itemSlot), any()) } returns Unit
        }
        wrapper.addListener(listener)

        wrapper.updateQueueSnapshot(track("first"), emptyList())
        wrapper.updateQueueSnapshot(track("second"), emptyList())

        verify(exactly = 2) { listener.onMediaItemTransition(any(), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
    }

    @Test fun `empty snapshot reports zero count and null current item`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        wrapper.updateQueueSnapshot(currentTrack = null, upNext = emptyList())
        assertEquals(0, wrapper.mediaItemCount)
        assertNull(wrapper.currentMediaItem)
        assertEquals(C.INDEX_UNSET, wrapper.currentMediaItemIndex)
    }

    @Test fun `current to null transition fires onMediaItemTransition with null item`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        val listener = mockk<Player.Listener>(relaxed = true)
        wrapper.addListener(listener)

        wrapper.updateQueueSnapshot(track("c"), emptyList())  // current: c → c (was null)
        wrapper.updateQueueSnapshot(currentTrack = null, upNext = emptyList())  // current: c → null

        verify(exactly = 2) { listener.onMediaItemTransition(any(), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
    }

    @Test fun `getMediaItemAt returns the right item for valid index`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1"), track("u2")))
        assertEquals("c", wrapper.getMediaItemAt(0).mediaId)
        assertEquals("u1", wrapper.getMediaItemAt(1).mediaId)
        assertEquals("u2", wrapper.getMediaItemAt(2).mediaId)
    }

    @Test fun `addMediaItems is a no-op (snapshot unchanged)`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1")))
        val countBefore = wrapper.mediaItemCount

        wrapper.addMediaItems(0, listOf(track("new").toAutoMediaItem()))

        assertEquals(countBefore, wrapper.mediaItemCount)  // snapshot didn't change
    }

    @Test fun `addListener after updateQueueSnapshot does NOT replay last event`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1")))

        val lateListener = mockk<Player.Listener>(relaxed = true)
        wrapper.addListener(lateListener)

        // No replay: lateListener has not seen the previous snapshot.
        verify(exactly = 0) { lateListener.onTimelineChanged(any(), any()) }
        verify(exactly = 0) { lateListener.onMediaItemTransition(any(), any()) }

        // Next snapshot does fire on the late listener.
        wrapper.updateQueueSnapshot(track("c2"), emptyList())
        verify(exactly = 1) { lateListener.onTimelineChanged(any(), Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
        verify(exactly = 1) { lateListener.onMediaItemTransition(any(), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
    }

    @Test fun `delegate isPlayingChanged is forwarded to external listeners`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val forwarderSlot = slot<Player.Listener>()
        every { delegate.addListener(capture(forwarderSlot)) } returns Unit
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        val external = mockk<Player.Listener>(relaxed = true)
        wrapper.addListener(external)
        // Wrapper installed the internal forwarder on delegate. Simulate a
        // delegate-side state change.
        forwarderSlot.captured.onIsPlayingChanged(true)
        verify { external.onIsPlayingChanged(true) }
    }

    @Test fun `delegate timeline events are NOT forwarded to external listeners`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val forwarderSlot = slot<Player.Listener>()
        every { delegate.addListener(capture(forwarderSlot)) } returns Unit
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        val external = mockk<Player.Listener>(relaxed = true)
        wrapper.addListener(external)
        // The wrapper installs an internal delegateForwarder on the delegate
        // (NOT the external listener itself). That forwarder re-emits a
        // curated subset of non-timeline events to externalListeners and
        // drops `onTimelineChanged` / `onMediaItemTransition` from the
        // delegate.
        //
        // This test pins THE bug fix: if a future change brings back the
        // "forward everything" behavior, the delegate's single-item
        // silence-loop timeline leaks into Auto and the Up Next list goes
        // empty after every track transition.
        val fakeTimeline = mockk<Timeline>(relaxed = true)
        val fakeItem = track("delegate-item").toAutoMediaItem()
        forwarderSlot.captured.onTimelineChanged(fakeTimeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        forwarderSlot.captured.onMediaItemTransition(fakeItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        verify(exactly = 0) { external.onTimelineChanged(any(), any()) }
        verify(exactly = 0) { external.onMediaItemTransition(any(), any()) }
    }

    @Test fun `silence-loop timeline reload does not clobber synthetic queue snapshot`() {
        // Regression test for the queue-disappears-after-track-transition
        // bug: delegate fires onTimelineChanged when the underlying
        // ExoPlayer's silence-loop or single-track MediaItem is replaced,
        // and that event must NOT propagate to Auto. The wrapper's
        // synthetic timeline (built from the real QueueManager) must
        // remain the only timeline Auto sees.
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val forwarderSlot = slot<Player.Listener>()
        every { delegate.addListener(capture(forwarderSlot)) } returns Unit
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        // First register a no-op listener to trigger the delegateForwarder
        // install (otherwise forwarderSlot stays empty).
        wrapper.addListener(mockk(relaxed = true))
        // Build a 3-track synthetic queue.
        wrapper.updateQueueSnapshot(track("current"), listOf(track("u1"), track("u2")))
        assertEquals(3, wrapper.currentTimeline.windowCount)
        // Now register the listener we care about — AFTER the snapshot,
        // so its onTimelineChanged invocation count starts at 0 even
        // though synthetic events already fired for the first listener.
        val external = mockk<Player.Listener>(relaxed = true)
        wrapper.addListener(external)
        // Simulate a silence-loop reload firing from the delegate.
        // (Timeline argument identity is irrelevant — what matters is
        // whether any external listener sees the event AT ALL.)
        val silenceLoopTimeline = mockk<Timeline>(relaxed = true)
        forwarderSlot.captured.onTimelineChanged(
            silenceLoopTimeline,
            Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
        )
        // Synthetic queue must still be the wrapper's reported timeline.
        assertEquals(3, wrapper.currentTimeline.windowCount)
        // External listener must not have received the silence-loop event.
        // (any() to avoid Timeline.equals() pulling unmocked getWindowCount.)
        verify(exactly = 0) { external.onTimelineChanged(any(), any()) }
    }

    @Test fun `getMediaItemAt out of bounds throws`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.updateQueueSnapshot(track("c"), listOf(track("u1")))
        // Pin Media3-conformant behavior: out-of-bounds throws (matches Timeline.getWindow contract).
        try {
            wrapper.getMediaItemAt(2)
            org.junit.Assert.fail("expected IndexOutOfBoundsException")
        } catch (e: IndexOutOfBoundsException) {
            // expected
        }
    }

    @Test fun `external-mode duration is TIME_UNSET when stateHolder duration is zero`() {
        // Regression test for the 10:00 progress-bar leak (issue #111).
        // The silence WAV is exactly 10 minutes; if we fell through to
        // super.getDuration() during external mode while the real
        // duration hasn't been populated yet, Auto would display 600_000ms.
        // We return TIME_UNSET instead so Auto shows an indeterminate bar.
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        every { delegate.duration } returns 600_000L  // silence WAV
        every { delegate.contentDuration } returns 600_000L
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.externalMode = true

        // stateHolder.duration is 0 by default — real duration not yet known.
        assertEquals(C.TIME_UNSET, wrapper.duration)
        assertEquals(C.TIME_UNSET, wrapper.contentDuration)
    }

    @Test fun `external-mode duration reflects stateHolder when populated`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        every { delegate.duration } returns 600_000L
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)
        wrapper.externalMode = true

        stateHolder.update { copy(duration = 245_000L) }
        assertEquals(245_000L, wrapper.duration)
        assertEquals(245_000L, wrapper.contentDuration)
    }

    @Test fun `same current track does not re-fire onMediaItemTransition`() {
        val delegate = mockk<androidx.media3.exoplayer.ExoPlayer>(relaxed = true)
        every { delegate.currentTimeline } returns Timeline.EMPTY
        val controller = mockk<PlaybackController>(relaxed = true)
        val stateHolder = PlaybackStateHolder()
        val wrapper = PlaybackService.ExternalPlaybackForwardingPlayer(delegate, controller, stateHolder)

        val listener = mockk<Player.Listener>(relaxed = true)
        wrapper.addListener(listener)

        wrapper.updateQueueSnapshot(track("c"), listOf(track("a")))
        wrapper.updateQueueSnapshot(track("c"), listOf(track("a"), track("b")))

        verify(exactly = 1) { listener.onMediaItemTransition(any(), Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
    }
}
