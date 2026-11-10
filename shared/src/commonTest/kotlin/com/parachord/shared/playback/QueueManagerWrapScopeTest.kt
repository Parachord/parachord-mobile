package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Repeat all wraps only this queue's tracks: history from before a cleared
 * queue and spinoff pool tracks stay reachable by Previous but aren't wrapped,
 * and a spinoff's seed stays in the lap.
 */
class QueueManagerWrapScopeTest {
    private fun t(id: String) = Track(id = id, title = id, artist = "artist")
    private val a = t("a")
    private val b = t("b")
    private val c = t("c")
    private val x = t("x")
    private val y = t("y")
    private val p1 = t("p1")
    private val p3 = t("p3")

    @Test
    fun `a single-track play after a queue wraps only itself`() {
        val q = QueueManager()
        q.setQueue(listOf(a, b, c), 0)
        assertEquals(b, q.skipNext(a))
        q.clearQueue()
        q.addToQueue(listOf(y))
        assertEquals(y, q.skipNext(x))
        assertEquals(x, q.wrapToStart(y))
        assertEquals(listOf(y), q.snapshot.value.upNext)
    }

    @Test
    fun `previous still reaches history from before the clear`() {
        val q = QueueManager()
        q.setQueue(listOf(a, b, c), 0)
        assertEquals(b, q.skipNext(a))
        q.clearQueue()
        q.addToQueue(listOf(y))
        assertEquals(y, q.skipNext(x))
        assertEquals(x, q.skipPrevious(y))
        assertEquals(a, q.skipPrevious(x))
    }

    @Test
    fun `a seed and pool round trip keeps the seed and leaves the pool out`() {
        val q = QueueManager()
        q.setQueue(listOf(a, b), 0)
        // The first pool pull: the seed a stays in the lap.
        q.pushToHistory(a, inQueue = true)
        // Pool tracks play outside the manager; leaving the pool on p3.
        assertEquals(b, q.skipNext(p3, currentInQueue = false))
        // At b's end repeat all wraps to a, with b next and no p3.
        assertEquals(a, q.wrapToStart(b))
        assertEquals(listOf(b), q.snapshot.value.upNext)
    }

    @Test
    fun `previous from the queue still reaches the last pool track`() {
        val q = QueueManager()
        q.setQueue(listOf(a, b), 0)
        q.pushToHistory(a, inQueue = true)
        assertEquals(b, q.skipNext(p3, currentInQueue = false))
        assertEquals(p3, q.skipPrevious(b))
        assertEquals(a, q.skipPrevious(p3))
    }

    @Test
    fun `a wrap after the pool runs out at the end of the queue wraps the queue only`() {
        val q = QueueManager()
        q.setQueue(listOf(a, b), 0)
        assertEquals(b, q.skipNext(a))
        // Spinoff seeded on b, the last queue track.
        q.pushToHistory(b, inQueue = true)
        assertEquals(a, q.wrapToStart(p1, currentInQueue = false))
        assertEquals(listOf(b), q.snapshot.value.upNext)
    }

    @Test
    fun `playing from the queue with a pool track current leaves it out of the wrap`() {
        val q = QueueManager()
        q.setQueue(listOf(a, b, c), 0)
        q.pushToHistory(a, inQueue = true)
        assertEquals(c, q.playFromQueue(1, p1, currentInQueue = false))
        assertEquals(a, q.wrapToStart(c))
        assertEquals(listOf(c), q.snapshot.value.upNext)
    }

    @Test
    fun `persistence round trip keeps the flags`() {
        val q = QueueManager()
        q.setQueue(listOf(a, b), 0)
        assertEquals(b, q.skipNext(a))
        q.clearQueue()
        q.addToQueue(listOf(y))
        assertEquals(y, q.skipNext(x))
        assertEquals(listOf(a, x), q.history)
        assertEquals(listOf(false, true), q.historyInQueue)

        val state = PersistedQueueState(
            currentTrack = y,
            upNext = q.snapshot.value.upNext,
            playHistory = q.history,
            playHistoryInQueue = q.historyInQueue,
        )
        val decoded = assertNotNull(QueuePersistenceCodec.decode(QueuePersistenceCodec.encode(state)))
        assertEquals(listOf(false, true), decoded.playHistoryInQueue)

        val restored = QueueManager()
        restored.restoreState(
            restoredUpNext = decoded.upNext,
            restoredHistory = decoded.playHistory,
            restoredOriginalOrder = null,
            context = null,
            shuffle = false,
            restoredHistoryInQueue = decoded.playHistoryInQueue,
        )
        assertEquals(x, restored.wrapToStart(y))
        assertEquals(listOf(y), restored.snapshot.value.upNext)
    }

    @Test
    fun `a blob without the flag list restores every entry in the queue`() {
        val old = """{"currentTrack":{"id":"c","title":"c","artist":"artist"},"playHistory":[{"id":"a","title":"a","artist":"artist"},{"id":"b","title":"b","artist":"artist"}]}"""
        val decoded = assertNotNull(QueuePersistenceCodec.decode(old))
        assertEquals(null, decoded.playHistoryInQueue)
        val restored = QueueManager()
        restored.restoreState(
            restoredUpNext = decoded.upNext,
            restoredHistory = decoded.playHistory,
            restoredOriginalOrder = null,
            context = null,
            shuffle = false,
            restoredHistoryInQueue = decoded.playHistoryInQueue,
        )
        assertEquals(listOf(true, true), restored.historyInQueue)
        assertEquals("a", restored.wrapToStart(decoded.currentTrack)?.id)
        assertEquals(listOf("b", "c"), restored.snapshot.value.upNext.map { it.id })
    }

    @Test
    fun `a short flag list treats the missing entries as in the queue`() {
        val restored = QueueManager()
        restored.restoreState(
            restoredUpNext = emptyList(),
            restoredHistory = listOf(a, b),
            restoredOriginalOrder = null,
            context = null,
            shuffle = false,
            restoredHistoryInQueue = listOf(false),
        )
        assertEquals(listOf(false, true), restored.historyInQueue)
        assertEquals(b, restored.wrapToStart(c))
        assertEquals(listOf(c), restored.snapshot.value.upNext)
    }
}
