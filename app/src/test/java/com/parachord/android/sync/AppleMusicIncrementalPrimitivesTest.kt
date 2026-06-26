package com.parachord.android.sync

import com.parachord.shared.api.AmPlaylist
import com.parachord.shared.api.AmPlaylistAttributes
import com.parachord.shared.api.AmPlaylistListResponse
import com.parachord.shared.api.AmTracksRequest
import com.parachord.shared.sync.AppleMusicSyncProvider
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Locks the Apple Music incremental add primitive (Task 3 of the
 * incremental-materialization plan). AM library playlists are add-only — its
 * `trackRemoveMode` is `Unsupported`, so BOTH remove primitives stay the
 * throwing interface default and the executor never calls them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppleMusicIncrementalPrimitivesTest {

    private fun okResponse(): HttpResponse = mockk { every { status } returns HttpStatusCode.OK }

    @Test
    fun `addPlaylistTracks POST-appends songs references and returns snapshot`() = runTest {
        val bodySlot = slot<AmTracksRequest>()
        val api = mockk<com.parachord.shared.api.AppleMusicLibraryClient>(relaxed = true) {
            coEvery { appendPlaylistTracks(eq("PL1"), capture(bodySlot)) } returns okResponse()
            coEvery { listPlaylists(any(), any()) } returns AmPlaylistListResponse(
                data = listOf(
                    AmPlaylist(
                        id = "PL1",
                        type = "library-playlists",
                        attributes = AmPlaylistAttributes(
                            name = "P",
                            lastModifiedDate = "2026-06-23T12:00:00Z",
                        ),
                    ),
                ),
                next = null,
            )
        }
        val provider = AppleMusicSyncProvider(api = api, catalogClient = mockk(relaxed = true))

        val snap = provider.addPlaylistTracks("PL1", listOf("123", "456"))

        coVerify(exactly = 1) { api.appendPlaylistTracks("PL1", any()) }
        // Body carries each id as a {"id":..,"type":"songs"} reference.
        assertEquals(2, bodySlot.captured.data.size)
        assertEquals("123", bodySlot.captured.data[0].id)
        assertEquals("songs", bodySlot.captured.data[0].type)
        assertEquals("456", bodySlot.captured.data[1].id)
        assertEquals("2026-06-23T12:00:00Z", snap)
    }

    @Test
    fun `removePlaylistTracksByNativeId stays the throwing default`() = runTest {
        val provider = AppleMusicSyncProvider(api = mockk(relaxed = true), catalogClient = mockk(relaxed = true))
        try {
            provider.removePlaylistTracksByNativeId("PL1", listOf("123"))
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) { /* ok */ }
    }

    @Test
    fun `removePlaylistTracksByPosition stays the throwing default`() = runTest {
        val provider = AppleMusicSyncProvider(api = mockk(relaxed = true), catalogClient = mockk(relaxed = true))
        try {
            provider.removePlaylistTracksByPosition("PL1", listOf(0))
            fail("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) { /* ok */ }
    }
}
