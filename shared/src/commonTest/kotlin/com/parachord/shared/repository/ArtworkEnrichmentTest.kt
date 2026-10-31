package com.parachord.shared.repository

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkEnrichmentTest {

    private data class Item(val key: String, val art: String? = null)

    private suspend fun fill(
        items: List<Item>,
        limit: Int = ARTWORK_LOOKUP_LIMIT,
        lookup: suspend (Item) -> String?,
    ) = fillMissingArtwork(items, { it.art }, { it.key }, lookup, { i, url -> i.copy(art = url) }, limit)

    @Test
    fun `only items without art are looked up`() = runTest {
        val asked = mutableListOf<String>()
        val out = fill(listOf(Item("a", "kept"), Item("b"))) { asked += it.key; "found-${it.key}" }

        assertEquals(listOf("b"), asked)
        assertEquals(listOf(Item("a", "kept"), Item("b", "found-b")), out)
    }

    @Test
    fun `items sharing a key share one lookup`() = runTest {
        var calls = 0
        val out = fill(listOf(Item("a"), Item("a"), Item("a"))) { calls++; "found" }

        assertEquals(1, calls)
        assertEquals(List(3) { Item("a", "found") }, out)
    }

    @Test
    fun `lookups stop at the limit`() = runTest {
        var calls = 0
        val out = fill((1..5).map { Item("k$it") }, limit = 2) { calls++; "found" }

        assertEquals(2, calls)
        assertEquals(listOf("found", "found", null, null, null), out.map { it.art })
    }

    @Test
    fun `a failed or empty lookup leaves the item as it was`() = runTest {
        val out = fill(listOf(Item("boom"), Item("miss"), Item("hit"))) {
            when (it.key) {
                "boom" -> error("network")
                "miss" -> null
                else -> "found"
            }
        }

        assertEquals(listOf(Item("boom"), Item("miss"), Item("hit", "found")), out)
    }
}
