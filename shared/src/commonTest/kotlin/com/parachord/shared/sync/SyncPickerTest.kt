package com.parachord.shared.sync

import com.parachord.shared.model.Playlist
import com.parachord.shared.playlist.PlaylistSort
import com.parachord.shared.playlist.PlaylistSorting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sync picker's rows and the Sync screen's status text (#591). */
class SyncPickerTest {
    private fun row(id: String, name: String, mirror: Boolean = false, created: Long = 0, updated: Long = 0, modified: Long = 0) =
        ProviderChannelPlaylist(id, name, 1, null, created, updated, modified, mirror, null, enabled = true)

    private fun remote(id: String, name: String) =
        SyncedPlaylist(entity = Playlist(id = "spotify-$id", name = name), spotifyId = id, snapshotId = null, trackCount = 3, isOwned = true)

    @Test fun `remote playlists already shown are left out`() {
        val local = listOf(row("spotify-a", "Mine"), row("local-1", "Road Trip", mirror = true))
        val out = SyncPickerRows.merge(
            providerId = "spotify",
            local = local,
            linkedExternalIds = setOf("b"),
            localNames = listOf("Mine", " road TRIP "),
            remote = listOf(remote("a", "Mine"), remote("b", "Copy"), remote("c", "Road Trip"), remote("d", "New one")),
            checked = emptySet(),
        )
        assertEquals(listOf("spotify-a", "local-1", "spotify-d"), out.map { it.localId })
        assertTrue(out.last().notImported)
        assertFalse(out.last().enabled)
    }

    @Test fun `a not-imported row the user checked stays checked`() {
        val out = SyncPickerRows.merge("spotify", emptyList(), emptySet(), emptyList(), listOf(remote("d", "New")), setOf("spotify-d"))
        assertTrue(out.single().enabled)
    }

    @Test fun `only Spotify and Apple Music import playlists`() {
        assertTrue(SyncPickerRows.isPullProvider("spotify"))
        assertTrue(SyncPickerRows.isPullProvider("applemusic"))
        assertFalse(SyncPickerRows.isPullProvider("listenbrainz"))
    }

    @Test fun `recently modified falls back to the row's last write`() {
        val rows = listOf(row("a", "A", updated = 50), row("b", "B", updated = 10, modified = 40), row("c", "C", updated = 60))
        assertEquals(listOf("c", "a", "b"), PlaylistSorting.channelRows(rows, PlaylistSort.MODIFIED).map { it.localId })
    }

    @Test fun `the playlists list filters by name and sorts`() {
        val list = listOf(Playlist(id = "1", name = "beta", createdAt = 2), Playlist(id = "2", name = "Alpha", createdAt = 1), Playlist(id = "3", name = "Gamma", createdAt = 3))
        assertEquals(listOf("Alpha", "beta", "Gamma"), PlaylistSorting.playlists(list, PlaylistSort.ALPHA_ASC, "").map { it.name })
        assertEquals(listOf("Gamma", "beta", "Alpha"), PlaylistSorting.playlists(list, PlaylistSort.RECENT, " ").map { it.name })
        assertEquals(listOf("Alpha", "Gamma"), PlaylistSorting.playlists(list, PlaylistSort.CREATED, "a").filter { it.name != "beta" }.map { it.name })
        assertEquals(PlaylistSort.RECENT, PlaylistSort.fromName("nope"))
    }

    @Test fun `status text`() {
        assertEquals("Liked Songs · Playlists", SyncStatusText.axesSummary(setOf("playlists", "tracks")))
        assertEquals("", SyncStatusText.axesSummary(emptySet()))
        val now = 10_000_000_000L
        assertEquals("Never", SyncStatusText.lastSynced(0, now))
        assertEquals("Just now", SyncStatusText.lastSynced(now - 59_000, now))
        assertEquals("5 minutes ago", SyncStatusText.lastSynced(now - 5 * 60_000, now))
        assertEquals("3 hours ago", SyncStatusText.lastSynced(now - 3 * 3_600_000L, now))
        assertEquals("2 days ago", SyncStatusText.lastSynced(now - 2 * 86_400_000L, now))
    }
}
