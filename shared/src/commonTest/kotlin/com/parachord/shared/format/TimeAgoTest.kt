package com.parachord.shared.format

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeAgoTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    @Test
    fun theScale() {
        assertEquals("just now", TimeAgo.ago(now - 20_000, now))
        assertEquals("18m ago", TimeAgo.ago(now - 18 * min, now))
        assertEquals("3h ago", TimeAgo.ago(now - 3 * 60 * min, now))
        assertEquals("5d ago", TimeAgo.ago(now - 5 * 24 * 60 * min, now))
        assertEquals("2mo ago", TimeAgo.ago(now - 65 * 24 * 60 * min, now))
        assertEquals("1y ago", TimeAgo.ago(now - 400 * 24 * 60 * min, now))
        assertEquals("just now", TimeAgo.ago(now + min, now))
    }
}
