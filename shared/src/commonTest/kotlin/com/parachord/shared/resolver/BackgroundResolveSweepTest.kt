package com.parachord.shared.resolver

import com.parachord.shared.model.PlaylistTrack
import com.parachord.shared.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackgroundResolveSweepTest {
    private val start = 1_000_000L
    private val ready = start + BackgroundResolveSweep.STARTUP_GRACE_MS
    private val idleSince = start - 60_000L

    private fun track(n: Int, spotifyId: String? = null) =
        Track(id = "t$n", title = "Song $n", artist = "Artist", spotifyId = spotifyId)

    private fun playlistTrack(n: Int) =
        PlaylistTrack(playlistId = "p", position = n, trackTitle = "Mix $n", trackArtist = "DJ")

    private fun sweep(collection: List<Track>, playlists: List<PlaylistTrack> = emptyList(), reads: IntArray = IntArray(1)) =
        BackgroundResolveSweep(
            collection = { reads[0]++; BackgroundResolveSweep.collectionItems(collection) },
            playlists = { BackgroundResolveSweep.playlistItems(playlists) },
            startedAtMs = start,
        )

    private suspend fun BackgroundResolveSweep.step(
        now: Long = ready,
        lastActivity: Long = idleSince,
        resolverOn: Boolean = true,
        playable: Set<String> = emptySet(),
    ) = next(now, lastActivity, resolverOn, playable)

    @Test
    fun `waits out the startup grace — then the person's activity — then for a resolver`() = runTest {
        val s = sweep(listOf(track(1)))
        assertEquals(SweepStep.Wait(20_000), s.step(now = start + 10_000))
        assertEquals(SweepStep.Wait(BackgroundResolveSweep.ACTIVITY_QUIET_MS), s.step(lastActivity = ready - 1_000))
        assertEquals(SweepStep.Wait(BackgroundResolveSweep.NO_RESOLVERS_WAIT_MS), s.step(resolverOn = false))
        assertIs<SweepStep.Resolve>(s.step())
    }

    @Test
    fun `batches of 5 for the first three — then 25`() = runTest {
        val s = sweep((1..100).map { track(it) })
        val sizes = (1..5).map { (s.step() as SweepStep.Resolve).items.size }
        assertEquals(listOf(5, 5, 5, 25, 25), sizes)
    }

    @Test
    fun `only tracks with no source — none on the row and none cached`() = runTest {
        val s = sweep(listOf(track(1, spotifyId = "sp1"), track(2), track(3), track(4)))
        val batch = s.step(playable = setOf("song 3|artist")) as SweepStep.Resolve
        assertEquals(listOf("Song 2", "Song 4"), batch.items.map { it.title })
    }

    @Test
    fun `the collection first — then the playlists — each track once`() = runTest {
        val s = sweep(listOf(track(1), track(1), track(2)), (1..3).map { playlistTrack(it) })
        val batch = s.step() as SweepStep.Resolve
        assertEquals(listOf("Song 1", "Song 2", "Mix 1", "Mix 2", "Mix 3"), batch.items.map { it.title })
        assertTrue(batch.items.last().playlistTrack != null)
        // Nothing left: a track tried this run isn't tried again, even with no source.
        assertEquals(SweepStep.Wait(BackgroundResolveSweep.NOTHING_TO_DO_WAIT_MS), s.step())
    }

    @Test
    fun `reads the collection once per pass — and starts over after a pass with nothing to do`() = runTest {
        val reads = IntArray(1)
        val s = sweep((1..12).map { track(it) }, reads = reads)
        s.step(); s.step(); s.step()
        assertEquals(1, reads[0])
        assertEquals(SweepStep.Wait(BackgroundResolveSweep.NOTHING_TO_DO_WAIT_MS), s.step())
        s.step()
        assertEquals(2, reads[0])
    }
}
