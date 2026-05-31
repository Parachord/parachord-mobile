package com.parachord.android.sync

import com.parachord.shared.api.LbPlaylist
import com.parachord.shared.api.LbPlaylistTrack
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.api.MbidMapperResult
import com.parachord.shared.metadata.MbidEnrichmentService
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.sync.DeleteResult
import com.parachord.shared.sync.ListenBrainzSyncProvider
import com.parachord.shared.sync.ListenBrainzUnauthorizedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Locks the contract of [ListenBrainzSyncProvider.fetchPlaylists] (Task 10 of
 * the LB-sync rollout, #156).
 *
 * Covers:
 *  - early-return when token / username unset (provider not configured)
 *  - kill-switch: 401 trips `authFailedForSession` and subsequent calls
 *    short-circuit without hitting the client
 *  - `LbPlaylist` → `SyncedPlaylist` field mapping (mbid, title, annotation,
 *    lastModifiedAt → snapshotId)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenBrainzSyncProviderTest {

    @Test
    fun `fetchPlaylists returns empty when token unset`() = runTest {
        val settings: SettingsStore = mockk {
            coEvery { getListenBrainzToken() } returns null
        }
        val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
        assertTrue(provider.fetchPlaylists().isEmpty())
    }

    @Test
    fun `fetchPlaylists returns empty when token is blank`() = runTest {
        val settings: SettingsStore = mockk {
            coEvery { getListenBrainzToken() } returns ""
        }
        val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
        assertTrue(provider.fetchPlaylists().isEmpty())
    }

    @Test
    fun `fetchPlaylists returns empty when username unset`() = runTest {
        val settings: SettingsStore = mockk {
            coEvery { getListenBrainzToken() } returns "valid-token"
            coEvery { getListenBrainzUsername() } returns null
        }
        val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
        assertTrue(provider.fetchPlaylists().isEmpty())
    }

    @Test
    fun `fetchPlaylists returns empty when username is blank`() = runTest {
        val settings: SettingsStore = mockk {
            coEvery { getListenBrainzToken() } returns "valid-token"
            coEvery { getListenBrainzUsername() } returns ""
        }
        val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
        assertTrue(provider.fetchPlaylists().isEmpty())
    }

    @Test
    fun `fetchPlaylists trips kill-switch on ListenBrainzUnauthorizedException (defensive — unauth endpoint today)`() = runTest {
        // The pull endpoint is unauth today, so the typed exception flows only from
        // a mocked client. Real 401 paths exit through the generic-Exception arm
        // (separate test). This pins the kill-switch invariant for the day LB
        // ever requires auth on this endpoint OR if a mutation lands on the kill-switch
        // path during a pull cycle.
        val client: ListenBrainzClient = mockk {
            coEvery { getUserOwnedPlaylists("test-user") } throws ListenBrainzUnauthorizedException()
        }
        val settings: SettingsStore = mockk {
            coEvery { getListenBrainzToken() } returns "valid-but-server-rejected"
            coEvery { getListenBrainzUsername() } returns "test-user"
        }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        assertTrue(provider.fetchPlaylists().isEmpty())
        // Second call short-circuits (no second client call) — kill-switch
        // active means we don't ping the API again until session restart.
        assertTrue(provider.fetchPlaylists().isEmpty())
        coVerify(exactly = 1) { client.getUserOwnedPlaylists("test-user") }
    }

    @Test
    fun `fetchPlaylists propagates a real 401 from the unauth endpoint as generic Exception (does not trip kill-switch)`() = runTest {
        val client: ListenBrainzClient = mockk {
            coEvery { getUserOwnedPlaylists("test-user") } throws Exception("getUserOwnedPlaylists(test-user) failed: HTTP 401 Unauthorized")
        }
        val settings: SettingsStore = mockk {
            coEvery { getListenBrainzToken() } returns "tok"
            coEvery { getListenBrainzUsername() } returns "test-user"
        }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        // First call: generic Exception propagates
        try {
            provider.fetchPlaylists()
            error("expected Exception to propagate")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("HTTP 401") == true)
        }
        // Second call: kill-switch NOT tripped, client called again
        try {
            provider.fetchPlaylists()
            error("expected Exception to propagate")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("HTTP 401") == true)
        }
        coVerify(exactly = 2) { client.getUserOwnedPlaylists("test-user") }
    }

    @Test
    fun `fetchPlaylists returns empty list when user has no playlists`() = runTest {
        val client: ListenBrainzClient = mockk {
            coEvery { getUserOwnedPlaylists("test-user") } returns emptyList()
        }
        val settings: SettingsStore = mockk {
            coEvery { getListenBrainzToken() } returns "tok"
            coEvery { getListenBrainzUsername() } returns "test-user"
        }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        assertTrue(provider.fetchPlaylists().isEmpty())
    }

    @Test
    fun `fetchPlaylists propagates non-401 errors`() = runTest {
        val client: ListenBrainzClient = mockk {
            coEvery { getUserOwnedPlaylists("test-user") } throws Exception("HTTP 503")
        }
        val settings: SettingsStore = mockk {
            coEvery { getListenBrainzToken() } returns "tok"
            coEvery { getListenBrainzUsername() } returns "test-user"
        }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        try {
            provider.fetchPlaylists()
            error("expected Exception to propagate")
        } catch (e: Exception) {
            assertEquals("HTTP 503", e.message)
        }
    }

    @Test
    fun `fetchPlaylists maps LbPlaylist to SyncedPlaylist`() = runTest {
        val lbPlaylists = listOf(
            LbPlaylist(
                mbid = "mbid-1",
                title = "Playlist 1",
                annotation = "Desc 1",
                lastModifiedAt = "2026-05-27T10:00:00Z",
            ),
            LbPlaylist(
                mbid = "mbid-2",
                title = "Playlist 2",
                annotation = "",
                lastModifiedAt = null,
            ),
        )
        val client: ListenBrainzClient = mockk {
            coEvery { getUserOwnedPlaylists("test-user") } returns lbPlaylists
        }
        val settings: SettingsStore = mockk {
            coEvery { getListenBrainzToken() } returns "tok"
            coEvery { getListenBrainzUsername() } returns "test-user"
        }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        val result = provider.fetchPlaylists()
        assertEquals(2, result.size)

        // First playlist — full data.
        assertEquals("mbid-1", result[0].spotifyId)
        assertEquals("Playlist 1", result[0].entity.name)
        assertEquals("Desc 1", result[0].entity.description)
        assertEquals("2026-05-27T10:00:00Z", result[0].snapshotId)
        assertEquals("listenbrainz-mbid-1", result[0].entity.id)
        // LB has no notion of unowned playlists in this list endpoint
        // (it's the user's OWN playlists), so isOwned must be true to
        // allow push-back.
        assertTrue(result[0].isOwned)

        // Second playlist — blank annotation → null description, null
        // lastModifiedAt → null snapshotId.
        assertEquals("mbid-2", result[1].spotifyId)
        assertEquals("Playlist 2", result[1].entity.name)
        assertNull(result[1].entity.description)
        assertNull(result[1].snapshotId)
    }

    // ── fetchPlaylistTracks (Task 11) ──────────────────────────────────

    @Test
    fun `fetchPlaylistTracks delegates to client and maps to PlaylistTrack`() = runTest {
        val richTracks = listOf(
            LbPlaylistTrack(
                id = "mbid-1",
                title = "Track1",
                artist = "Artist1",
                album = "Album1",
                mbid = "mbid-1",
            ),
            LbPlaylistTrack(
                id = "mbid-2",
                title = "Track2",
                artist = "Artist2",
                album = null,
                mbid = "mbid-2",
            ),
        )
        val client: ListenBrainzClient = mockk {
            coEvery { getPlaylistTracksRich("playlist-mbid") } returns richTracks
        }
        val provider = ListenBrainzSyncProvider(client, mockk(relaxed = true), mockk())
        val result = provider.fetchPlaylistTracks("playlist-mbid")
        assertEquals(2, result.size)
        assertEquals("Track1", result[0].trackTitle)
        assertEquals("Artist1", result[0].trackArtist)
        assertEquals("Album1", result[0].trackAlbum)
        assertEquals("mbid-1", result[0].trackRecordingMbid)
        assertEquals(0, result[0].position)
        assertEquals(1, result[1].position)
        assertNull(result[1].trackAlbum)
        // playlistId follows the `<provider>-<externalId>` convention used by
        // SpotifySyncProvider + AppleMusicSyncProvider. SyncEngine remaps this
        // to the local id when persisting.
        assertEquals("listenbrainz-playlist-mbid", result[0].playlistId)
        assertEquals("listenbrainz-playlist-mbid", result[1].playlistId)
    }

    @Test
    fun `fetchPlaylistTracks returns empty list when playlist has no tracks`() = runTest {
        val client: ListenBrainzClient = mockk {
            coEvery { getPlaylistTracksRich("mbid") } returns emptyList()
        }
        val provider = ListenBrainzSyncProvider(client, mockk(relaxed = true), mockk())
        assertTrue(provider.fetchPlaylistTracks("mbid").isEmpty())
    }

    // ── getPlaylistSnapshotId (Task 11) ────────────────────────────────

    @Test
    fun `getPlaylistSnapshotId returns last_modified_at from client`() = runTest {
        val client: ListenBrainzClient = mockk {
            coEvery { getPlaylistLastModified("mbid") } returns "2026-05-27T10:00:00Z"
        }
        val provider = ListenBrainzSyncProvider(client, mockk(relaxed = true), mockk())
        assertEquals("2026-05-27T10:00:00Z", provider.getPlaylistSnapshotId("mbid"))
    }

    @Test
    fun `getPlaylistSnapshotId returns null when client returns null`() = runTest {
        val client: ListenBrainzClient = mockk {
            coEvery { getPlaylistLastModified("mbid") } returns null
        }
        val provider = ListenBrainzSyncProvider(client, mockk(relaxed = true), mockk())
        assertNull(provider.getPlaylistSnapshotId("mbid"))
    }

    // ── createPlaylist (Task 12) ───────────────────────────────────────

    @Test
    fun `createPlaylist returns RemoteCreated with server-assigned MBID`() = runTest {
        val client: ListenBrainzClient = mockk {
            coEvery { createPlaylist(any(), any(), any(), any()) } returns "new-mbid-uuid"
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        val result = provider.createPlaylist("Test", "Desc")
        assertEquals("new-mbid-uuid", result.externalId)
        // Snapshot is fetched separately via getPlaylistSnapshotId — the create
        // endpoint returns only the MBID, not a last_modified timestamp.
        assertNull(result.snapshotId)
        // Default PRIVATE (isPublic=false): matches desktop + Achordion interop
        // contract; LB treats a missing flag as public, so we send it explicitly.
        coVerify { client.createPlaylist("Test", "Desc", false, "tok") }
    }

    @Test
    fun `createPlaylist propagates 401 as ListenBrainzUnauthorizedException`() = runTest {
        // Kill-switch invariant: a 401 from a mutation endpoint should trip
        // authFailedForSession AND propagate to the caller so SyncEngine knows
        // the push failed. We can't directly read the @Volatile field from
        // outside the class — pin the typed-exception propagation instead
        // (matches the fetchPlaylists defensive 401 test pattern above).
        val client: ListenBrainzClient = mockk {
            coEvery { createPlaylist(any(), any(), any(), any()) } throws ListenBrainzUnauthorizedException()
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        try {
            provider.createPlaylist("Test", "Desc")
            fail("expected ListenBrainzUnauthorizedException")
        } catch (e: ListenBrainzUnauthorizedException) { /* ok */ }
    }

    @Test
    fun `createPlaylist throws IllegalStateException when token unset`() = runTest {
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns null }
        val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
        try {
            provider.createPlaylist("Test", null)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) { /* ok */ }
    }

    @Test
    fun `createPlaylist throws IllegalStateException when token blank`() = runTest {
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "" }
        val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
        try {
            provider.createPlaylist("Test", null)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) { /* ok */ }
    }

    // ── replacePlaylistTracks (Task 12) ────────────────────────────────

    @Test
    fun `replacePlaylistTracks does delete-all + add-all + returns new snapshot`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true) {
            coEvery { getPlaylistTracksRich("mbid") } returns listOf(mockk(), mockk(), mockk()) // 3 items
            coEvery { getPlaylistLastModified("mbid") } returns "2026-05-27T12:00:00Z"
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        val newSnap = provider.replacePlaylistTracks("mbid", listOf("a", "b", "c", "d"))
        coVerifyOrder {
            client.getPlaylistTracksRich("mbid")
            client.deletePlaylistItems("mbid", 0, 3, "tok")
            client.addPlaylistItems("mbid", listOf("a", "b", "c", "d"), "tok")
            client.getPlaylistLastModified("mbid")
        }
        assertEquals("2026-05-27T12:00:00Z", newSnap)
    }

    @Test
    fun `replacePlaylistTracks skips deleteItems when current is empty`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true) {
            coEvery { getPlaylistTracksRich("mbid") } returns emptyList()
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        provider.replacePlaylistTracks("mbid", listOf("a", "b"))
        coVerify(exactly = 0) { client.deletePlaylistItems(any(), any(), any(), any()) }
        coVerify(exactly = 1) { client.addPlaylistItems("mbid", listOf("a", "b"), "tok") }
    }

    @Test
    fun `replacePlaylistTracks skips addItems when desired is empty`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true) {
            coEvery { getPlaylistTracksRich("mbid") } returns listOf(mockk(), mockk())
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        provider.replacePlaylistTracks("mbid", emptyList())
        coVerify(exactly = 1) { client.deletePlaylistItems("mbid", 0, 2, "tok") }
        coVerify(exactly = 0) { client.addPlaylistItems(any(), any(), any()) }
    }

    @Test
    fun `replacePlaylistTracks throws IllegalStateException when token unset`() = runTest {
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns null }
        val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
        try {
            provider.replacePlaylistTracks("mbid", listOf("a"))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) { /* ok */ }
    }

    @Test
    fun `replacePlaylistTracks propagates 401 from deletePlaylistItems`() = runTest {
        val client: ListenBrainzClient = mockk {
            coEvery { getPlaylistTracksRich("mbid") } returns listOf(mockk(), mockk())
            coEvery { deletePlaylistItems(any(), any(), any(), any()) } throws ListenBrainzUnauthorizedException()
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        try {
            provider.replacePlaylistTracks("mbid", listOf("a"))
            fail("expected ListenBrainzUnauthorizedException")
        } catch (e: ListenBrainzUnauthorizedException) { /* ok */ }
    }

    @Test
    fun `replacePlaylistTracks propagates 401 from addPlaylistItems`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true) {
            coEvery { getPlaylistTracksRich("mbid") } returns emptyList()
            coEvery { addPlaylistItems(any(), any(), any()) } throws ListenBrainzUnauthorizedException()
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        try {
            provider.replacePlaylistTracks("mbid", listOf("a"))
            fail("expected ListenBrainzUnauthorizedException")
        } catch (e: ListenBrainzUnauthorizedException) { /* ok */ }
    }

    // ── updatePlaylistDetails (Task 12) ────────────────────────────────

    @Test
    fun `updatePlaylistDetails delegates to client editPlaylist`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true)
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        provider.updatePlaylistDetails("mbid", "New Name", "New Desc")
        coVerify(exactly = 1) { client.editPlaylist("mbid", "New Name", "New Desc", "tok") }
    }

    @Test
    fun `updatePlaylistDetails forwards a name-only update`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true)
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        provider.updatePlaylistDetails("mbid", "New Name", null)
        coVerify(exactly = 1) { client.editPlaylist("mbid", "New Name", null, "tok") }
    }

    @Test
    fun `updatePlaylistDetails no-ops when both name and description are null`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true)
        val settings: SettingsStore = mockk(relaxed = true)
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        provider.updatePlaylistDetails("mbid", null, null)
        coVerify(exactly = 0) { client.editPlaylist(any(), any(), any(), any()) }
        // Don't even read the token — pure short-circuit.
        coVerify(exactly = 0) { settings.getListenBrainzToken() }
    }

    @Test
    fun `updatePlaylistDetails throws IllegalStateException when token unset`() = runTest {
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns null }
        val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
        try {
            provider.updatePlaylistDetails("mbid", "Name", null)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) { /* ok */ }
    }

    @Test
    fun `updatePlaylistDetails propagates 401 as ListenBrainzUnauthorizedException`() = runTest {
        val client: ListenBrainzClient = mockk {
            coEvery { editPlaylist(any(), any(), any(), any()) } throws ListenBrainzUnauthorizedException()
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        try {
            provider.updatePlaylistDetails("mbid", "Name", null)
            fail("expected ListenBrainzUnauthorizedException")
        } catch (e: ListenBrainzUnauthorizedException) { /* ok */ }
    }

    // ── deletePlaylist (Task 13) ───────────────────────────────────────

    @Test
    fun `deletePlaylist returns Success on happy path`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true)
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        val result = provider.deletePlaylist("mbid")
        assertEquals(DeleteResult.Success, result)
        coVerify(exactly = 1) { client.deletePlaylist("mbid", "tok") }
    }

    @Test
    fun `deletePlaylist 401 returns Unsupported (not thrown)`() = runTest {
        // Per the SyncProvider contract, deletePlaylist must NEVER throw on
        // documented-unsupported responses. Caller surfaces "remove manually"
        // UX from Unsupported(401).
        val client: ListenBrainzClient = mockk {
            coEvery { deletePlaylist(any(), any()) } throws ListenBrainzUnauthorizedException()
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        val result = provider.deletePlaylist("mbid")
        assertTrue("expected Unsupported, got $result", result is DeleteResult.Unsupported)
        assertEquals(401, (result as DeleteResult.Unsupported).status)
    }

    @Test
    fun `deletePlaylist returns Failed on other errors`() = runTest {
        val client: ListenBrainzClient = mockk {
            coEvery { deletePlaylist(any(), any()) } throws Exception("HTTP 500")
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        val result = provider.deletePlaylist("mbid")
        assertTrue("expected Failed, got $result", result is DeleteResult.Failed)
        assertEquals("HTTP 500", (result as DeleteResult.Failed).error.message)
    }

    @Test
    fun `deletePlaylist returns Failed when token unset`() = runTest {
        // Token-unset is a hard config error, but the contract still says
        // never throw — return Failed instead.
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns null }
        val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
        val result = provider.deletePlaylist("mbid")
        assertTrue("expected Failed, got $result", result is DeleteResult.Failed)
        assertTrue((result as DeleteResult.Failed).error is IllegalStateException)
    }

    @Test
    fun `deletePlaylist returns Failed when token blank`() = runTest {
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "" }
        val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
        val result = provider.deletePlaylist("mbid")
        assertTrue("expected Failed, got $result", result is DeleteResult.Failed)
    }

    // ── searchForTrackId (Task 13) ─────────────────────────────────────

    @Test
    fun `searchForTrackId returns recordingMbid from mapper on hit`() = runTest {
        // mapperLookup signature is (artist, recording) — NOT (title, artist).
        // The provider must pass them in the right order.
        val enrichment: MbidEnrichmentService = mockk {
            coEvery { mapperLookup("Artist", "Title") } returns MbidMapperResult(
                artistMbid = "artist-mbid",
                artistCreditName = "Artist",
                recordingName = "Title",
                recordingMbid = "mbid-result",
                releaseName = null,
                releaseMbid = null,
                confidence = 0.95,
            )
        }
        val provider = ListenBrainzSyncProvider(mockk(), mockk(relaxed = true), enrichment)
        assertEquals("mbid-result", provider.searchForTrackId("Title", "Artist"))
    }

    @Test
    fun `searchForTrackId returns null on mapper miss`() = runTest {
        val enrichment: MbidEnrichmentService = mockk {
            coEvery { mapperLookup(any(), any()) } returns null
        }
        val provider = ListenBrainzSyncProvider(mockk(), mockk(relaxed = true), enrichment)
        assertNull(provider.searchForTrackId("Title", "Artist"))
    }

    @Test
    fun `searchForTrackId returns null when mapper throws`() = runTest {
        // Defensive — mapperLookup already swallows exceptions internally
        // and returns null, but if it ever stops doing so, the provider
        // must not propagate the failure (sync engine would abort the
        // whole hydrate-loop on a single bad lookup).
        val enrichment: MbidEnrichmentService = mockk {
            coEvery { mapperLookup(any(), any()) } throws Exception("Network error")
        }
        val provider = ListenBrainzSyncProvider(mockk(), mockk(relaxed = true), enrichment)
        assertNull(provider.searchForTrackId("Title", "Artist"))
    }

    @Test
    fun `searchForTrackId returns null when recordingMbid is null`() = runTest {
        // Mapper sometimes returns a result with artist MBID resolved but
        // no recording MBID — treat as miss.
        val enrichment: MbidEnrichmentService = mockk {
            coEvery { mapperLookup(any(), any()) } returns MbidMapperResult(
                artistMbid = "artist-mbid",
                artistCreditName = "Artist",
                recordingName = null,
                recordingMbid = null,
                releaseName = null,
                releaseMbid = null,
            )
        }
        val provider = ListenBrainzSyncProvider(mockk(), mockk(relaxed = true), enrichment)
        assertNull(provider.searchForTrackId("Title", "Artist"))
    }
}
