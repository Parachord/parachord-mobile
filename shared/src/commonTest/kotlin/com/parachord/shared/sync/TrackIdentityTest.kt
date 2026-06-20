package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackIdentityTest {

    @Test
    fun validIsrc_keysByIsrc_normalizedUppercase() {
        assertEquals("isrc-USRC17607839", TrackIdentity.canonicalTrackId("usrc17607839", "spotify-abc"))
        assertEquals("isrc-USRC17607839", TrackIdentity.canonicalTrackId("  USRC17607839  ", "spotify-abc"))
    }

    @Test
    fun missingOrMalformedIsrc_fallsBackToProviderId() {
        assertEquals("spotify-abc", TrackIdentity.canonicalTrackId(null, "spotify-abc"))
        assertEquals("applemusic-123", TrackIdentity.canonicalTrackId("", "applemusic-123"))
        assertEquals("applemusic-123", TrackIdentity.canonicalTrackId("   ", "applemusic-123"))
        assertEquals("spotify-abc", TrackIdentity.canonicalTrackId("not-an-isrc", "spotify-abc"))
    }

    @Test
    fun sameIsrcDifferentProviders_collapseToOneKey() {
        // The whole point: Spotify + Apple Music rows for the same recording merge.
        val fromSpotify = TrackIdentity.canonicalTrackId("USRC17607839", "spotify-x")
        val fromAppleMusic = TrackIdentity.canonicalTrackId("usrc17607839", "applemusic-y")
        assertEquals(fromSpotify, fromAppleMusic)
    }
}
