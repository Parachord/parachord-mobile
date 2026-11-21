package com.parachord.shared.home

import com.parachord.shared.car.CarPlayRequest
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.Track
import com.parachord.shared.playback.CatalogQueues
import com.parachord.shared.playback.PlaybackContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HomeQueuesTest {
    private fun track(t: String) = Track(id = t, title = t, artist = "A")
    private fun mix() = HistoryMix("m", "m", "Daily", url = "https://x/m", resolverId = "r", resolverName = "R")

    private fun queues(
        loves: List<Track> = emptyList(),
        playlist: suspend (String) -> List<Track> = { emptyList() },
        mixTracks: suspend (String) -> List<Track> = { emptyList() },
        radio: CarPlayRequest? = null,
    ) = HomeQueues(
        lovedTracks = { loves },
        playlistTracks = playlist,
        mixTracks = mixTracks,
        catalogQueues = CatalogQueues(MetadataService(emptyList(), { emptySet() })),
        collectionRadio = { radio },
    )

    @Test
    fun `mix lookup that throws fails with the could not load copy`() = runTest {
        val r = queues(mixTracks = { error("boom") }).mix(mix())
        assertEquals(HomeQueue.Failed(HomeCopy.couldNotLoad("Daily")), r)
    }

    @Test
    fun `mix with no tracks fails`() = runTest {
        assertEquals(HomeQueue.Failed(HomeCopy.couldNotLoad("Daily")), queues().mix(mix()))
    }

    @Test
    fun `mix plays under its link`() = runTest {
        val r = queues(mixTracks = { listOf(track("a")) }).mix(mix())
        assertEquals(HomeQueue.Play(listOf(track("a")), PlaybackContext("mix", "Daily", "https://x/m")), r)
    }

    @Test
    fun `loves play under the loves context`() = runTest {
        val r = queues(loves = listOf(track("a"))).loves()
        assertEquals(HomeQueue.Play(listOf(track("a")), PlaybackContext(LOVES_CONTEXT, HomeCopy.RECENT_LOVES)), r)
    }

    @Test
    fun `empty playlist is empty`() = runTest {
        assertEquals(HomeQueue.Empty, queues().playlist("p", "P"))
    }

    @Test
    fun `collection radio shuffles by default`() = runTest {
        val r = queues(radio = CarPlayRequest(listOf(track("a")), 0, null)).collectionRadio()
        assertEquals(HomeQueue.Play(listOf(track("a")), PlaybackContext(COLLECTION_CONTEXT, HomeCopy.COLLECTION_RADIO), shuffle = true), r)
        assertIs<HomeQueue.Empty>(queues().collectionRadio())
    }

    @Test
    fun `a playlist read that throws fails with the could not load copy`() = runTest {
        val r = queues(playlist = { error("db closed") }).playlist("p", "Road Trip")
        assertEquals(HomeQueue.Failed(HomeCopy.couldNotLoad("Road Trip")), r)
    }

    @Test
    fun `loves that throw fail under the Recent Loves name`() = runTest {
        val q = HomeQueues(
            lovedTracks = { error("boom") },
            playlistTracks = { emptyList() },
            mixTracks = { emptyList() },
            catalogQueues = CatalogQueues(MetadataService(emptyList(), { emptySet() })),
            collectionRadio = { null },
        )
        assertEquals(HomeQueue.Failed(HomeCopy.couldNotLoad(HomeCopy.RECENT_LOVES)), q.loves())
        assertEquals(HomeQueue.Failed(HomeCopy.couldNotLoad(HomeCopy.RECENT_LOVES)), q.tile(JumpTile.Loves(emptyList())))
    }

    @Test
    fun `collection radio that throws fails with the could not load copy`() = runTest {
        val q = HomeQueues(
            lovedTracks = { emptyList() },
            playlistTracks = { emptyList() },
            mixTracks = { emptyList() },
            catalogQueues = CatalogQueues(MetadataService(emptyList(), { emptySet() })),
            collectionRadio = { error("boom") },
        )
        assertEquals(HomeQueue.Failed(HomeCopy.couldNotLoad(HomeCopy.COLLECTION_RADIO)), q.collectionRadio())
    }
}
