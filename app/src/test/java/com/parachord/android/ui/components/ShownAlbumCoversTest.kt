package com.parachord.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShownAlbumCoversTest {

    @Test
    fun `a shown cover is found by artist and album, ignoring case and spaces`() {
        ShownAlbumCovers.remember("Thee Oh Sees", "Floating Coffin", "https://img.example/fc.jpg")
        assertEquals("https://img.example/fc.jpg", ShownAlbumCovers.get(" thee oh sees", "FLOATING COFFIN "))
    }

    @Test
    fun `the most recently shown cover wins`() {
        ShownAlbumCovers.remember("Wire", "Pink Flag", "https://img.example/a.jpg")
        ShownAlbumCovers.remember("Wire", "Pink Flag", "https://img.example/b.jpg")
        assertEquals("https://img.example/b.jpg", ShownAlbumCovers.get("Wire", "Pink Flag"))
    }

    @Test
    fun `a cover without an artist or album is not kept`() {
        ShownAlbumCovers.remember(null, "Untitled", "https://img.example/u.jpg")
        ShownAlbumCovers.remember("Nobody", "", "https://img.example/n.jpg")
        assertNull(ShownAlbumCovers.get("Nobody", ""))
    }
}
