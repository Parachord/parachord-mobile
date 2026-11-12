package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #602: a second Next or Previous pressed while the first one's track is
 * still starting hands in the track still on screen, which the queue
 * manager already moved. It must not go into Up Next or history twice.
 */
class QueueManagerRapidSkipTest {

    private fun t(id: String) = Track(id = id, title = id, artist = "x")
    private val a = t("a"); private val b = t("b"); private val c = t("c"); private val d = t("d")

    private fun QueueManager.upNextIds() = snapshot.value.upNext.map { it.id }
    private fun QueueManager.historyIds() = history.map { it.id }

    @Test
    fun twoQuickPreviousPressesQueueEachTrackOnce() {
        val q = QueueManager()
        q.setQueue(listOf(a, b, c, d), 0)
        q.skipNext(a)
        q.skipNext(b)                       // c plays, shown
        assertEquals(b, q.skipPrevious(c))  // b starting, c still on screen
        assertEquals(a, q.skipPrevious(c))
        assertEquals(listOf("b", "c", "d"), q.upNextIds())
        assertEquals(emptyList(), q.historyIds())
    }

    @Test
    fun twoQuickNextPressesPutEachTrackInHistoryOnce() {
        val q = QueueManager()
        q.setQueue(listOf(a, b, c, d), 0)
        assertEquals(b, q.skipNext(a))
        assertEquals(c, q.skipNext(a))      // b still starting, a on screen
        assertEquals(listOf("a", "b"), q.historyIds())
        assertEquals(listOf("d"), q.upNextIds())
    }

    @Test
    fun aTapInTheQueueThenNext() {
        val q = QueueManager()
        q.setQueue(listOf(a, b, c, d), 0)
        assertEquals(c, q.playFromQueue(1, a))
        assertEquals(d, q.skipNext(a))      // c still starting, a on screen
        assertEquals(listOf("a", "c"), q.historyIds())
        assertEquals(emptyList(), q.upNextIds())
    }

    @Test
    fun nothingOnScreenIsTakenAsNothingCurrent() {
        val q = QueueManager()
        q.setQueue(listOf(a, b, c), 0)
        assertEquals(b, q.skipNext(null))
        assertEquals(emptyList(), q.historyIds())
    }

    @Test
    fun withTheScreenCaughtUpNothingChanges() {
        val q = QueueManager()
        q.setQueue(listOf(a, b, c, d), 0)
        q.skipNext(a)
        q.skipNext(b)
        assertEquals(b, q.skipPrevious(c))
        assertEquals(a, q.skipPrevious(b))
        assertEquals(listOf("b", "c", "d"), q.upNextIds())
    }

    @Test
    fun aTrackPlayedFromOutsideTheQueueIsTakenAsShown() {
        val q = QueueManager()
        q.setQueue(listOf(a, b, c), 0)
        q.skipNext(a)                       // b
        q.poolTrackStarted(b)               // a spinoff track plays
        val pool = t("pool")
        assertEquals(c, q.skipNext(pool))
        assertEquals(listOf("a", "b", "pool"), q.historyIds())
    }
}
