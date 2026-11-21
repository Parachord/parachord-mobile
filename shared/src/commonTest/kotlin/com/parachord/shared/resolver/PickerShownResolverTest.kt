package com.parachord.shared.resolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the Now Playing source switcher shows while a picked source starts. */
class PickerShownResolverTest {

    @Test
    fun `no pick shows the source that is playing`() {
        assertEquals("spotify", SourceReselection.pickerShownResolver("spotify", null, "t1"))
    }

    @Test
    fun `a pick for the current track shows the picked source at once`() {
        val pick = PendingSourcePick(trackId = "t1", resolver = "applemusic")
        assertEquals("applemusic", SourceReselection.pickerShownResolver("spotify", pick, "t1"))
    }

    @Test
    fun `a pick for another track is ignored`() {
        val pick = PendingSourcePick(trackId = "t1", resolver = "applemusic")
        assertEquals("spotify", SourceReselection.pickerShownResolver("spotify", pick, "t2"))
    }

    @Test
    fun `with no current track the pick is ignored`() {
        val pick = PendingSourcePick(trackId = "t1", resolver = "applemusic")
        assertNull(SourceReselection.pickerShownResolver(null, pick, null))
    }

    @Test
    fun `a cleared pick goes back to the real source`() {
        // A switch that failed: the platform drops the pick and the label
        // shows what really plays (iOS #287).
        assertEquals("spotify", SourceReselection.pickerShownResolver("spotify", null, "t1"))
    }

    @Test
    fun `a pick shows even before any source is known`() {
        val pick = PendingSourcePick(trackId = "t1", resolver = "localfiles")
        assertEquals("localfiles", SourceReselection.pickerShownResolver(null, pick, "t1"))
    }
}
