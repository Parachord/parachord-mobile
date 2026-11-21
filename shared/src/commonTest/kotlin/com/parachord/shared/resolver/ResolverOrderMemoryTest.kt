package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Desktop's vectors from `tests/renderer/resolver-order-memory.test.js`, ported. */
class ResolverOrderMemoryTest {
    private val m = ResolverOrderMemory

    @Test
    fun `a disabled plug-in keeps its place after the one it followed`() {
        assertEquals(listOf("a", "b", "c", "d"), m.rememberOrder(listOf("a", "b", "c", "d"), listOf("a", "c", "d")))
    }

    @Test
    fun `the active order wins and disabled ones ride along with their neighbour`() {
        assertEquals(listOf("d", "a", "b", "c"), m.rememberOrder(listOf("a", "b", "c", "d"), listOf("d", "a", "c")))
    }

    @Test
    fun `a disabled plug-in that led the list stays first`() {
        assertEquals(listOf("b", "a", "c"), m.rememberOrder(listOf("b", "a", "c"), listOf("a", "c")))
    }

    @Test
    fun `no memory yet - the order itself`() {
        assertEquals(listOf("a", "b"), m.rememberOrder(null, listOf("a", "b")))
    }

    @Test
    fun `re-enabled - back in its remembered place and not the canonical one`() {
        val memory = listOf("soundcloud", "spotify", "applemusic")
        assertEquals(listOf("soundcloud", "spotify", "applemusic"), m.insertRemembered(listOf("spotify", "applemusic"), "soundcloud", memory))
    }

    @Test
    fun `after a drag while it was off it follows the plug-in it followed`() {
        val memory = m.rememberOrder(listOf("a", "b", "c", "d"), listOf("d", "a", "c"))
        assertEquals(listOf("d", "a", "b", "c"), m.insertRemembered(listOf("d", "a", "c"), "b", memory))
    }

    @Test
    fun `never ranked - the canonical place`() {
        assertEquals(listOf("spotify", "soundcloud", "youtube"), m.insertRemembered(listOf("spotify", "youtube"), "soundcloud", listOf("spotify", "youtube")))
        assertEquals(listOf("spotify", "applemusic", "amazonmusic", "bandcamp"), m.insertRemembered(listOf("spotify", "applemusic", "bandcamp"), "amazonmusic", null))
    }

    @Test
    fun `already in the order - unchanged`() {
        val order = listOf("a", "b")
        assertSame(order, m.insertRemembered(order, "a", listOf("b", "a")))
    }
}
