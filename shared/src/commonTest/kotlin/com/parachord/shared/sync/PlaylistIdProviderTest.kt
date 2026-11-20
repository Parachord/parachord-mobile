package com.parachord.shared.sync

import com.parachord.shared.model.Playlist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A playlist imported from a link (`PlaylistUrlImporter`) is saved as
 * `<provider>-import-<uuid>`, and a saved recommendation as
 * `<provider>-import-rec-<hash>`. Those are local snapshots, not rows a sync
 * provider owns, so the id must not name a pull source.
 */
class PlaylistIdProviderTest {

    @Test
    fun `provider-owned ids name their provider and remote id`() {
        assertEquals("spotify", impliedPlaylistProvider("spotify-37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", impliedPlaylistExternalId("spotify-37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("applemusic", impliedPlaylistProvider("applemusic-p.abc123"))
        assertEquals("p.abc123", impliedPlaylistExternalId("applemusic-p.abc123"))
        val mbid = "0f3b2a4c-1111-2222-3333-444455556666"
        assertEquals("listenbrainz", impliedPlaylistProvider("listenbrainz-$mbid"))
        assertEquals(mbid, impliedPlaylistExternalId("listenbrainz-$mbid"))
    }

    @Test
    fun `link-imported ids name no provider`() {
        for (id in listOf(
            "spotify-import-0b6f6d4e-aaaa-bbbb-cccc-111122223333",
            "applemusic-import-0b6f6d4e-aaaa-bbbb-cccc-111122223333",
            "listenbrainz-import-x",
            "soundcloud-import-x",
            "link-import-x",
            "spotify-import-rec-abc123",
            "applemusic-import-rec-abc123",
        )) {
            assertTrue(isLinkImportedPlaylistId(id), id)
            assertNull(impliedPlaylistProvider(id), id)
            assertNull(impliedPlaylistExternalId(id), id)
        }
    }

    @Test
    fun `local and hosted ids name no provider`() {
        for (id in listOf("local-1", "hosted-xspf-1", "queue-1", "")) {
            assertFalse(isLinkImportedPlaylistId(id), id)
            assertNull(impliedPlaylistProvider(id), id)
        }
    }

    @Test
    fun `link import origin is the provider it came from`() {
        assertEquals("spotify", linkImportOrigin("spotify-import-x"))
        assertEquals("applemusic", linkImportOrigin("applemusic-import-rec-x"))
        assertNull(linkImportOrigin("spotify-x"))
    }

    private fun pl(id: String) = Playlist(id = id, name = "P")

    @Test
    fun `a Spotify link import never auto-mirrors`() {
        val p = pl("spotify-import-x")
        assertFalse(autoMirrorsByDefault(p))
        // Not a candidate for the service it came from.
        assertFalse(isPlaylistPushCandidate(p, "spotify"))
        // Opt-in to the others, like a local playlist.
        assertTrue(isPlaylistPushCandidate(p, "applemusic"))
        assertTrue(isPlaylistPushCandidate(p, "listenbrainz"))
    }

    @Test
    fun `an Apple Music link import never auto-mirrors`() {
        val p = pl("applemusic-import-rec-x")
        assertFalse(autoMirrorsByDefault(p))
        assertFalse(isPlaylistPushCandidate(p, "applemusic"))
        assertTrue(isPlaylistPushCandidate(p, "spotify"))
        assertTrue(isPlaylistPushCandidate(p, "listenbrainz"))
    }

    @Test
    fun `provider-owned rows keep their push rules`() {
        assertTrue(autoMirrorsByDefault(pl("spotify-x")))
        assertTrue(isPlaylistPushCandidate(pl("spotify-x"), "applemusic"))
        assertFalse(isPlaylistPushCandidate(pl("spotify-x").copy(spotifyId = "x"), "spotify"))
        assertTrue(isPlaylistPushCandidate(pl("applemusic-x"), "listenbrainz"))
        assertFalse(isPlaylistPushCandidate(pl("applemusic-x"), "spotify"))
        assertTrue(isPlaylistPushCandidate(pl("local-x"), "spotify"))
        assertTrue(autoMirrorsByDefault(pl("local-x")))
    }
}
