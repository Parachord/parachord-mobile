package com.parachord.shared.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeSetupTest {
    @Test
    fun `nothing left is mode none`() {
        val s = homeSetupItems()
        assertEquals("none", s.mode)
        assertEquals(0, s.total)
        assertEquals(0, s.done)
    }

    @Test
    fun `skipped steps come back as one open item`() {
        val s = homeSetupItems(gettingStartedLeft = true)
        assertEquals(listOf("getting-started"), s.items.map { it.id })
        assertEquals(listOf("Finish getting started"), s.items.map { it.label })
        assertEquals("setup", s.mode)
        assertEquals(1, s.total)
        assertEquals(0, s.done)
        assertTrue(s.items.all { it.action == it.id })
    }

    @Test
    fun `a dismissed id leaves items and total`() {
        val s = homeSetupItems(gettingStartedLeft = true, dismissed = setOf("getting-started"))
        assertEquals(0, s.total)
        assertEquals("none", s.mode)
    }

    @Test
    fun `no item names a service`() {
        val labels = homeSetupItems(gettingStartedLeft = true).items.joinToString(" ") { it.label }
        assertTrue(!Regex("Spotify|Last\\.fm|ListenBrainz").containsMatchIn(labels))
    }
}
