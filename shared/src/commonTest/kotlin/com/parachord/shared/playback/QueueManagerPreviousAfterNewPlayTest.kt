package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #605: a play that replaces the current track (a single-track play, a new
 * queue) puts that track on history, out of the repeat-all lap, and a new
 * queue keeps the earlier history. Previous reaches them; repeat all doesn't
 * wrap them.
 */
class QueueManagerPreviousAfterNewPlayTest {
    private fun t(id: String) = Track(id = id, title = id, artist = "artist")
    private val a = t("a")
    private val b = t("b")
    private val roads = t("roads")
    private val single = t("single")
    private val x = t("x")
    private val y = t("y")

    /** Play a, b, roads: roads is current, a and b are history. */
    private fun atThirdTrack(q: QueueManager) {
        q.setQueue(listOf(a, b, roads), 0)
        assertEquals(b, q.skipNext(a))
        assertEquals(roads, q.skipNext(b))
    }

    @Test
    fun previousAfterASingleTrackPlayReachesTheTrackItReplaced() {
        val q = QueueManager()
        atThirdTrack(q)
        q.clearQueue(outgoing = roads, next = single)

        assertEquals(roads, q.skipPrevious(single))
        assertEquals(b, q.skipPrevious(roads))
        assertEquals(a, q.skipPrevious(b))
    }

    @Test
    fun previousAfterANewQueueReachesTheTrackItReplacedAndTheOldHistory() {
        val q = QueueManager()
        atThirdTrack(q)
        val first = q.setQueue(listOf(x, y), 0, outgoing = roads)
        assertEquals(x, first)

        assertEquals(listOf(a, b, roads), q.history)
        assertEquals(roads, q.skipPrevious(x))
        assertEquals(b, q.skipPrevious(roads))
    }

    @Test
    fun theOldTracksAreOutOfTheNewQueuesLap() {
        val q = QueueManager()
        atThirdTrack(q)
        q.setQueue(listOf(x, y), 0, outgoing = roads)
        assertEquals(listOf(false, false, false), q.historyInQueue)
        // Repeat all at the end of the new queue wraps only x and y.
        assertEquals(y, q.skipNext(x))
        assertEquals(x, q.wrapToStart(y))
        assertEquals(listOf(y), q.snapshot.value.upNext)

        val q2 = QueueManager()
        atThirdTrack(q2)
        q2.clearQueue(outgoing = roads, next = single)
        // A single-track play's queue is the track alone.
        assertEquals(single, q2.wrapToStart(single))
        assertEquals(emptyList(), q2.snapshot.value.upNext)
    }

    @Test
    fun playingTheSameTrackAgainAddsNothing() {
        val q = QueueManager()
        atThirdTrack(q)
        q.clearQueue(outgoing = roads, next = roads)
        assertEquals(listOf(a, b), q.history)
        q.setQueue(listOf(roads, x), 0, outgoing = roads)
        assertEquals(listOf(a, b), q.history)
    }

    @Test
    fun clearingTheQueueLeavesTheCurrentTrackOffHistory() {
        val q = QueueManager()
        atThirdTrack(q)
        q.clearQueue()
        assertEquals(listOf(a, b), q.history)
    }

    @Test
    fun historyIsCappedDroppingTheOldestFromEarlierQueuesFirst() {
        val q = QueueManager()
        var current: Track? = null
        // 250 single-track plays: each after the first pushes the one before (249).
        for (i in 0 until 250) {
            val next = t("s$i")
            q.clearQueue(outgoing = current, next = next)
            current = next
        }
        assertEquals(QueueManager.MAX_HISTORY, q.history.size)
        assertEquals("s49", q.history.first().id)
        assertEquals("s248", q.history.last().id)
    }

    @Test
    fun theCurrentLapIsNeverTrimmed() {
        val q = QueueManager()
        val lap = (0 until QueueManager.MAX_HISTORY + 20).map { t("l$it") }
        q.setQueue(listOf(single), 0, outgoing = roads)
        q.setQueue(lap, 0, outgoing = single)
        var current = lap.first()
        for (next in lap.drop(1)) {
            assertEquals(next, q.skipNext(current))
            current = next
        }
        // roads and single (earlier queues) went first; the lap's tracks all stayed.
        assertEquals(lap.dropLast(1), q.history)
    }
}
