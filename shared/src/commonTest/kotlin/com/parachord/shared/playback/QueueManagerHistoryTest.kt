package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueManagerHistoryTest {
    private fun t(id: String) = Track(id = id, title = id, artist = "artist")
    private val a = t("a")
    private val b = t("b")
    private val c = t("c")

    @Test
    fun `previous after the end of the queue goes back to the last track`() {
        val q = QueueManager()
        q.setQueue(listOf(a, b, c), 0)
        assertEquals(b, q.skipNext(a))
        assertEquals(c, q.skipNext(b))
        // The queue ran out on c: it goes to history and is cleared.
        q.pushToHistory(c)
        assertTrue(q.hasPrevious)
        assertEquals(c, q.skipPrevious(null))
        assertEquals(b, q.skipPrevious(c))
    }

    @Test
    fun `pushing to history leaves Up Next alone`() {
        val q = QueueManager()
        q.setQueue(listOf(a, b), 0)
        q.pushToHistory(a)
        assertEquals(listOf(b), q.snapshot.value.upNext)
        assertEquals(listOf(a), q.history)
    }

    @Test
    fun `an empty history has nothing before it`() {
        assertFalse(QueueManager().hasPrevious)
    }
}
