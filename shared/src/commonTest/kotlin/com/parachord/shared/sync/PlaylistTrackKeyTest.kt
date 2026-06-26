package com.parachord.shared.sync

import com.parachord.shared.model.PlaylistTrack
import kotlin.test.Test
import kotlin.test.assertEquals

/** Cross-engine vectors for the canonical track key (precedence + normalization). */
class PlaylistTrackKeyTest {

    @Test
    fun isrc_wins_and_is_uppercased() {
        // validateIsrc trims + uppercases to the canonical USX… shape.
        assertEquals("isrc-USRC17607839", canonicalTrackKey(" usrc17607839 ", "abc-mbid", "Artist", "Title"))
    }

    @Test
    fun mbid_when_no_isrc_lowercased() {
        assertEquals(
            "mbid-b9ad642e-b012-41c7-b4a2-11b3f1f9b0aa",
            canonicalTrackKey(null, "B9AD642E-B012-41C7-B4A2-11B3F1F9B0AA", "Artist", "Title"),
        )
    }

    @Test
    fun invalidIsrc_fallsThroughToMbid() {
        // "not-an-isrc" fails the canonical ISRC regex → use the MBID.
        assertEquals("mbid-xyz", canonicalTrackKey("not-an-isrc", "XYZ", "A", "T"))
    }

    @Test
    fun norm_when_no_ids_lowercasedAndTrimmed() {
        assertEquals("norm-radiohead|creep", canonicalTrackKey(null, null, "  Radiohead ", " Creep "))
        assertEquals("norm-radiohead|creep", canonicalTrackKey(" ", "", "RADIOHEAD", "CREEP"))
    }

    @Test
    fun norm_handlesMissingArtistOrTitle() {
        assertEquals("norm-|creep", canonicalTrackKey(null, null, null, "Creep"))
        assertEquals("norm-radiohead|", canonicalTrackKey(null, null, "Radiohead", null))
        assertEquals("norm-|", canonicalTrackKey(null, null, null, null))
    }

    @Test
    fun nwayBaselineKeys_ordersByPositionAndDerivesKeys() {
        val tracks = listOf(
            PlaylistTrack(playlistId = "p", position = 2, trackTitle = "Creep", trackArtist = "Radiohead"),
            PlaylistTrack(
                playlistId = "p", position = 0, trackTitle = "Idioteque", trackArtist = "Radiohead",
                trackRecordingMbid = "ABC-MBID",
            ),
            PlaylistTrack(playlistId = "p", position = 1, trackTitle = "No Surprises", trackArtist = "Radiohead"),
        )
        // Position order (0,1,2); track with MBID → mbid- key, others → norm-.
        assertEquals(
            listOf("mbid-abc-mbid", "norm-radiohead|no surprises", "norm-radiohead|creep"),
            nwayBaselineKeys(tracks),
        )
    }

    @Test
    fun sameSong_differentProviders_sameKey() {
        // Spotify gives ISRC, Apple Music gives the same ISRC → identical key,
        // so the merge treats them as one track.
        val spotify = canonicalTrackKey("GBAYE0601477", null, "Radiohead", "Creep")
        val apple = canonicalTrackKey("gbaye0601477", "some-mbid", "Radiohead (Remastered)", "Creep")
        assertEquals(spotify, apple)
    }
}
