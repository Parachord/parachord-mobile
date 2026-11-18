package com.parachord.shared.sync

import com.parachord.shared.model.SyncSource
import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Library sync matches an incoming track to the row that already holds it (#682, desktop parachord#1404 / #1419). */
class SyncTrackMatchTest {
    private val sp = "spotify"
    private val nativeOf: (Track) -> String? = { it.spotifyId }

    private fun row(
        id: String, title: String, artist: String, album: String? = null,
        spotifyId: String? = null, addedAt: Long = 0, sources: Map<String, String> = emptyMap(),
    ) = SyncTrackMatch.LocalRow(
        Track(id = id, title = title, artist = artist, album = album, spotifyId = spotifyId, addedAt = addedAt),
        sources.mapValues { (pid, ext) -> SyncSource(itemId = id, itemType = "track", providerId = pid, externalId = ext) },
    )

    private fun remote(ext: String, title: String, artist: String, album: String? = null, id: String = "spotify-$ext") =
        SyncedTrack(Track(id = id, title = title, artist = artist, album = album, spotifyId = ext), ext, addedAt = 1)

    @Test fun `a heart then a sync of the same song makes one row`() {
        val heart = row("search-1", "White Roses", "Mondo Cozmo, Jim James", "White Roses", spotifyId = "S1")
        val r = SyncTrackMatch.match(listOf(remote("S1", "White Roses", "Mondo Cozmo", "White Roses")), listOf(heart), sp, nativeOf)
        assertEquals(listOf("search-1"), r.pairs.map { it.local.track.id })
        assertTrue(r.unmatched.isEmpty())
    }

    @Test fun `a no-album row and a sync match by name in both directions`() {
        val love = row("lastfm-creep", "Creep", "Radiohead", null)
        val r1 = SyncTrackMatch.match(listOf(remote("S2", "Creep", "Radiohead", "Pablo Honey")), listOf(love), sp, nativeOf)
        assertEquals("lastfm-creep", r1.pairs.single().local.track.id)

        val synced = row("spotify-S2", "Creep", "Radiohead", "Pablo Honey", sources = mapOf(sp to "S2"))
        val heart = row("search-creep", "Creep", "Radiohead", null)
        val r2 = SyncTrackMatch.match(listOf(remote("S2", "Creep", "Radiohead", "Pablo Honey")), listOf(synced, heart), sp, nativeOf)
        assertEquals(1, r2.pairs.size)
        assertEquals(1, r2.merged.size, "the existing pair heals into one row")
    }

    @Test fun `the same song on the same album matches by name`() {
        val heart = row("album-x-3", "Creep", "Radiohead", "Pablo Honey")
        val r = SyncTrackMatch.match(listOf(remote("S2", "Creep", "Radiohead", "Pablo Honey")), listOf(heart), sp, nativeOf)
        assertEquals("album-x-3", r.pairs.single().local.track.id)
    }

    @Test fun `two real editions stay two rows`() {
        val studio = row("search-a", "Creep", "Radiohead", "Pablo Honey", spotifyId = "S2")
        val r = SyncTrackMatch.match(listOf(remote("S2", "Creep", "Radiohead", "Creep (Acoustic) - EP Live")), listOf(studio), sp, nativeOf)
        assertTrue(r.pairs.isEmpty())
        assertEquals(1, r.unmatched.size)
    }

    @Test fun `a matched row is found by its link on the next sync`() {
        val heart = row("search-1", "White Roses", "Mondo Cozmo", "White Roses", sources = mapOf(sp to "S1"))
        val r = SyncTrackMatch.match(listOf(remote("S1", "White Roses", "Mondo Cozmo", "White Roses")), listOf(heart), sp, nativeOf)
        assertTrue(r.pairs.single().viaExactLink)
        assertTrue("search-1" in r.claimed)
    }

    @Test fun `an existing pair heals into the row the user saved`() {
        val heart = row("search-1", "Creep", "Radiohead", "Pablo Honey", spotifyId = "S2", addedAt = 50)
        val synced = row("spotify-S2", "Creep", "Radiohead", "Pablo Honey", spotifyId = "S2", addedAt = 10, sources = mapOf(sp to "S2"))
        val r = SyncTrackMatch.match(listOf(remote("S2", "Creep", "Radiohead", "Pablo Honey")), listOf(heart, synced), sp, nativeOf)
        assertEquals("search-1", r.pairs.single().local.track.id)
        assertEquals(listOf("spotify-S2"), r.merged.map { it.row.track.id })
        assertEquals("search-1", r.merged.single().keeperId)
    }

    @Test fun `Apple Music single and credit naming match Spotify's`() {
        val heart = row("search-1", "White Roses", "Mondo Cozmo & Jim James", "White Roses - Single")
        val r = SyncTrackMatch.match(
            listOf(remote("S1", "White Roses", "Mondo Cozmo, Jim James", "White Roses")), listOf(heart), sp, nativeOf,
        )
        assertEquals("search-1", r.pairs.single().local.track.id)
    }

    @Test fun `a remote that repeats one already placed is neither matched nor added`() {
        val synced = row("spotify-S1", "Song", "Artist", "Album", spotifyId = "S1", sources = mapOf(sp to "S1"))
        val repeat = SyncedTrack(Track(id = "spotify-S9", title = "Song", artist = "Artist", album = "Album", spotifyId = "S1"), "S9", 1)
        val r = SyncTrackMatch.match(listOf(remote("S1", "Song", "Artist", "Album"), repeat), listOf(synced), sp, nativeOf)
        assertEquals(1, r.pairs.size)
        assertEquals(listOf(repeat), r.duplicates)
        assertTrue(r.unmatched.isEmpty())
    }

    @Test fun `a fallback never takes a row another remote links to exactly`() {
        val a = row("spotify-S1", "Song", "Artist", "Album", sources = mapOf(sp to "S1"))
        val r = SyncTrackMatch.match(
            listOf(remote("S7", "Song", "Artist", "Album"), remote("S1", "Song", "Artist", "Album")), listOf(a), sp, nativeOf,
        )
        assertEquals("S1", r.pairs.single().remote.spotifyId)
    }

    @Test fun `two saved entries under one playable id keep their own rows`() {
        // Spotify lists an original and a remaster (two ISRCs) under one track id.
        val original = row("isrc-A", "Your Misfortune", "Mike Doughty", "Haughty Melodic", spotifyId = "S", sources = mapOf(sp to "S"))
        val remaster = row("isrc-B", "Your Misfortune", "Mike Doughty", "Haughty Melodic (Deluxe Remaster)", spotifyId = "S", sources = mapOf(sp to "S"))
        val r = SyncTrackMatch.match(
            listOf(
                remote("S", "Your Misfortune", "Mike Doughty", "Haughty Melodic (Deluxe Remaster)", id = "isrc-B"),
                remote("S", "Your Misfortune", "Mike Doughty", "Haughty Melodic", id = "isrc-A"),
            ),
            listOf(remaster, original), sp, nativeOf,
        )
        assertEquals(setOf("isrc-A", "isrc-B"), r.claimed)
        assertTrue(r.duplicates.isEmpty())
    }

    @Test fun `another edition under the same playable id is a repeat when it has no row`() {
        val remaster = row("isrc-B", "Your Misfortune", "Mike Doughty", "Haughty Melodic (Deluxe Remaster)", spotifyId = "S", sources = mapOf(sp to "S"))
        val r = SyncTrackMatch.match(
            listOf(
                remote("S", "Your Misfortune", "Mike Doughty", "Haughty Melodic (Deluxe Remaster)", id = "isrc-B"),
                remote("S", "Your Misfortune", "Mike Doughty", "Haughty Melodic", id = "isrc-A"),
            ),
            listOf(remaster), sp, nativeOf,
        )
        assertTrue(r.unmatched.isEmpty(), "the collection keeps one edition, as on desktop")
        assertEquals(listOf("isrc-A"), r.duplicates.map { it.entity.id })
    }

    @Test fun `a row carrying another song's id never takes it`() {
        // Older data left a wrong Spotify id on a different song's row.
        val wrong = row("isrc-C", "Dream About Changing", "Sally Seltmann", "Heart That's Pounding", spotifyId = "S3")
        val r = SyncTrackMatch.match(
            listOf(remote("S3", "Heart That's Pounding", "Sally Seltmann", "Heart That's Pounding", id = "isrc-D")),
            listOf(wrong), sp, nativeOf,
        )
        assertTrue(r.pairs.isEmpty())
        assertEquals(listOf("isrc-D"), r.unmatched.map { it.entity.id })
    }

    @Test fun `placeholder albums`() {
        fun t(album: String?, resolver: String? = null) = Track(id = "x", title = "t", artist = "a", album = album, resolver = resolver)
        assertTrue(SyncTrackMatch.isPlaceholderAlbum(t(null)))
        assertTrue(SyncTrackMatch.isPlaceholderAlbum(t("Single")))
        assertTrue(SyncTrackMatch.isPlaceholderAlbum(t("Unknown Album")))
        assertTrue(SyncTrackMatch.isPlaceholderAlbum(t("SoundCloud", resolver = "soundcloud")))
        assertTrue(!SyncTrackMatch.isPlaceholderAlbum(t("SoundCloud")))
        assertTrue(!SyncTrackMatch.isPlaceholderAlbum(t("Pablo Honey")))
    }
}
