package com.parachord.android.deeplink

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.PlaybackController
import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.deeplink.RadioMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PlayRadioDispatcher] Mode B (artist seed) — #397, parity with desktop
 * parachord#1005 / PR #1015. Mode C lives in [PlayRadioModeCTest].
 *
 * The invariant every test here protects: **the user's queue is never cleared
 * for a radio that won't start.** The seed is resolved (artist-only → the
 * artist's top Last.fm track) AND the whole pool is built before
 * [ProtocolPlayTeardown.prepareForNewPlayback]; any failure before that returns
 * without calling it.
 */
class PlayRadioModeBTest {

    private val track = TrackEntity(id = "t1", title = "Alison", artist = "Slowdive")

    /** A controller that has a top track and a non-empty pool by default. */
    private fun controller(
        topTrack: String? = "When the Sun Hits",
        pool: List<TrackEntity> = listOf(track),
    ): PlaybackController = mockk(relaxed = true) {
        coEvery { artistTopTrackTitle(any()) } returns topTrack
        coEvery { buildSpinoffPool(any(), any()) } returns pool
    }

    private fun teardown(): ProtocolPlayTeardown = mockk {
        coEvery { prepareForNewPlayback() } just runs
    }

    /** Mode B never uses the protocol handler — strict, so any call fails the test. */
    private fun dispatcher(pc: PlaybackController, td: ProtocolPlayTeardown) =
        PlayRadioDispatcher(pc, td, mockk<ProtocolPlayHandler>())

    private fun radio(artist: String, title: String? = null, name: String? = null) =
        DeepLinkAction.PlayRadio(mode = RadioMode.ArtistSeed(artist, title), name = name)

    // ── success: resolve everything, THEN tear down, THEN start ─────────

    @Test
    fun titleBearing_buildsThePoolBeforeTearingDown_thenStartsAndKicks() = runTest {
        val pc = controller()
        val td = teardown()

        val result = dispatcher(pc, td).dispatch(radio("Slowdive", "Sugar For The Pill"))

        assertEquals(PlayRadioResult.StartedModeB("Radio: Slowdive – Sugar For The Pill"), result)
        coVerifyOrder {
            pc.buildSpinoffPool("Slowdive", "Sugar For The Pill")
            td.prepareForNewPlayback()
            pc.startSpinoffFromPool(
                pool = listOf(track),
                displayName = "Radio: Slowdive – Sugar For The Pill",
                seedArtist = "Slowdive",
                seedTitle = "Sugar For The Pill",
                kickStartFirstTrack = true,
            )
        }
    }

    @Test
    fun titleBearing_usesTheTitleAsIs_withoutATopTrackLookup() = runTest {
        val pc = controller()
        dispatcher(pc, teardown()).dispatch(radio("Slowdive", "Sugar For The Pill"))
        coVerify(exactly = 0) { pc.artistTopTrackTitle(any()) }
    }

    @Test
    fun artistOnly_seedsFromTheTopTrack_namesTheRadioAfterTheArtist() = runTest {
        // The pre-#397 behaviour synthesized a ListenBrainz lb-radio URL, which
        // 401s without an LB token. Now it's desktop's top-track seed.
        val pc = controller(topTrack = "When the Sun Hits")
        val td = teardown()

        val result = dispatcher(pc, td).dispatch(radio("Slowdive"))

        assertEquals(PlayRadioResult.StartedModeB("Radio: Slowdive"), result)
        coVerifyOrder {
            pc.artistTopTrackTitle("Slowdive")
            pc.buildSpinoffPool("Slowdive", "When the Sun Hits")
            td.prepareForNewPlayback()
            pc.startSpinoffFromPool(any(), "Radio: Slowdive", "Slowdive", "When the Sun Hits", true)
        }
    }

    @Test
    fun explicitName_isPreferred() = runTest {
        val result = dispatcher(controller(), teardown()).dispatch(radio("Slowdive", name = "My Station"))
        assertEquals(PlayRadioResult.StartedModeB("My Station"), result)
    }

    // ── failure: return WITHOUT touching the queue ───────────────────────

    @Test
    fun artistOnly_noTopTrack_isNoSeed_andTheQueueIsUntouched() = runTest {
        // The desktop bug (parachord#1005): clear the queue, then play nothing.
        val pc = controller(topTrack = null)
        val td = teardown()

        val result = dispatcher(pc, td).dispatch(radio("Nobody"))

        assertEquals(PlayRadioResult.NoSeed("Nobody"), result)
        coVerify(exactly = 0) { td.prepareForNewPlayback() }
        coVerify(exactly = 0) { pc.buildSpinoffPool(any(), any()) }
        coVerify(exactly = 0) { pc.startSpinoffFromPool(any(), any(), any(), any(), any()) }
    }

    @Test
    fun emptyPool_failsWithoutTearingDown() = runTest {
        // Stronger than desktop, which only guarantees the SEED before clearing:
        // no similar tracks — or none resolvable, e.g. only Bandcamp connected —
        // must not cost the user their queue.
        val pc = controller(pool = emptyList())
        val td = teardown()

        val result = dispatcher(pc, td).dispatch(radio("Slowdive", "Sugar For The Pill"))

        assertEquals(PlayRadioResult.Failed("No similar tracks found for \"Sugar For The Pill\""), result)
        coVerify(exactly = 0) { td.prepareForNewPlayback() }
        coVerify(exactly = 0) { pc.startSpinoffFromPool(any(), any(), any(), any(), any()) }
    }

    @Test
    fun lastFmFailure_failsWithoutTearingDown() = runTest {
        val pc = controller()
        coEvery { pc.buildSpinoffPool(any(), any()) } throws IllegalStateException("Last.fm down")
        val td = teardown()

        val result = dispatcher(pc, td).dispatch(radio("Slowdive", "Sugar For The Pill"))

        assertEquals(PlayRadioResult.Failed("Couldn't fetch similar tracks"), result)
        coVerify(exactly = 0) { td.prepareForNewPlayback() }
    }

    @Test
    fun theLoadingSpinnerIsClearedOnEveryOutcome() = runTest {
        for (pc in listOf(controller(), controller(topTrack = null), controller(pool = emptyList()))) {
            dispatcher(pc, teardown()).dispatch(radio("Slowdive"))
            verify { pc.setPlaybarLoading(true) }
            verify { pc.setPlaybarLoading(false) }
        }
    }
}
