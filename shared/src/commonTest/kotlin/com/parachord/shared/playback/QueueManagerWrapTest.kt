package com.parachord.shared.playback

import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueManagerWrapTest {
    private fun t(id: String) = Track(id = id, title = id, artist = "artist")
    private val a = t("a")
    private val b = t("b")
    private val c = t("c")

    @Test
    fun `wrapping returns the first track played and queues the rest`() {
        val q = QueueManager()
        q.setQueue(listOf(a, b, c), 0)
        assertEquals(b, q.skipNext(a))
        assertEquals(c, q.skipNext(b))
        assertEquals(a, q.wrapToStart(c))
        assertEquals(listOf(b, c), q.snapshot.value.upNext)
        assertFalse(q.hasPrevious)
        assertTrue(q.hasNext)
    }

    @Test
    fun `added tracks are part of the wrap`() {
        val q = QueueManager()
        val x = t("x")
        q.setQueue(listOf(a, b), 0)
        q.addToQueue(listOf(x))
        assertEquals(b, q.skipNext(a))
        assertEquals(x, q.skipNext(b))
        assertEquals(a, q.wrapToStart(x))
        assertEquals(listOf(b, x), q.snapshot.value.upNext)
    }

    @Test
    fun `wrapping with shuffle on keeps every track once`() {
        val q = QueueManager()
        q.setQueue(listOf(a, b, c), 0, shuffle = true)
        val second = q.skipNext(a)!!
        val third = q.skipNext(second)!!
        val first = q.wrapToStart(third)!!
        val rest = q.snapshot.value.upNext
        assertEquals(setOf(a, b, c), (listOf(first) + rest).toSet())
        assertEquals(3, rest.size + 1)
        assertEquals(listOf(a, second, third).filter { it != first }, q.savedOriginalOrder)
        assertTrue(q.shuffleEnabled)
        assertFalse(q.hasPrevious)
    }

    @Test
    fun `wrapping a single track replays it`() {
        val q = QueueManager()
        q.setQueue(listOf(a), 0)
        assertEquals(a, q.wrapToStart(a))
        assertTrue(q.snapshot.value.upNext.isEmpty())
        val s = QueueManager()
        s.setQueue(listOf(a), 0, shuffle = true)
        assertEquals(a, s.wrapToStart(a))
        assertTrue(s.snapshot.value.upNext.isEmpty())
    }

    @Test
    fun `wrapping an empty queue returns null`() {
        val q = QueueManager()
        assertNull(q.wrapToStart(null))
    }

    @Test
    fun `hasNext and hasPrevious follow the queue`() {
        val q = QueueManager()
        assertFalse(q.hasNext)
        assertFalse(q.hasPrevious)
        q.setQueue(listOf(a, b), 0)
        assertTrue(q.hasNext)
        assertFalse(q.hasPrevious)
        q.skipNext(a)
        assertFalse(q.hasNext)
        assertTrue(q.hasPrevious)
    }
}
