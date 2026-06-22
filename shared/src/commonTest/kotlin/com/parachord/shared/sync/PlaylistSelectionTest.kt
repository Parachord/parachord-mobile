package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the per-provider playlist-push selection (the ListenBrainz-flood fix).
 * The load-bearing invariant is the DEFAULT: a provider with nothing stored
 * must push NOTHING to ListenBrainz, while Spotify / Apple Music keep mirroring
 * everything — and the per-mode inclusion decision the push loop filters on.
 */
class PlaylistSelectionTest {

    @Test
    fun listenBrainz_default_is_NONE() {
        assertEquals(PlaylistSyncMode.NONE, ProviderPlaylistSelection.defaultMode("listenbrainz"))
    }

    @Test
    fun spotify_and_appleMusic_default_is_ALL() {
        assertEquals(PlaylistSyncMode.ALL, ProviderPlaylistSelection.defaultMode("spotify"))
        assertEquals(PlaylistSyncMode.ALL, ProviderPlaylistSelection.defaultMode("applemusic"))
        assertEquals(PlaylistSyncMode.ALL, ProviderPlaylistSelection.defaultMode("tidal"))
    }

    @Test
    fun ALL_includes_everything() {
        val sel = ProviderPlaylistSelection(PlaylistSyncMode.ALL)
        assertTrue(sel.includes("local-1"))
        assertTrue(sel.includes("applemusic-99"))
    }

    @Test
    fun NONE_includes_nothing() {
        val sel = ProviderPlaylistSelection(PlaylistSyncMode.NONE)
        assertFalse(sel.includes("local-1"))
        assertFalse(sel.includes("spotify-7"))
    }

    @Test
    fun SELECTED_includes_only_chosen_ids() {
        val sel = ProviderPlaylistSelection(PlaylistSyncMode.SELECTED, setOf("local-1", "applemusic-2"))
        assertTrue(sel.includes("local-1"))
        assertTrue(sel.includes("applemusic-2"))
        assertFalse(sel.includes("local-3"))
        assertFalse(sel.includes("spotify-9"))
    }

    // ── #269: a followed playlist mirrors OUT but never writes BACK ───

    @Test
    fun spotifyImportedPlaylist_mirrorsOutToOtherProviders() {
        val p = com.parachord.shared.model.Playlist(id = "spotify-1", name = "Mine", writable = true)
        assertTrue(isPlaylistPushCandidate(p, "applemusic"))
        assertTrue(isPlaylistPushCandidate(p, "listenbrainz"))
    }

    @Test
    fun followedPlaylist_STILL_mirrorsOut_butNeverBackToSpotify() {
        // A read-only FOLLOWED Spotify playlist CAN mirror out to AM/LB (you own
        // those copies). It must NOT push back to Spotify — but that's enforced by
        // the spotifyId != null clause, NOT by a writable gate. Here spotifyId is
        // set, id-prefix is spotify-, so Spotify push is already excluded while
        // AM/LB mirroring stays allowed.
        val followed = com.parachord.shared.model.Playlist(
            id = "spotify-1", name = "Theirs", spotifyId = "1", writable = false,
        )
        assertTrue(isPlaylistPushCandidate(followed, "applemusic"), "followed playlists still mirror to AM")
        assertTrue(isPlaylistPushCandidate(followed, "listenbrainz"), "followed playlists still mirror to LB")
        assertFalse(isPlaylistPushCandidate(followed, "spotify"), "never writes back to the followed Spotify source")
    }
}
