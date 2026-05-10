package com.parachord.android.deeplink

import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.PlaybackController
import com.parachord.android.resolver.TrackResolverCache
import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ProtocolInputResolver
import com.parachord.shared.deeplink.ProtocolPlayInput
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.deeplink.ProtocolTrack
import com.parachord.shared.deeplink.RadioMode
import com.parachord.shared.deeplink.ResolvedProtocolPlay
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end coverage for `parachord://play/radio` Mode C (pool-based).
 *
 * Exercises the full chain:
 *   [PlayRadioDispatcher] → [ProtocolPlayHandler.handle] (PlayRadio) →
 *   [PlaybackController.startPoolBasedSpinoff].
 *
 * The Phase 2 [ProtocolInputResolver] is mocked per-test to control which
 * priority slot fires (URL vs. inline tracks) since the radio Mode C
 * options gate to `allowUrl = true, allowTracks = true` only.
 */
class PlayRadioModeCTest {

    private val sampleTracks = listOf(
        ProtocolTrack(artist = "A1", title = "T1"),
        ProtocolTrack(artist = "A2", title = "T2"),
    )

    private fun buildHandler(
        resolver: ProtocolInputResolver = mockk(relaxed = true),
        teardown: ProtocolPlayTeardown = mockk<ProtocolPlayTeardown>().also {
            coEvery { it.prepareForNewPlayback() } just runs
        },
        playbackController: PlaybackController = mockk(relaxed = true),
        trackResolverCache: TrackResolverCache = mockk(relaxed = true),
    ): ProtocolPlayHandler = ProtocolPlayHandler(
        resolver = resolver,
        teardown = teardown,
        playbackController = playbackController,
        trackResolverCache = trackResolverCache,
    )

    private fun buildDispatcher(
        playbackController: PlaybackController,
        teardown: ProtocolPlayTeardown,
        handler: ProtocolPlayHandler,
    ): PlayRadioDispatcher = PlayRadioDispatcher(playbackController, teardown, handler)

    @Test
    fun modeC_inlineTracks_buildsPoolWithStableIdsAndKicksOff() = runTest {
        // Inline tracks bypass the resolver entirely (resolveProtocolPlayInput
        // wraps them directly into a ResolvedProtocolPlay). Strict resolver —
        // any call would throw.
        val resolver = mockk<ProtocolInputResolver>()
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForNewPlayback() } just runs
        val handler = buildHandler(resolver = resolver, teardown = td, playbackController = pc)
        val dispatcher = buildDispatcher(pc, td, handler)

        val poolSlot = slot<List<TrackEntity>>()
        val nameSlot = slot<String>()
        val refillSlot = slot<String?>()
        coEvery { pc.startPoolBasedSpinoff(capture(poolSlot), capture(nameSlot), captureNullable(refillSlot)) } just runs

        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.PoolBased,
                input = ProtocolPlayInput(tracks = sampleTracks, title = "Inline Pool"),
                refillUrl = null,
            )
        )

        result as PlayRadioResult.StartedModeC
        assertEquals("Inline Pool", result.displayName)
        assertEquals(2, result.trackCount)

        // Pool entities have stable protocol-radio-{ts}-{idx} IDs.
        assertEquals(2, poolSlot.captured.size)
        assertTrue(
            "first id was '${poolSlot.captured[0].id}'",
            poolSlot.captured[0].id.startsWith("protocol-radio-") &&
                poolSlot.captured[0].id.endsWith("-0"),
        )
        assertTrue(poolSlot.captured[1].id.endsWith("-1"))
        assertEquals("A1", poolSlot.captured[0].artist)
        assertEquals("T1", poolSlot.captured[0].title)

        assertEquals("Inline Pool", nameSlot.captured)
        assertNull(refillSlot.captured)
    }

    @Test
    fun modeC_url_fetchesAndParses_passesRefillUrlThroughToController() = runTest {
        val urlPool = (1..5).map { ProtocolTrack(artist = "A$it", title = "T$it") }
        val resolver = mockk<ProtocolInputResolver> {
            coEvery { resolveByUrl("https://api.listenbrainz.org/1/explore/lb-radio?mode=easy") } returns
                ResolvedProtocolPlay("LB Radio", urlPool)
        }
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForNewPlayback() } just runs
        val handler = buildHandler(resolver = resolver, teardown = td, playbackController = pc)
        val dispatcher = buildDispatcher(pc, td, handler)

        val refillSlot = slot<String?>()
        coEvery { pc.startPoolBasedSpinoff(any(), any(), captureNullable(refillSlot)) } just runs

        val refillUrl = "https://api.listenbrainz.org/1/explore/lb-radio?mode=easy&refill=1"
        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.PoolBased,
                input = ProtocolPlayInput(url = "https://api.listenbrainz.org/1/explore/lb-radio?mode=easy"),
                refillUrl = refillUrl,
            )
        )

        result as PlayRadioResult.StartedModeC
        assertEquals(5, result.trackCount)
        assertEquals(refillUrl, refillSlot.captured)

        coVerify(exactly = 1) {
            pc.startPoolBasedSpinoff(match { it.size == 5 }, "LB Radio", refillUrl)
        }
    }

    @Test
    fun modeC_emptyPool_returnsFailedAndDoesNotCallTeardownOrController() = runTest {
        val resolver = mockk<ProtocolInputResolver> {
            coEvery { resolveByUrl(any()) } returns ResolvedProtocolPlay("Empty", emptyList())
        }
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)
        val handler = buildHandler(resolver = resolver, teardown = td, playbackController = pc)
        val dispatcher = buildDispatcher(pc, td, handler)

        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.PoolBased,
                input = ProtocolPlayInput(url = "https://example.com/empty.xspf"),
            )
        )

        assertTrue(result is PlayRadioResult.Failed)
        // Match handle(PlayAlbum)'s convention exactly: empty-tracks bails
        // BEFORE teardown, so neither teardown nor the controller fire.
        coVerify(exactly = 0) { td.prepareForNewPlayback() }
        coVerify(exactly = 0) { pc.startPoolBasedSpinoff(any(), any(), any()) }
    }

    @Test
    fun modeC_resolverThrows_returnsFailedAndDoesNotCallController() = runTest {
        val resolver = mockk<ProtocolInputResolver> {
            coEvery { resolveByUrl(any()) } throws RuntimeException("network blew up")
        }
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)
        val handler = buildHandler(resolver = resolver, teardown = td, playbackController = pc)
        val dispatcher = buildDispatcher(pc, td, handler)

        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.PoolBased,
                input = ProtocolPlayInput(url = "https://example.com/broken.xspf"),
            )
        )

        result as PlayRadioResult.Failed
        assertTrue(
            "reason was '${result.reason}'",
            result.reason.contains("network blew up") || result.reason.contains("Radio fetch failed"),
        )
        coVerify(exactly = 0) { pc.startPoolBasedSpinoff(any(), any(), any()) }
    }

    @Test
    fun modeC_missingInput_returnsFailedWithoutTouchingResolver() = runTest {
        val resolver = mockk<ProtocolInputResolver>()  // strict — any call fails
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>(relaxed = true)
        val handler = buildHandler(resolver = resolver, teardown = td, playbackController = pc)
        val dispatcher = buildDispatcher(pc, td, handler)

        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(mode = RadioMode.PoolBased, input = null)
        )

        result as PlayRadioResult.Failed
        coVerify(exactly = 0) { pc.startPoolBasedSpinoff(any(), any(), any()) }
    }

    // ── Display-name precedence: action.name → input.title → resolver → "Radio" ──

    @Test
    fun modeC_namePrecedence_explicitNameWinsOverInputTitleAndResolvedName() = runTest {
        val resolver = mockk<ProtocolInputResolver> {
            coEvery { resolveByUrl(any()) } returns ResolvedProtocolPlay("resolved name", sampleTracks)
        }
        val pc = mockk<PlaybackController>(relaxed = true)
        val handler = buildHandler(resolver = resolver, playbackController = pc)
        val dispatcher = buildDispatcher(pc, mockk<ProtocolPlayTeardown>(relaxed = true), handler)

        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.PoolBased,
                input = ProtocolPlayInput(url = "https://example.com/x", title = "input title"),
                name = "explicit name",
            )
        )

        coVerify(exactly = 1) { pc.startPoolBasedSpinoff(any(), "explicit name", any()) }
    }

    @Test
    fun modeC_namePrecedence_inputTitleWinsOverResolvedNameWhenNoExplicit() = runTest {
        val resolver = mockk<ProtocolInputResolver> {
            coEvery { resolveByUrl(any()) } returns ResolvedProtocolPlay("resolved name", sampleTracks)
        }
        val pc = mockk<PlaybackController>(relaxed = true)
        val handler = buildHandler(resolver = resolver, playbackController = pc)
        val dispatcher = buildDispatcher(pc, mockk<ProtocolPlayTeardown>(relaxed = true), handler)

        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.PoolBased,
                input = ProtocolPlayInput(url = "https://example.com/x", title = "input title"),
                name = null,
            )
        )

        coVerify(exactly = 1) { pc.startPoolBasedSpinoff(any(), "input title", any()) }
    }

    @Test
    fun modeC_namePrecedence_resolvedNameWinsWhenNoExplicitNoInputTitle() = runTest {
        val resolver = mockk<ProtocolInputResolver> {
            coEvery { resolveByUrl(any()) } returns ResolvedProtocolPlay("resolved name", sampleTracks)
        }
        val pc = mockk<PlaybackController>(relaxed = true)
        val handler = buildHandler(resolver = resolver, playbackController = pc)
        val dispatcher = buildDispatcher(pc, mockk<ProtocolPlayTeardown>(relaxed = true), handler)

        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.PoolBased,
                input = ProtocolPlayInput(url = "https://example.com/x"),
                name = null,
            )
        )

        coVerify(exactly = 1) { pc.startPoolBasedSpinoff(any(), "resolved name", any()) }
    }

    @Test
    fun modeC_namePrecedence_radioFallback() = runTest {
        // All four sources blank/null → literal "Radio" default. The
        // resolver here returns a blank displayName (mirrors an XSPF/JSPF
        // parser that didn't find a <title>); the chain's
        // .takeIf { it.isNotBlank() } guards must fall through to "Radio".
        val resolver = mockk<ProtocolInputResolver> {
            coEvery { resolveByUrl(any()) } returns ResolvedProtocolPlay(
                displayName = "",
                tracks = sampleTracks,
            )
        }
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForNewPlayback() } just runs
        val handler = buildHandler(resolver = resolver, teardown = td, playbackController = pc)
        val dispatcher = buildDispatcher(pc, td, handler)

        val result = dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.PoolBased,
                input = ProtocolPlayInput(url = "https://example.com/p.jspf", title = null),
                name = null,
            )
        )

        assertTrue(result is PlayRadioResult.StartedModeC)
        coVerify(exactly = 1) { pc.startPoolBasedSpinoff(any(), "Radio", any()) }
    }

    @Test
    fun modeC_teardownFiresBeforeStartPoolBasedSpinoff() = runTest {
        val resolver = mockk<ProtocolInputResolver> {
            coEvery { resolveByUrl(any()) } returns ResolvedProtocolPlay("X", sampleTracks)
        }
        val pc = mockk<PlaybackController>(relaxed = true)
        val td = mockk<ProtocolPlayTeardown>()
        coEvery { td.prepareForNewPlayback() } just runs
        val handler = buildHandler(resolver = resolver, teardown = td, playbackController = pc)
        val dispatcher = buildDispatcher(pc, td, handler)

        dispatcher.dispatch(
            DeepLinkAction.PlayRadio(
                mode = RadioMode.PoolBased,
                input = ProtocolPlayInput(url = "https://example.com/x"),
            )
        )

        coVerifyOrder {
            td.prepareForNewPlayback()
            pc.startPoolBasedSpinoff(any(), any(), any())
        }
    }
}
