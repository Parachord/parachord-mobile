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
        assertFalse(q.currentInLap)
        assertEquals(b, p.next())
        assertEquals(a, p.next())
        assertEquals(listOf(b), q.snapshot.value.upNext)
    }

    @Test
    fun `previous from a pool track goes back to the seed and the pool track stays out of the lap`() {
        val q = QueueManager()
        val p = Player(q)
        p.current = q.setQueue(listOf(a, b), 0)
        p.pool += listOf(p1, p2)
        assertEquals(p1, p.next())
        assertEquals(a, p.previous())
        assertFalse(q.currentIsPoolTrack)
        assertTrue(q.currentInLap)
        // p1 waits in Up Next, out of the lap, as Previous always did.
        assertEquals(listOf(p1, b), q.snapshot.value.upNext)
        // The pool goes on; the seed goes back into the lap, once.
        assertEquals(p2, p.next())
        assertEquals(p1, p.next())
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
    fun `two previous presses across a cleared queue then two next presses retrace the path`() {
        val q = QueueManager()
        val p = Player(q)
        val a1 = t("a1"); val a2 = t("a2"); val a3 = t("a3"); val bb = t("B")
        p.current = q.setQueue(listOf(a1, a2, a3), 0)
        assertEquals(a2, p.next())
        assertEquals(a3, p.next())
        // A single track is played: the queue is cleared and B plays.
        q.clearQueue()
        p.current = bb
        assertEquals(a2, p.previous())
        assertEquals(a1, p.previous())
        assertEquals(listOf(a2, bb), q.snapshot.value.upNext)
        assertEquals(a2, p.next())
        assertEquals(bb, p.next())
        // The wrap holds only B's queue.
        assertEquals(bb, p.next())
        assertTrue(q.snapshot.value.upNext.isEmpty())
    }

    @Test
    fun `two previous presses across the pool boundary then two next presses retrace the path`() {
        val q = QueueManager()
        val p = Player(q)
        p.current = q.setQueue(listOf(a, b), 0)
        p.pool += listOf(p1, p2)
        assertEquals(p1, p.next())
        assertEquals(p2, p.next())
        assertEquals(b, p.next())
        assertEquals(p2, p.previous())
        assertEquals(a, p.previous())
        assertEquals(listOf(p2, b), q.snapshot.value.upNext)
        assertEquals(p2, p.next())
        assertEquals(b, p.next())
        // The wrap has no pool track.
        assertEquals(a, p.next())
        assertEquals(listOf(b), q.snapshot.value.upNext)
    }

    @Test
    fun `an out-of-lap track in up next is left out of the wrap`() {
        val q = QueueManager()
        val p = Player(q)
        val a1 = t("a1"); val a2 = t("a2"); val a3 = t("a3"); val bb = t("B")
        p.current = q.setQueue(listOf(a1, a2, a3), 0)
        p.next(); p.next()
        q.clearQueue()
        p.current = bb
        p.previous(); p.previous()
        q.addToQueue(listOf(y))
        assertEquals(listOf(a2, bb, y), q.snapshot.value.upNext)
        assertEquals(bb, q.wrapToStart(a1))
        assertEquals(listOf(y), q.snapshot.value.upNext)
    }

    @Test
    fun `shuffling up next keeps which tracks are out of the lap`() {
        val q = QueueManager()
        val p = Player(q)
        val a1 = t("a1"); val a2 = t("a2"); val a3 = t("a3"); val bb = t("B")
        p.current = q.setQueue(listOf(a1, a2, a3), 0)
        p.next(); p.next()
        q.clearQueue()
        p.current = bb
        p.previous(); p.previous()
        q.addToQueue(listOf(y))
        q.toggleShuffle()
        q.toggleShuffle()
        assertEquals(listOf(a2, bb, y), q.snapshot.value.upNext)
        // Removing B and reordering keep the markers with their tracks.
        q.removeFromQueue(1)
        q.moveInQueue(1, 0)
        assertEquals(listOf(y, a2), q.snapshot.value.upNext)
        assertEquals(y, q.wrapToStart(a1))
        assertTrue(q.snapshot.value.upNext.isEmpty())
    }

    @Test
    fun `a spinoff started on a track reached by previous keeps it reachable`() {
        val q = QueueManager()
        val p = Player(q)
        val a1 = t("a1"); val a2 = t("a2"); val bb = t("B")
        p.current = q.setQueue(listOf(a1, a2), 0)
        p.next()
        q.clearQueue()
        p.current = bb
        assertEquals(a1, p.previous())
        assertFalse(q.currentIsPoolTrack)
        p.pool += listOf(p1)
        assertEquals(p1, p.next())
        assertEquals(a1, q.history.last())
        assertEquals(false, q.historyInQueue.last())
        assertEquals(a1, p.previous())
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
    fun `persistence round trip keeps which up next tracks are out of the lap`() {
        val q = QueueManager()
        val p = Player(q)
        val a1 = t("a1"); val a2 = t("a2"); val a3 = t("a3"); val bb = t("B")
        p.current = q.setQueue(listOf(a1, a2, a3), 0)
        p.next(); p.next()
        q.clearQueue()
        p.current = bb
        p.previous(); p.previous()
        val state = PersistedQueueState(
            currentTrack = p.current,
            upNext = q.snapshot.value.upNext,
            playHistory = q.history,
            playHistoryInQueue = q.historyInQueue,
            currentIsPoolTrack = q.currentIsPoolTrack,
            upNextInLap = q.upNextInLap,
            currentInLap = q.currentInLap,
        )
        val decoded = assertNotNull(QueuePersistenceCodec.decode(QueuePersistenceCodec.encode(state)))
        assertEquals(listOf(false, true), decoded.upNextInLap)
        assertFalse(decoded.currentInLap)
        val restored = QueueManager()
        restored.restoreState(
            restoredUpNext = decoded.upNext,
            restoredHistory = decoded.playHistory,
            restoredOriginalOrder = null,
            context = null,
            shuffle = false,
            restoredHistoryInQueue = decoded.playHistoryInQueue,
            restoredCurrentIsPoolTrack = decoded.currentIsPoolTrack,
            restoredUpNextInLap = decoded.upNextInLap,
            restoredCurrentInLap = decoded.currentInLap,
        )
        assertEquals(bb, restored.wrapToStart(decoded.currentTrack))
        assertTrue(restored.snapshot.value.upNext.isEmpty())
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

    @Test
    fun `a restored listen along context is dropped and others are kept`() {
        val restored = QueueManager()
        restored.restoreState(
            restoredUpNext = listOf(b),
            restoredHistory = emptyList(),
            restoredOriginalOrder = null,
            context = PlaybackContext(type = LISTEN_ALONG_CONTEXT, name = "Pat"),
            shuffle = false,
        )
        assertNull(restored.playbackContext)
        assertNull(restored.snapshot.value.playbackContext)

        val playlist = PlaybackContext(type = "playlist", name = "Mine")
        restored.restoreState(
            restoredUpNext = listOf(b),
            restoredHistory = emptyList(),
            restoredOriginalOrder = null,
            context = playlist,
            shuffle = false,
        )
        assertEquals(playlist, restored.playbackContext)
    }
}
