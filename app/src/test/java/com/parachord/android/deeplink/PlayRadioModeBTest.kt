package com.parachord.android.deeplink

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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlayRadioDispatcher] (Mode B — artist seed). Mode C
 * (pool-based) lives in [PlayRadioModeCTest].
 *
 * Updated for the result-type pattern (Task 4): the dispatcher no longer
 * takes a toast lambda — the VM emits toasts based on the returned
 * [PlayRadioResult]. Tests assert on the result shape + verify call
 * ordering against the playback / teardown mocks.
 */
class PlayRadioModeBTest {

    private fun build(
        pc: PlaybackController = mockk(relaxed = true),
        td: ProtocolPlayTeardown = mockk<ProtocolPlayTeardown>().also {
            coEvery { it.prepareForNewPlayback() } just runs
        },
        // Mode B never invokes the handler — pass a relaxed mock so a
        // stray call would surface as a verification failure, not a NPE.
        handler: ProtocolPlayHandler = mockk(relaxed = true),
    ): PlayRadioDispatcher = PlayRadioDispatcher(pc, td, handler)

    @Test
    fun modeB_callsTeardownBeforeStartSpinoffWithSeed() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForNewPlayback() } just runs

        val dispatcher = build(pc, td)
        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", null),
            )
        )

        assertTrue(result is PlayRadioResult.StartedModeB)
        coVerifyOrder {
            td.prepareForNewPlayback()
            pc.startSpinoffWithSeed("Slowdive", null, any(), any())
        }
    }

    @Test
    fun modeB_displayName_prefersExplicitNameOverArtistTitleFallback() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val dispatcher = build(pc = pc)

        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", "Sugar For The Pill"),
                name = "My Custom Station",
            )
        )

        assertEquals("My Custom Station", (result as PlayRadioResult.StartedModeB).displayName)
        coVerify(exactly = 1) {
            pc.startSpinoffWithSeed("Slowdive", "Sugar For The Pill", "My Custom Station", any())
        }
    }

    @Test
    fun modeB_displayName_artistTitleFallbackWhenNoExplicitName() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val dispatcher = build(pc = pc)

        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", "Sugar For The Pill"),
                name = null,
            )
        )

        assertEquals(
            "Radio: Slowdive – Sugar For The Pill",
            (result as PlayRadioResult.StartedModeB).displayName,
        )
        coVerify(exactly = 1) {
            pc.startSpinoffWithSeed(
                "Slowdive",
                "Sugar For The Pill",
                "Radio: Slowdive – Sugar For The Pill",
                any(),
            )
        }
    }

    @Test
    fun modeB_displayName_artistOnlyFallback() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val dispatcher = build(pc = pc)

        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", null),
                name = null,
            )
        )

        assertEquals("Radio: Slowdive", (result as PlayRadioResult.StartedModeB).displayName)
        coVerify(exactly = 1) {
            pc.startSpinoffWithSeed("Slowdive", null, "Radio: Slowdive", any())
        }
    }

    @Test
    fun modeB_passesKickStartFirstTrackTrue_soPoolStartsPlayingImmediately() = runTest {
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForNewPlayback() } just runs

        val dispatcher = build(pc, td)
        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.ArtistSeed("Slowdive", null),
            )
        )

        // Mode B is "start fresh radio now" — the pool's first track must
        // begin playing without waiting for an existing track to finish.
        coVerify(exactly = 1) {
            pc.startSpinoffWithSeed(
                seedArtist = "Slowdive",
                seedTitle = null,
                displayName = "Radio: Slowdive",
                kickStartFirstTrack = true,
            )
        }
    }

    @Test
    fun modeB_doesNotCallProtocolPlayHandler() = runTest {
        // Mode B is fully handled by the dispatcher + PlaybackController —
        // never delegates to the protocol play handler. (The handler's
        // `handle(PlayRadio)` requires PoolBased and would throw on
        // ArtistSeed.)
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForNewPlayback() } just runs
        val handler = mockk<ProtocolPlayHandler>()  // strict — any call fails

        val dispatcher = PlayRadioDispatcher(pc, td, handler)
        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(mode = RadioMode.ArtistSeed("Slowdive", null))
        )
        // Strict mock — no verification block needed; any invocation throws.
    }
}
