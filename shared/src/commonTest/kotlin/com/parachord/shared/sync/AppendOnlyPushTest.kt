package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class AppendOnlyPushTest {

    @Test
    fun `a playlist that holds everything needs nothing appended`() {
        assertEquals(emptyList(), AppendOnlyPush.missingIds(listOf("a", "b", "c"), listOf("a", "b", "c")))
    }

    @Test
    fun `only the tracks it lacks are appended, in order`() {
        assertEquals(listOf("b", "d"), AppendOnlyPush.missingIds(listOf("a", "c"), listOf("a", "b", "c", "d")))
    }

    @Test
    fun `a repeated track is appended only as often as it's missing`() {
        assertEquals(listOf("a"), AppendOnlyPush.missingIds(listOf("a", "b"), listOf("a", "a", "b")))
        assertEquals(emptyList(), AppendOnlyPush.missingIds(listOf("a", "a", "b"), listOf("a", "b")))
    }

    @Test
    fun `an empty playlist gets the whole list`() {
        assertEquals(listOf("a", "b"), AppendOnlyPush.missingIds(emptyList(), listOf("a", "b")))
    }
}
