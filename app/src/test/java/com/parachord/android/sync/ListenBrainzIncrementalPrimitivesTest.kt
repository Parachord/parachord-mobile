package com.parachord.android.sync

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.sync.ListenBrainzSyncProvider
import com.parachord.shared.sync.ListenBrainzUnauthorizedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Locks the ListenBrainz incremental add/remove primitives (Task 3 of the
 * incremental-materialization plan).
 *
 * The one piece with real logic is the descending-positional delete: LB removes
 * by 0-based index+count, so deleting an EARLIER index shifts every LATER index
 * down by one. We must therefore issue the per-position deletes in DESCENDING
 * order so each remaining delete targets the position the caller intended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenBrainzIncrementalPrimitivesTest {

    // ── addPlaylistTracks ──────────────────────────────────────────────

    @Test
    fun `addPlaylistTracks appends via client and returns new last-modified`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true) {
            coEvery { getPlaylistLastModified("mbid") } returns "2026-06-23T10:00:00Z"
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())

        val snap = provider.addPlaylistTracks("mbid", listOf("a", "b", "c"))

        coVerifyOrder {
            client.addPlaylistItems("mbid", listOf("a", "b", "c"), "tok")
            client.getPlaylistLastModified("mbid")
        }
        assertEquals("2026-06-23T10:00:00Z", snap)
    }

    @Test
    fun `addPlaylistTracks throws IllegalStateException when token unset`() = runTest {
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns null }
        val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
        try {
            provider.addPlaylistTracks("mbid", listOf("a"))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) { /* ok */ }
    }

    @Test
    fun `addPlaylistTracks propagates 401 as ListenBrainzUnauthorizedException`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true) {
            coEvery { addPlaylistItems(any(), any(), any()) } throws ListenBrainzUnauthorizedException()
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        try {
            provider.addPlaylistTracks("mbid", listOf("a"))
            fail("expected ListenBrainzUnauthorizedException")
        } catch (e: ListenBrainzUnauthorizedException) { /* ok */ }
    }

    // ── removePlaylistTracksByPosition ─────────────────────────────────

    @Test
    fun `removePlaylistTracksByPosition deletes in DESCENDING index order`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true) {
            coEvery { getPlaylistLastModified("mbid") } returns "2026-06-23T11:00:00Z"
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())

        // Caller passes the positions in ascending (or arbitrary) order; the
        // provider must delete them high-to-low so an earlier delete doesn't
        // shift the indices of the not-yet-deleted positions.
        val snap = provider.removePlaylistTracksByPosition("mbid", listOf(0, 2, 4))

        coVerifyOrder {
            client.deletePlaylistItems("mbid", 4, 1, "tok")
            client.deletePlaylistItems("mbid", 2, 1, "tok")
            client.deletePlaylistItems("mbid", 0, 1, "tok")
            client.getPlaylistLastModified("mbid")
        }
        assertEquals("2026-06-23T11:00:00Z", snap)
    }

    @Test
    fun `removePlaylistTracksByPosition sorts arbitrary input descending`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true)
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())

        provider.removePlaylistTracksByPosition("mbid", listOf(3, 1, 5))

        coVerifyOrder {
            client.deletePlaylistItems("mbid", 5, 1, "tok")
            client.deletePlaylistItems("mbid", 3, 1, "tok")
            client.deletePlaylistItems("mbid", 1, 1, "tok")
        }
    }

    @Test
    fun `removePlaylistTracksByPosition with empty list issues no deletes`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true) {
            coEvery { getPlaylistLastModified("mbid") } returns "2026-06-23T11:00:00Z"
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())

        provider.removePlaylistTracksByPosition("mbid", emptyList())
        coVerify(exactly = 0) { client.deletePlaylistItems(any(), any(), any(), any()) }
    }

    @Test
    fun `removePlaylistTracksByPosition throws IllegalStateException when token unset`() = runTest {
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns null }
        val provider = ListenBrainzSyncProvider(mockk(), settings, mockk())
        try {
            provider.removePlaylistTracksByPosition("mbid", listOf(0))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) { /* ok */ }
    }

    @Test
    fun `removePlaylistTracksByPosition propagates 401 as ListenBrainzUnauthorizedException`() = runTest {
        val client: ListenBrainzClient = mockk(relaxed = true) {
            coEvery { deletePlaylistItems(any(), any(), any(), any()) } throws ListenBrainzUnauthorizedException()
        }
        val settings: SettingsStore = mockk { coEvery { getListenBrainzToken() } returns "tok" }
        val provider = ListenBrainzSyncProvider(client, settings, mockk())
        try {
            provider.removePlaylistTracksByPosition("mbid", listOf(0))
            fail("expected ListenBrainzUnauthorizedException")
        } catch (e: ListenBrainzUnauthorizedException) { /* ok */ }
    }

    // ── removePlaylistTracksByNativeId stays the throwing default ───────

    @Test
    fun `removePlaylistTracksByNativeId is unsupported for ListenBrainz`() = runTest {
        val provider = ListenBrainzSyncProvider(mockk(relaxed = true), mockk(relaxed = true), mockk())
        try {
            provider.removePlaylistTracksByNativeId("mbid", listOf("a"))
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) { /* ok */ }
    }
}
