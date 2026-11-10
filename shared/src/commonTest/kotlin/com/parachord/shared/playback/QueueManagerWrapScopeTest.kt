package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Repeat all wraps only this queue's tracks: history from before a cleared
 * queue and spinoff pool tracks stay reachable by Previous but aren't wrapped,
 * and a spinoff's seed stays in the lap.
 *
 * The controller-shaped tests drive the manager the way both platforms do: the
 * spinoff branch calls [QueueManager.poolTrackStarted] with the track playing
 * now, and every other play goes through the manager.
 */
class QueueManagerWrapScopeTest {
    private fun t(id: String) = Track(id = id, title = id, artist = "artist")
    private val a = t("a")
    private val b = t("b")
    private val c = t("c")
    private val x = t("x")
    private val y = t("y")
    private val p1 = t("p1")
    private val p2 = t("p2")
    private val p3 = t("p3")

    /** A minimal controller: what's playing, and the spinoff pool. */
    private class Player(val q: QueueManager) {
        var current: Track? = null
        val pool = mutableListOf<Track>()

        /** Next or a track end, as `skipNextInternal` / `advance` do. */
        fun next(repeatAll: Boolean = true): Track? {
            if (pool.isNotEmpty()) {
                val p = pool.removeAt(0)
                q.poolTrackStarted(current)
                current = p
                return p
            }
            val n = if (q.hasNext) q.skipNext(current) else if (repeatAll) q.wrapToStart(current) else null
            if (n == null) {
                current?.let { q.pushToHistory(it) }
                current = null
            } else {
                current = n
            }
            return n
        }

        fun previous(): Track? = q.skipPrevious(current)?.also { current = it }
    }

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
    fun `a seed and two pool pulls then exhaustion wrap to the seed without pool tracks`() {
        val q = QueueManager()
        val p = Player(q)
        p.current = q.setQueue(listOf(a, b), 0)
        p.pool += listOf(p1, p2)
        assertEquals(p1, p.next())
        assertTrue(q.currentIsPoolTrack)
        assertEquals(p2, p.next())
        // Pool exhausted: the queue resumes.
        assertEquals(b, p.next())
        assertFalse(q.currentIsPoolTrack)
        assertEquals(a, p.next())
        assertEquals(listOf(b), q.snapshot.value.upNext)
    }

    @Test
    fun `the seed as the last queue track wraps to the queue only`() {
        val q = QueueManager()
        val p = Player(q)
        p.current = q.setQueue(listOf(a, b), 0)
        assertEquals(b, p.next())
        p.pool += listOf(p1)
        assertEquals(p1, p.next())
        assertEquals(a, p.next())
        assertEquals(listOf(b), q.snapshot.value.upNext)
    }

    @Test
    fun `a radio kick-start leaves the lap empty so the end stops`() {
        val q = QueueManager()
        val p = Player(q)
        p.current = q.setQueue(listOf(a, b), 0)
        // The radio clears the queue and starts its first track as a pool track.
        q.clearQueue()
        p.current = p1
        q.poolTrackStarted(null)
        p.pool += listOf(p2)
        assertEquals(p2, p.next())
        assertNull(p.next())
        assertNull(p.current)
        assertFalse(q.hasNext)
    }

    @Test
    fun `previous across the pool boundary then next keeps the pool track out of the lap`() {
        val q = QueueManager()
        val p = Player(q)
        p.current = q.setQueue(listOf(a, b), 0)
        p.pool += listOf(p3)
        assertEquals(p3, p.next())
        assertEquals(b, p.next())
        assertEquals(p3, p.previous())
        assertTrue(q.currentIsPoolTrack)
        assertEquals(b, p.next())
        assertEquals(a, p.next())
        assertEquals(listOf(b), q.snapshot.value.upNext)
    }

    @Test
    fun `previous from a pool track goes back to the seed and keeps the pool track out of the queue`() {
        val q = QueueManager()
        val p = Player(q)
        p.current = q.setQueue(listOf(a, b), 0)
        p.pool += listOf(p1, p2)
        assertEquals(p1, p.next())
        assertEquals(a, p.previous())
        assertFalse(q.currentIsPoolTrack)
        assertEquals(listOf(b), q.snapshot.value.upNext)
        // The pool goes on; the seed goes back into the lap, once.
        assertEquals(p2, p.next())
        assertEquals(b, p.next())
        assertEquals(a, p.next())
        assertEquals(listOf(b), q.snapshot.value.upNext)
    }

    @Test
    fun `an auto-skipped unplayable pool track adds nothing to the lap`() {
        val q = QueueManager()
        val p = Player(q)
        p.current = q.setQueue(listOf(a, b), 0)
        p.pool += listOf(p1, p2)
        assertEquals(p1, p.next())
        // p1 couldn't play: the advance skips it to the next pool track.
        assertEquals(p2, p.next())
        assertEquals(b, p.next())
        assertEquals(a, p.next())
        assertEquals(listOf(b), q.snapshot.value.upNext)
    }

    @Test
    fun `a spinoff exited while a pool track plays then next resumes the queue`() {
        val q = QueueManager()
        val p = Player(q)
        p.current = q.setQueue(listOf(a, b, c), 0)
        p.pool += listOf(p1, p2)
        assertEquals(p1, p.next())
        p.pool.clear() // the user left the spinoff
        assertEquals(b, p.next())
        assertEquals(c, p.next())
        assertEquals(a, p.next())
        assertEquals(listOf(b, c), q.snapshot.value.upNext)
    }

    @Test
    fun `playing from the queue with a pool track current leaves it out of the wrap`() {
        val q = QueueManager()
        val p = Player(q)
        p.current = q.setQueue(listOf(a, b, c), 0)
        p.pool += listOf(p1)
        assertEquals(p1, p.next())
        assertEquals(c, q.playFromQueue(1, p.current))
        assertFalse(q.currentIsPoolTrack)
        assertEquals(a, q.wrapToStart(c))
        assertEquals(listOf(c), q.snapshot.value.upNext)
    }

    @Test
    fun `a pool track at the end of the queue goes to history out of the lap`() {
        val q = QueueManager()
        val p = Player(q)
        p.current = q.setQueue(listOf(a), 0)
        p.pool += listOf(p1)
        assertEquals(p1, p.next())
        assertNull(p.next(repeatAll = false))
        assertEquals(listOf(a, p1), q.history)
        assertEquals(listOf(true, false), q.historyInQueue)
    }

    @Test
    fun `persistence round trip keeps the flags`() {
        val q = QueueManager()
        q.setQueue(listOf(a, b), 0)
        assertEquals(b, q.skipNext(a))
        q.clearQueue()
        q.addToQueue(listOf(y))
        assertEquals(y, q.skipNext(x))
        q.poolTrackStarted(y)
        assertEquals(listOf(a, x, y), q.history)
        assertEquals(listOf(false, true, true), q.historyInQueue)

        val state = PersistedQueueState(
            currentTrack = p1,
            upNext = q.snapshot.value.upNext,
            playHistory = q.history,
            playHistoryInQueue = q.historyInQueue,
            currentIsPoolTrack = q.currentIsPoolTrack,
        )
        val decoded = assertNotNull(QueuePersistenceCodec.decode(QueuePersistenceCodec.encode(state)))
        assertEquals(listOf(false, true, true), decoded.playHistoryInQueue)
        assertTrue(decoded.currentIsPoolTrack)

        val restored = QueueManager()
        restored.restoreState(
            restoredUpNext = decoded.upNext,
            restoredHistory = decoded.playHistory,
            restoredOriginalOrder = null,
            context = null,
            shuffle = false,
            restoredHistoryInQueue = decoded.playHistoryInQueue,
            restoredCurrentIsPoolTrack = decoded.currentIsPoolTrack,
        )
        assertEquals(x, restored.wrapToStart(decoded.currentTrack))
        assertEquals(listOf(y), restored.snapshot.value.upNext)
    }

    @Test
    fun `a blob without the flag list restores every entry in the queue`() {
        val old = """{"currentTrack":{"id":"c","title":"c","artist":"artist"},"playHistory":[{"id":"a","title":"a","artist":"artist"},{"id":"b","title":"b","artist":"artist"}]}"""
        val decoded = assertNotNull(QueuePersistenceCodec.decode(old))
        assertEquals(null, decoded.playHistoryInQueue)
        assertFalse(decoded.currentIsPoolTrack)
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
