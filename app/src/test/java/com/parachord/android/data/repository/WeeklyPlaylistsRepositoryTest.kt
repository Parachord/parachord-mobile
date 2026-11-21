package com.parachord.android.data.repository

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
    fun `loadPlaylistTracks returns empty on API error`() = runTest {
        coEvery { api.getPlaylistTracksRich("p1") } throws RuntimeException("Network error")
        assertTrue(repo.loadPlaylistTracks("p1").isEmpty())
    }
}
