package com.parachord.android.playback

import com.parachord.android.data.db.entity.TrackEntity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QueueManagerTest {

    private lateinit var queueManager: QueueManager

    @Before
    fun setup() {
        queueManager = QueueManager()
    }

    private fun track(id: String, title: String = "Track $id") = TrackEntity(
        id = id, title = title, artist = "Artist"
    )

    // -- setQueue --

    @Test
    fun `setQueue returns track at startIndex`() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        val result = queueManager.setQueue(tracks, startIndex = 1)
        assertEquals("2", result?.id)
    }

    @Test
    fun `setQueue puts remaining tracks in upNext`() {
        val tracks = listOf(track("1"), track("2"), track("3"), track("4"))
        queueManager.setQueue(tracks, startIndex = 1)
        val snapshot = queueManager.snapshot.value
        assertEquals(listOf("3", "4"), snapshot.upNext.map { it.id })
    }

    @Test
    fun `setQueue returns null for empty list`() {
        assertNull(queueManager.setQueue(emptyList(), 0))
    }

    @Test
    fun `setQueue returns null for out-of-bounds index`() {
        assertNull(queueManager.setQueue(listOf(track("1")), 5))
    }

    @Test
    fun `setQueue keeps earlier history out of the new queue's lap`() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        queueManager.setQueue(tracks, 0)
        queueManager.skipNext(track("1")) // builds history
        assertEquals(1, queueManager.history.size)

        // A new queue keeps it for Previous (#605), out of the repeat-all lap.
        queueManager.setQueue(tracks, 0)
        assertEquals(listOf("1"), queueManager.history.map { it.id })
        assertEquals(listOf(false), queueManager.historyInQueue)
    }

    @Test
    fun `setQueue at last index leaves upNext empty`() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        val result = queueManager.setQueue(tracks, startIndex = 2)
        assertEquals("3", result?.id)
        assertTrue(queueManager.snapshot.value.upNext.isEmpty())
    }

    // -- skipNext --

    @Test
    fun `skipNext returns first upNext track and pushes current to history`() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        queueManager.setQueue(tracks, 0)

        val next = queueManager.skipNext(track("1"))
        assertEquals("2", next?.id)
        assertEquals(listOf("1"), queueManager.history.map { it.id })
    }

    @Test
    fun `skipNext returns null when queue is empty`() {
        assertNull(queueManager.skipNext(track("1")))
    }

    @Test
    fun `skipNext with null currentTrack does not add to history`() {
        val tracks = listOf(track("1"), track("2"))
        queueManager.setQueue(tracks, 0)
        queueManager.skipNext(null)
        assertTrue(queueManager.history.isEmpty())
    }

    // -- skipPrevious --

    @Test
    fun `skipPrevious returns previous track and pushes current to front of upNext`() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        queueManager.setQueue(tracks, 0)
        queueManager.skipNext(track("1"))  // now playing "2", history = ["1"]

        val previous = queueManager.skipPrevious(track("2"))
        assertEquals("1", previous?.id)
        // "2" should be back at front of upNext, followed by "3"
        assertEquals(listOf("2", "3"), queueManager.snapshot.value.upNext.map { it.id })
    }

    @Test
    fun `skipPrevious returns null when history is empty`() {
        assertNull(queueManager.skipPrevious(track("1")))
    }

    // -- addToQueue --

    @Test
    fun `addToQueue appends tracks to end`() {
        val tracks = listOf(track("1"), track("2"))
        queueManager.setQueue(tracks, 0)
        queueManager.addToQueue(listOf(track("3"), track("4")))
        assertEquals(listOf("2", "3", "4"), queueManager.snapshot.value.upNext.map { it.id })
    }

    // -- insertNext --

    @Test
    fun `insertNext puts tracks at front of queue`() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        queueManager.setQueue(tracks, 0)
        queueManager.insertNext(listOf(track("X")))
        assertEquals("X", queueManager.snapshot.value.upNext.first().id)
    }

    // -- playFromQueue --

    @Test
    fun `playFromQueue removes item and items before it`() {
        val tracks = listOf(track("1"), track("2"), track("3"), track("4"))
        queueManager.setQueue(tracks, 0)
        // upNext = [2, 3, 4]; play index 1 (track "3")
        val result = queueManager.playFromQueue(1, track("1"))
        assertEquals("3", result?.id)
        assertEquals(listOf("4"), queueManager.snapshot.value.upNext.map { it.id })
    }

    @Test
    fun `playFromQueue returns null for invalid index`() {
        assertNull(queueManager.playFromQueue(10, track("1")))
    }

    // -- moveInQueue --

    @Test
    fun `moveInQueue reorders tracks`() {
        val tracks = listOf(track("1"), track("2"), track("3"), track("4"))
        queueManager.setQueue(tracks, 0)
        // upNext = [2, 3, 4]; move index 2 to index 0
        queueManager.moveInQueue(2, 0)
        assertEquals(listOf("4", "2", "3"), queueManager.snapshot.value.upNext.map { it.id })
    }

    @Test
    fun `moveInQueue no-op for same index`() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        queueManager.setQueue(tracks, 0)
        val before = queueManager.snapshot.value.upNext.map { it.id }
        queueManager.moveInQueue(0, 0)
        assertEquals(before, queueManager.snapshot.value.upNext.map { it.id })
    }

    // -- removeFromQueue --

    @Test
    fun `removeFromQueue removes track at index`() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        queueManager.setQueue(tracks, 0)
        queueManager.removeFromQueue(0) // remove "2"
        assertEquals(listOf("3"), queueManager.snapshot.value.upNext.map { it.id })
    }

    // -- clearQueue --

    @Test
    fun `clearQueue empties upNext but preserves history`() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        queueManager.setQueue(tracks, 0)
        queueManager.skipNext(track("1"))
        queueManager.clearQueue()

        assertTrue(queueManager.snapshot.value.upNext.isEmpty())
        assertEquals(1, queueManager.history.size) // history preserved
    }

    // -- toggleShuffle --

    @Test
    fun `toggleShuffle enables shuffle and saves original order`() {
        val tracks = (1..20).map { track("$it") }
        queueManager.setQueue(tracks, 0)
        val originalUpNext = queueManager.snapshot.value.upNext.map { it.id }

        val enabled = queueManager.toggleShuffle()
        assertTrue(enabled)
        assertTrue(queueManager.shuffleEnabled)
        assertNotNull(queueManager.savedOriginalOrder)
        // Shuffled order should contain the same tracks
        assertEquals(
            originalUpNext.toSet(),
            queueManager.snapshot.value.upNext.map { it.id }.toSet()
        )
    }

    @Test
    fun `toggleShuffle off restores original order`() {
        val tracks = (1..20).map { track("$it") }
        queueManager.setQueue(tracks, 0)
        val originalUpNext = queueManager.snapshot.value.upNext.map { it.id }

        queueManager.toggleShuffle() // on
        queueManager.toggleShuffle() // off

        assertFalse(queueManager.shuffleEnabled)
        assertEquals(originalUpNext, queueManager.snapshot.value.upNext.map { it.id })
    }

    @Test
    fun `toggleShuffle off removes played tracks from restored order`() {
        val tracks = (1..10).map { track("$it") }
        queueManager.setQueue(tracks, 0)
        queueManager.toggleShuffle() // on — saves original

        // Skip a track (removes from upNext)
        val currentTrack = track("0") // dummy current
        queueManager.skipNext(currentTrack)

        queueManager.toggleShuffle() // off — restore, minus played tracks

        val upNext = queueManager.snapshot.value.upNext
        // Should have one fewer than original
        assertEquals(tracks.size - 2, upNext.size) // -1 for startIndex, -1 for skip
    }

    // -- snapshot emissions --

    @Test
    fun `snapshot reflects shuffle state`() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        queueManager.setQueue(tracks, 0, shuffle = true)
        assertTrue(queueManager.snapshot.value.shuffleEnabled)
    }

    @Test
    fun `snapshot reflects playback context`() {
        val ctx = PlaybackContext(type = "album", name = "Abbey Road", id = "abc")
        val tracks = listOf(track("1"), track("2"))
        queueManager.setQueue(tracks, 0, context = ctx)
        assertEquals(ctx, queueManager.snapshot.value.playbackContext)
    }

    // -- restoreState --

    @Test
    fun `restoreState restores full queue state`() {
        val upNext = listOf(track("2"), track("3"))
        val history = listOf(track("1"))
        val original = listOf(track("2"), track("3"))
        val ctx = PlaybackContext("playlist", "My Playlist", "p1")

        queueManager.restoreState(upNext, history, original, ctx, shuffle = true)

        assertEquals(upNext.map { it.id }, queueManager.snapshot.value.upNext.map { it.id })
        assertEquals(history.map { it.id }, queueManager.history.map { it.id })
        assertEquals(original.map { it.id }, queueManager.savedOriginalOrder?.map { it.id })
        assertEquals(ctx, queueManager.snapshot.value.playbackContext)
        assertTrue(queueManager.snapshot.value.shuffleEnabled)
    }
}
