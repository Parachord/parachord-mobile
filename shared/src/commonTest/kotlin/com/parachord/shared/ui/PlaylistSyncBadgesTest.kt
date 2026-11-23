package com.parachord.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistSyncBadgesTest {
    @Test
    fun `badges follow the fixed order — then others by id`() {
        val ids = PlaylistSyncBadges.of(listOf("tidal", "listenbrainz", "spotify", "applemusic", "spotify")).map { it.providerId }
        assertEquals(listOf("spotify", "applemusic", "listenbrainz", "tidal"), ids)
    }

    @Test
    fun `labels are the plugin names and notes say where it syncs`() {
        val badge = PlaylistSyncBadges.of(listOf("applemusic")).single()
        assertEquals("Apple Music", badge.label)
        assertTrue(badge.colored)
        assertEquals("Syncs to Apple Music", badge.note)
    }

    @Test
    fun `a local-only playlist's badges are uncoloured and only linked`() {
        val badge = PlaylistSyncBadges.of(listOf("spotify"), localOnly = true).single()
        assertEquals(false, badge.colored)
        assertEquals("Linked to Spotify, but kept local only", badge.note)
    }

    @Test
    fun `page links exist for the three services only`() {
        assertEquals("https://open.spotify.com/playlist/abc", PlaylistSyncBadges.pageUrl("spotify", "abc"))
        assertEquals("https://listenbrainz.org/playlist/abc/", PlaylistSyncBadges.pageUrl("listenbrainz", "abc"))
        assertEquals("https://music.apple.com/library/playlist/abc", PlaylistSyncBadges.pageUrl("applemusic", "abc"))
        assertNull(PlaylistSyncBadges.pageUrl("tidal", "abc"))
    }
}
