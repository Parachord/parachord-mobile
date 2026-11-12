package com.parachord.shared.library

import com.parachord.shared.model.Album
import com.parachord.shared.model.Artist
import com.parachord.shared.model.Friend
import com.parachord.shared.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Collection screen's sorts and filter (#593). */
class CollectionSortTest {
    private fun t(title: String, artist: String = "A", album: String? = null, duration: Long? = null, added: Long = 0) =
        Track(id = "$title|$artist", title = title, artist = artist, album = album, duration = duration, addedAt = added)

    @Test fun `defaults and labels are desktop's`() {
        assertEquals(ArtistSort.ALPHA_ASC, ArtistSort.DEFAULT)
        assertEquals(AlbumSort.RECENT, AlbumSort.DEFAULT)
        assertEquals(TrackSort.RECENT, TrackSort.DEFAULT)
        assertEquals(FriendSort.ALPHA_ASC, FriendSort.DEFAULT)
        assertEquals("A-Z", ArtistSort.ALPHA_ASC.label)
        assertEquals("Title Z-A", TrackSort.TITLE_DESC.label)
    }

    @Test fun `a saved name maps back and an unknown one is the default`() {
        assertEquals(TrackSort.DURATION, TrackSort.fromName("DURATION"))
        assertEquals(TrackSort.RECENT, TrackSort.fromName("nope"))
        assertEquals(FriendSort.ALPHA_ASC, FriendSort.fromName(null))
    }

    @Test fun `title sort ignores case and is stable`() {
        val list = listOf(t("b", artist = "1"), t("A"), t("B", artist = "2"))
        assertEquals(listOf("A", "b", "B"), CollectionSort.tracks(list, TrackSort.TITLE_ASC, "").map { it.title })
        assertEquals(listOf("1", "2"), CollectionSort.tracks(list, TrackSort.TITLE_ASC, "").drop(1).map { it.artist })
    }

    @Test fun `duration is longest first and a missing one counts as zero`() {
        val list = listOf(t("x", duration = null), t("y", duration = 200), t("z", duration = 100))
        assertEquals(listOf("y", "z", "x"), CollectionSort.tracks(list, TrackSort.DURATION, "").map { it.title })
    }

    @Test fun `the filter matches title artist or album`() {
        val list = listOf(t("Creep", "Radiohead", "Pablo Honey"), t("Roads", "Portishead", "Dummy"))
        assertEquals(listOf("Creep"), CollectionSort.tracks(list, TrackSort.RECENT, "pablo").map { it.title })
        assertEquals(listOf("Roads"), CollectionSort.tracks(list, TrackSort.RECENT, " PORTIS ").map { it.title })
        assertEquals(2, CollectionSort.tracks(list, TrackSort.RECENT, "  ").size)
    }

    @Test fun `albums and artists`() {
        val albums = listOf(Album("1", "Zebra", "Beach House", addedAt = 1), Album("2", "alpha", "Zed", addedAt = 2))
        assertEquals(listOf("alpha", "Zebra"), CollectionSort.albums(albums, AlbumSort.ALPHA_ASC, "").map { it.title })
        assertEquals(listOf("Zebra", "alpha"), CollectionSort.albums(albums, AlbumSort.ARTIST, "").map { it.title })
        assertEquals(listOf("alpha", "Zebra"), CollectionSort.albums(albums, AlbumSort.RECENT, "").map { it.title })
        val artists = listOf(Artist("1", "beck"), Artist("2", "Air"))
        assertEquals(listOf("beck", "Air"), CollectionSort.artists(artists, ArtistSort.ALPHA_DESC, "").map { it.name })
    }

    @Test fun `recently active keeps friends who played in the last 14 days`() {
        val nowMs = 1_800_000_000_000L
        val nowS = nowMs / 1000
        fun f(name: String, playedAgoS: Long?) = Friend(
            id = name, username = name, service = "lastfm", displayName = name, addedAt = 0,
            cachedTrackTimestamp = playedAgoS?.let { nowS - it } ?: 0,
        )
        val friends = listOf(f("old", 15 * 86_400L), f("week", 7 * 86_400L), f("never", null), f("day", 86_400L))
        assertEquals(listOf("day", "week"), CollectionSort.friends(friends, FriendSort.ACTIVE, "", nowMs).map { it.displayName })
    }
}
