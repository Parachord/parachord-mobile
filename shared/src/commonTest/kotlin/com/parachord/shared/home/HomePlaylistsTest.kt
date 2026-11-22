package com.parachord.shared.home

import com.parachord.shared.model.Playlist
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomePlaylistsTest {
    private val day = 86_400_000L
    private val utc = TimeZone.UTC
    private fun at(y: Int, mo: Int, d: Int, h: Int = 0): Long =
        LocalDateTime(y, mo, d, h, 0).toInstant(utc).toEpochMilliseconds()
    private val now = at(2026, 11, 12, 15)

    private fun pl(
        id: String = "p",
        name: String = "P",
        changed: Long? = null,
        modified: Long = 0,
        created: Long = 0,
        added: Int = 0,
    ) = Playlist(id = id, name = name, tracksChangedAt = changed, lastModified = modified, createdAt = created, lastChangeAdded = added)

    @Test
    fun freshnessFallsBackThroughTheFields() {
        assertEquals(5L, playlistFreshness(pl(changed = 5, modified = 9, created = 1)))
        assertEquals(9L, playlistFreshness(pl(modified = 9, created = 1)))
        assertEquals(1L, playlistFreshness(pl(created = 1)))
        assertEquals(0L, playlistFreshness(pl()))
    }

    @Test
    fun aRenamedPlaylistSortsByTracksChangedAt() {
        val renamed = pl("r", modified = now, changed = now - 10 * day)
        val edited = pl("e", modified = now - 2 * day, changed = now - 2 * day)
        assertEquals(listOf("e", "r"), freshestPlaylists(listOf(renamed, edited)).map { it.id })
    }

    @Test
    fun freshestLimitDefaultsToSix() {
        val ps = (0 until 9).map { pl("$it", modified = it.toLong() + 1) }
        assertEquals(6, freshestPlaylists(ps).size)
        assertEquals("8", freshestPlaylists(ps)[0].id)
    }

    @Test
    fun freshestIsStableOnTies() {
        val ps = listOf(pl("a", modified = 5), pl("b", modified = 5), pl("c", modified = 5))
        assertEquals(listOf("a", "b", "c"), freshestPlaylists(ps).map { it.id })
    }

    @Test
    fun badgeIsNullWhenNothingWasAdded() {
        assertNull(changeBadge(pl(added = 0, changed = now), now))
    }

    @Test
    fun badgeIsNullWhenOlderThanSevenDays() {
        assertNull(changeBadge(pl(added = 12, changed = now - 8 * day), now))
    }

    @Test
    fun badgeIsNullWithoutATracksChangedAt() {
        assertNull(changeBadge(pl(added = 12, changed = null), now))
    }

    @Test
    fun badgeShowsDeltaInsideTheWindow() {
        assertEquals("Δ 12", changeBadge(pl(added = 12, changed = now - 7 * day), now))
    }

    @Test
    fun changeLineWording() {
        val p = pl(name = "Daily Brew", added = 12, changed = at(2026, 11, 12, 8))
        assertEquals("Daily Brew picked up 12 new tracks this morning", freshChangeLine(p, now, utc))
        assertNull(freshChangeLine(p.copy(lastChangeAdded = 0), now, utc))
        assertEquals("Daily Brew picked up 1 new track this morning", freshChangeLine(p.copy(lastChangeAdded = 1), now, utc))
    }

    @Test
    fun whenPhraseBuckets() {
        assertEquals("this morning", whenPhrase(at(2026, 11, 12, 8), now, utc))
        assertEquals("this afternoon", whenPhrase(at(2026, 11, 12, 12), now, utc))
        assertEquals("this evening", whenPhrase(at(2026, 11, 12, 18), now, utc))
        assertEquals("yesterday", whenPhrase(at(2026, 11, 11, 23), now, utc))
        assertEquals("3 days ago", whenPhrase(at(2026, 11, 9, 1), now, utc))
        assertEquals("today", whenPhraseShort(at(2026, 11, 12, 8), now, utc))
        assertEquals("yesterday", whenPhraseShort(at(2026, 11, 11, 8), now, utc))
    }

    @Test
    fun subtitleIsTheRelativeTimeOnly() {
        val s = playlistSubtitle(pl(changed = now - 2 * 3_600_000, created = at(2023, 1, 1)), now)
        assertEquals(PlaylistSubtitle("Refreshed 2h ago"), s)
        assertEquals(PlaylistSubtitle("Refreshed 3d ago"), playlistSubtitle(pl(changed = now - 3 * day), now))
    }
}
