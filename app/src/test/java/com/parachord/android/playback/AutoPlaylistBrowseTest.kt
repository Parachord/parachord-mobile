package com.parachord.android.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Android Auto pages a playlist's tracklist when the head unit asks for pages. */
class AutoPlaylistBrowseTest {

    @Test
    fun pagingReturnsTheAskedPage_orEverythingWhenNotPaged() {
        val items = (0 until 25).toList()
        assertEquals((10 until 20).toList(), pageOf(items, page = 1, pageSize = 10))
        assertEquals((20 until 25).toList(), pageOf(items, page = 2, pageSize = 10))
        assertEquals(emptyList<Int>(), pageOf(items, page = 3, pageSize = 10))
        assertEquals(items, pageOf(items, page = 0, pageSize = Int.MAX_VALUE))
    }
}
