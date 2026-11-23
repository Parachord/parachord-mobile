package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncingProvidersTest {

    @Test
    fun `Spotify syncs only while its own switch is on`() {
        // Android's wizard adds to the set's default of Spotify, so an Apple
        // Music only user has both in it with Spotify's switch off.
        assertEquals(setOf("applemusic"), syncingProviders(false, setOf("spotify", "applemusic")))
        assertEquals(setOf("spotify", "applemusic"), syncingProviders(true, setOf("spotify", "applemusic")))
    }

    @Test
    fun `a switch that's on doesn't add Spotify when the set doesn't name it`() {
        assertEquals(setOf("listenbrainz"), syncingProviders(true, setOf("listenbrainz")))
    }

    @Test
    fun `the default set with the switch off syncs nothing`() {
        assertEquals(emptySet(), syncingProviders(false, setOf("spotify")))
    }
}
