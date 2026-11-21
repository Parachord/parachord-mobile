package com.parachord.android.data.repository

import com.parachord.shared.api.LbPlaylistTrack
import com.parachord.shared.api.ListenBrainzClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WeeklyPlaylistsRepositoryTest {

    private lateinit var api: ListenBrainzClient
    private lateinit var repo: WeeklyPlaylistsRepository

    @Before
    fun setup() {
        api = mockk()
        repo = WeeklyPlaylistsRepository(api)
    }

    @Test
    fun `loadPlaylistCovers extracts up to 4 distinct album art URLs`() = runTest {
        coEvery { api.getPlaylistTracksRich("p1") } returns listOf(
            LbPlaylistTrack("1", "T1", "A1", albumArt = "http://img1"),
            LbPlaylistTrack("2", "T2", "A2", albumArt = "http://img1"), // duplicate
            LbPlaylistTrack("3", "T3", "A3", albumArt = "http://img2"),
            LbPlaylistTrack("4", "T4", "A4", albumArt = "http://img3"),
            LbPlaylistTrack("5", "T5", "A5", albumArt = "http://img4"),
            LbPlaylistTrack("6", "T6", "A6", albumArt = "http://img5"),
        )

        val covers = repo.loadPlaylistCovers("p1")
        assertEquals(4, covers.size)
        assertEquals(covers.distinct(), covers) // all unique
    }

    @Test
    fun `loadPlaylistTracks returns empty on API error`() = runTest {
        coEvery { api.getPlaylistTracksRich("p1") } throws RuntimeException("Network error")
        assertTrue(repo.loadPlaylistTracks("p1").isEmpty())
    }
}
