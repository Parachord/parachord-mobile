package com.parachord.android.sync

import com.parachord.shared.api.AmPlaylistAttributes
import com.parachord.shared.api.AmPlaylistDetail
import com.parachord.shared.api.AmPlaylistDetailResponse
import com.parachord.shared.api.AppleMusicLibraryClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `fetchPlaylist` fetches one library playlist by id, for a pulled playlist
 * Apple's library list leaves out. Apple answers some of those without a
 * name, so the local row's name is kept instead of dropping the playlist.
 */
class AppleMusicFetchPlaylistTest {

    private fun provider(name: String?): AppleMusicSyncProvider {
        val api = mockk<AppleMusicLibraryClient>()
        coEvery { api.getLibraryPlaylistWithTracks("p.goleKsZDVNL", any()) } returns AmPlaylistDetailResponse(
            listOf(
                AmPlaylistDetail(
                    id = "p.goleKsZDVNL",
                    type = "library-playlists",
                    attributes = AmPlaylistAttributes(name = name, lastModifiedDate = "2026-04-22T12:14:32Z"),
                ),
            ),
        )
        return AppleMusicSyncProvider(api = api, catalogClient = mockk(relaxed = true))
    }

    @Test fun `a nameless answer keeps the local name`() = runBlocking {
        val fetched = provider(name = null).fetchPlaylist("p.goleKsZDVNL", knownName = "Rock's Not Dead, It's Ruled By Women")
        assertEquals("Rock's Not Dead, It's Ruled By Women", fetched?.entity?.name)
        assertEquals("p.goleKsZDVNL", fetched?.spotifyId)
        assertEquals("2026-04-22T12:14:32Z", fetched?.snapshotId)
    }

    @Test fun `a named answer keeps Apple's name`() = runBlocking {
        val fetched = provider(name = "Renamed on Apple").fetchPlaylist("p.goleKsZDVNL", knownName = "Old name")
        assertEquals("Renamed on Apple", fetched?.entity?.name)
    }

    @Test fun `a nameless answer with no known name is skipped`() = runBlocking {
        assertNull(provider(name = null).fetchPlaylist("p.goleKsZDVNL", knownName = null))
    }
}
