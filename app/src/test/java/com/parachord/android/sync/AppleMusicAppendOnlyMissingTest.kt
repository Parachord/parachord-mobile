package com.parachord.android.sync

import com.parachord.shared.api.AmPlayParams
import com.parachord.shared.api.AmPlaylistTrackListResponse
import com.parachord.shared.api.AmTrack
import com.parachord.shared.api.AmTrackAttributes
import com.parachord.shared.api.AmTracksRequest
import com.parachord.shared.api.AppleMusicLibraryClient
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple refuses a replace of a library playlist, so a push falls back to
 * appending. It appends only the tracks the playlist doesn't hold yet: the whole
 * list again duplicated every track on every push (#802).
 */
class AppleMusicAppendOnlyMissingTest {

    private fun response(status: HttpStatusCode): HttpResponse = mockk { every { this@mockk.status } returns status }

    private fun song(catalogId: String) = AmTrack(
        id = "i.$catalogId",
        type = "library-songs",
        attributes = AmTrackAttributes(
            name = "Song $catalogId",
            artistName = "Artist",
            playParams = AmPlayParams(id = "i.$catalogId", kind = "song", catalogId = catalogId),
        ),
    )

    private fun api(present: List<String>?): Pair<AppleMusicLibraryClient, io.mockk.CapturingSlot<AmTracksRequest>> {
        val appended = slot<AmTracksRequest>()
        val api = mockk<AppleMusicLibraryClient>(relaxed = true)
        coEvery { api.replacePlaylistTracks("p.1", any()) } returns response(HttpStatusCode.Unauthorized)
        if (present == null) {
            coEvery { api.listPlaylistTracks("p.1", any(), any()) } throws RuntimeException("offline")
        } else {
            coEvery { api.listPlaylistTracks("p.1", any(), any()) } returns
                AmPlaylistTrackListResponse(data = present.map(::song), next = null)
        }
        coEvery { api.appendPlaylistTracks("p.1", capture(appended)) } returns response(HttpStatusCode.Created)
        return api to appended
    }

    @Test fun `only the tracks the playlist lacks are appended`() = runBlocking {
        val (api, appended) = api(present = listOf("111", "333"))
        AppleMusicSyncProvider(api = api, catalogClient = mockk(relaxed = true))
            .replacePlaylistTracks("p.1", listOf("111", "222", "333", "444"))
        assertEquals(listOf("222", "444"), appended.captured.data.map { it.id })
    }

    @Test fun `a playlist that holds everything gets no append`() = runBlocking {
        val (api, _) = api(present = listOf("111", "222"))
        AppleMusicSyncProvider(api = api, catalogClient = mockk(relaxed = true))
            .replacePlaylistTracks("p.1", listOf("111", "222"))
        coVerify(exactly = 0) { api.appendPlaylistTracks(any(), any()) }
    }

    @Test fun `a playlist that can't be read gets no append, and the push fails`() = runBlocking {
        val (api, _) = api(present = null)
        val failed = runCatching {
            AppleMusicSyncProvider(api = api, catalogClient = mockk(relaxed = true))
                .replacePlaylistTracks("p.1", listOf("111"))
        }.isFailure
        assertTrue(failed)
        coVerify(exactly = 0) { api.appendPlaylistTracks(any(), any()) }
    }
}
