package com.parachord.shared.playlist

import com.parachord.shared.model.PlaylistTrack
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistDuplicatesTest {

    private fun t(
        pos: Int,
        artist: String,
        title: String,
        isrc: String? = null,
        mbid: String? = null,
    ) = PlaylistTrack(
        playlistId = "p",
        position = pos,
        trackTitle = title,
        trackArtist = artist,
        trackIsrc = isrc,
        trackRecordingMbid = mbid,
    )

    private fun titles(tracks: List<PlaylistTrack>) = tracks.map { "${it.position}:${it.trackTitle}" }

    @Test
    fun `no duplicates leaves the list alone`() {
        val tracks = listOf(t(0, "A", "One"), t(1, "B", "Two"))
        assertEquals(0, PlaylistDuplicates.count(tracks))
        assertEquals(titles(tracks), titles(PlaylistDuplicates.withoutDuplicates(tracks)))
    }

    @Test
    fun `keeps the first copy in place and renumbers`() {
        // The #802 shape: the whole list appended again at the end.
        val tracks = listOf(
            t(0, "Birdmonster", "Balloons"), t(1, "Pond", "Glimmer"), t(2, "C", "Three"),
            t(3, "Birdmonster", "Balloons"), t(4, "Pond", "Glimmer"),
            t(5, "Birdmonster", "Balloons"),
        )
        assertEquals(setOf(3, 4, 5), PlaylistDuplicates.duplicatePositions(tracks))
        assertEquals(
            listOf("0:Balloons", "1:Glimmer", "2:Three"),
            titles(PlaylistDuplicates.withoutDuplicates(tracks)),
        )
    }

    @Test
    fun `reads rows in position order whatever order they arrive in`() {
        val tracks = listOf(t(2, "A", "One"), t(0, "A", "One"), t(1, "B", "Two"))
        assertEquals(setOf(2), PlaylistDuplicates.duplicatePositions(tracks))
    }

    @Test
    fun `case and spacing don't make a different song`() {
        val tracks = listOf(t(0, "Pond", "Glimmer"), t(1, " pond ", "GLIMMER"))
        assertEquals(1, PlaylistDuplicates.count(tracks))
    }

    @Test
    fun `a remaster tag is the same song`() {
        val tracks = listOf(t(0, "Ramones", "Judy Is a Punk"), t(1, "Ramones", "Judy Is a Punk - 2016 Remaster"))
        assertEquals(1, PlaylistDuplicates.count(tracks))
    }

    @Test
    fun `a live version or remix is a different song`() {
        val tracks = listOf(
            t(0, "Ramones", "Judy Is a Punk"),
            t(1, "Ramones", "Judy Is a Punk (Live)"),
            t(2, "Ramones", "Judy Is a Punk - Remix"),
        )
        assertEquals(0, PlaylistDuplicates.count(tracks))
    }

    @Test
    fun `the same ISRC under different names is a duplicate`() {
        val tracks = listOf(
            t(0, "Beatles", "Help", isrc = "GBAYE6500001"),
            t(1, "The Beatles", "Help!", isrc = "GBAYE6500001"),
        )
        assertEquals(1, PlaylistDuplicates.count(tracks))
    }

    @Test
    fun `the same MBID under different names is a duplicate`() {
        val mbid = "b1a9c0e9-d987-4042-ae91-78d6a3267d69"
        val tracks = listOf(t(0, "X", "Song", mbid = mbid), t(1, "X feat Y", "Song (Edit)", mbid = mbid))
        assertEquals(1, PlaylistDuplicates.count(tracks))
    }

    @Test
    fun `two different ISRCs with one name stay two recordings`() {
        val tracks = listOf(
            t(0, "Band", "Intro", isrc = "USAAA0000001"),
            t(1, "Band", "Intro", isrc = "USAAA0000002"),
        )
        assertEquals(0, PlaylistDuplicates.count(tracks))
    }

    @Test
    fun `blank rows are never duplicates of each other`() {
        val tracks = listOf(t(0, "", ""), t(1, "", ""), t(2, "A", "One"))
        assertEquals(0, PlaylistDuplicates.count(tracks))
    }

    @Test
    fun `the banner shows only for a playlist we can edit that has duplicates`() {
        val local = com.parachord.shared.model.Playlist(id = "local-1", name = "Mine")
        val hosted = local.copy(id = "hosted-xspf-1", sourceUrl = "https://example.com/p.xspf")
        assertEquals(true, PlaylistDuplicates.offersRemoval(local, 2))
        assertEquals(false, PlaylistDuplicates.offersRemoval(local, 0))
        assertEquals(false, PlaylistDuplicates.offersRemoval(hosted, 2))
        assertEquals(false, PlaylistDuplicates.offersRemoval(null, 2))
    }

    @Test
    fun `copy counts read naturally`() {
        assertEquals("1 duplicate track in this playlist", PlaylistDuplicateCopy.banner(1))
        assertEquals("Remove 877 duplicate tracks?", PlaylistDuplicateCopy.confirmTitle(877))
        assertEquals("Removed 1,204 duplicate tracks", PlaylistDuplicateCopy.done(1204))
    }
}
