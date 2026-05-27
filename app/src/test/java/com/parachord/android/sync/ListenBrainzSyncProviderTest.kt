package com.parachord.android.sync

import com.parachord.shared.api.LbPlaylist
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.sync.ListenBrainzSyncProvider
import com.parachord.shared.sync.ListenBrainzUnauthorizedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `fetchPlaylists swallows 401 and trips kill-switch`() = runTest {
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
}
